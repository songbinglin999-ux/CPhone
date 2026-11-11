package com.yuanhou.hand.session;

import android.content.Context;
import android.graphics.Bitmap;

import com.yuanhou.hand.agent.IntentGate;
import com.yuanhou.hand.gate.ConfirmationGate;
import com.yuanhou.hand.gate.IrrevAction;
import com.yuanhou.hand.gate.IrreversibleDetector;
import com.yuanhou.hand.hand.HandExecutor;
import com.yuanhou.hand.hand.SuiteState;
import com.yuanhou.hand.memory.DoneGate;
import com.yuanhou.hand.memory.GoalCheck;
import com.yuanhou.hand.memory.Playbook;
import com.yuanhou.hand.memory.Sensitive;
import com.yuanhou.hand.memory.StepHistory;
import com.yuanhou.hand.model.BatchPlan;
import com.yuanhou.hand.model.ModelAction;
import com.yuanhou.hand.model.ModelClient;
import com.yuanhou.hand.model.ModelConfig;
import com.yuanhou.hand.policy.ConfirmationPolicy;
import com.yuanhou.hand.policy.InterruptionGuard;
import com.yuanhou.hand.policy.OperationGuard;
import com.yuanhou.hand.policy.SafetyGuard;
import com.yuanhou.hand.policy.UntilCheck;
import com.yuanhou.hand.port.ActionResult;
import com.yuanhou.hand.port.MainScreenEsp32Port;
import com.yuanhou.hand.port.MainScreenPorts;
import com.yuanhou.hand.port.ScreenPort;
import com.yuanhou.hand.screen.ReadonlyAccessibilityService;
import com.yuanhou.hand.screen.ScreenCapture;
import com.yuanhou.hand.screen.ScreenSnapshot;
import com.yuanhou.hand.util.AppLog;
import com.yuanhou.hand.util.Timing;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次任务会话（0.1.5）：意图闸（讨论/读取/操作）→ 只有「操作」才进入 读屏 → 动作 → 执行 → 等稳定 → 再读屏 的循环
 * → 关键步骤过加严的确认闸 → 回传结果。默认不发、不删、不提交、不下单；付款永远由本人操作。
 *
 * 屏幕全部经 ScreenPort（AppAccessScreenPort 套 MainScreenEsp32Port）：读 = 只读无障碍主屏树，动 = ESP32 HID。
 * 状态机借鉴 Mika core/agent/TaskState.kt 与 StopRules.kt（结局由产生它的代码给出，不从文字里猜）；
 * 确认闸借鉴 core/gate/IrreversibleDetector.kt + ConfirmationGate.kt（见 gate/），意图闸借鉴 core/agent/IntentGate.kt（见 agent/）。
 * 不学 Mika 的后台虚拟屏、shell 助手、无障碍执行手势、接管等待：远手只在主屏上、只用 ESP32 HID 动手，需要本人处理的页面直接停。
 */
public final class TaskSession implements CallChannel.Listener {

    public interface Host {
        Context context();
        HandExecutor hand();
        SuiteState suite();
        ExecutorService worker();
    }

    private final Host host;
    private volatile CallChannel channel;
    private final LinkedBlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private volatile boolean awaitingConfirm;
    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile String stateText = "空闲";
    private volatile int confirmSeq;
    private volatile int treeSeq;
    private volatile String lastTree = "";

    /** 0.1.13：这件事从哪来。只影响等确认的时长（微信来的等久一点），不改确认闸规则。 */
    public enum Origin { LOCAL, WECHAT }
    private volatile Origin origin = Origin.LOCAL;
    private volatile Origin nextOrigin = Origin.LOCAL;
    private volatile String taskText = "";
    /** 正在等确认的那一步的申报（给首页「等你确认」显示要点）；不等确认时为 null。付款永远不会出现在这里。 */
    private volatile ConfirmationGate.Declaration pendingConfirm;
    /** 开始等确认那一刻的主屏缩略图（只在本次运行已授权截图时有；只在内存里，不落盘）。 */
    private volatile Bitmap confirmPreview;

    /** 会话阶段（给通知/界面看，也写日志）。 */
    public enum Phase { IDLE, READBACK, OBSERVING, DECIDING, ACTING, WAITING_CONFIRM, ENDED }

    /** 任务结局，由产生它的代码给出（Mika FailKind 的远手版）。 */
    public enum End {
        DONE("办完了"), CANCELLED("已取消"), DECLINED("关键步没有执行"), NEEDS_HUMAN("需要本人处理"),
        GUARD("操作保护叫停"), HAND_FAILED("键鼠动作失败"), MODEL_STOP("模型要求停下"), STEP_LIMIT("步数用完"),
        NOT_READY("两件套未就绪"), BLIND("看不到屏幕"), NOT_FOUND("屏幕上没找到目标"),
        VERIFY_FAILED("输入核对不符");
        public final String label;
        End(String l) { label = l; }
    }

    private volatile Phase phase = Phase.IDLE;
    private volatile int step;
    private volatile String lastDone;
    private volatile End lastEnd;
    private volatile boolean restate;
    private final ConfirmationGate gate = new ConfirmationGate();
    private final ScreenKeepAwake keepAwake = new ScreenKeepAwake();

    public TaskSession(Host host, CallChannel channel) {
        this.host = host;
        setChannel(channel);
    }

    public void setChannel(CallChannel c) {
        channel = c;
        c.setListener(this);
    }

    public String stateText() {
        return stateText;
    }

    public boolean awaitingConfirm() {
        return awaitingConfirm;
    }

    public Phase phase() { return phase; }

    /** 有任务在办（含等确认）。局域网 /say 用它等这一件事办完。 */
    public boolean running() { return running; }

    /** 每开始等一次确认口令加 1：局域网 /say 用它分辨「这次新问了确认」。 */
    public int confirmSeq() { return confirmSeq; }

    /** 每读出一次界面树（本地意图「读屏」）加 1，[lastTree] 是那次的编号列表。 */
    public int treeSeq() { return treeSeq; }
    public String lastTree() { return lastTree; }

    public End lastEnd() { return lastEnd; }

    /** 下一件新事的来源（微信通道在交话之前调用）；开始办之后自动复位为 LOCAL。 */
    public void markNextTaskOrigin(Origin o) { nextOrigin = o == null ? Origin.LOCAL : o; }
    public Origin origin() { return origin; }
    /** 正在办的那件事的原话。 */
    public String taskText() { return taskText; }
    public ConfirmationGate.Declaration pendingConfirm() { return awaitingConfirm ? pendingConfirm : null; }
    public Bitmap confirmPreview() { return awaitingConfirm ? confirmPreview : null; }

    private void phase(Phase p, String text) {
        phase = p;
        stateText = text;
    }

    /**
     * 收尾：记结局；不是办完时按 Mika StopSummary 的思路交代停在哪一步、最后做了什么（不再操作任何东西）。
     */
    private void end(End e, String detail) {
        lastEnd = e;
        phase = Phase.ENDED;
        AppLog.i("任务结局：" + e.name() + (detail == null ? "" : "（" + detail + "）") + "，第 " + step + " 步");
        if (e == End.DONE || e == End.NOT_READY) return;
        if (e == End.CANCELLED && restate) {
            say("好，没按刚才的理解办。请再说一遍要办的事。");
            return;
        }
        if (step > 0) {
            say("停在第 " + step + " 步" + (lastDone == null ? "，还没动手。" : "，最后做的是：" + lastDone + "。")
                    + (e == End.CANCELLED ? "" : "没有发出、没有删除、没有提交、没有下单、没有付款。"));
        }
    }

    @Override
    public void onUtterance(String text) {
        if (awaitingConfirm) {
            replies.offer(text);
            return;
        }
        if (running) {
            if (ConfirmationPolicy.isCancelWord(text)) cancel(ConfirmationPolicy.normalize(text).equals("不对"));
            else host.worker().submit(() -> say("上一件事还在办，说「取消」可以停下。"));
            return;
        }
        if (ConfirmationPolicy.isCancelWord(text)) {
            host.worker().submit(() -> say("现在没有在办的事。"));
            return;
        }
        running = true;
        origin = nextOrigin;
        nextOrigin = Origin.LOCAL;
        taskText = text.trim();
        cancelled = false;
        restate = false;
        step = 0;
        lastDone = null;
        lastEnd = null;
        host.hand().clearStop();
        keepAwake.acquire(host.context());
        host.worker().submit(() -> {
            try {
                run(text);
            } catch (InterruptedException e) {
                say("任务被中断。");
            } catch (Exception e) {
                AppLog.i("任务异常: " + e);
                say("出错了，先停下。");
            } finally {
                running = false;
                awaitingConfirm = false;
                pendingConfirm = null;
                confirmPreview = null;
                gate.reset();
                keepAwake.release();
                phase = Phase.IDLE;
                stateText = "空闲";
            }
        });
    }

    @Override
    public void onCallEnded() {
        cancel();
    }

    /** 「取消」/家里急停：立即中止当前任务和探头动作。 */
    public void cancel() {
        cancel(false);
    }

    /** [restate] 为 true 表示用户说的是「不对」：理解错了，停下后请他重说。 */
    public void cancel(boolean restate) {
        this.restate = restate;
        cancelled = true;
        replies.offer("取消");
        if (running) host.hand().emergencyStop();
        AppLog.i("任务已取消");
    }

    private void say(String s) {
        CallChannel c = channel;
        if (c != null) c.speak(s);
    }

    private boolean requireSuite() {
        String r = host.suite().notReadyReason();
        if (r != null) {
            say("两件套没就绪：" + r + "。先不操作主屏。");
            return false;
        }
        return true;
    }

    private static final Pattern OPEN = Pattern.compile("^打开(微信|淘宝|闲鱼|支付宝|美团外卖|美团)$");
    private static final Pattern DRILL_SEND = Pattern.compile("^演练发微信[：:\\s]*(\\S+)[\\s：:]+(.+)$");
    private static final Pattern DRILL_ORDER = Pattern.compile("^演练下单[：:\\s]*(.+)$");

    private void run(String text) throws InterruptedException {
        phase(Phase.READBACK, "处理：" + text);
        Timing.sleep(Timing.beforeReadback());
        String t = text.trim();
        Matcher m;

        if ((m = DRILL_SEND.matcher(t)).matches()) {
            ConfirmationGate.Declaration d = new ConfirmationGate.Declaration(IrrevAction.SEND, "", m.group(1), m.group(2), "");
            say("这是确认流程演练，不会真的发出。");
            boolean ok = awaitConfirm(d);
            say(ok ? "演练：已听到「发送」。真实任务里这时才会再核对一次屏幕后点发送；这次是演练，没有发出。"
                    : "演练：没有发出。");
            return;
        }
        if ((m = DRILL_ORDER.matcher(t)).matches()) {
            String raw = m.group(1);
            String amount = ConfirmationGate.extractAmount(raw);
            String rest = ConfirmationGate.withoutAmount(raw);
            ConfirmationGate.Declaration d = new ConfirmationGate.Declaration(IrrevAction.ORDER, "", rest, rest, amount);
            say("这是下单确认演练，不会真的下单。");
            boolean ok = awaitConfirm(d);
            say(ok ? "演练：已听到「确认下单」。这次是演练，没有下单。" : "演练：没有下单。");
            return;
        }
        if (t.startsWith("演练付款")) {
            say(ConfirmationGate.readback(new ConfirmationGate.Declaration(IrrevAction.PAY, "", "", "", "")));
            return;
        }

        if ((m = OPEN.matcher(t)).matches()) {
            if (!requireSuite()) return;
            String pkg = appPackage(m.group(1));
            if (pkg == null) { say("这个应用不在允许打开的范围里。"); return; }
            say("好，打开" + m.group(1) + "。");
            ActionResult r = port().openApp(pkg);
            if (r.isOk()) say("前台现在是 " + pkg + "。");
            else say("没打开" + m.group(1) + "：" + r.reason + "。");
            return;
        }
        java.util.List<LocalIntent.Step> plan = LocalIntent.parse(t);
        if (plan != null) {
            localLoop(t, plan);
            return;
        }

        ModelConfig cfg = ModelConfig.load(host.context());
        String miss = cfg.missing();
        if (miss != null) {
            // 没 Key / 没选服务商 / 没同意：清楚提示去设置里填，不崩溃、不调用任何云端服务
            say(miss + "。现在没有可用的模型，这句话我先不办。本地能办的：点击 某个按钮的文字、在 某个输入框 输入 一段文字、"
                    + "输入 一段文字、上滑、下滑、返回、主页、读屏，可以用「然后」串起来。");
            return;
        }
        ModelClient mc = new ModelClient(cfg);
        if (!mc.probe()) {
            say(mc.lastErrorOr("连不上模型服务，先停下。"));
            end(End.MODEL_STOP, mc.lastError());
            return;
        }

        // 意图闸（Mika IntentGate）：讨论 / 读取 / 操作。只有操作才进入读屏→动作→执行→等稳定→再读屏的循环。
        phase(Phase.READBACK, "判断意图：" + t);
        IntentGate.Intent intent = new IntentGate(mc::complete).assess(t);
        AppLog.i("意图闸：" + intent);
        if (cancelled) { end(End.CANCELLED, null); return; }
        if (mc.lastError() != null) {
            say(mc.lastError());
            end(End.MODEL_STOP, mc.lastError());
            return;
        }
        switch (intent) {
            case DISCUSS: {
                String ans = mc.answer(t, null);
                say(ans == null || ans.trim().isEmpty()
                        ? mc.lastErrorOr("这句话我没能回答（模型没有回复）。") : ans.trim());
                end(mc.lastError() != null ? End.MODEL_STOP : End.DONE, mc.lastError() != null ? mc.lastError() : "讨论");
                return;
            }
            case READ: {
                if (!ReadonlyAccessibilityService.isRunning()) {
                    say("只读无障碍没在运行，读不到屏幕，先不回答。");
                    end(End.BLIND, null);
                    return;
                }
                ScreenSnapshot snap = port().snapshot();
                if (snap == null) { say("读不到界面树。"); end(End.BLIND, null); return; }
                SafetyGuard.Verdict v = SafetyGuard.check(snap);
                if (v.stop()) {
                    say("这一屏需要你本人处理：" + v.reason + "。我不把它发给模型。");
                    end(End.NEEDS_HUMAN, v.reason);
                    return;
                }
                String ans = mc.answer(t, snap.toModelText(MainScreenEsp32Port.MAX_REFS));
                if (ans == null || ans.trim().isEmpty()) {
                    say(mc.lastErrorOr("模型没有回复，这一屏我先不念。"));
                    end(End.MODEL_STOP, mc.lastError());
                    return;
                }
                say(ans.trim());
                end(End.DONE, "读取");
                return;
            }
            case ACT:
            default:
                break;
        }
        if (!requireSuite()) { end(End.NOT_READY, host.suite().notReadyReason()); return; }
        // 短复述意图（设计 5.2）：发送、删除、提交、下单前会先念对象、内容、金额；付款留给本人
        say("我理解为：" + t + "。我会在主屏上一步步操作" + (mentionsCritical(t) ? "，要发送、删除、提交或下单前先把内容和金额念给你，等你说口令；付款留给你本人" : "")
                + "。不对请说「不对」，随时可以说取消。");
        if (cancelled) { end(End.CANCELLED, null); return; }
        modelLoop(t, cfg);
    }

    // ---------------- 本地意图（没有模型 Key 也能跑）：拆句 → 复述 → 每步读树 → ESP32 动手 → 静下来再读核对 ----------------

    /** 最近一次本地意图的过程记录（写到外部私有目录 intent-last.txt，也给界面看）。 */
    private volatile String lastLocalReport = "";

    public String lastLocalReport() { return lastLocalReport; }

    private void localLoop(String intent, java.util.List<LocalIntent.Step> plan) throws InterruptedException {
        StringBuilder rep = new StringBuilder();
        rep.append("远手 本地意图 ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date())).append('\n');
        rep.append("意图：").append(intent).append('\n');
        rep.append("计划：").append(LocalIntent.summary(plan)).append('\n');
        try {
            localLoopInner(plan, rep);
        } finally {
            rep.append("结局：").append(lastEnd == null ? "（无）" : lastEnd.name() + " " + lastEnd.label)
                    .append("，停在第 ").append(step).append(" 步\n");
            rep.append("动作通道：").append(host.suite().transport()).append('\n');
            lastLocalReport = rep.toString();
            AppLog.writeFile(host.context(), "intent-last.txt", lastLocalReport);
            AppLog.i("本地意图记录已写入 intent-last.txt");
        }
    }

    private void localLoopInner(java.util.List<LocalIntent.Step> plan, StringBuilder rep) throws InterruptedException {
        boolean needHand = false;
        for (LocalIntent.Step st : plan) needHand |= st.needsHand();
        say("我理解为：" + LocalIntent.summary(plan) + "。在主屏上一步步做，每步做完再看屏幕核对；碰到发送、删除、提交、下单先念给你确认，付款留给你本人。随时说「取消」。");
        if (needHand && !requireSuite()) { end(End.NOT_READY, host.suite().notReadyReason()); return; }
        if (!ReadonlyAccessibilityService.isRunning()) {
            say("只读无障碍没在运行，读不到界面树，先不动手，不猜坐标。");
            end(End.BLIND, null);
            return;
        }
        SuiteState s = host.suite();
        ScreenPort port = port();
        OperationGuard guard = new OperationGuard();
        gate.reset();
        ScreenSnapshot prev = null;
        for (int i = 0; i < plan.size(); i++) {
            LocalIntent.Step st = plan.get(i);
            step = i + 1;
            if (cancelled) { end(End.CANCELLED, null); return; }
            if (st.needsHand() && !requireSuite()) { end(End.NOT_READY, s.notReadyReason()); return; }

            phase(Phase.OBSERVING, "第 " + step + " 步：看屏幕");
            ScreenSnapshot snap = prev != null ? prev : settledSnapshot(false);
            prev = null;
            if (snap == null) {
                say("读不到界面树，先停下，不猜坐标。");
                end(End.BLIND, null);
                return;
            }
            SafetyGuard.Verdict v = SafetyGuard.check(snap);
            if (v.stop()) {
                say("停下了：" + v.reason + "。这一屏需要你本人处理，我不自动输入，也不会把它发给模型。");
                rep.append("第 ").append(step).append(" 步前 安全闸：").append(v.reason).append('\n');
                end(End.NEEDS_HUMAN, v.reason);
                return;
            }
            if (i == 0) guard.onScreen(SafetyGuard.isBlank(snap) ? null : snap.fingerprint(), false);
            rep.append("\n— 第 ").append(step).append(" 步：").append(st.describe()).append('\n');
            rep.append("  动手前：").append(snap.summary(8)).append("，指纹 ").append(Long.toHexString(snap.fingerprint())).append('\n');

            if (st.kind == LocalIntent.Kind.READ) {
                String tree = snap.toModelText(MainScreenEsp32Port.MAX_REFS);
                rep.append(tree);
                AppLog.writeFile(host.context(), "tree-last.txt", tree);
                lastTree = tree;
                treeSeq++;
                say("读到了：" + snap.summary(6) + "。完整编号列表在 tree-last.txt。");
                lastDone = "读屏";
                continue;
            }
            if (st.kind == LocalIntent.Kind.WAIT) {
                port.waitStable(Timing.afterHeavyPage());
                lastDone = "等一下";
                continue;
            }

            // 定位：只按界面树里的文字/编号/输入框找；找不到就停，不猜坐标
            int x = -1, y = -1;
            ScreenSnapshot.Node target = null;
            switch (st.kind) {
                case CLICK_TEXT:
                    target = snap.findByText(st.target);
                    if (target == null) { notFound("屏幕上没找到「" + st.target + "」", rep); return; }
                    break;
                case CLICK_REF:
                    target = snap.byRef(st.ref);   // 编号规则与「读屏」相同（端口读屏时已编号）
                    if (target == null) { notFound("界面树里没有 #" + st.ref, rep); return; }
                    break;
                case CLICK_XY:
                    x = st.x;
                    y = st.y;
                    target = snap.nodeAt(x, y);
                    break;
                case PASTE_INTO:
                    target = snap.findField(st.target);
                    if (target == null) { notFound("屏幕上没找到「" + st.target + "」输入框", rep); return; }
                    break;
                case PASTE:
                    target = snap.focusedField();
                    if (target == null) { notFound("现在没有输入框在等输入。先说「点击 某个输入框」或「在 某个输入框 输入 …」", rep); return; }
                    break;
                case SCROLL_DOWN:
                case SCROLL_UP:
                    target = snap.mainScrollable();
                    break;
                default:
                    break;
            }
            boolean click = st.kind == LocalIntent.Kind.CLICK_TEXT || st.kind == LocalIntent.Kind.CLICK_REF || st.kind == LocalIntent.Kind.CLICK_XY;
            if (target != null && x < 0 && (click || st.kind == LocalIntent.Kind.PASTE_INTO)) {
                int[] p = visibleCenter(target.bounds, s);
                if (p == null) { notFound("「" + label(target) + "」不在屏幕可点的范围里（可能要先上滑/下滑）", rep); return; }
                x = p[0];
                y = p[1];
            }
            if (target != null) rep.append("  目标：").append(label(target)).append(" ").append(target.bounds.toShortString())
                    .append(target.editable ? " 输入框" : "").append(target.clickable ? " 可点" : "").append('\n');
            if (x >= 0) rep.append("  落点：").append(x).append(',').append(y).append('\n');

            // 关键按钮走加严的确认闸（发送/删除/提交/下单/带金额的确认）；付款永不执行
            if (click) {
                IrreversibleDetector.Detection det = IrreversibleDetector.beforeTap(snap, target, x, y);
                if (det != null) {
                    rep.append("  确认闸：").append(det.action.label).append("（").append(det.why).append("）\n");
                    ModelAction a = new ModelAction();
                    a.type = "tap";
                    a.x = x;
                    a.y = y;
                    if (!passGate(det.action, a, snap, null, step)) return;
                }
            }
            if (cancelled) { end(End.CANCELLED, null); return; }
            OperationGuard.Verdict rv = guard.beforeAction(OperationGuard.signature(kindSig(st.kind), x, y, x, y, st.text));
            if (rv != null && rv.stop) { stopByGuard(rv); return; }

            phase(Phase.ACTING, "第 " + step + " 步：" + st.describe());
            long fp0 = snap.fingerprint();
            ActionResult res;
            switch (st.kind) {
                case CLICK_TEXT: case CLICK_REF: case CLICK_XY:
                    res = port.tapXY(x, y);
                    break;
                case PASTE_INTO:
                    // 先清空（Ctrl+End + 回退）再剪贴板 + Ctrl+V，读回完全一致才算；不一致 VERIFY_FAILED，不再贴
                    res = target.ref > 0 ? port.typeText(target.ref, port.snapshotId(), st.text)
                            : focusThenType(port, x, y, st.text);
                    break;
                case PASTE:
                    res = port.typeText(0, null, st.text);
                    break;
                case SCROLL_DOWN:
                case SCROLL_UP:
                    res = port.scroll(target == null ? 0 : target.ref, port.snapshotId(), st.kind == LocalIntent.Kind.SCROLL_DOWN);
                    break;
                case BACK:
                    res = port.back();
                    break;
                case HOME:
                    res = port.pressKey(ScreenPort.KEYCODE_HOME);
                    break;
                default:
                    res = ActionResult.ok();
                    break;
            }
            rep.append("  端口结果：").append(res).append('\n');
            if (!res.isOk()) {
                say("第 " + step + " 步" + st.describe() + "没做成：" + res.reason + "。没有改用其他方式重试，任务停下。");
                end(cancelled ? End.CANCELLED : endFor(res), res.reason);
                return;
            }
            gate.noteWrite();
            lastDone = st.describe();

            // 动手后：等页面静下来再读，核对
            ScreenSnapshot after = settledSnapshot(true);
            String check;
            if (after == null) {
                check = "动手后读不到界面树";
            } else {
                boolean changed = after.fingerprint() != fp0;
                check = changed ? "界面有变化" : "界面没有变化";
                if (res.verification == ActionResult.Verification.MATCH) check += "；输入框文字等于「" + st.text + "」";
                rep.append("  动手后：").append(after.summary(8)).append("，指纹 ").append(Long.toHexString(after.fingerprint())).append('\n');
                OperationGuard.Verdict gv = guard.onScreen(SafetyGuard.isBlank(after) ? null : after.fingerprint(), true);
                if (gv != null && gv.stop) { rep.append("  核对：").append(check).append('\n'); stopByGuard(gv); return; }
                prev = after;
            }
            rep.append("  核对：").append(check).append('\n');
            AppLog.i("第 " + step + " 步 " + st.describe() + " 经 " + s.transport() + "：" + check);
            say("第 " + step + " 步" + st.describe() + "：做了，" + check + "。");
        }
        say("办完了，共 " + plan.size() + " 步。");
        end(End.DONE, null);
    }

    /** 输入框没进编号（列表被截断）时：先点它拿焦点，再对焦点框打字。 */
    private static ActionResult focusThenType(ScreenPort port, int x, int y, String text) {
        ActionResult t = port.tapXY(x, y);
        if (!t.isOk()) return t;
        return port.typeText(0, null, text);
    }

    /** 端口结果 → 任务结局。 */
    private static End endFor(ActionResult r) {
        switch (r.status) {
            case VERIFY_FAILED: return End.VERIFY_FAILED;
            case NEEDS_HUMAN: return End.NEEDS_HUMAN;
            case NOT_READY: return End.NOT_READY;
            case BLOCKED: return End.DECLINED;
            case REF_EXPIRED: case UNSUPPORTED: return End.MODEL_STOP;
            default: return End.HAND_FAILED;
        }
    }

    private void notFound(String why, StringBuilder rep) {
        say(why + "，先停下，不猜坐标。");
        rep.append("  ").append(why).append('\n');
        end(End.NOT_FOUND, why);
    }

    private static String label(ScreenSnapshot.Node n) {
        String l = n.label();
        if (l.isEmpty() && n.hint != null) l = n.hint;
        if (l.length() > 20) l = l.substring(0, 20) + "…";
        return l.isEmpty() ? "(无字元素)" : l;
    }

    private static String kindSig(LocalIntent.Kind k) {
        switch (k) {
            case CLICK_TEXT: case CLICK_REF: case CLICK_XY: case PASTE_INTO: return "tap";
            case PASTE: return "paste";
            case SCROLL_DOWN: case SCROLL_UP: return "scroll";
            case BACK: return "back";
            case HOME: return "home";
            default: return "wait";
        }
    }

    /** 元素在主屏可点范围（避开状态栏、底部手势条、左右返回手势边缘）里的可见部分的中心；整块不在范围里返回 null。 */
    static int[] visibleCenter(android.graphics.Rect b, SuiteState s) {
        int left = Math.max(b.left, HandExecutor.SAFE_SIDE), right = Math.min(b.right, s.screenW - HandExecutor.SAFE_SIDE);
        int top = Math.max(b.top, HandExecutor.SAFE_TOP), bottom = Math.min(b.bottom, s.screenH - HandExecutor.SAFE_BOTTOM);
        if (right - left < 8 || bottom - top < 8) return null;
        return new int[]{(left + right) / 2, (top + bottom) / 2};
    }

    private static boolean mentionsCritical(String t) {
        for (String w : new String[]{"发", "回复", "回他", "回她", "告诉", "买", "下单", "订", "付", "转账", "评论"}) {
            if (t.contains(w)) return true;
        }
        return false;
    }


    /** 主屏 + ESP32 的屏幕端口（外面套 App 范围装饰器）；第一次用时建。 */
    private volatile ScreenPort port;

    private ScreenPort port() {
        ScreenPort p = port;
        if (p == null) {
            synchronized (this) {
                if (port == null) port = MainScreenPorts.create(host.context(), host.hand(), host.suite());
                p = port;
            }
        }
        return p;
    }

    /** 模型/口头说的应用名 → 包名；不在范围里返回 null。是否允许打开由 AppAccessScreenPort 决定。 */
    private static String appPackage(String name) {
        if (name == null) return null;
        switch (name.trim().toLowerCase()) {
            case "微信": case "wechat": return "com.tencent.mm";
            case "淘宝": case "taobao": return "com.taobao.taobao";
            case "闲鱼": case "xianyu": return "com.taobao.idlefish";
            case "支付宝": case "alipay": return "com.eg.android.AlipayGphone";
            case "美团外卖": case "meituanwaimai": return "com.sankuai.meituan.takeoutnew";
            case "美团": case "meituan": return "com.sankuai.meituan";
            default: return null;
        }
    }

    /** 确认门闩：复述 → 等 12 秒 → 无回答只再问一次 → 只有完整口令算同意，含糊当取消。付款永远不代付、不问。 */
    /** 0.1.13：带申报的版本，首页「等你确认」用 pendingConfirm 显示要点；规则与下面完全相同。 */
    private boolean awaitConfirm(ConfirmationGate.Declaration d) throws InterruptedException {
        if (d.action.neverExecuted()) return awaitConfirm(d.action, ConfirmationGate.readback(d));
        pendingConfirm = d;
        confirmPreview = null;
        if (ScreenCapture.available()) {
            // 另起线程截一张缩略图，不耽误复述；只放内存
            SuiteState s = host.suite();
            Thread t = new Thread(() -> {
                try {
                    int w = s.screenW > 0 ? s.screenW : 1080, h = s.screenH > 0 ? s.screenH : 2400;
                    Bitmap b = ScreenCapture.capture(host.context(), w, h, 320);
                    if (b != null && pendingConfirm == d) confirmPreview = Bitmap.createScaledBitmap(b, 360, 360 * h / w, true);
                } catch (Exception ignored) {}
            }, "confirm-preview");
            t.setDaemon(true);
            t.start();
        }
        try {
            return awaitConfirm(d.action, ConfirmationGate.readback(d));
        } finally {
            pendingConfirm = null;
            confirmPreview = null;
        }
    }

    private boolean awaitConfirm(IrrevAction g, String readback) throws InterruptedException {
        if (g.neverExecuted()) {
            say(readback);
            return false;
        }
        phase(Phase.WAITING_CONFIRM, "等待确认：" + g.phrase);
        // 微信交来的事等久一点（本人可能在开车）；首页/局域网的仍是 12 秒 × 2
        long wait = origin == Origin.WECHAT ? Timing.WECHAT_CONFIRM_WAIT_MS : Timing.CONFIRM_WAIT_MS;
        for (int attempt = 0; attempt < 2; attempt++) {
            replies.clear();
            confirmSeq++;
            awaitingConfirm = true;
            say(attempt == 0 ? readback : "没听到回答，再问一次：" + readback);
            String r = replies.poll(wait, TimeUnit.MILLISECONDS);
            awaitingConfirm = false;
            if (cancelled) {
                say("好，已取消。");
                return false;
            }
            if (r == null) continue;
            ConfirmationGate.Reply rep = ConfirmationGate.classify(g, r);
            if (rep == ConfirmationGate.Reply.CONFIRM) {
                AppLog.i("确认通过：" + g.phrase);
                return true;
            }
            say(rep == ConfirmationGate.Reply.CANCEL ? "好，取消了。" : "没听到「" + g.phrase + "」这个口令，按取消处理。");
            return false;
        }
        say("一直没听到回答，已取消。");
        return false;
    }

    // ---------------- 一句话办事（意图闸判为 ACT 之后）：读屏 → 问模型 → 确认闸 → 执行 → 等稳定 → 再读屏 ----------------

    /**
     * 模型循环步数上限。0 = 不限制（与 Mika 设置默认一致）；do_steps 整批只算一步。
     * 有限上限时，用尽后再给一轮只能 done/stop 的收尾。
     */
    static final int MAX_STEPS = 0;
    static final int UNLIMITED_STEPS = Integer.MAX_VALUE;

    private static final java.util.Set<String> NEEDS_POINT = new java.util.HashSet<>(java.util.Arrays.asList(
            "tap", "long_press", "swipe"));
    /** 会「落定」的动作：确认闸只拦这些（打字、滚动、返回本身不发出任何东西）。 */
    private static final java.util.Set<String> COMMITTING = new java.util.HashSet<>(java.util.Arrays.asList(
            "tap", "long_press", "enter", "swipe"));

    private static int stepBudget() {
        return MAX_STEPS <= 0 ? UNLIMITED_STEPS : MAX_STEPS;
    }

    private void modelLoop(String intent, ModelConfig cfg) throws InterruptedException {
        ModelClient mc = new ModelClient(cfg);
        ScreenPort port = port();
        StepHistory hist = new StepHistory();
        Playbook book = openPlaybook();
        SuiteState s = host.suite();
        OperationGuard guard = new OperationGuard();
        InterruptionGuard interruptions = new InterruptionGuard();
        gate.reset();
        String pendingHint = null;
        int budget = stepBudget();
        try {
            for (int step = 1; step <= budget; step++) {
                this.step = step;
                if (cancelled) { end(End.CANCELLED, null); return; }
                if (!requireSuite()) { end(End.NOT_READY, null); return; }

                phase(Phase.OBSERVING, "第 " + step + " 步：看屏幕");
                ScreenSnapshot snap = settledSnapshot(step > 1);
                if (snap != null) {
                    snap = settleScreen(step, snap, port, s, hist, book, intent, interruptions);
                    if (snap == null) return;
                }
                OperationGuard.Verdict gv = guard.onScreen(SafetyGuard.isBlank(snap) ? null : snap.fingerprint(), step > 1);
                if (gv != null && gv.stop) { stopByGuard(gv); return; }
                if (gv != null) pendingHint = gv.message;

                String jpeg = null;
                if (cfg.vision && ScreenCapture.available()) {
                    Bitmap b = ScreenCapture.capture(host.context(), s.screenW, s.screenH, 320);
                    jpeg = ScreenCapture.toJpegBase64(b, 720);
                }
                if (snap == null && jpeg == null) {
                    say("看不到屏幕：只读无障碍没开，也没有可用的截图。先停下，不猜坐标。");
                    end(End.BLIND, null);
                    return;
                }
                if (cancelled) { end(End.CANCELLED, null); return; }

                phase(Phase.DECIDING, "第 " + step + " 步：问模型");
                if (pendingHint != null) {
                    hist.note(step, "提醒：" + pendingHint);
                    pendingHint = null;
                }
                if (budget != UNLIMITED_STEPS && budget >= 10 && (step == budget / 2 || step == (budget * 3) / 4)) {
                    hist.note(step, "提醒：已用 " + step + "/" + budget + " 步。");
                }
                String prior = book.relevant(intent, snap == null ? "" : snap.packageName);
                ModelAction a = mc.nextAction(intent, hist.forModel(),
                        snap == null ? null : snap.toModelText(MainScreenEsp32Port.MAX_REFS), jpeg, prior);
                AppLog.i("模型第 " + step + " 步: " + a.type
                        + ("do_steps".equals(a.type) && a.steps != null ? " ×" + a.steps.size() : "")
                        + (a.ref > 0 ? " ref=" + a.ref : "") + (a.gate != null ? " gate=" + a.gate : ""));
                if (cancelled) { end(End.CANCELLED, null); return; }

                if ("done".equals(a.type)) {
                    if (acceptDone(mc, intent, hist, snap, a)) return;
                    continue;
                }
                if ("goal_check".equals(a.type)) {
                    hist.note(step, "模型自行返回 goal_check，不能代替本机核对，任务继续。");
                    continue;
                }
                if ("stop".equals(a.type) && DoneGate.decide(a.type, false) == DoneGate.Decision.STOP) {
                    say(a.say.isEmpty() ? "先停下。" : a.say);
                    end(End.MODEL_STOP, a.say);
                    return;
                }
                if ("do_steps".equals(a.type)) {
                    String bad = BatchPlan.validate(a);
                    if (bad != null) {
                        hist.note(step, "一次多步无效：" + bad + "，没有执行。请改成合法的 steps 或改回单步。");
                        continue;
                    }
                    String[] hint = new String[] { pendingHint };
                    if (!runBatch(step, a, snap, port, s, guard, hist, hint, interruptions)) return;
                    pendingHint = hint[0];
                    if (step % 15 == 0) say(hist.progressSpeech(step));
                    continue;
                }
                if (!BatchPlan.KNOWN.contains(a.type)) {
                    say(a.say.isEmpty() ? "先停下。" : a.say);
                    end(End.MODEL_STOP, a.say);
                    return;
                }

                String[] hint = new String[] { pendingHint };
                String r = runOneAction(step, a, snap, port, s, guard, hist, hint, true);
                pendingHint = hint[0];
                if ("ended".equals(r)) return;
                if (r != null && !"gated".equals(r)) {
                    hist.action(step, a.type, targetOf(a, null), appOf(a, snap), "没做成：" + r, screenNote(snap),
                            step + ". 没做成：" + r);
                    say(r);
                    end(End.MODEL_STOP, r);
                    return;
                }
                if (step % 15 == 0) say(hist.progressSpeech(step));
            }
            closeout(intent, cfg, mc, hist, s, book, interruptions);
        } finally {
            try {
                book.record(intent, hist, hist.goalVerified(), clearFailure(lastEnd, hist));
            } catch (Exception e) {
                AppLog.i("本机记事没写成: " + e.getClass().getSimpleName());
            }
        }
    }

    private Playbook openPlaybook() {
        try {
            return Playbook.open(new File(host.context().getFilesDir(), "playbook"));
        } catch (Exception e) {
            AppLog.i("本机记事读不出: " + e.getClass().getSimpleName());
            return Playbook.open(null);
        }
    }

    /** 明确失败才记进应用经验。取消、本人接手、步数用完都不记成「这条路走不通」。 */
    private static String clearFailure(End e, StepHistory hist) {
        if (e == null) return null;
        switch (e) {
            case GUARD:
            case HAND_FAILED:
            case NOT_FOUND:
            case VERIFY_FAILED:
                return e.label;
            case MODEL_STOP:
                return hardMiss(hist) ? "这一步没做成" : null;
            default:
                return null;
        }
    }

    private static boolean hardMiss(StepHistory hist) {
        if (hist == null) return false;
        for (StepHistory.Turn t : hist.turns()) {
            if ("action".equals(t.kind) && t.result != null && t.result.startsWith("没做成")) return true;
        }
        return false;
    }

    /**
     * done 不能直接结束。先强制一轮目标核对；只有核对明确达成才 DONE。
     * 没达成就写进步骤并继续。返回 true 表示任务已经结束（办完或取消）。
     */
    private boolean acceptDone(ModelClient mc, String intent, StepHistory hist, ScreenSnapshot snap, ModelAction a)
            throws InterruptedException {
        if (cancelled) { end(End.CANCELLED, null); return true; }
        GoalCheck.Verdict v = askGoal(mc, intent, hist, snap);
        if (cancelled) { end(End.CANCELLED, null); return true; }
        if (DoneGate.decide("done", v.met) != DoneGate.Decision.ACCEPT) {
            String why = v.say == null || v.say.isEmpty() ? "目标还没在当前屏幕上得到核对。" : v.say;
            hist.note(this.step, "目标核对未通过：" + why + "。还不能结束，请对照用户原话继续办，不要重复同一句 done。");
            return false;
        }
        hist.markGoalVerified();
        String spoken = a.say == null || a.say.isEmpty() ? v.say : a.say;
        if (spoken == null || spoken.isEmpty() || Sensitive.blocked(spoken)) spoken = "办完了。";
        say(spoken);
        end(End.DONE, null);
        return true;
    }

    private GoalCheck.Verdict askGoal(ModelClient mc, String intent, StepHistory hist, ScreenSnapshot snap) {
        String screen = snap == null ? null : snap.toModelText(MainScreenEsp32Port.MAX_REFS);
        String raw = mc.complete(GoalCheck.SYSTEM, GoalCheck.userPrompt(intent, hist.forModel(), screen));
        GoalCheck.Verdict v = GoalCheck.parse(raw);
        AppLog.i("目标核对：" + (v.met ? "达成" : "未达成"));
        return v;
    }

    /** 有限步数用尽后再问一轮。done 仍要过目标核对；核对不过就按步数用完停下。stop 不另做核对。 */
    private void closeout(String intent, ModelConfig cfg, ModelClient mc, StepHistory hist, SuiteState s, Playbook book,
                          InterruptionGuard interruptions)
            throws InterruptedException {
        if (cancelled) { end(End.CANCELLED, null); return; }
        this.step = (MAX_STEPS <= 0 ? step : MAX_STEPS) + 1;
        hist.note(this.step, "提醒：步数已用完，只能返回 done 或 stop，用 say 说明办到哪了，不要再给操作。");
        phase(Phase.DECIDING, "收尾：问模型");
        ScreenSnapshot snap = port().snapshot();
        if (snap != null) {
            snap = settleScreen(this.step, snap, port(), s, hist, book, intent, interruptions);
            if (snap == null) return;
        }
        String jpeg = null;
        if (cfg.vision && ScreenCapture.available() && s != null) {
            Bitmap b = ScreenCapture.capture(host.context(), s.screenW, s.screenH, 320);
            jpeg = ScreenCapture.toJpegBase64(b, 720);
        }
        String prior = book.relevant(intent, snap == null ? "" : snap.packageName);
        ModelAction a = mc.nextAction(intent, hist.forModel(),
                snap == null ? null : snap.toModelText(MainScreenEsp32Port.MAX_REFS), jpeg, prior);
        if ("done".equals(a.type)) {
            if (acceptDone(mc, intent, hist, snap, a)) return;
            say("步骤已用完，目标也还没核对到办完，先停下。");
            end(End.STEP_LIMIT, null);
            return;
        }
        say(a.say.isEmpty() ? "步骤太多，先停下。" : a.say);
        end(End.STEP_LIMIT, null);
    }

    /**
     * 读屏之后、问模型之前：安全闸，然后由代码处理广告和跳转。返回 null 表示任务已经结束。
     * 交给模型的那一屏是处理完之后重新读的，上一屏的编号不再使用。
     */
    private ScreenSnapshot settleScreen(int step, ScreenSnapshot snap, ScreenPort port, SuiteState suite,
                                        StepHistory hist, Playbook book, String intent, InterruptionGuard guard)
            throws InterruptedException {
        for (int round = 0; round < 8; round++) {
            if (cancelled) { end(End.CANCELLED, null); return null; }
            if (snap == null) {
                say("看不到屏幕：只读无障碍没开，也没有可用的截图。先停下，不猜坐标。");
                end(End.BLIND, null);
                return null;
            }
            SafetyGuard.Verdict safety = SafetyGuard.check(snap);
            if (safety.stop()) {
                say("停下了：" + safety.reason + "。这一步需要你本人处理，我不会继续自动输入。");
                end(End.NEEDS_HUMAN, safety.reason);
                return null;
            }
            guard.setEntryHint(book.skills().entryFor(intent));
            InterruptionGuard.Decision d = guard.assess(snap);
            if (d.action == InterruptionGuard.Action.CLEAR) {
                guard.accept(snap);
                return snap;
            }
            if (d.action == InterruptionGuard.Action.STOP) {
                say(d.speak.isEmpty() ? "先停下。" : d.speak);
                end(d.needsHuman ? End.NEEDS_HUMAN : End.GUARD, d.speak);
                return null;
            }
            if (!applyInterrupt(step, d, port, suite, hist, book, guard)) return null;
            snap = d.action == InterruptionGuard.Action.WAIT ? port.snapshot() : settledSnapshot(true);
            if (d.action == InterruptionGuard.Action.TAP_CLOSE && guard.overCap()) {
                say(guard.consecutivePopups() >= InterruptionGuard.CONSECUTIVE_POPUP_CAP
                        ? "红包弹窗一连关了 " + InterruptionGuard.CONSECUTIVE_POPUP_CAP + " 次，先停下，没有继续点。"
                        : "这一趟已经关了 " + InterruptionGuard.TASK_POPUP_CAP + " 次广告弹窗，先停下，没有继续点。");
                end(End.GUARD, "广告弹窗过多");
                return null;
            }
        }
        say("广告或跳转还在，需要你本人处理。我没有在那一页上继续点。");
        end(End.NEEDS_HUMAN, "广告还在");
        return null;
    }

    /**
     * 执行打断处理。只点关闭/跳过，或按返回、主页、进入路线。不过确认闸，不用截图找叉，不用无障碍点击。
     * @return false 表示任务已经结束
     */
    private boolean applyInterrupt(int step, InterruptionGuard.Decision d, ScreenPort port, SuiteState suite,
                                   StepHistory hist, Playbook book, InterruptionGuard guard) throws InterruptedException {
        switch (d.action) {
            case TAP_CLOSE: {
                if (d.target == null) {
                    guard.markCloseUnusable();
                    return true;
                }
                int[] p = visibleCenter(d.target.bounds, suite);
                if (p == null) {
                    guard.markCloseUnusable();
                    return true;
                }
                ActionResult res = port.tapXY(p[0], p[1]);
                if (!res.isOk()) {
                    say("关弹窗没点到：" + res.reason + "。没有改用其他方式。");
                    end(cancelled ? End.CANCELLED : endFor(res), res.reason);
                    return false;
                }
                guard.commit(d);
                hist.note(step, InterruptionGuard.popupNote(step));
                if (d.memoryApp != null && !d.memoryApp.isEmpty()) book.memory().learnInterruption(d.memoryApp, d.homeFeed);
                AppLog.i("关掉弹窗");
                return true;
            }
            case BACK: {
                ActionResult res = port.back();
                if (!res.isOk()) {
                    say("返回没做成：" + res.reason + "。没有改用其他方式。");
                    end(cancelled ? End.CANCELLED : endFor(res), res.reason);
                    return false;
                }
                guard.commit(d);
                hist.note(step, "第 " + step + " 步按了返回，离开跳转页。");
                return true;
            }
            case HOME: {
                ActionResult res = port.pressKey(ScreenPort.KEYCODE_HOME);
                if (!res.isOk()) {
                    say("回主页没做成：" + res.reason + "。没有在外面的页面上点。");
                    end(cancelled ? End.CANCELLED : endFor(res), res.reason);
                    return false;
                }
                guard.commit(d);
                hist.note(step, "第 " + step + " 步回到桌面，没有在外面的页面上点。");
                return true;
            }
            case WAIT: {
                guard.commit(d);
                for (int i = 0; i < InterruptionGuard.FULLSCREEN_WAIT_SLICES; i++) {
                    if (cancelled) { end(End.CANCELLED, null); return false; }
                    Timing.sleep(Timing.afterHeavyPage());
                }
                hist.note(step, "第 " + step + " 步广告没有关闭按钮，只等不点。");
                return true;
            }
            case REENTER: {
                String pkg = guard.expectedPackage();
                if (pkg != null && !pkg.isEmpty()) {
                    ActionResult opened = port.openApp(pkg);
                    if (!opened.isOk()) {
                        say("没能按进入路线重新打开应用：" + opened.reason + "。没有在广告页里继续点。");
                        end(cancelled ? End.CANCELLED : endFor(opened), opened.reason);
                        return false;
                    }
                    ScreenSnapshot now = settledSnapshot(true);
                    if (now != null) {
                        SafetyGuard.Verdict sv = SafetyGuard.check(now);
                        if (sv.stop()) {
                            say("停下了：" + sv.reason + "。这一步需要你本人处理，我不会继续自动输入。");
                            end(End.NEEDS_HUMAN, sv.reason);
                            return false;
                        }
                        ScreenSnapshot.Node entry = InterruptionGuard.entryNode(now, guard.entryHint());
                        if (entry != null) {
                            int[] p = visibleCenter(entry.bounds, suite);
                            if (p != null) {
                                ActionResult tap = port.tapXY(p[0], p[1]);
                                if (!tap.isOk()) {
                                    say("进入路线没点到：" + tap.reason + "。没有在广告页里继续点。");
                                    end(cancelled ? End.CANCELLED : endFor(tap), tap.reason);
                                    return false;
                                }
                            }
                        }
                    }
                }
                guard.commit(d);
                hist.note(step, "第 " + step + " 步按进入路线重新打开，没有在广告页里点。");
                return true;
            }
            default:
                return true;
        }
    }

    /**
     * 一次多步：整批对步数上限算一步；子步逐个执行、按条件等界面，不再为加载问模型。
     * 编号以「此刻这一屏」为准，缺编号或条件没等到就停在这一批、把原因写进历史，任务继续下一轮。
     * 发送/删除/提交/下单停在该子步改走确认闸，后面的子步不做；付款直接停任务。
     * @return false 表示任务已结束
     */
    private boolean runBatch(int step, ModelAction batch, ScreenSnapshot snap, ScreenPort port, SuiteState s,
                             OperationGuard guard, StepHistory hist, String[] hint, InterruptionGuard interruptions)
            throws InterruptedException {
        StringBuilder line = new StringBuilder();
        line.append(step).append(". 一次多步共 ").append(batch.steps.size()).append(" 个子动作");
        if (!batch.say.isEmpty()) line.append(" — ").append(batch.say);
        line.append('\n');
        hist.action(step, "do_steps", batch.steps.size() + " 个子动作", appOf(batch, snap), "开始执行", screenNote(snap), line.toString());
        String plannedPkg = snap == null ? "" : snap.packageName;
        long plannedFp = snap == null ? 0L : snap.fingerprint();
        ScreenSnapshot cur = snap;
        for (int i = 0; i < batch.steps.size(); i++) {
            if (cancelled) { end(End.CANCELLED, null); return false; }
            if (i > 0 && InterruptionGuard.refsStale(plannedPkg, plannedFp, cur)) {
                hist.note(step, "一次多步停住：包名或界面指纹已经变了，这一屏之前的编号全部作废，后面没做。");
                AppLog.i("一次多步因包名或指纹变化停住");
                return true;
            }
            if (cur != null && interruptions.assess(cur).action != InterruptionGuard.Action.CLEAR) {
                hist.note(step, "一次多步遇到广告或跳转，编号作废，后面没做。没有把这屏交给模型。");
                AppLog.i("一次多步遇到打断，停住");
                return true;
            }
            ModelAction a = batch.steps.get(i);
            if (cur != null) {
                SafetyGuard.Verdict v = SafetyGuard.check(cur);
                if (v.stop()) {
                    say("停下了：" + v.reason + "。这一步需要你本人处理，我不会继续自动输入。");
                    end(End.NEEDS_HUMAN, v.reason);
                    return false;
                }
            }
            String r = runOneAction(step, a, cur, port, s, guard, hist, hint, false);
            if ("ended".equals(r)) return false;
            if ("gated".equals(r)) {
                hist.note(step, "一次多步在已确认的关键步之后结束，后面没做。");
                return true;
            }
            if (r != null) {
                hist.note(step, "一次多步停在子步 " + (i + 1) + "：" + r + "，后面没做。");
                AppLog.i("一次多步停在子步 " + (i + 1) + "：" + r);
                return true;
            }
            if (UntilCheck.hasUntil(a)) {
                boolean met = waitUntil(a, port);
                if (cancelled) { end(End.CANCELLED, null); return false; }
                if (!met && UntilCheck.hasContent(a)) {
                    hist.note(step, "一次多步停在子步 " + (i + 1) + "：等到时限仍未满足等待条件，后面没做。");
                    AppLog.i("一次多步等待未满足，子步 " + (i + 1));
                    return true;
                }
            }
            cur = settledSnapshot(true);
            if (cur != null) {
                SafetyGuard.Verdict v = SafetyGuard.check(cur);
                if (v.stop()) {
                    say("停下了：" + v.reason + "。这一步需要你本人处理，我不会继续自动输入。");
                    end(End.NEEDS_HUMAN, v.reason);
                    return false;
                }
            }
            OperationGuard.Verdict gv = guard.onScreen(SafetyGuard.isBlank(cur) ? null : cur.fingerprint(), true);
            if (gv != null && gv.stop) { stopByGuard(gv); return false; }
            if (gv != null) hint[0] = gv.message;
        }
        return true;
    }

    /**
     * 解析目标、过确认闸、执行一个已知动作。
     * @param failTaskOnBadTarget 单步为 true（找不到就停任务）；一次多步为 false（把原因交回）。
     * @return null 已执行；"ended" 任务已结束；其他字符串是未执行的原因
     */
    private String runOneAction(int step, ModelAction a, ScreenSnapshot snap, ScreenPort port, SuiteState s,
                                OperationGuard guard, StepHistory hist, String[] hint,
                                boolean failTaskOnBadTarget) throws InterruptedException {
        ScreenSnapshot.Node target = null;
        if (a.ref > 0) {
            target = snap == null ? null : snap.byRef(a.ref);
            if (target == null) {
                return "第 " + a.ref + " 号元素不在这一屏上，不猜坐标";
            }
            if (!"swipe".equals(a.type)) {
                int[] p = visibleCenter(target.bounds, s);
                if (p == null && NEEDS_POINT.contains(a.type)) {
                    return "第 " + a.ref + " 号元素不在屏幕可点的范围里";
                }
                if (p != null) { a.x = p[0]; a.y = p[1]; }
            }
        } else if (snap != null && a.x >= 0) {
            target = snap.nodeAt(a.x, a.y);
        }
        if (NEEDS_POINT.contains(a.type) && (a.x < 0 || a.y < 0)) {
            return "这一步没有给出要点的位置，不猜坐标";
        }

        IrrevAction g = IrrevAction.fromModel(a.gate);
        String typing = null;
        if (!COMMITTING.contains(a.type)) {
            if (g == IrrevAction.PAY) {
                say(ConfirmationGate.readback(ConfirmationGate.Declaration.from(IrrevAction.PAY, snap == null ? "" : snap.packageName, a.details)));
                end(End.NEEDS_HUMAN, "付款交本人");
                return "ended";
            }
            g = null;
        } else if ("tap".equals(a.type) || "long_press".equals(a.type)) {
            IrreversibleDetector.Detection det = IrreversibleDetector.beforeTap(snap, target, a.x, a.y);
            g = IrrevAction.stricter(g, det == null ? null : det.action);
        } else if ("enter".equals(a.type)) {
            IrreversibleDetector.Detection det = IrreversibleDetector.beforeEnter(snap);
            if (det != null) {
                g = IrrevAction.stricter(g, det.action);
                ScreenSnapshot.Node f = snap == null ? null : snap.focusedField();
                typing = f == null ? null : f.typedText();
            }
        }
        if (g != null && !failTaskOnBadTarget && g != IrrevAction.PAY) {
            // 批里遇到关键步：走确认闸，只做这一下
            AppLog.i("一次多步遇到关键步 " + g.label + "，改走确认闸");
        }
        if (g != null && !passGate(g, a, snap, typing, step)) return "ended";
        if (cancelled) { end(End.CANCELLED, null); return "ended"; }

        OperationGuard.Verdict rv = guard.beforeAction(OperationGuard.signature(a.type, a.x, a.y, a.x2, a.y2, a.text));
        if (rv != null && rv.stop) { stopByGuard(rv); return "ended"; }
        if (rv != null) hint[0] = rv.message;

        phase(Phase.ACTING, "第 " + step + " 步：" + describe(a, target));
        ActionResult res = execute(port, a, g != null);
        AppLog.i("第 " + step + " 步 " + a.type + " → " + res.status);
        if (!res.isOk()) {
            hist.action(step, a.type, targetOf(a, target), appOf(a, snap), "没做成：" + res.reason, screenNote(snap),
                    step + ". " + describe(a, target) + " 没做成：" + res.reason);
            say("这一步没做成：" + res.reason + "。没有改用其他方式重试，任务停下。");
            end(cancelled ? End.CANCELLED : endFor(res), res.reason);
            return "ended";
        }
        if (g == null && !"wait".equals(a.type)) gate.noteWrite();
        lastDone = describe(a, target);
        StringBuilder line = new StringBuilder();
        line.append(step).append(". ").append(lastDone);
        String result = "做成了";
        if (res.verification == ActionResult.Verification.MATCH) {
            line.append("（输入框核对一致）");
            result = "做成了（输入框核对一致）";
        }
        if (!a.say.isEmpty()) line.append(" — ").append(a.say);
        line.append('\n');
        hist.action(step, a.type, targetOf(a, target), appOf(a, snap), result, screenNote(snap), line.toString());
        if (g != null) {
            hist.note(step, "（上一步是你确认过的「" + g.label + "」，已执行；不要再做第二次）");
            port.awaitChange(Timing.afterPageOpen());
            return "gated";
        }
        return null;
    }

    private boolean waitUntil(ModelAction a, ScreenPort port) throws InterruptedException {
        if (a.waitStable) port.waitStable(Timing.afterPageOpen());
        if (!UntilCheck.hasContent(a)) return true;
        long deadline = System.currentTimeMillis() + UntilCheck.timeoutMs(a);
        while (true) {
            if (cancelled) return false;
            ScreenSnapshot now = port.snapshot();
            if (UntilCheck.holds(a, now)) return true;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return false;
            port.awaitChange((int) Math.min(left, 500));
        }
    }

    private static String targetOf(ModelAction a, ScreenSnapshot.Node target) {
        String lab = target == null ? "" : target.label();
        lab = lab.replace('\n', ' ').trim();
        if (lab.length() > 24) lab = lab.substring(0, 24) + "…";
        if (!lab.isEmpty() && !Sensitive.blocked(lab)) return lab;
        if (a == null) return "";
        if ("open_app".equals(a.type)) return a.app == null ? "" : a.app.trim();
        if ("type".equals(a.type)) {
            String t = a.text == null ? "" : a.text.trim();
            if (t.isEmpty() || Sensitive.blocked(t) || t.length() > 40) return "输入框";
            return t;
        }
        if ("scroll".equals(a.type)) return "up".equals(a.text) ? "往回" : "往下";
        if ("clear".equals(a.type)) return "输入框";
        return "";
    }

    private static String appOf(ModelAction a, ScreenSnapshot snap) {
        if (a != null && "open_app".equals(a.type)) {
            String d = UntilCheck.displayName(a.app);
            if (d != null) return d;
        }
        if (snap != null) {
            String d = UntilCheck.displayName(snap.packageName);
            if (d != null) return d;
        }
        return "";
    }

    private static String screenNote(ScreenSnapshot snap) {
        if (snap == null) return "";
        String app = UntilCheck.displayName(snap.packageName);
        if (app == null) app = "";
        String title = snap.windowTitle == null ? "" : snap.windowTitle.trim().replace('\n', ' ');
        if (title.length() > 24) title = title.substring(0, 24) + "…";
        if (Sensitive.blocked(title)) title = "";
        if (app.isEmpty()) return title;
        if (title.isEmpty()) return app;
        return app + " · " + title;
    }

    /**
     * 确认闸：付款永不执行；否则申报（对象/内容/金额）→ 复述给本人 → 等口令 → 用新读的一屏核对后才放行。
     * 返回 false 时任务已结束（已说明原因）。
     */
    private boolean passGate(IrrevAction g, ModelAction a, ScreenSnapshot snap, String typing, int step) throws InterruptedException {
        String app = snap == null ? "" : snap.packageName;
        if (g.neverExecuted()) {
            say(ConfirmationGate.readback(ConfirmationGate.Declaration.from(g, app, a.details)));
            end(End.NEEDS_HUMAN, "付款交本人");
            return false;
        }
        CallChannel c = channel;
        if (c == null || !c.active()) {
            // Mika：没人在场的任务遇到要确认的一步不等人，直接不做并照实说明
            end(End.DECLINED, "没有人在线确认");
            return false;
        }
        if (snap == null) {
            say("读不到界面树，没法核对要" + g.label + "的内容，这一步不做。");
            end(End.DECLINED, "无界面树不放行");
            return false;
        }
        // 发送：正文以输入框里真实的字为准（先起草再发）
        if (g == IrrevAction.SEND && typing != null && !typing.isEmpty()) a.details.put("text", typing);
        if (g == IrrevAction.SEND && blank(a.details.get("text"))) {
            for (ScreenSnapshot.Node n : snap.nodes) {
                if (n.editable && !n.typedText().isEmpty()) { a.details.put("text", n.typedText()); break; }
            }
        }
        // 金额：屏上有应付金额而模型没报时，用屏上的数复述（本人听到的就是屏上的数，放行前还会再核对一次）
        String payable = IrreversibleDetector.payableAmount(snap);
        if (payable != null && blank(a.details.get("amount")) && g != IrrevAction.SEND) a.details.put("amount", payable);
        ConfirmationGate.Declaration decl = ConfirmationGate.Declaration.from(g, app, a.details);
        ConfirmationGate.Declare dr = gate.declare(decl, snap.packageName, snap);
        if (dr != ConfirmationGate.Declare.PENDING) {
            switch (dr) {
                case DECLINED: say("这件事你刚才已经说不要了，我不再问，先停下。"); break;
                case INCOMPLETE: say("要" + g.label + "，但" + gate.problem() + "，我不问也不执行，先停下。"); break;
                default: say("要" + g.label + "的应用和前台对不上，我不问也不执行，先停下。"); break;
            }
            end(End.DECLINED, gate.problem());
            return false;
        }
        boolean ok = awaitConfirm(decl);
        gate.decide(ok, step);
        if (!ok) {
            say("没有执行这一步。");
            end(cancelled ? End.CANCELLED : End.DECLINED, null);
            return false;
        }
        // 用此刻重新读的一屏核对：前台、对象、内容、金额，还有要点的那个按钮还在原处
        ScreenSnapshot fresh = port().snapshot();
        String freshTyping = null;
        if ("enter".equals(a.type)) {
            ScreenSnapshot.Node f = fresh == null ? null : fresh.focusedField();
            freshTyping = f == null ? "" : f.typedText();
        }
        ConfirmationGate.Permit p = gate.permits(g, fresh, step, freshTyping);
        if (p.allowed && ("tap".equals(a.type) || "long_press".equals(a.type))) {
            IrreversibleDetector.Detection now = IrreversibleDetector.beforeTap(fresh, null, a.x, a.y);
            ScreenSnapshot.Node before = snap.nodeAt(a.x, a.y), after = fresh == null ? null : fresh.nodeAt(a.x, a.y);
            String lb = before == null ? "" : before.label(), la = after == null ? "" : after.label();
            if (!lb.equals(la) && (now == null || now.action != g)) p = ConfirmationGate.Permit.no("要点的按钮位置变了");
        }
        if (!p.allowed) {
            say("听到了「" + g.phrase + "」，但核对没通过：" + p.reason + "。这一步不做，先停下。");
            end(End.DECLINED, p.reason);
            return false;
        }
        AppLog.i("确认闸放行：" + g.label);
        return true;
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /** 经屏幕端口执行一个模型动作。[gated] 的点击只按核对过的坐标点，不再用编号（编号属于确认前那一屏）。 */
    private ActionResult execute(ScreenPort port, ModelAction a, boolean gated) throws InterruptedException {
        String sid = gated ? null : port.snapshotId();
        switch (a.type) {
            case "tap":
                return port.tapXY(a.x, a.y);
            case "long_press":
                return port.longPressXY(a.x, a.y);
            case "type":
                return port.typeText(a.ref, sid, a.text);
            case "clear":
                return port.clearText(a.ref, sid);
            case "scroll":
                return port.scroll(a.ref, sid, !"up".equals(a.text));
            case "swipe":
                return port.swipe(a.x, a.y, a.x2, a.y2);
            case "enter":
                return port.pressKey(ScreenPort.KEYCODE_ENTER);
            case "back":
                return port.back();
            case "home":
                return port.pressKey(ScreenPort.KEYCODE_HOME);
            case "open_app": {
                String pkg = appPackage(a.app);
                if (pkg == null) return ActionResult.of(ActionResult.Status.BLOCKED, "「" + a.app + "」不在允许打开的范围里");
                return port.openApp(pkg);
            }
            case "wait":
            default:
                port.waitStable(Timing.afterHeavyPage());
                return ActionResult.ok();
        }
    }

    /**
     * 等页面静下来再读（经屏幕端口，读的是主屏只读无障碍树并编号）：读一屏，等界面静下来再读。
     * 读不出内容（加载中/空白）时按较重页面等一次。只用设计第 7 节已有的间隔，不另加停顿。
     */
    private ScreenSnapshot settledSnapshot(boolean afterAction) throws InterruptedException {
        ScreenPort port = port();
        ScreenSnapshot a = port.snapshot();
        if (a == null) return null;
        if (SafetyGuard.isBlank(a)) {
            Timing.sleep(Timing.afterHeavyPage());
            ScreenSnapshot b = port.snapshot();
            return b == null ? a : b;
        }
        if (!afterAction) return a;
        port.waitStable(Timing.afterPageOpen() + Timing.afterPageOpen());
        ScreenSnapshot b = port.snapshot();
        return b == null ? a : b;
    }

    private void stopByGuard(OperationGuard.Verdict v) {
        say("已安全停下：" + v.message + "。没有换方式重试，也没有改间隔。");
        end(End.GUARD, v.code);
    }

    private static String describe(ModelAction a, ScreenSnapshot.Node target) {
        String lab = target == null ? "" : target.label();
        if (lab.length() > 16) lab = lab.substring(0, 16) + "…";
        switch (a.type) {
            case "tap": return lab.isEmpty() ? "点击 " + a.x + "," + a.y : "点「" + lab + "」";
            case "long_press": return lab.isEmpty() ? "长按 " + a.x + "," + a.y : "长按「" + lab + "」";
            case "scroll": return "up".equals(a.text) ? "往回翻" : "往下翻";
            case "swipe": return "滑动";
            case "type": return (lab.isEmpty() ? "" : "在「" + lab + "」") + "清空后贴入 " + a.text.length() + " 个字";
            case "clear": return "清空" + (lab.isEmpty() ? "输入框" : "「" + lab + "」");
            case "enter": return "按回车";
            case "back": return "返回";
            case "home": return "回到桌面";
            case "open_app": return "打开 " + a.app;
            default: return "等页面";
        }
    }
}
