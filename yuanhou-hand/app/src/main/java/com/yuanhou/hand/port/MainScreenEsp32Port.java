package com.yuanhou.hand.port;

import com.yuanhou.hand.screen.ScreenSnapshot;
import com.yuanhou.hand.util.Timing;

import java.util.List;

/**
 * 主屏 + ESP32 的 ScreenPort（远手 0.1.5）。
 *
 * - 读屏：只读无障碍（com.yuanhou.hand/.screen.ReadonlyAccessibilityService，capabilities=1），主屏 display 0 的活动窗口树。
 * - 点击 / 长按 / 滑动 / 滚轮 / 按键：ESP32-S3 BLE HID（Wi-Fi 透传 s3-phone-hand.local:3333，GATT 备用）。
 *   HID 失败就返回 FAILED，不退回无障碍点击，不换通道重发，不加抖动或弧线。
 * - 打字：①点目标输入框拿焦点 → ②清空：Ctrl+End + Backspace×(字数+12)，不用 Ctrl+A，也不用右方向键（这台机的百度输入法会吞掉）
 *   → ③读回必须已空，否则 VERIFY_FAILED、不贴 → ④剪贴板 + Ctrl+V → ⑤读回输入框文字必须与要打的字完全一致，否则 VERIFY_FAILED、不再贴第二次。
 *   密码框：NEEDS_HUMAN，不点、不清、不贴，也不做粘贴探测（Mika ContextPastePolicy 同理）。
 * - 打开 App：本应用 startActivity，打开后核对前台；不经 shell、不建虚拟屏、不挪任务。
 *
 * 这里不写任何「该不该做」的策略：确认闸在 gate/ConfirmationGate，App 范围在 AppAccessScreenPort（装饰器）。
 * 纯 Java（不依赖 android.*），桌面 jvmcheck 能用替身跑。
 */
public final class MainScreenEsp32Port implements ScreenPort {

    /** 给模型列出的元素上限（ref 编号规则与 ScreenSnapshot.toModelText 相同）。 */
    public static final int MAX_REFS = 150;
    /** 主屏可点范围：避开状态栏、底部手势条、左右返回手势边缘（与 HandExecutor 的 swipe 夹边一致）。 */
    public static final int SAFE_TOP = 160, SAFE_BOTTOM = 200, SAFE_SIDE = 80;
    /** 清空时回退次数上限 / 读不到字数时的守护次数（与 0.1.4 真机自测一致）。 */
    public static final int CLEAR_MAX = 200, CLEAR_GUARD = 64;

    private final TreeSource tree;
    private final HandPort hand;
    private final AppLauncher launcher;
    private final Sleeper sleeper;

    private ScreenSnapshot last;
    private String lastId;
    private long counter;
    /** 最近一次写操作之后 lastId 作废：旧编号不能再用。 */
    private boolean stale = true;

    public MainScreenEsp32Port(TreeSource tree, HandPort hand, AppLauncher launcher, Sleeper sleeper) {
        this.tree = tree;
        this.hand = hand;
        this.launcher = launcher;
        this.sleeper = sleeper == null ? Sleeper.REAL : sleeper;
    }

    /** 回退次数：读到的框内字数 + 12 的余量；读不到（-1）时用守护次数。 */
    public static int clearCount(int knownLen) {
        if (knownLen < 0) return CLEAR_GUARD;
        if (knownLen == 0) return 0;
        return Math.min(CLEAR_MAX, knownLen + 12);
    }

    /** 元素在主屏可点范围里的可见部分的中心；整块不在范围里返回 null。 */
    public static int[] visibleCenter(ScreenSnapshot.Node n, int w, int h) {
        int left = Math.max(n.bounds.left, SAFE_SIDE), right = Math.min(n.bounds.right, w - SAFE_SIDE);
        int top = Math.max(n.bounds.top, SAFE_TOP), bottom = Math.min(n.bounds.bottom, h - SAFE_BOTTOM);
        if (right - left < 8 || bottom - top < 8) return null;
        return new int[]{(left + right) / 2, (top + bottom) / 2};
    }

    // ---------------- 读屏 ----------------

    @Override
    public synchronized ScreenSnapshot snapshot() {
        ScreenSnapshot s = tree.read();
        last = s;
        if (s == null) {
            lastId = null;
            stale = true;
            return null;
        }
        s.toModelText(MAX_REFS);   // 编号
        lastId = "s" + (++counter);
        stale = false;
        return s;
    }

    @Override
    public synchronized String snapshotId() { return stale ? null : lastId; }

    @Override
    public String foregroundPackage() {
        ScreenSnapshot s = tree.read();
        return s == null || s.packageName == null ? "" : s.packageName;
    }

    // ---------------- 点击 / 长按 ----------------

    @Override
    public ActionResult tapRef(int ref, String snapshotId) {
        Resolved r = resolve(ref, snapshotId);
        if (r.error != null) return r.error;
        int[] p = visibleCenter(r.node, hand.screenW(), hand.screenH());
        if (p == null) return ActionResult.failed("#" + ref + " 不在屏幕可点范围里（可能要先翻页）");
        return tapXY(p[0], p[1]);
    }

    @Override
    public ActionResult tapXY(int x, int y) {
        return write(() -> {
            hand.click(x, y);
            sleeper.sleep(Timing.hitVerify());
        });
    }

    @Override
    public ActionResult longPressRef(int ref, String snapshotId) {
        Resolved r = resolve(ref, snapshotId);
        if (r.error != null) return r.error;
        int[] p = visibleCenter(r.node, hand.screenW(), hand.screenH());
        if (p == null) return ActionResult.failed("#" + ref + " 不在屏幕可点范围里");
        return longPressXY(p[0], p[1]);
    }

    @Override
    public ActionResult longPressXY(int x, int y) {
        return write(() -> {
            hand.longPress(x, y);
            sleeper.sleep(Timing.hitVerify());
        });
    }

    // ---------------- 打字 / 清空 ----------------

    @Override
    public ActionResult typeText(int ref, String snapshotId, String text) {
        if (text == null) text = "";
        Field f = focusField(ref, snapshotId);
        if (f.error != null) return f.error;
        String cleared = clearFocused(f.node);
        if (cleared != null) return ActionResult.verifyFailed(cleared + "，没有贴", ActionResult.Verification.MISMATCH);
        if (text.isEmpty()) return ActionResult.typed(true);
        try {
            hand.paste(text);
            sleeper.sleep(Timing.afterText());
        } catch (PortException e) {
            return fromHand(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.failed("被中断");
        } finally {
            invalidate();
        }
        ScreenSnapshot after = tree.read();
        ScreenSnapshot.Node now = after == null ? null : sameField(after, f.node);
        if (now == null) return ActionResult.verifyFailed("贴完读不回输入框，没法核对", ActionResult.Verification.UNKNOWN);
        if (now.password) return ActionResult.of(ActionResult.Status.NEEDS_HUMAN, "输入框变成了密码框");
        String got = now.typedText();
        if (!got.equals(text)) {
            return ActionResult.verifyFailed("输入框里是「" + clip(got) + "」，不等于要打的「" + clip(text) + "」；没有再贴第二次",
                    ActionResult.Verification.MISMATCH);
        }
        return ActionResult.typed(true);
    }

    @Override
    public ActionResult clearText(int ref, String snapshotId) {
        Field f = focusField(ref, snapshotId);
        if (f.error != null) return f.error;
        String cleared = clearFocused(f.node);
        invalidate();
        return cleared == null ? ActionResult.typed(true)
                : ActionResult.verifyFailed(cleared, ActionResult.Verification.MISMATCH);
    }

    private static final class Field {
        ScreenSnapshot.Node node;
        ActionResult error;
    }

    /** 找到并点中要打字的输入框，返回此刻有焦点的那个节点（来自刚读的一屏）。 */
    private Field focusField(int ref, String snapshotId) {
        Field out = new Field();
        String nr = hand.notReadyReason();
        if (nr != null) { out.error = ActionResult.of(ActionResult.Status.NOT_READY, nr); return out; }
        ScreenSnapshot.Node target;
        if (ref > 0) {
            Resolved r = resolve(ref, snapshotId);
            if (r.error != null) { out.error = r.error; return out; }
            target = r.node;
        } else {
            ScreenSnapshot s = tree.read();
            target = s == null ? null : s.focusedField();
            if (target == null) { out.error = ActionResult.failed("现在没有有焦点的输入框"); return out; }
        }
        if (target.password) {
            out.error = ActionResult.of(ActionResult.Status.NEEDS_HUMAN, "目标是密码框：交给本人，不点、不贴、不探测");
            return out;
        }
        if (!target.editable) { out.error = ActionResult.failed("目标不是输入框"); return out; }
        if (!target.focused) {
            int[] p = visibleCenter(target, hand.screenW(), hand.screenH());
            if (p == null) { out.error = ActionResult.failed("输入框不在屏幕可点范围里"); return out; }
            ActionResult t = tapXY(p[0], p[1]);
            if (!t.isOk()) { out.error = t; return out; }
        }
        ScreenSnapshot s = tree.read();
        ScreenSnapshot.Node f = s == null ? null : s.focusedField();
        if (f == null) { out.error = ActionResult.failed("点了输入框，但它没拿到焦点；没有贴"); return out; }
        if (f.password) {
            out.error = ActionResult.of(ActionResult.Status.NEEDS_HUMAN, "焦点在密码框：交给本人，不贴、不探测");
            return out;
        }
        if (!f.bounds.contains(target.cx(), target.cy()) && !target.bounds.contains(f.cx(), f.cy())) {
            out.error = ActionResult.failed("焦点落到了别的输入框；没有贴");
            return out;
        }
        out.node = f;
        return out;
    }

    /** 清空有焦点的输入框；null = 已空，否则是失败原因。只用 Ctrl+End + Backspace，没有右方向键回退。 */
    private String clearFocused(ScreenSnapshot.Node field) {
        int len = field.typedText().length();
        if (len == 0) return null;
        try {
            hand.cursorToEnd();
            hand.backspaces(clearCount(len));
            sleeper.sleep(Timing.beforeReadback());
        } catch (PortException e) {
            return "清空时键盘动作失败：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "被中断";
        } finally {
            invalidate();
        }
        ScreenSnapshot s = tree.read();
        ScreenSnapshot.Node now = s == null ? null : sameField(s, field);
        if (now == null) return "清空后读不回输入框";
        String rem = now.typedText();
        if (!rem.isEmpty()) return "Ctrl+End + 回退后框里还剩「" + clip(rem) + "」";
        return null;
    }

    /** 新一屏里的同一个输入框：优先有焦点的，否则按位置重叠找可输入的。 */
    private static ScreenSnapshot.Node sameField(ScreenSnapshot s, ScreenSnapshot.Node old) {
        ScreenSnapshot.Node f = s.focusedField();
        if (f != null) return f;
        for (ScreenSnapshot.Node n : s.nodes) {
            if (n.editable && n.bounds.contains(old.cx(), old.cy())) return n;
        }
        return null;
    }

    // ---------------- 滚动 / 滑动 / 按键 ----------------

    @Override
    public ActionResult scroll(int ref, String snapshotId, boolean down) {
        ScreenSnapshot.Node n = null;
        if (ref > 0) {
            Resolved r = resolve(ref, snapshotId);
            if (r.error != null) return r.error;
            n = r.node;
        } else {
            ScreenSnapshot s = last != null && !stale ? last : tree.read();
            if (s != null) n = s.mainScrollable();
        }
        int[] p = n == null ? null : visibleCenter(n, hand.screenW(), hand.screenH());
        if (p == null) p = new int[]{hand.screenW() / 2, hand.screenH() / 2};
        final int x = p[0], y = p[1];
        return write(() -> {
            hand.scroll(x, y, down ? 3 : -3);
            sleeper.sleep(Timing.afterSwipe());
        });
    }

    @Override
    public ActionResult swipe(int x1, int y1, int x2, int y2) {
        return write(() -> {
            hand.swipe(x1, y1, x2, y2);
            sleeper.sleep(Timing.afterSwipe());
        });
    }

    @Override
    public ActionResult back() {
        return write(() -> {
            hand.back();
            sleeper.sleep(Timing.afterPageOpen());
        });
    }

    @Override
    public ActionResult pressKey(int keyCode) {
        switch (keyCode) {
            case KEYCODE_HOME:
                return write(() -> { hand.home(); sleeper.sleep(Timing.afterPageOpen()); });
            case KEYCODE_BACK:
                return back();
            case KEYCODE_ENTER:
                return write(() -> { hand.enter(); sleeper.sleep(Timing.afterText()); });
            case KEYCODE_DEL:
                return write(() -> hand.backspaces(1));
            case KEYCODE_ESCAPE:
                return write(() -> { hand.escape(); sleeper.sleep(Timing.afterText()); });
            case KEYCODE_MOVE_END:
                return write(hand::cursorToEnd);
            case KEYCODE_DPAD_RIGHT:
                return ActionResult.unsupported("右方向键（这台机的百度输入法会吞掉）");
            default:
                return ActionResult.unsupported("键码 " + keyCode);
        }
    }

    // ---------------- 打开 App ----------------

    @Override
    public ActionResult openApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) return ActionResult.failed("没给包名");
        String err = launcher == null ? "没有启动器" : launcher.launch(packageName);
        invalidate();
        if (err != null) return ActionResult.failed(err);
        try {
            sleeper.sleep(Timing.afterHeavyPage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.failed("被中断");
        }
        String fg = foregroundPackage();
        if (!packageName.equals(fg)) {
            return ActionResult.of(ActionResult.Status.FAILED, "发出了打开指令，但前台是「" + fg + "」");
        }
        return ActionResult.ok(true, null);
    }

    @Override
    public List<String[]> listApps() {
        return launcher == null ? ScreenPort.super.listApps() : launcher.listApps();
    }

    // ---------------- 等待 ----------------

    @Override
    public boolean waitStable(long timeoutMs) {
        int rounds = (int) Math.max(1, timeoutMs / 280);
        try {
            ScreenSnapshot a = tree.read();
            for (int i = 0; i < rounds; i++) {
                sleeper.sleep(Timing.betweenOps());
                ScreenSnapshot b = tree.read();
                if (a != null && b != null && a.fingerprint() == b.fingerprint()) return true;
                a = b;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    @Override
    public boolean awaitChange(long timeoutMs) {
        ScreenSnapshot base = last;
        if (base == null) return false;
        long fp = base.fingerprint();
        int rounds = (int) Math.max(1, timeoutMs / 280);
        try {
            for (int i = 0; i < rounds; i++) {
                sleeper.sleep(Timing.betweenOps());
                ScreenSnapshot b = tree.read();
                if (b != null && b.fingerprint() != fp) return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    // ---------------- 内部 ----------------

    private interface HandStep {
        void run() throws PortException, InterruptedException;
    }

    private ActionResult write(HandStep step) {
        String nr = hand.notReadyReason();
        if (nr != null) return ActionResult.of(ActionResult.Status.NOT_READY, nr);
        ScreenSnapshot before = last != null && !stale ? last : null;
        try {
            step.run();
        } catch (PortException e) {
            return fromHand(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.failed("被中断");
        } finally {
            invalidate();
        }
        if (before == null) return ActionResult.ok();
        ScreenSnapshot after = tree.read();
        if (after == null) return ActionResult.ok();
        String sw = before.packageName != null && !before.packageName.equals(after.packageName) ? after.packageName : null;
        return ActionResult.ok(after.fingerprint() != before.fingerprint(), sw);
    }

    private synchronized void invalidate() { stale = true; }

    private static ActionResult fromHand(PortException e) {
        if (e.notReady) return ActionResult.of(ActionResult.Status.NOT_READY, e.getMessage());
        return ActionResult.failed(e.getMessage() + "（没有改用其他方式重试）");
    }

    private static final class Resolved {
        ScreenSnapshot.Node node;
        ActionResult error;
    }

    private synchronized Resolved resolve(int ref, String snapshotId) {
        Resolved r = new Resolved();
        if (last == null || stale || lastId == null || (snapshotId != null && !snapshotId.equals(lastId))) {
            r.error = ActionResult.of(ActionResult.Status.REF_EXPIRED, "编号来自旧的一屏，先重新读屏");
            return r;
        }
        r.node = last.byRef(ref);
        if (r.node == null) r.error = ActionResult.failed("这一屏没有 #" + ref);
        return r;
    }

    private static String clip(String s) {
        s = s == null ? "" : s.replace('\n', ' ');
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
