package com.yuanhou.hand.hand;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.yuanhou.hand.util.AppLog;
import com.yuanhou.hand.util.Timing;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「手」：把动作发给 ESP32，由 ESP32 以 HID 报告注回手机。本阶段优先走 Wi-Fi 透传（{@link WifiHandClient}），
 * Wi-Fi 不可用时走自定义 GATT（备用）。一个动作只走一条通道；某条通道上失败了不换另一条重发（避免重复执行）。
 * 阻塞式，只能在工作线程调用。一次只有一个动作在途。
 * HID 失败不回退到无障碍点击：直接抛出 HandException，由上层记为该步失败或套件未就绪。
 * 鼠标走直线最短路（由固件 move_straight 实现），这里不加抖动、弧线或落点偏移。
 */
public final class HandExecutor {

    public static final class HandException extends Exception {
        public final int result;
        public HandException(String msg, int result) {
            super(msg);
            this.result = result;
        }
    }

    private final Context app;
    private final HandGattClient client;
    private final WifiHandClient wifi;
    private final SuiteState suite;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private int seq = 1;
    private int pendSeq = -1, pendOp = -1, pendResult = -2;
    private CountDownLatch latch;
    private volatile boolean stopRequested;
    /** 本应用估计的光标位置（只用于滚轮前把光标移到目标上方）。 */
    private boolean ptrKnown;
    private int ptrX, ptrY;

    public HandExecutor(Context ctx, HandGattClient client, WifiHandClient wifi, SuiteState suite) {
        this.app = ctx.getApplicationContext();
        this.client = client;
        this.wifi = wifi;
        this.suite = suite;
    }

    public boolean isBusy() {
        return busy.get();
    }

    /** 由 GATT 回调（主线程）调用。 */
    public void onAck(int ackSeq, int opcode, int result) {
        synchronized (lock) {
            if (latch != null && ackSeq == pendSeq && opcode == pendOp && HandProtocol.isFinalResult(result)) {
                pendResult = result;
                latch.countDown();
            }
        }
    }

    /** 链路断开时让在途动作立即失败。 */
    public void failPending() {
        failPending(-3);
    }

    private void failPending(int code) {
        synchronized (lock) {
            if (latch != null) {
                pendResult = code;
                latch.countDown();
            }
        }
    }

    private int nextSeq() {
        synchronized (lock) {
            int s = seq++;
            if (seq > 250) seq = 1;
            return s;
        }
    }

    private int send(byte[] payload, int timeoutMs) throws HandException, InterruptedException {
        if (wifi != null && suite.wifiReady()) return sendWifi(payload, timeoutMs);
        return sendGatt(payload, timeoutMs);
    }

    /** 经 Wi-Fi 透传发一个动作并等最终结果。失败不改走 GATT、不改走无障碍。 */
    private int sendWifi(byte[] payload, int timeoutMs) throws HandException, InterruptedException {
        int op = payload[0] & 0xff;
        busy.set(true);
        try {
            int r;
            try {
                r = wifi.exchange(payload, timeoutMs + 2000);
            } catch (java.io.IOException e) {
                if (stopRequested) throw new HandException("已急停", HandProtocol.RES_CANCELLED);
                throw new HandException("Wi-Fi 透传失败：" + WifiHandClient.describe(e)
                        + " op=0x" + Integer.toHexString(op) + "（没有改走其他方式重发）", -3);
            }
            if (stopRequested || r == HandProtocol.RES_CANCELLED) throw new HandException("已急停", HandProtocol.RES_CANCELLED);
            if (r != HandProtocol.RES_OK) {
                throw new HandException("探头返回 " + HandProtocol.resultName(r) + " op=0x" + Integer.toHexString(op), r);
            }
            return r;
        } finally {
            busy.set(false);
        }
    }

    private int sendGatt(byte[] payload, int timeoutMs) throws HandException, InterruptedException {
        int op = payload[0] & 0xff;
        int s = payload[1] & 0xff;
        CountDownLatch l = new CountDownLatch(1);
        busy.set(true);
        try {
            synchronized (lock) {
                pendSeq = s;
                pendOp = op;
                pendResult = -2;
                latch = l;
            }
            boolean ok = client.writeAction(payload);
            if (!ok) {
                // GATT 上一个写可能还没完成：等一小会儿再写一次（同一条指令，不改参数）
                Thread.sleep(120);
                ok = client.writeAction(payload);
            }
            if (!ok) throw new HandException("指令写入失败（GATT 未就绪或忙）", -1);
            boolean got = l.await(timeoutMs, TimeUnit.MILLISECONDS);
            int r;
            synchronized (lock) {
                r = pendResult;
                latch = null;
                pendSeq = pendOp = -1;
            }
            if (!got) throw new HandException("等待探头结果超时 op=0x" + Integer.toHexString(op), -2);
            if (r == -3) throw new HandException("蓝牙断开，动作中止", -3);
            if (r == -4) throw new HandException("已急停", HandProtocol.RES_CANCELLED);
            if (r != HandProtocol.RES_OK) {
                throw new HandException("探头返回 " + HandProtocol.resultName(r)
                        + " op=0x" + Integer.toHexString(op), r);
            }
            return r;
        } finally {
            synchronized (lock) {
                if (latch == l) latch = null;
            }
            busy.set(false);
        }
    }

    private void requireLink() throws HandException {
        if (!suite.linkReady()) {
            String r = suite.notReadyReason();
            throw new HandException("套件未就绪：" + (r == null ? "未知" : r), HandProtocol.RES_NOT_READY);
        }
    }

    private void requireScreen() throws HandException {
        requireLink();
        if (!suite.screenSet) throw new HandException("套件未就绪：还没 SET_SCREEN", HandProtocol.RES_NOT_READY);
    }

    private void checkStop() throws HandException {
        if (stopRequested) throw new HandException("已急停", HandProtocol.RES_CANCELLED);
    }

    /** SET_SCREEN 必须用 getRealSize()，本机为 1080x2400。 */
    public void setScreen(int w, int h, int cpp, int maxStep) throws HandException, InterruptedException {
        requireLink();
        AppLog.i("SET_SCREEN " + w + "x" + h + " cpp=" + cpp + " max_step=" + maxStep);
        send(HandProtocol.setScreen(nextSeq(), w, h, cpp, maxStep), 4000);
        suite.screenW = w;
        suite.screenH = h;
        suite.cpp = cpp;
        suite.screenSet = true;
        suite.lastFault = null;
        ptrKnown = false;
    }

    public void homeCursor() throws HandException, InterruptedException {
        requireScreen();
        checkStop();
        send(HandProtocol.homeCursor(nextSeq()), 15000);
        ptrKnown = true;
        ptrX = ptrY = 0;
    }

    public void click(int x, int y) throws HandException, InterruptedException {
        requireScreen();
        checkStop();
        clampCheck(x, y);
        int hold = Timing.clickHold();
        AppLog.i("点击 " + x + "," + y + " 按住 " + hold + "ms");
        ptrKnown = false;
        send(HandProtocol.clickAbs(nextSeq(), x, y, hold), 30000);
        ptrKnown = true;
        ptrX = x;
        ptrY = y;
    }

    public void longPress(int x, int y) throws HandException, InterruptedException {
        requireScreen();
        checkStop();
        clampCheck(x, y);
        int hold = Timing.longPressHold();
        AppLog.i("长按 " + x + "," + y + " 按住 " + hold + "ms");
        ptrKnown = false;
        send(HandProtocol.longPressAbs(nextSeq(), x, y, hold), 30000);
        ptrKnown = true;
        ptrX = x;
        ptrY = y;
    }

    public void swipe(int x1, int y1, int x2, int y2) throws HandException, InterruptedException {
        requireScreen();
        checkStop();
        clampCheck(x1, y1);
        clampCheck(x2, y2);
        // 避开状态栏、底部手势条和左右返回手势边缘
        y1 = clamp(y1, SAFE_TOP, suite.screenH - SAFE_BOTTOM);
        y2 = clamp(y2, SAFE_TOP, suite.screenH - SAFE_BOTTOM);
        x1 = clamp(x1, SAFE_SIDE, suite.screenW - SAFE_SIDE);
        x2 = clamp(x2, SAFE_SIDE, suite.screenW - SAFE_SIDE);
        int dur = Timing.swipeDuration();
        AppLog.i("滑动 " + x1 + "," + y1 + " → " + x2 + "," + y2 + " 直线 " + dur + "ms"
                + "（注意：拖动段受系统指针加速影响，实际位移会比指令长）");
        ptrKnown = false;
        send(HandProtocol.swipeAbs(nextSeq(), x1, y1, x2, y2, dur), 30000);
    }

    public static final int SAFE_TOP = com.yuanhou.hand.port.MainScreenEsp32Port.SAFE_TOP,
            SAFE_BOTTOM = com.yuanhou.hand.port.MainScreenEsp32Port.SAFE_BOTTOM,
            SAFE_SIDE = com.yuanhou.hand.port.MainScreenEsp32Port.SAFE_SIDE;

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 把光标直线移到 (x, y)，不按键。用于滚轮前定位。 */
    public void moveTo(int x, int y) throws HandException, InterruptedException {
        requireScreen();
        checkStop();
        clampCheck(x, y);
        if (!ptrKnown) homeCursor();
        int cpp = suite.cpp == 0 ? 1000 : suite.cpp;
        int dx = Math.round((x - ptrX) * cpp / 1000f), dy = Math.round((y - ptrY) * cpp / 1000f);
        AppLog.i("移动光标到 " + x + "," + y);
        ptrKnown = false;
        if (dx != 0 || dy != 0) send(HandProtocol.moveRel(nextSeq(), dx, dy, 0), 30000);
        ptrKnown = true;
        ptrX = x;
        ptrY = y;
    }

    /**
     * 滚动列表：光标移到 (x, y) 上方后发鼠标滚轮。notches>0 让内容往下翻（相当于手指上滑）。
     * 用滚轮而不用拖动，是因为拖动段会被系统指针加速放大（真机：指令 840 px，实际约 1630 px）。
     */
    public void scroll(int x, int y, int notches) throws HandException, InterruptedException {
        moveTo(x, y);
        int n = Math.max(-10, Math.min(10, notches));
        AppLog.i("滚轮 " + (n > 0 ? "向下翻 " : "向上翻 ") + Math.abs(n) + " 格");
        send(HandProtocol.wheel(nextSeq(), -n, 0), 5000);
    }

    public void key(int modifiers, int usage) throws HandException, InterruptedException {
        requireLink();
        checkStop();
        send(HandProtocol.key(nextSeq(), modifiers, usage, 0), 5000);
    }

    /** HID 回车键（usage 0x28）。是否算「发出」由 TaskSession 的门闩判断，这里只执行。 */
    public void enter() throws HandException, InterruptedException {
        requireLink();
        checkStop();
        AppLog.i("HID 回车");
        send(HandProtocol.key(nextSeq(), 0, 0x28, 0), 5000);
    }

    public void back() throws HandException, InterruptedException {
        requireLink();
        checkStop();
        AppLog.i("HID 返回键");
        send(HandProtocol.consumer(nextSeq(), HandProtocol.CONSUMER_AC_BACK), 5000);
    }

    /** HID Esc（usage 0x29）。 */
    public void escape() throws HandException, InterruptedException {
        requireLink();
        checkStop();
        AppLog.i("HID Esc");
        send(HandProtocol.key(nextSeq(), 0, 0x29, 0), 5000);
    }

    public void home() throws HandException, InterruptedException {
        requireLink();
        checkStop();
        AppLog.i("HID 主页键");
        send(HandProtocol.consumer(nextSeq(), HandProtocol.CONSUMER_AC_HOME), 5000);
    }

    /**
     * 输入文字：先写剪贴板，再发 HID Ctrl+V。百度输入法会把 Shift 当中英切换，
     * 所以中英文都走粘贴，不逐键打字，也不预先按 Shift。
     */
    public void paste(String text) throws HandException, InterruptedException {
        requireLink();
        checkStop();
        setClipboard(text);
        Thread.sleep(150);
        AppLog.i("Ctrl+V 粘贴 " + text.length() + " 个字");
        send(HandProtocol.key(nextSeq(), HandProtocol.MOD_LCTRL, HandProtocol.USAGE_V, 0), 5000);
    }

    /** 清空时回退次数的上限（读得到长度时）。 */
    public static final int CLEAR_MAX = com.yuanhou.hand.port.MainScreenEsp32Port.CLEAR_MAX;
    /** 读不到框内文字时的守护回退次数。 */
    public static final int CLEAR_GUARD = com.yuanhou.hand.port.MainScreenEsp32Port.CLEAR_GUARD;

    /**
     * 把光标移到文末：Ctrl+End（整段末尾，多行也适用）。
     * 不按 Shift（百度输入法会当中英切换），也不用无障碍改光标。
     */
    public void cursorToEnd() throws HandException, InterruptedException {
        requireLink();
        checkStop();
        AppLog.i("HID Ctrl+End 光标到文末");
        send(HandProtocol.key(nextSeq(), HandProtocol.MOD_LCTRL, HandProtocol.USAGE_END, 0), 5000);
        Thread.sleep(Timing.keyGap());
    }

    /**
     * 回退键按 n 下（HID Backspace 0x2A），每下之间 30–80 ms（默认中值 50）。
     * 不用 Ctrl+A，也不用无障碍 ACTION_SET_TEXT/清空（无障碍只读）。HID 失败直接抛出，不换方式重试。
     */
    public void backspaces(int n) throws HandException, InterruptedException {
        requireLink();
        n = Math.max(0, Math.min(CLEAR_MAX, n));
        AppLog.i("HID 回退键 " + n + " 下");
        for (int i = 0; i < n; i++) {
            checkStop();
            send(HandProtocol.key(nextSeq(), 0, HandProtocol.USAGE_BACKSPACE, 0), 5000);
            Thread.sleep(Timing.keyGap());
        }
    }

    /**
     * 回退次数：读到的框内字数 + 12 的余量（emoji 等代理对按 2 个 char 计，余量够用）；
     * 读不到（knownLen<0）时用有上限的守护次数。
     */
    public static int clearCount(int knownLen) {
        return com.yuanhou.hand.port.MainScreenEsp32Port.clearCount(knownLen);
    }

    private void setClipboard(String text) throws HandException, InterruptedException {
        CountDownLatch l = new CountDownLatch(1);
        final boolean[] ok = {false};
        main.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("远手", text));
                    ok[0] = true;
                }
            } catch (Exception e) {
                AppLog.i("写剪贴板失败: " + e.getMessage());
            }
            l.countDown();
        });
        if (!l.await(2, TimeUnit.SECONDS) || !ok[0]) throw new HandException("写剪贴板失败", -1);
    }

    /** 急停：立即让探头中止当前动作并松开所有键。可从任何线程调用。 */
    public void emergencyStop() {
        stopRequested = true;
        if (wifi != null) wifi.abort();
        byte[] p = HandProtocol.cancel(0);
        if (suite.channelReady) {
            boolean ok = client.writeAction(p);
            if (!ok) main.postDelayed(() -> client.writeAction(p), 120);
        }
        AppLog.i("急停已发出 CANCEL");
        failPending(-4);
    }

    public void clearStop() {
        stopRequested = false;
    }

    private void clampCheck(int x, int y) throws HandException {
        if (x < 0 || y < 0 || x >= suite.screenW || y >= suite.screenH) {
            throw new HandException("坐标超出屏幕 " + x + "," + y, HandProtocol.RES_BAD_PARAM);
        }
    }
}
