import com.yuanhou.hand.screen.ScreenSnapshot;
import com.yuanhou.hand.session.LocalIntent;
import com.yuanhou.hand.session.LocalIntent.Kind;

import java.util.List;

/** 本地意图拆句 + 按文字找元素的桌面自检。 */
public class IntentCheck {
    static int pass, fail;
    static void eq(String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what + ": got " + got + " want " + want); }
    }
    static String kinds(List<LocalIntent.Step> p) {
        if (p == null) return null;
        StringBuilder b = new StringBuilder();
        for (LocalIntent.Step s : p) b.append(s.kind).append('[').append(s.target).append('|').append(s.text).append(']').append(' ');
        return b.toString().trim();
    }
    static ScreenSnapshot.Node n(String text, String hint, int t, boolean edit, boolean click) {
        ScreenSnapshot.Node x = new ScreenSnapshot.Node();
        x.cls = edit ? "android.widget.EditText" : "android.widget.Button";
        x.text = text; x.desc = ""; x.hint = hint;
        x.bounds.set(100, t, 900, t + 120);
        x.editable = edit; x.clickable = click;
        return x;
    }
    public static void main(String[] a) {
        eq("单击", kinds(LocalIntent.parse("点击 自测按钮")), "CLICK_TEXT[自测按钮|]");
        eq("串步", kinds(LocalIntent.parse("点击 自测按钮，然后在 自测输入框 输入 你好Hello123")),
                "CLICK_TEXT[自测按钮|] PASTE_INTO[自测输入框|你好Hello123]");
        eq("紧凑", kinds(LocalIntent.parse("在自测输入框输入你好")), "PASTE_INTO[自测输入框|你好]");
        eq("里", kinds(LocalIntent.parse("在搜索框里输入 abc")), "PASTE_INTO[搜索框|abc]");
        eq("坐标", kinds(LocalIntent.parse("点击 540,1200")), "CLICK_XY[|]");
        eq("编号", kinds(LocalIntent.parse("点 #12")), "CLICK_REF[|]");
        eq("引号", kinds(LocalIntent.parse("输入「你好，世界」然后返回")), "PASTE[|你好，世界] BACK[|]");
        eq("逗号并入", kinds(LocalIntent.parse("输入 你好，世界")), "PASTE[|你好，世界]");
        eq("滚动", kinds(LocalIntent.parse("上滑；下滑；读屏；主页")), "SCROLL_DOWN[|] SCROLL_UP[|] READ[|] HOME[|]");
        eq("看不懂", kinds(LocalIntent.parse("帮我订一张明天去上海的机票")), null);
        eq("再", kinds(LocalIntent.parse("点击 设置，再点击 蓝牙")), "CLICK_TEXT[设置|] CLICK_TEXT[蓝牙|]");

        ScreenSnapshot s = new ScreenSnapshot();
        s.packageName = "com.yuanhou.hand";
        s.nodes.add(n("控制自测区", "", 100, false, false));
        s.nodes.add(n("自测按钮", "", 250, false, true));
        s.nodes.add(n("自测按钮已点 0 次", "", 250, false, false));
        s.nodes.add(n("自测输入框", "自测输入框", 400, true, true));
        s.nodes.add(n("点击 自测按钮", "说一句要办的事", 600, true, true));
        eq("精确", s.findByText("自测按钮").bounds.top, 250);
        eq("提示语", s.findField("自测输入框").bounds.top, 400);
        eq("包含", s.findByText("已点").text, "自测按钮已点 0 次");
        eq("没有", s.findByText("微信"), null);
        System.out.println("IntentCheck 通过 " + pass + "，失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
