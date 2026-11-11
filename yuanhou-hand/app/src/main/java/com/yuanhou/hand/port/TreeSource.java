package com.yuanhou.hand.port;

import com.yuanhou.hand.screen.ScreenSnapshot;

/** 读主屏（display 0）界面树：生产环境是只读无障碍 ReadonlyAccessibilityService（capabilities=1，只读）。读不到返回 null。 */
public interface TreeSource {
    ScreenSnapshot read();

    /** 只读无障碍是否在运行。 */
    boolean running();
}
