import com.yuanhou.hand.port.ActionResult;
import com.yuanhou.hand.port.AppAccessPolicy;
import com.yuanhou.hand.port.AppAccessScreenPort;
import com.yuanhou.hand.port.AppLauncher;
import com.yuanhou.hand.port.HandPort;
import com.yuanhou.hand.port.MainScreenEsp32Port;
import com.yuanhou.hand.port.PortException;
import com.yuanhou.hand.port.ScreenPort;
import com.yuanhou.hand.port.TreeSource;
import com.yuanhou.hand.screen.ScreenSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** MainScreenEsp32Port 的桌面自检：假的「手」+ 假的界面树，模拟一个输入框（不连手机、不动 ESP32）。 */
public class PortCheck {
    static int pass, fail;

    static void eq(String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what + ": got " + got + " want " + want); }
    }

    /** 假手机：一个输入框 + 一个按钮。记录所有 HID 动作。 */
    static final class Phone implements TreeSource, HandPort {
        String pkg = "com.tencent.mm";
        String text = "";
        boolean focused, password;
        /** 模拟 End 没生效（光标在中间）：回退删不干净。 */
        boolean endBroken;
        /** 模拟输入法改字：贴进去的字被替换。 */
        String pasteMangle;
        String notReady;
        final List<String> log = new ArrayList<>();

        @Override public boolean running() { return true; }

        @Override
        public ScreenSnapshot read() {
            ScreenSnapshot s = new ScreenSnapshot();
            s.packageName = pkg; s.screenH = 2400;
            ScreenSnapshot.Node t = new ScreenSnapshot.Node();
            t.cls = "android.widget.TextView"; t.text = "张三"; t.desc = ""; t.hint = "";
            t.bounds.set(200, 100, 800, 200);
            s.nodes.add(t);
            ScreenSnapshot.Node f = new ScreenSnapshot.Node();
            f.cls = "android.widget.EditText"; f.text = password ? "" : text; f.desc = ""; f.hint = "";
            f.bounds.set(100, 2000, 850, 2150);
            f.editable = true; f.clickable = true; f.focused = focused; f.password = password;
            s.nodes.add(f);
            ScreenSnapshot.Node b = new ScreenSnapshot.Node();
            b.cls = "android.widget.Button"; b.text = "发送"; b.desc = ""; b.hint = "";
            b.bounds.set(870, 2000, 1000, 2150); b.clickable = true;
            s.nodes.add(b);
            return s;
        }

        @Override public String notReadyReason() { return notReady; }
        @Override public int screenW() { return 1080; }
        @Override public int screenH() { return 2400; }
        @Override public void click(int x, int y) { log.add("click"); if (y > 2000 && y < 2150 && x < 850) focused = true; }
        @Override public void longPress(int x, int y) { log.add("long"); }
        @Override public void swipe(int a, int b, int c, int d) { log.add("swipe"); }
        @Override public void scroll(int x, int y, int n) { log.add("scroll" + n); }
        @Override public void back() { log.add("back"); }
        @Override public void home() { log.add("home"); }
        @Override public void enter() { log.add("enter"); }
        @Override public void escape() { log.add("esc"); }
        @Override public void cursorToEnd() { log.add("ctrl+end"); }
        @Override public void backspaces(int n) {
            log.add("bs" + n);
            int keep = endBroken ? Math.min(3, text.length()) : 0;
            text = text.substring(0, Math.max(keep, text.length() - n));
        }
        @Override public void paste(String s) throws PortException {
            log.add("paste");
            if (!focused) throw new PortException("没焦点");
            text = text + (pasteMangle != null ? pasteMangle : s);
        }
    }

    static MainScreenEsp32Port port(Phone p) {
        AppLauncher l = new AppLauncher() {
            @Override public String launch(String pkg) { p.pkg = pkg; return null; }
            @Override public List<String[]> listApps() { return Collections.emptyList(); }
        };
        return new MainScreenEsp32Port(p, p, l, ms -> {});
    }

    static int fieldRef(ScreenSnapshot s) {
        for (ScreenSnapshot.Node n : s.nodes) if (n.editable) return n.ref;
        return -1;
    }

    public static void main(String[] a) {
        // 1) 正常：点框 → Ctrl+End + 回退 → 贴 → 读回一致
        Phone p = new Phone();
        p.text = "旧的草稿";
        MainScreenEsp32Port port = port(p);
        ScreenSnapshot s = port.snapshot();
        ActionResult r = port.typeText(fieldRef(s), port.snapshotId(), "晚上七点到");
        eq("打字 OK", r.status, ActionResult.Status.OK);
        eq("核对 MATCH", r.verification, ActionResult.Verification.MATCH);
        eq("框里", p.text, "晚上七点到");
        eq("顺序", String.join(",", p.log), "click,ctrl+end,bs16,paste");
        eq("没有 Ctrl+A / 右键", p.log.toString().contains("right") || p.log.toString().contains("ctrl+a"), false);

        // 2) 清不干净：VERIFY_FAILED，没有贴
        p = new Phone(); p.text = "很长的一段旧字"; p.focused = true; p.endBroken = true;
        port = port(p);
        r = port.typeText(0, null, "新字");
        eq("清空失败 VERIFY_FAILED", r.status, ActionResult.Status.VERIFY_FAILED);
        eq("清空失败没贴", p.log.contains("paste"), false);
        eq("没有右方向键回退", String.join(",", p.log), "ctrl+end,bs19");

        // 3) 贴后不一致：VERIFY_FAILED，只贴了一次
        p = new Phone(); p.focused = true; p.pasteMangle = "晚上7点到";
        port = port(p);
        r = port.typeText(0, null, "晚上七点到");
        eq("核对不符", r.status, ActionResult.Status.VERIFY_FAILED);
        eq("核对 MISMATCH", r.verification, ActionResult.Verification.MISMATCH);
        eq("只贴一次", Collections.frequency(p.log, "paste"), 1);

        // 4) 密码框：NEEDS_HUMAN，不点不贴
        p = new Phone(); p.password = true;
        port = port(p);
        s = port.snapshot();
        r = port.typeText(fieldRef(s), port.snapshotId(), "123456");
        eq("密码框交本人", r.status, ActionResult.Status.NEEDS_HUMAN);
        eq("密码框没有任何 HID 动作", p.log.isEmpty(), true);

        // 5) 旧编号：动过之后旧 ref 作废
        p = new Phone();
        port = port(p);
        s = port.snapshot();
        String sid = port.snapshotId();
        port.back();
        eq("写操作后编号作废", port.tapRef(fieldRef(s), sid).status, ActionResult.Status.REF_EXPIRED);

        // 6) 没就绪不发
        p = new Phone(); p.notReady = "Wi-Fi 透传连不上";
        port = port(p);
        eq("未就绪", port.tapXY(500, 500).status, ActionResult.Status.NOT_READY);
        eq("未就绪没动", p.log.isEmpty(), true);

        // 7) 按键
        p = new Phone();
        port = port(p);
        eq("右方向键不支持", port.pressKey(ScreenPort.KEYCODE_DPAD_RIGHT).status, ActionResult.Status.UNSUPPORTED);
        eq("回车", port.pressKey(ScreenPort.KEYCODE_ENTER).isOk(), true);
        eq("双击不支持", port.doubleTapXY(1, 1).status, ActionResult.Status.UNSUPPORTED);
        eq("截图默认空", port.screenshotPng(), null);
        eq("等稳定", port.waitStable(1000), true);

        // 8) App 范围装饰器
        p = new Phone(); p.pkg = "me.ele";
        ScreenPort guarded = new AppAccessScreenPort(port(p), AppAccessPolicy.defaults());
        eq("不允许的前台不点", guarded.tapXY(500, 500).status, ActionResult.Status.BLOCKED);
        eq("拦下没发 HID", p.log.isEmpty(), true);
        eq("读屏不拦", guarded.snapshot() != null, true);
        eq("不允许打开饿了么", guarded.openApp("me.ele").status, ActionResult.Status.BLOCKED);
        p.pkg = "com.tencent.mm";
        eq("允许的前台可点", guarded.tapXY(500, 500).isOk(), true);
        eq("打开淘宝", guarded.openApp("com.taobao.taobao").isOk(), true);
        eq("打开美团", guarded.openApp("com.sankuai.meituan").isOk(), true);
        p.pkg = "com.sankuai.meituan";
        eq("美团前台可点", guarded.tapXY(500, 500).isOk(), true);

        System.out.println("PortCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
