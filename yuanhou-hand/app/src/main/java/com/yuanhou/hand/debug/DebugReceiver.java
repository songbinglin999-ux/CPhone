package com.yuanhou.hand.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import com.yuanhou.hand.screen.ReadonlyAccessibilityService;
import com.yuanhou.hand.screen.ScreenSnapshot;
import com.yuanhou.hand.service.OrchestratorService;
import com.yuanhou.hand.util.AppLog;

/**
 * 桌上联调入口（只在调试版生效）。清单里用 android.permission.DUMP 保护：只有 adb shell / 系统能发，
 * 其他应用发不进来，所以别的应用没法借它让远手动手。
 *   adb shell am broadcast -a com.yuanhou.hand.DEBUG_SAY --es text '点击 自测按钮'
 *   adb shell am broadcast -a com.yuanhou.hand.DEBUG_TREE      （只读一次界面树写到 tree-last.txt）
 */
public final class DebugReceiver extends BroadcastReceiver {
    public static final String ACTION_SAY = "com.yuanhou.hand.DEBUG_SAY";
    public static final String ACTION_TREE = "com.yuanhou.hand.DEBUG_TREE";

    @Override
    public void onReceive(Context c, Intent i) {
        if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        AppLog.init(c);
        String a = i == null ? null : i.getAction();
        if (ACTION_SAY.equals(a)) {
            String t = i.getStringExtra("text");
            OrchestratorService s = OrchestratorService.get();
            if (s == null) {
                AppLog.i("联调：前台服务没在运行，这句没交给会话");
                return;
            }
            AppLog.i("联调（adb）交来一句：" + t);
            s.local().deliver(t);
        } else if (ACTION_TREE.equals(a)) {
            ScreenSnapshot snap = ReadonlyAccessibilityService.snapshot();
            String body = snap == null
                    ? "读不到界面树（只读无障碍" + (ReadonlyAccessibilityService.isRunning() ? "在运行但没有活动窗口" : "没在运行") + "）\n"
                    : snap.summary(12) + "\n指纹 " + Long.toHexString(snap.fingerprint()) + "\n" + snap.toModelText(150);
            AppLog.writeFile(c, "tree-last.txt", body);
            AppLog.i("联调：界面树已写入 tree-last.txt — " + (snap == null ? "读不到" : snap.summary(4)));
        }
    }
}
