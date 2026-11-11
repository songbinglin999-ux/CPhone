import android.graphics.Rect;

import com.yuanhou.hand.memory.AgentMemory;
import com.yuanhou.hand.memory.Playbook;
import com.yuanhou.hand.memory.StepHistory;
import com.yuanhou.hand.policy.InterruptionGuard;
import com.yuanhou.hand.policy.SafetyGuard;
import com.yuanhou.hand.screen.ScreenSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 0.1.21：广告和跳转由代码处理，不问模型。 */
public class InterruptionCheck {
    static int pass, fail;

    static void eq(String name, Object a, Object b) {
        if (String.valueOf(a).equals(String.valueOf(b))) pass++;
        else { fail++; System.out.println("FAIL " + name + ": " + a + " != " + b); }
    }

    static void yes(String name, boolean b) { eq(name, b, true); }

    static ScreenSnapshot.Node node(String text, int l, int t, int r, int b, boolean clickable) {
        ScreenSnapshot.Node n = new ScreenSnapshot.Node();
        n.cls = "android.widget.TextView";
        n.text = text;
        n.bounds = new Rect(l, t, r, b);
        n.clickable = clickable;
        n.enabled = true;
        return n;
    }

    static ScreenSnapshot page(String pkg, String title, ScreenSnapshot.Node... nodes) {
        ScreenSnapshot s = new ScreenSnapshot();
        s.packageName = pkg;
        s.windowTitle = title;
        s.screenH = 2400;
        for (ScreenSnapshot.Node n : nodes) s.nodes.add(n);
        return s;
    }

    public static void main(String[] args) throws Exception {
        detect();
        caps();
        jumps();
        batchAndRefs();
        memoryAndEntry();
        sources();
        System.out.println("InterruptionCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void detect() {
        ScreenSnapshot clean = page("com.taobao.taobao", "搜索",
                node("搜索", 40, 80, 300, 160, true),
                node("杯子", 40, 800, 400, 900, true));
        InterruptionGuard g = new InterruptionGuard();
        g.accept(clean);
        eq("干净页面交给模型", g.assess(clean).action, InterruptionGuard.Action.CLEAR);

        ScreenSnapshot popup = page("com.taobao.taobao", "首页",
                node("开红包", 200, 700, 800, 900, true),
                node("立即领取", 200, 920, 800, 1040, true),
                node("跳过", 800, 200, 1000, 280, true));
        popup.toModelText(20);
        InterruptionGuard.Decision tap = g.assess(popup);
        eq("弹窗点跳过", tap.action, InterruptionGuard.Action.TAP_CLOSE);
        eq("点的是跳过", tap.target.label(), "跳过");
        yes("不点立即领取", tap.target != null && !tap.target.label().contains("领取"));
        yes("上一屏编号作废", tap.invalidateRefs);
        yes("跳过不是上一屏的节点", clean.toModelText(20).contains("#") && tap.target != clean.byRef(1));

        ScreenSnapshot claimOnly = page("com.taobao.taobao", "首页",
                node("立即领取", 200, 900, 800, 1040, true),
                node("摇一摇", 200, 1100, 800, 1240, true));
        InterruptionGuard.Decision waitClaim = g.assess(claimOnly);
        eq("只有领取时不点", waitClaim.action, InterruptionGuard.Action.WAIT);
        yes("领取没有点击目标", waitClaim.target == null);

        ScreenSnapshot countdown = page("com.taobao.taobao", "广告",
                node("广告倒计时 5 秒", 40, 800, 600, 900, false));
        InterruptionGuard fresh = new InterruptionGuard();
        InterruptionGuard.Decision wait = fresh.assess(countdown);
        eq("倒计时只等", wait.action, InterruptionGuard.Action.WAIT);
        yes("倒计时没有点击目标", wait.target == null);
        fresh.commit(wait);
        InterruptionGuard.Decision still = fresh.assess(countdown);
        eq("等完还在就停", still.action, InterruptionGuard.Action.STOP);
        yes("全屏广告交给本人", still.needsHuman);
        yes("停下时说明不乱点", still.speak.contains("不会在倒计时结束前乱点"));

        ScreenSnapshot web = page("com.taobao.taobao", "", node("", 0, 0, 10, 10, false));
        web.nodes.get(0).cls = "android.webkit.WebView";
        eq("读不出的网页只等", fresh.assess(web).action == InterruptionGuard.Action.WAIT
                || fresh.assess(web).action == InterruptionGuard.Action.STOP, true);

        ScreenSnapshot pay = page("com.eg.android.AlipayGphone", "支付",
                node("请输入支付密码", 40, 800, 800, 960, false));
        InterruptionGuard.Decision paid = g.assess(pay);
        eq("付款页停下", paid.action, InterruptionGuard.Action.STOP);
        yes("付款页不点", paid.target == null && paid.needsHuman);
        eq("安全闸仍认付款", SafetyGuard.check(pay).kind, SafetyGuard.Kind.PAYMENT_PAGE);
    }

    static void caps() {
        InterruptionGuard g = new InterruptionGuard();
        ScreenSnapshot popup = page("com.taobao.taobao", "首页",
                node("开红包", 200, 700, 800, 900, true),
                node("跳过", 800, 200, 1000, 280, true));
        for (int i = 0; i < InterruptionGuard.CONSECUTIVE_POPUP_CAP; i++) {
            InterruptionGuard.Decision d = g.assess(popup);
            eq("第 " + (i + 1) + " 个弹窗仍关", d.action, InterruptionGuard.Action.TAP_CLOSE);
            g.commit(d);
        }
        InterruptionGuard.Decision stop = g.assess(popup);
        eq("一连 3 个就停", stop.action, InterruptionGuard.Action.STOP);
        yes("说明关了 3 次", stop.speak.contains("3"));
        yes("停下时没有点击目标", stop.target == null);

        InterruptionGuard spread = new InterruptionGuard();
        ScreenSnapshot clean = page("com.taobao.taobao", "搜索", node("搜索", 40, 80, 300, 160, true));
        for (int i = 0; i < InterruptionGuard.TASK_POPUP_CAP; i++) {
            spread.accept(clean);
            InterruptionGuard.Decision d = spread.assess(popup);
            eq("全任务第 " + (i + 1) + " 个仍关", d.action, InterruptionGuard.Action.TAP_CLOSE);
            spread.commit(d);
        }
        InterruptionGuard.Decision fifth = spread.assess(popup);
        eq("满 5 次就停", fifth.action, InterruptionGuard.Action.STOP);
        yes("说明这一趟 5 次", fifth.speak.contains("5"));
    }

    static void jumps() {
        InterruptionGuard g = new InterruptionGuard();
        ScreenSnapshot search = page("com.taobao.taobao", "搜索",
                node("杯子", 40, 800, 400, 900, true));
        g.accept(search);
        ScreenSnapshot live = page("com.taobao.taobao", "直播间",
                node("进入直播", 40, 800, 400, 900, true));
        InterruptionGuard.Decision back = g.assess(live);
        eq("应用内跳转只返回一次", back.action, InterruptionGuard.Action.BACK);
        yes("返回不点屏幕", back.target == null && !back.foreign);
        g.commit(back);
        eq("返回没回来就按进入路线重开", g.assess(live).action, InterruptionGuard.Action.REENTER);
        g.commit(g.assess(live));
        eq("重开仍在活动页就停", g.assess(live).action, InterruptionGuard.Action.STOP);
        yes("活动页不提供入口按钮", InterruptionGuard.entryNode(live, "从搜索进入") == null);

        ScreenSnapshot shop = page("com.taobao.taobao", "搜索",
                node("搜索", 40, 80, 300, 160, true),
                node("我的", 40, 2000, 200, 2200, true));
        eq("干净页可以点搜索", InterruptionGuard.entryNode(shop, "从搜索进入").label(), "搜索");

        InterruptionGuard out = new InterruptionGuard();
        out.accept(search);
        ScreenSnapshot chrome = page("com.android.chrome", "网页",
                node("立即领取", 40, 800, 400, 900, true));
        InterruptionGuard.Decision leave = out.assess(chrome);
        eq("离开应用先返回", leave.action, InterruptionGuard.Action.BACK);
        yes("外面的页面不点", leave.target == null && leave.foreign);
        out.commit(leave);
        eq("第二次回主页", out.assess(chrome).action, InterruptionGuard.Action.HOME);
        out.commit(out.assess(chrome));
        InterruptionGuard.Decision giveUp = out.assess(chrome);
        eq("两次都没回来就停", giveUp.action, InterruptionGuard.Action.STOP);
        yes("停下时仍不点", giveUp.target == null);

        ScreenSnapshot goods = page("com.taobao.taobao", "商品",
                node("加入购物车", 40, 2000, 400, 2200, true));
        eq("普通翻页不是广告跳转", g.assess(goods).action, InterruptionGuard.Action.CLEAR);
    }

    static void batchAndRefs() {
        ScreenSnapshot planned = page("com.sankuai.meituan", "搜索",
                node("咖啡", 40, 800, 400, 900, true));
        long fp = planned.fingerprint();
        yes("同一屏编号还有效", !InterruptionGuard.refsStale(planned.packageName, fp, planned));
        ScreenSnapshot moved = page("com.sankuai.meituan", "直播间",
                node("进入直播", 40, 800, 400, 900, true));
        yes("指纹变了整批作废", InterruptionGuard.refsStale(planned.packageName, fp, moved));
        ScreenSnapshot other = page("com.android.chrome", "网页",
                node("打开", 40, 800, 400, 900, true));
        yes("包名变了整批作废", InterruptionGuard.refsStale(planned.packageName, fp, other));
        yes("读不到也作废", InterruptionGuard.refsStale(planned.packageName, fp, null));
        eq("关掉弹窗的记录", InterruptionGuard.popupNote(4), "第 4 步关掉红包弹窗");
    }

    static void memoryAndEntry() throws Exception {
        Path dir = Files.createTempDirectory("yuanhou-interrupt");
        Playbook book = Playbook.open(dir.toFile());
        book.memory().learnInterruption("淘宝", true);
        String remembered = book.memory().forIntent("在淘宝搜杯子", "com.taobao.taobao");
        yes("首页弹窗写进经验", remembered.contains("从首页进弹窗多"));
        yes("下次走搜索", remembered.contains("搜索"));
        book.memory().save(dir.toFile());
        String again = Playbook.open(dir.toFile()).memory().forIntent("打开淘宝", "");
        yes("重启后还记得弹窗", again.contains("从首页进弹窗多"));
        String json = Files.readString(dir.resolve("memory.json"), StandardCharsets.UTF_8);
        yes("经验里没有密钥", !json.contains("sk-") && !json.contains("密码"));

        StepHistory hist = new StepHistory();
        hist.action(1, "open_app", "淘宝", "淘宝", "做成了", "淘宝", "1. 打开 淘宝\n");
        hist.action(2, "tap", "搜索", "淘宝", "做成了", "淘宝", "2. 点「搜索」\n");
        hist.markGoalVerified();
        book.record("在淘宝搜杯子", hist, true, null);
        String skill = Playbook.open(dir.toFile()).relevant("在淘宝搜杯子", "");
        yes("技能写了从搜索进入", skill.contains("从搜索进入"));
        yes("技能不要从首页信息流进", skill.contains("不从首页信息流进"));
    }

    static void sources() throws Exception {
        String ts = Files.readString(Path.of("../app/src/main/java/com/yuanhou/hand/session/TaskSession.java"), StandardCharsets.UTF_8);
        int settleCall = ts.indexOf("snap = settleScreen");
        int ask = ts.indexOf("mc.nextAction");
        yes("问模型前先处理打断", settleCall >= 0 && settleCall < ask);
        int method = ts.indexOf("private ScreenSnapshot settleScreen");
        int apply = ts.indexOf("private boolean applyInterrupt");
        String body = ts.substring(method, apply);
        yes("安全闸在打断判断之前", body.indexOf("SafetyGuard.check") >= 0 && body.indexOf("SafetyGuard.check") < body.indexOf(".assess"));
        yes("处理打断不过确认闸", !body.contains("passGate") && !ts.substring(apply, ts.indexOf("private boolean runBatch")).contains("passGate"));
        String applyBody = ts.substring(apply, ts.indexOf("private boolean runBatch"));
        yes("关闭走 HID 点击", applyBody.contains("tapXY"));
        yes("不用截图找关闭按钮", !applyBody.contains("ScreenCapture"));
        yes("一批里包名或指纹变了就停", ts.contains("InterruptionGuard.refsStale"));
        yes("关掉弹窗会写进步骤", ts.contains("popupNote"));

        String prompt = Files.readString(Path.of("../app/src/main/java/com/yuanhou/hand/model/ModelClient.java"), StandardCharsets.UTF_8);
        yes("提示词不让模型点红包", prompt.contains("开红包") && prompt.contains("不要从首页信息流进"));

        String readme = Files.readString(Path.of("../README.md"), StandardCharsets.UTF_8);
        yes("说明是 0.1.21", readme.contains("0.1.21"));
        yes("说明写了打断", readme.contains("关掉红包弹窗") || readme.contains("跳过"));
        yes("说明里的摇一摇由人在手机上关", readme.contains("摇一摇"));

        String gradle = Files.readString(Path.of("../app/build.gradle"), StandardCharsets.UTF_8);
        yes("versionName 0.1.21", gradle.contains("versionName '0.1.21'"));
        yes("versionCode 22", gradle.contains("versionCode 22"));
    }
}
