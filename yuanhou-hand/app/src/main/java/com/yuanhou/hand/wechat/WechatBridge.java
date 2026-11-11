package com.yuanhou.hand.wechat;

import android.content.Context;

import com.yuanhou.hand.model.ModelConfig;
import com.yuanhou.hand.service.OrchestratorService;
import com.yuanhou.hand.session.LocalTextChannel;
import com.yuanhou.hand.session.SipConfig;
import com.yuanhou.hand.session.TaskSession;
import com.yuanhou.hand.util.AppLog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 0.1.12：微信官方 Bot 通道（照 Mika WechatChannel / WechatRuntime 移植协议和行为，不搬 Mika 的虚拟屏、无障碍点击和界面）。
 *
 * 归 OrchestratorService 所有：「启动服务」时只要已绑定且开着「接收微信任务」就开始长轮询，「停止服务」就停——一个开关管局域网 + 手 + 微信。
 * 进来的话：只收绑定本人的私聊文字，走和首页输入框 / 局域网 /say / DEBUG_SAY 完全相同的 LocalTextChannel.deliver → TaskSession，
 * 不另起任务引擎。有任务在办时非「取消」的话不交（同 /say 409），「取消」随时立即交。
 * 回去的话：微信交来的那件事办的过程中，远手说的每句话（复述、确认口令提问、进度、结果、需要本人处理的提示）都发回这条私聊；
 * 确认闸在等「发送」「确认下单」时可以直接在微信里回；付款永远不代付。
 * 不把局域网口令、模型 Key、SIP 密码、Bot 令牌写进微信消息或日志；日志里也不写游标、context_token、用户 id。
 */
public final class WechatBridge {

    public enum State {
        UNBOUND("未绑定"), PAUSED("已暂停接收"), CONNECTING("连接中"), CONNECTED("已连接"),
        RETRYING("连接中（重试）"), NEEDS_REBIND("需重新绑定"), STOPPED("服务没启动");
        public final String label;
        State(String l) { label = l; }
    }

    private static final long BATCH_MS = 600;

    private final OrchestratorService svc;
    private final WechatStore store;
    private final WechatApi api;
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private final Object nudge = new Object();
    private volatile State state;
    private volatile Thread poller, sender;
    private volatile boolean running;
    /** 每次 start 加 1；旧线程看到代号变了就退出，免得停了又开时两个长轮询并行。 */
    private volatile int loopGen;
    /** 正在办的那件事是从微信交来的：远手说的话要发回微信，确认可以在微信里回。 */
    private volatile boolean owning;
    private volatile int ownGen;
    private volatile String lastProblem;

    private final LocalTextChannel.Transcript transcript = (who, line) -> {
        if (owning && "远手".equals(who)) outbox.offer(line);
    };

    public WechatBridge(OrchestratorService svc) {
        this.svc = svc;
        this.store = WechatStore.get(svc);
        this.api = new WechatApi(new WechatApi.UrlTransport(), versionName(svc));
        this.state = idleState();
    }

    public State state() { return state; }
    /** 最近一次连接问题（只含 HTTP 码/异常类名，不含令牌或消息内容）。 */
    public String lastProblem() { return lastProblem; }
    public boolean owningTask() { return owning; }

    private State idleState() {
        if (!store.bound()) return store.needsRebind() ? State.NEEDS_REBIND : State.UNBOUND;
        if (store.needsRebind()) return State.NEEDS_REBIND;
        if (!store.enabled()) return State.PAUSED;
        return State.CONNECTING;
    }

    /** 随服务启动；没绑定或关着接收就什么都不做。 */
    public synchronized void start() {
        if (running) return;
        State s = idleState();
        if (s != State.CONNECTING) {
            setState(s);
            return;
        }
        running = true;
        final int gen = ++loopGen;
        LocalTextChannel local = svc.local();
        if (local != null) {
            local.removeTranscript(transcript);   // 内部因 401/403 停下时没摘掉，这里防重复
            local.addTranscript(transcript);
        }
        setState(State.CONNECTING);
        sender = new Thread(() -> sendLoop(gen), "wechat-send");
        sender.start();
        poller = new Thread(() -> pollLoop(gen), "wechat-poll");
        poller.start();
        AppLog.i("微信通道：开始接收本人私聊");
    }

    /** 随服务停止；打断进行中的长轮询。 */
    public synchronized void stop() {
        boolean was = running;
        running = false;
        loopGen++;
        owning = false;
        // 断开进行中的连接放到后台线程（可能从界面线程调用）
        Thread cancel = new Thread(api::cancelAll, "wechat-cancel");
        cancel.setDaemon(true);
        cancel.start();
        Thread p = poller, s = sender;
        poller = null;
        sender = null;
        if (p != null) p.interrupt();
        if (s != null) s.interrupt();
        LocalTextChannel local = svc.local();
        if (local != null) local.removeTranscript(transcript);
        outbox.clear();
        State idle = idleState();
        setState(idle == State.CONNECTING ? State.STOPPED : idle);
        if (was) AppLog.i("微信通道：已停止");
    }

    /** 绑定/解绑/开关接收之后调用。 */
    public void restart() {
        stop();
        start();
    }

    /** 网络回来了：结束退避等待，马上重连。 */
    public void networkChanged() {
        synchronized (nudge) { nudge.notifyAll(); }
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        svc.lanChanged();   // 刷新主界面（任意线程可调）
    }

    // ---------------- 长轮询（Mika WechatRuntime.monitorLoop） ----------------

    private boolean alive(int gen) { return running && gen == loopGen; }

    private void pollLoop(int gen) {
        int failures = 0;
        while (alive(gen) && store.enabled()) {
            try {
                pollOnce(gen);
                failures = 0;
                lastProblem = null;
                if (alive(gen)) setState(State.CONNECTED);
            } catch (WechatApi.HttpException e) {
                if (!alive(gen)) return;
                if (e.needsRebind()) {
                    // 401/403：登录失效。标记需重新绑定、停止轮询、不崩溃。
                    store.markNeedsRebind();
                    lastProblem = "HTTP " + e.httpCode;
                    AppLog.i("微信通道：登录已失效（HTTP " + e.httpCode + "），需要在远手里重新扫码绑定");
                    running = false;
                    setState(State.NEEDS_REBIND);
                    return;
                }
                failures++;
                lastProblem = "HTTP " + e.httpCode;
                backoff(failures);
            } catch (WechatApi.ApiException e) {
                if (!alive(gen)) return;
                failures++;
                lastProblem = "ret " + e.code;
                backoff(failures);
            } catch (Exception e) {
                if (!alive(gen)) return;
                failures++;
                lastProblem = e.getClass().getSimpleName();
                backoff(failures);
            }
        }
    }

    private void backoff(int failures) {
        setState(State.RETRYING);
        if (failures == 1 || failures % 10 == 0) AppLog.i("微信通道：连接失败（" + lastProblem + "），稍后重试");
        long ms = Math.min(30_000L, 1_000L * (1L << Math.min(5, failures - 1)));
        synchronized (nudge) {
            try { nudge.wait(ms); } catch (InterruptedException ignored) {}
        }
    }

    private void pollOnce(int gen) throws Exception {
        WechatApi.Account acct = store.account();
        if (acct == null) { running = false; return; }
        WechatApi.Updates u = api.updates(acct, store.cursor());
        // 换绑或停止后，旧请求的结果不能再交给任务，也不能推进新账号的游标
        if (!alive(gen) || !acct.equals(store.account()) || !store.enabled()) return;
        for (WechatApi.Message m : u.msgs) {
            if (!acct.ownerId.equals(m.fromUserId)) continue;   // 只认绑定本人
            store.rememberContext(m.contextToken);
            String text = WechatPolicy.ownerText(m, acct.ownerId);
            if (text == null) continue;                         // 群聊、图片、语音、文件都不收
            if (!store.markHandled(m.messageId)) continue;      // 同一条只办一次
            handleOwnerText(text);
        }
        if (!u.cursor.isEmpty() && acct.equals(store.account()) && store.enabled()) store.saveCursor(u.cursor);
    }

    // ---------------- 交给 TaskSession（与首页输入框 / 局域网 /say 同一条路） ----------------

    private void handleOwnerText(String text) {
        TaskSession ts = svc.session();
        LocalTextChannel local = svc.local();
        if (ts == null || local == null) {
            outbox.offer("远手的会话还没准备好，这句先不办。");
            return;
        }
        WechatPolicy.Route r = WechatPolicy.route(text, ts.running(), ts.awaitingConfirm(), owning);
        AppLog.i("微信通道：收到本人私聊一句 → " + r);
        switch (r) {
            case START_TASK: {
                int gen = ++ownGen;
                owning = true;
                outbox.offer("收到，开始办：" + text);
                ts.markNextTaskOrigin(TaskSession.Origin.WECHAT);   // 0.1.13：微信来的事等确认久一点
                local.deliver(text);
                Thread w = new Thread(() -> watchTask(ts, gen), "wechat-task");
                w.setDaemon(true);
                w.start();
                break;
            }
            case CONFIRM_REPLY:
                local.deliver(text);
                break;
            case CANCEL:
                local.deliver(text);
                if (!owning) outbox.offer("已叫停正在办的事。");
                break;
            default: {
                String reply = WechatPolicy.replyFor(r);
                if (reply != null) outbox.offer(reply);
            }
        }
    }

    /** 等微信交来的那件事办完，补一句结局，然后不再转发远手的话。 */
    private void watchTask(TaskSession ts, int gen) {
        try {
            long until = System.currentTimeMillis() + 60 * 60_000L;
            while (running && ts.running() && System.currentTimeMillis() < until) Thread.sleep(300);
            // 收尾的话是排到工作线程上的：等它说完
            try { svc.worker().submit(() -> {}).get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
            Thread.sleep(300);
            if (gen != ownGen) return;
            TaskSession.End end = ts.lastEnd();
            if (running && !ts.running() && end != null) outbox.offer("（这件事结束：" + end.label + "）");
        } catch (InterruptedException ignored) {
        } finally {
            if (gen == ownGen && !ts.running()) owning = false;
        }
    }

    // ---------------- 发回微信 ----------------

    private void sendLoop(int gen) {
        while (alive(gen)) {
            try {
                String first = outbox.take();
                List<String> lines = new ArrayList<>();
                lines.add(first);
                // 合并紧挨着的几句，少发几条；在等确认时马上发，别耽误回口令的时间
                long until = System.currentTimeMillis() + BATCH_MS;
                while (true) {
                    TaskSession ts = svc.session();
                    if (ts != null && ts.awaitingConfirm()) break;
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) break;
                    String more = outbox.poll(left, TimeUnit.MILLISECONDS);
                    if (more == null) break;
                    lines.add(more);
                }
                outbox.drainTo(lines);
                sendNow(lines);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void sendNow(List<String> lines) {
        WechatApi.Account acct = store.account();
        if (acct == null || !running) return;
        String[] secrets = secrets();
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            String t = WechatPolicy.forWechat(l, secrets);
            if (t.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(t);
        }
        if (b.length() == 0) return;
        String text = b.length() > WechatPolicy.MAX_OUT ? b.substring(0, WechatPolicy.MAX_OUT) + "…" : b.toString();
        try {
            api.sendText(acct, acct.ownerId, "yuanhou-" + UUID.randomUUID(), text, store.contextToken());
        } catch (WechatApi.HttpException e) {
            AppLog.i("微信通道：回传失败（HTTP " + e.httpCode + "）");
            if (e.needsRebind()) {
                store.markNeedsRebind();
                running = false;
                api.cancelAll();
                setState(State.NEEDS_REBIND);
            }
        } catch (WechatApi.ApiException e) {
            AppLog.i("微信通道：回传失败（ret " + e.code + "）");
        } catch (Exception e) {
            AppLog.i("微信通道：回传失败（" + e.getClass().getSimpleName() + "）");
        }
    }

    /** 发回微信前要打码的机密：Bot 令牌/游标/context_token、局域网口令、模型 Key、SIP 密码。 */
    private String[] secrets() {
        List<String> s = new ArrayList<>(store.secrets());
        try { if (svc.lan() != null) s.add(svc.lan().token()); } catch (Exception ignored) {}
        try { ModelConfig.load(svc).addSecrets(s); } catch (Exception ignored) {}
        try { s.add(SipConfig.load(svc).password); } catch (Exception ignored) {}
        return s.toArray(new String[0]);
    }

    static String versionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null || v.isEmpty() ? "1" : v;
        } catch (Exception e) {
            return "1";
        }
    }
}
