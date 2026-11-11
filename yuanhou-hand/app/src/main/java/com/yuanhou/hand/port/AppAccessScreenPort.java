package com.yuanhou.hand.port;

import com.yuanhou.hand.screen.ScreenSnapshot;

import java.util.List;

/**
 * 装饰器：在内层 ScreenPort 的每个写操作前核对前台 App 是否在允许范围里（思路同 Mika core/uistate/AppAccessScreenPort.kt）。
 * 策略在 AppAccessPolicy，不写进 MainScreenEsp32Port。读屏、等待原样转发。被拦下的动作没有发给 ESP32，返回 BLOCKED。
 */
public final class AppAccessScreenPort implements ScreenPort {
    private final ScreenPort inner;
    private final AppAccessPolicy policy;

    public AppAccessScreenPort(ScreenPort inner, AppAccessPolicy policy) {
        this.inner = inner;
        this.policy = policy == null ? AppAccessPolicy.defaults() : policy;
    }

    public ScreenPort inner() { return inner; }

    @Override
    public String checkAppAccess() {
        return policy.checkWrite(inner.foregroundPackage());
    }

    private interface Body { ActionResult run(); }

    private ActionResult checked(Body b) {
        String why = checkAppAccess();
        if (why != null) return ActionResult.of(ActionResult.Status.BLOCKED, why);
        return b.run();
    }

    @Override public ScreenSnapshot snapshot() { return inner.snapshot(); }
    @Override public String snapshotId() { return inner.snapshotId(); }
    @Override public String foregroundPackage() { return inner.foregroundPackage(); }
    @Override public byte[] screenshotPng() { return inner.screenshotPng(); }
    @Override public List<String> readVisibleText() { return inner.readVisibleText(); }

    @Override public ActionResult tapRef(int ref, String sid) { return checked(() -> inner.tapRef(ref, sid)); }
    @Override public ActionResult tapXY(int x, int y) { return checked(() -> inner.tapXY(x, y)); }
    @Override public ActionResult longPressRef(int ref, String sid) { return checked(() -> inner.longPressRef(ref, sid)); }
    @Override public ActionResult longPressXY(int x, int y) { return checked(() -> inner.longPressXY(x, y)); }
    @Override public ActionResult typeText(int ref, String sid, String text) { return checked(() -> inner.typeText(ref, sid, text)); }
    @Override public ActionResult clearText(int ref, String sid) { return checked(() -> inner.clearText(ref, sid)); }
    @Override public ActionResult scroll(int ref, String sid, boolean down) { return checked(() -> inner.scroll(ref, sid, down)); }
    @Override public ActionResult swipe(int x1, int y1, int x2, int y2) { return checked(() -> inner.swipe(x1, y1, x2, y2)); }
    @Override public ActionResult back() { return checked(inner::back); }
    @Override public ActionResult pressKey(int keyCode) { return checked(() -> inner.pressKey(keyCode)); }

    @Override
    public ActionResult openApp(String packageName) {
        String why = policy.checkOpen(packageName);
        if (why != null) return ActionResult.of(ActionResult.Status.BLOCKED, why);
        return inner.openApp(packageName);
    }

    @Override public boolean waitStable(long t) { return inner.waitStable(t); }
    @Override public boolean awaitChange(long t) { return inner.awaitChange(t); }

    @Override
    public List<String[]> listApps() {
        List<String[]> all = new java.util.ArrayList<>();
        for (String[] a : inner.listApps()) if (policy.checkOpen(a[0]) == null) all.add(a);
        return all;
    }
}
