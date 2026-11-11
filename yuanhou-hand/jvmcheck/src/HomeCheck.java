import com.yuanhou.hand.gate.ConfirmCopy;
import com.yuanhou.hand.gate.ConfirmationGate;
import com.yuanhou.hand.gate.IrrevAction;
import com.yuanhou.hand.util.Timing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.13 首页（简一方案）的桌面自检：确认页文案只来自确认闸申报、确认按钮交的就是口令、付款不出确认页；
 * 微信任务等确认更久而首页/局域网不变；首页源码里没有口令、IP、ESP32、「执行」、微信绿，字号只用五档。
 * 0.1.14：底边 max(navInset+12, 20)、中间间距 8/10/28、设置热区 44。
 */
public class HomeCheck {
    static int pass, fail;

    static void eq(String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what + ": got " + got + " want " + want); }
    }

    static void yes(String what, boolean b) { eq(what, b, true); }

    public static void main(String[] a) throws Exception {
        ConfirmationGate.Declaration order = new ConfirmationGate.Declaration(IrrevAction.ORDER, "com.sankuai.meituan", "某店", "某菜 ×1", "¥18");
        eq("下单问句", ConfirmCopy.question(order), "确认在美团下单？");
        eq("下单口令", ConfirmCopy.confirmWord(order), "确认下单");
        eq("下单要点", ConfirmCopy.primary(order), "某菜 ×1");
        eq("下单金额", ConfirmCopy.amount(order), "¥18");
        eq("下单店家", ConfirmCopy.secondary(order), "某店");
        ConfirmationGate.Declaration orderUnknown = new ConfirmationGate.Declaration(IrrevAction.ORDER, "x.y", "", "", "");
        eq("不认识的应用", ConfirmCopy.question(orderUnknown), "确认下单？");
        ConfirmationGate.Declaration send = new ConfirmationGate.Declaration(IrrevAction.SEND, "com.tencent.mm", "张三", "你好", "");
        eq("发送问句", ConfirmCopy.question(send), "确认发给张三？");
        eq("发送口令", ConfirmCopy.confirmWord(send), "发送");
        eq("发送正文", ConfirmCopy.primary(send), "你好");
        eq("发送不重复对象", ConfirmCopy.secondary(send), "");
        eq("口令与确认闸一致（发送）", ConfirmationGate.classify(IrrevAction.SEND, ConfirmCopy.confirmWord(send)), ConfirmationGate.Reply.CONFIRM);
        eq("口令与确认闸一致（下单）", ConfirmationGate.classify(IrrevAction.ORDER, ConfirmCopy.confirmWord(order)), ConfirmationGate.Reply.CONFIRM);
        ConfirmationGate.Declaration pay = new ConfirmationGate.Declaration(IrrevAction.PAY, "com.sankuai.meituan", "", "", "¥18");
        yes("付款不出确认页", !ConfirmCopy.showable(pay));
        eq("付款没有确认口令", ConfirmCopy.confirmWord(pay), null);
        yes("null 不出确认页", !ConfirmCopy.showable(null));

        eq("首页/局域网等确认仍是 12 秒", Timing.CONFIRM_WAIT_MS, 12000);
        yes("微信任务等确认至少 60 秒", Timing.WECHAT_CONFIRM_WAIT_MS >= 60000);
        yes("微信任务等确认不超过 3 分钟", Timing.WECHAT_CONFIRM_WAIT_MS <= 180000);

        String ts = new String(Files.readAllBytes(Paths.get("../app/src/main/java/com/yuanhou/hand/session/TaskSession.java")), StandardCharsets.UTF_8);
        yes("等确认时长按来源选", ts.contains("origin == Origin.WECHAT ? Timing.WECHAT_CONFIRM_WAIT_MS : Timing.CONFIRM_WAIT_MS"));
        yes("仍是两次", ts.contains("for (int attempt = 0; attempt < 2; attempt++)"));
        yes("开始办后复位来源", ts.contains("nextOrigin = Origin.LOCAL;"));
        String wb = new String(Files.readAllBytes(Paths.get("../app/src/main/java/com/yuanhou/hand/wechat/WechatBridge.java")), StandardCharsets.UTF_8);
        yes("只有微信新任务标来源", wb.contains("markNextTaskOrigin(TaskSession.Origin.WECHAT)"));

        String home = new String(Files.readAllBytes(Paths.get("../app/src/main/java/com/yuanhou/hand/ui/MainActivity.java")), StandardCharsets.UTF_8);
        String code = home.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        yes("首页不取口令", !code.contains("token()") && !code.contains("PREF_TOKEN") && !code.contains("regenerateToken"));
        yes("首页不写 IP", !code.contains("listenAddr") && !code.contains("wifiIpv4(this) +"));
        yes("首页不写 ESP32", !code.contains("\"ESP32") && !code.contains("ESP32\""));
        yes("首页不用「执行」", !code.contains("\"执行\""));
        yes("首页没有急停/停止服务/重连常驻", !code.contains("emergencyStop") && !code.contains("\"停止服务\"") && !code.contains("\"急停\""));
        yes("首页没有「家里备用机的手」", !code.contains("家里备用机的手"));
        yes("首页按钮文案「去办」", code.contains("\"去办\""));
        yes("占位符", code.contains("在家里直接说也行") && code.contains("更正或补充"));
        yes("不用微信绿", !home.toUpperCase().contains("07C160"));
        Matcher m = Pattern.compile("Style\\.text\\(this, (\\d+),").matcher(code);
        int n = 0;
        while (m.find()) {
            n++;
            int sp = Integer.parseInt(m.group(1));
            if (sp != 13 && sp != 16 && sp != 20 && sp != 22 && sp != 28) { fail++; System.out.println("FAIL 首页字号 " + sp); }
            else pass++;
        }
        yes("首页字号用了 Style.text", n >= 8);
        String settings = new String(Files.readAllBytes(Paths.get("../app/src/main/java/com/yuanhou/hand/ui/SettingsActivity.java")), StandardCharsets.UTF_8);
        yes("设置里口令默认圆点", settings.contains("tokenShown ? t : \"••••••••••••\"") && settings.contains("tokenShown = false;"));
        yes("设置里有服务开关/重连/急停", settings.contains("toggleService") && settings.contains("reconnectNow") && settings.contains("emergencyStop"));
        yes("自测在设置里", settings.contains("runSelfTest") && settings.contains("Thread.sleep(600)") && settings.contains("落点不在自测框内"));
        yes("口令不写日志", !Pattern.compile("AppLog\\.i\\([^;]*(currentToken|token\\(\\))").matcher(settings).find());

        int buildCenter = code.indexOf("private void buildCenter");
        int buildWorking = code.indexOf("private void buildWorking");
        yes("找得到 buildCenter", buildCenter >= 0 && buildWorking > buildCenter);
        String center = (buildCenter >= 0 && buildWorking > buildCenter) ? code.substring(buildCenter, buildWorking) : "";
        yes("底垫用 Math.max", code.contains("Math.max("));
        yes("底垫读导航 inset", code.contains("getSystemWindowInsetBottom()"));
        yes("底垫加 12 dp", code.contains("insetBottom + dp(12)") || code.contains("getSystemWindowInsetBottom() + dp(12)"));
        yes("底垫下限 20 dp", code.contains("Math.max(insetBottom + dp(12), dp(20))") || code.contains("Math.max(ins.getSystemWindowInsetBottom() + dp(12), dp(20))"));
        yes("root 底垫为 0，不把底 inset 加在 root 上", Pattern.compile("setPadding\\(\\s*ins\\.getSystemWindowInsetLeft\\(\\),\\s*ins\\.getSystemWindowInsetTop\\(\\),\\s*ins\\.getSystemWindowInsetRight\\(\\),\\s*0\\s*\\)").matcher(code).find());
        yes("root 不再把 Bottom inset 当作第四个 padding", !Pattern.compile("setPadding\\([^;]*getSystemWindowInsetBottom\\(\\)\\s*\\)").matcher(code).find());
        yes("状态圆点右边距 8", center.contains("LayoutParams(dp(8), dp(8))") && center.contains("rightMargin = dp(8)"));
        yes("标题行高贴齐 28sp", center.contains("headline.setLineHeight(Style.spPx(this, 28))"));
        yes("副标题行高贴齐 16sp", center.contains("subtitle.setLineHeight(Style.spPx(this, 16))"));
        yes("标题到副标题可见间距 10", center.contains("topMargin = dp(10)"));
        yes("副标题到主按钮可见间距 28", center.contains("topMargin = dp(28)"));
        yes("设置热区宽至少 44", code.contains("setMinWidth(dp(44))"));
        yes("设置热区高至少 44", code.contains("setMinHeight(dp(44))"));

        System.out.println("HomeCheck: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
