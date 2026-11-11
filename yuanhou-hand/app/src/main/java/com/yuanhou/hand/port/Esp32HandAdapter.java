package com.yuanhou.hand.port;

import com.yuanhou.hand.hand.HandExecutor;
import com.yuanhou.hand.hand.HandProtocol;
import com.yuanhou.hand.hand.SuiteState;

/** 把 HandExecutor（ESP32 Wi-Fi 透传 / GATT 备用）接成 HandPort。只转调用，不加重试、不回退到无障碍。 */
public final class Esp32HandAdapter implements HandPort {
    private final HandExecutor hand;
    private final SuiteState suite;

    public Esp32HandAdapter(HandExecutor hand, SuiteState suite) {
        this.hand = hand;
        this.suite = suite;
    }

    private interface Call { void run() throws HandExecutor.HandException, InterruptedException; }

    private void call(Call c) throws PortException, InterruptedException {
        try {
            c.run();
        } catch (HandExecutor.HandException e) {
            throw new PortException(e.getMessage(), e.result,
                    e.result == HandProtocol.RES_NOT_READY, e.result == HandProtocol.RES_CANCELLED);
        }
    }

    @Override public String notReadyReason() { return suite.notReadyReason(); }
    @Override public int screenW() { return suite.screenW; }
    @Override public int screenH() { return suite.screenH; }
    @Override public void click(int x, int y) throws PortException, InterruptedException { call(() -> hand.click(x, y)); }
    @Override public void longPress(int x, int y) throws PortException, InterruptedException { call(() -> hand.longPress(x, y)); }
    @Override public void swipe(int x1, int y1, int x2, int y2) throws PortException, InterruptedException { call(() -> hand.swipe(x1, y1, x2, y2)); }
    @Override public void scroll(int x, int y, int n) throws PortException, InterruptedException { call(() -> hand.scroll(x, y, n)); }
    @Override public void back() throws PortException, InterruptedException { call(hand::back); }
    @Override public void home() throws PortException, InterruptedException { call(hand::home); }
    @Override public void enter() throws PortException, InterruptedException { call(hand::enter); }
    @Override public void escape() throws PortException, InterruptedException { call(hand::escape); }
    @Override public void cursorToEnd() throws PortException, InterruptedException { call(hand::cursorToEnd); }
    @Override public void backspaces(int n) throws PortException, InterruptedException { call(() -> hand.backspaces(n)); }
    @Override public void paste(String text) throws PortException, InterruptedException { call(() -> hand.paste(text)); }
}
