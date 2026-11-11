package com.yuanhou.hand.screen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

import com.yuanhou.hand.util.AppLog;

/**
 * 只读无障碍服务：只读取界面树，用来定位目标和识别敏感页。
 * 严禁 performAction / dispatchGesture / 无障碍点击、滑动、输入；
 * 点击和输入只走 ESP32 BLE HID。HID 失败也不回退到这里。
 * 应用不会自动引导用户去开启它，必须本人明确同意后在系统设置里打开。
 */
public final class ReadonlyAccessibilityService extends AccessibilityService {
    private static volatile ReadonlyAccessibilityService instance;
    private static volatile String lastPackage = "";

    public static boolean isRunning() {
        return instance != null;
    }

    public static String foregroundPackage() {
        return lastPackage;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // HarmonyOS 2 上系统可能已经 bind 服务，却不回调 onServiceConnected。
        // 在 onCreate 就记下实例，否则首页会一直停在「只读无障碍正在连接」。
        instance = this;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AppLog.i("只读无障碍服务已连接（只读，不执行任何操作）");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            lastPackage = event.getPackageName().toString();
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        // 升级后系统可能先连上新实例，再销毁旧的 ConnectionRecord。
        // 无条件清空会把新实例一起清掉，首页就会一直停在「只读无障碍正在连接」。
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /** 读取当前活动窗口的只读快照；服务未开启时返回 null。 */
    public static ScreenSnapshot snapshot() {
        ReadonlyAccessibilityService s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) {
            // 淘宝等应用上 getRootInActiveWindow 会空，但 getWindows 里仍有前台应用窗
            root = rootFromWindows(s);
            if (root != null) AppLog.i("活动窗口根节点为空，改从 getWindows 读取");
        }
        if (root == null) return null;
        ScreenSnapshot snap = new ScreenSnapshot();
        snap.takenAt = SystemClock.elapsedRealtime();
        snap.packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        snap.screenH = s.getResources().getDisplayMetrics().heightPixels;
        walk(root, snap, 0);
        return snap;
    }

    /** 优先取已激活或已聚焦的应用窗根节点；没有则退到任意应用窗。 */
    private static AccessibilityNodeInfo rootFromWindows(AccessibilityService s) {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = s.getWindows();
        } catch (Exception e) {
            return null;
        }
        if (windows == null || windows.isEmpty()) return null;
        AccessibilityNodeInfo fallback = null;
        for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null) continue;
            if (w.isActive() || w.isFocused()) return r;
            if (fallback == null) fallback = r;
        }
        return fallback;
    }

    private static void walk(AccessibilityNodeInfo n, ScreenSnapshot snap, int depth) {
        if (n == null || depth > 40 || snap.nodes.size() > 600) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        // Mika ScreenReader.MIN_SIZE：太小的节点不列
        if (n.isVisibleToUser() && r.width() >= 5 && r.height() >= 5) {
            ScreenSnapshot.Node out = new ScreenSnapshot.Node();
            out.cls = n.getClassName() == null ? "" : n.getClassName().toString();
            out.password = n.isPassword();
            out.text = out.password ? "" : (n.getText() == null ? "" : n.getText().toString());
            out.desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            out.viewId = n.getViewIdResourceName();
            out.bounds.set(r);
            CharSequence hint = n.getHintText();
            out.hint = out.password || hint == null ? "" : hint.toString();
            out.selected = n.isSelected();
            out.enabled = n.isEnabled();
            out.clickable = n.isClickable();
            out.editable = n.isEditable();
            out.focused = n.isFocused();
            out.scrollable = n.isScrollable();
            out.checked = n.isChecked();
            snap.nodes.add(out);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            walk(c, snap, depth + 1);
        }
    }
}
