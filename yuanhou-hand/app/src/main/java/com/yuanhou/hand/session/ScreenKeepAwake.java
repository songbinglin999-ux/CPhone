package com.yuanhou.hand.session;

import android.content.Context;
import android.os.PowerManager;

/**
 * 任务在跑时屏幕不熄灭（学 Mika KeepAwake / ScreenWakeLock）。
 * 不改系统超时和亮度；用户按电源键仍可熄屏。锁随进程走，异常退出由系统收回。
 */
public final class ScreenKeepAwake {
    @SuppressWarnings("deprecation")
    private static final int LEVEL = PowerManager.SCREEN_DIM_WAKE_LOCK;
    /** 兜底上限，防止哪条路径漏了释放。 */
    private static final long MAX_HOLD_MS = 6L * 60 * 60 * 1000;

    private PowerManager.WakeLock lock;

    public void acquire(Context ctx) {
        if (ctx == null) return;
        if (lock != null && lock.isHeld()) return;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        lock = pm.newWakeLock(LEVEL, "yuanhou:task");
        lock.setReferenceCounted(false);
        lock.acquire(MAX_HOLD_MS);
    }

    public void release() {
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
    }
}
