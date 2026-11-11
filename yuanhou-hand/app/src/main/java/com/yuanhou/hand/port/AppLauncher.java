package com.yuanhou.hand.port;

import java.util.List;

/** 在主屏上打开 App：本应用自己 startActivity(launchIntent)，不经 shell / ADB，不指定 displayId，不挪任务。 */
public interface AppLauncher {
    /** 发出打开指令；成功返回 null，否则返回原因（没装、系统拒绝）。 */
    String launch(String packageName);

    /** [包名, 显示名]；只列已装的、允许范围里的。 */
    List<String[]> listApps();
}
