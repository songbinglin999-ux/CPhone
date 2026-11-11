package com.yuanhou.hand.port;

/** 端口里的等待（生产 = Thread.sleep；桌面自检 = 不等）。间隔取自 util/Timing，不另加随机抖动。 */
public interface Sleeper {
    void sleep(int ms) throws InterruptedException;

    Sleeper REAL = ms -> { if (ms > 0) Thread.sleep(ms); };
}
