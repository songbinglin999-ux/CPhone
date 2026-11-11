package com.yuanhou.hand.port;

import com.yuanhou.hand.screen.ScreenSnapshot;

import java.util.Collections;
import java.util.List;

/**
 * 远手观察和操作主屏的唯一入口（接缝思路来自 Mika core/uistate/ScreenPort.kt，Java 重写，不是拷贝）。
 * 上层（TaskSession 的「读屏 → 动作 → 执行 → 等稳定 → 再读屏」循环）只认这个接口，不关心底下是什么。
 *
 * 和 Mika 的差别（有意为之）：
 * - 只有主屏 display 0。没有 displayId 参数，没有虚拟屏（AgentDisplay）、没有 shell 助手、没有无线调试配对。
 * - 坐标是主屏真实像素（1080×2400），不做 0–999 归一化；元素优先按 ref 选，ref 绑定 snapshotId，旧一屏的编号直接 REF_EXPIRED。
 * - 阻塞式（工作线程调用），不是协程。
 * - 故意不在接口上出现：systemCommand、openAppCopy、awaitAppFree、任务挪动、MainScreenSpill「挪回虚拟屏」。
 *   这些在远手里没有对应能力，也不打算有。
 *
 * 默认实现返回 UNSUPPORTED / 空 / null 的方法，MainScreenEsp32Port 不覆盖。
 */
public interface ScreenPort {

    /** Android KeyEvent 的键码（这里抄常量值，免得核心层依赖 android.view）。 */
    int KEYCODE_HOME = 3;
    int KEYCODE_BACK = 4;
    int KEYCODE_DPAD_RIGHT = 22;
    int KEYCODE_ENTER = 66;
    int KEYCODE_DEL = 67;
    int KEYCODE_ESCAPE = 111;
    int KEYCODE_MOVE_END = 123;

    // ---------------- 读屏 ----------------

    /** 读一屏主屏界面树，并给元素编上 ref（规则同 ScreenSnapshot.toModelText）。读不到返回 null。 */
    ScreenSnapshot snapshot();

    /** 最近一次 snapshot() 的编号；还没读过为 null。 */
    String snapshotId();

    /** 只读当前前台包名（不换 snapshotId）；读不到为空串。 */
    String foregroundPackage();

    /** 截图：远手默认不开（只靠树）。 */
    default byte[] screenshotPng() { return null; }

    /** 认字兜底：没有。 */
    default List<String> readVisibleText() { return null; }

    // ---------------- 动作（全部经 ESP32 HID） ----------------

    ActionResult tapRef(int ref, String snapshotId);

    ActionResult tapXY(int x, int y);

    ActionResult longPressRef(int ref, String snapshotId);

    ActionResult longPressXY(int x, int y);

    /**
     * 往输入框打字：ref&lt;=0 表示当前有焦点的输入框。
     * 先清空（Ctrl+End + Backspace），再剪贴板 + Ctrl+V，再读回核对完全一致；不一致 VERIFY_FAILED，不再贴第二次。
     * 密码框直接 NEEDS_HUMAN，不点、不贴、不做粘贴探测。
     */
    ActionResult typeText(int ref, String snapshotId, String text);

    /** 只清空（同 typeText 的第一段）。 */
    ActionResult clearText(int ref, String snapshotId);

    /** 在 ref 指的可滚动元素（ref&lt;=0 取面积最大的可滚动元素，再退到屏幕中间）上用滚轮翻一页。 */
    ActionResult scroll(int ref, String snapshotId, boolean down);

    /** 直线滑动。 */
    ActionResult swipe(int x1, int y1, int x2, int y2);

    ActionResult back();

    /** 支持：HOME、BACK、ENTER、DEL（单个回退）、ESCAPE、MOVE_END（Ctrl+End）。其余 UNSUPPORTED（含右方向键：输入法会吞）。 */
    ActionResult pressKey(int keyCode);

    /** 本应用 startActivity(launchIntent) 打开，打开后核对前台确实是它。 */
    ActionResult openApp(String packageName);

    /** 等界面静下来：连续两次读到的指纹相同。到时限还在变返回 false。 */
    boolean waitStable(long timeoutMs);

    /** 等界面相对上一次 snapshot 发生变化；到时限没变返回 false。 */
    boolean awaitChange(long timeoutMs);

    default List<String[]> listApps() { return Collections.emptyList(); }

    /** App 范围检查的挂点；装饰器 AppAccessScreenPort 覆盖。 */
    default String checkAppAccess() { return null; }

    // ---------------- 不支持（保持空） ----------------

    default ActionResult doubleTapRef(int ref, String snapshotId) { return ActionResult.unsupported("双击"); }

    default ActionResult doubleTapXY(int x, int y) { return ActionResult.unsupported("双击"); }

    default ActionResult typeAt(int x, int y, String text) { return ActionResult.unsupported("按坐标打字"); }

    default ActionResult clearAt(int x, int y) { return ActionResult.unsupported("按坐标清空"); }

    default ActionResult gesture(List<int[][]> paths, long durationMs) { return ActionResult.unsupported("多指手势"); }

    default ActionResult openLink(String action, String uri, String packageName) { return ActionResult.unsupported("打开链接"); }

    default ActionResult shareText(String packageName, String text) { return ActionResult.unsupported("分享文字"); }

    default boolean isProtected() { return false; }
}
