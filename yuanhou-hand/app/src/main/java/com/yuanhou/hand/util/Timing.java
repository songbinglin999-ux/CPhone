package com.yuanhou.hand.util;

import java.util.Random;

/**
 * 设计第 7 节的时序参数。随机间隔只为等页面画完、让 HID 动作被系统稳定识别，
 * 不是规避风控，也不按应用类型改节奏。这里没有抖动、落点偏移或「想事情」停顿。
 */
public final class Timing {
    private Timing() {}

    private static final Random R = new Random();

    public static int uniform(int min, int max) {
        if (max <= min) return min;
        synchronized (R) {
            return min + R.nextInt(max - min + 1);
        }
    }

    /** 两次主屏操作之间：截断正态 μ=450 σ=120，[280, 900]。 */
    public static int betweenOps() {
        for (int i = 0; i < 32; i++) {
            double v;
            synchronized (R) {
                v = 450 + 120 * R.nextGaussian();
            }
            if (v >= 280 && v <= 900) return (int) Math.round(v);
        }
        return 450;
    }

    public static int clickHold()      { return uniform(60, 120); }   // 默认中值 90
    public static int longPressHold()  { return uniform(620, 900); }  // 默认中值 750
    public static int swipeDuration()  { return uniform(200, 450); }  // 默认中值 300，直线
    public static int keyGap()         { return uniform(30, 80); }    // 默认中值 50
    public static int afterPageOpen()  { return uniform(1200, 1800); }
    public static int afterHeavyPage() { return uniform(2000, 2500); }
    public static int afterSwipe()     { return uniform(400, 1000); }
    public static int afterText()      { return uniform(300, 800); }
    /** HID 返回 OK 后，点击/滑动命中判定至少再等 800–1200 ms（真机约 900 ms 才落到控件）。 */
    public static int hitVerify()      { return uniform(900, 1200); }
    public static int beforeReadback() { return uniform(200, 500); }
    public static int betweenSpeech()  { return uniform(350, 450); }
    public static int bleReconnect()   { return uniform(2000, 5000); }
    public static final int CONFIRM_WAIT_MS = 12000;
    /** 0.1.13：只给微信交来的任务用——本人可能在开车，要打开微信回「发送」「确认下单」，每次等 90 秒（仍是两次）。首页/局域网任务不变。 */
    public static final int WECHAT_CONFIRM_WAIT_MS = 90000;

    public static void sleep(int ms) throws InterruptedException {
        if (ms > 0) Thread.sleep(ms);
    }
}
