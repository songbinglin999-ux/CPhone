import com.yuanhou.hand.memory.DoneGate;
import com.yuanhou.hand.memory.GoalCheck;
import com.yuanhou.hand.memory.Playbook;
import com.yuanhou.hand.memory.Sensitive;
import com.yuanhou.hand.memory.StepHistory;
import com.yuanhou.hand.model.PromptText;
import com.yuanhou.hand.policy.UntilCheck;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 0.1.20：结构化摘要、跨任务记事、技能提纲、done 前的目标核对。 */
public class LongHorizonCheck {
    static int pass, fail;

    static void eq(String name, Object a, Object b) {
        if (String.valueOf(a).equals(String.valueOf(b))) pass++;
        else { fail++; System.out.println("FAIL " + name + ": " + a + " != " + b); }
    }

    static void yes(String name, boolean b) { eq(name, b, true); }

    static void no(String name, boolean b) { eq(name, b, false); }

    public static void main(String[] args) throws Exception {
        summaries();
        memoryAndSkills();
        goalCheck();
        sources();
        System.out.println("LongHorizonCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void summaries() {
        StepHistory shortHist = new StepHistory();
        shortHist.action(1, "open_app", "美团", "美团", "做成了", "美团", "1. 打开 美团\n");
        shortHist.note(2, "提醒：上一步没效果");
        String shortText = shortHist.forModel();
        yes("短历史保留原文", shortText.contains("1. 打开 美团") && shortText.contains("提醒：上一步没效果"));
        no("短历史不收摘要", shortText.contains("结构化摘要"));
        no("短历史不写已省略", shortText.contains("已省略"));

        StepHistory longHist = new StepHistory();
        String pad = "已试过这个入口。".repeat(60);
        for (int i = 1; i <= 20; i++) {
            String coord = i == 1 ? "88,99" : (i == 20 ? "12,13" : "");
            String verbatim = i + ". 点击 " + (coord.isEmpty() ? "按钮" + i : coord) + " " + pad + "\n";
            longHist.action(i, "tap", coord.isEmpty() ? "按钮" + i : coord, "美团", "做成了", "美团 · 首页", verbatim);
        }
        String rendered = longHist.forModel();
        yes("超长后有结构化摘要", rendered.contains("结构化摘要"));
        no("不再写已省略", rendered.contains("已省略"));
        yes("第 1 步还在", rendered.contains("第1步"));
        yes("第 1 步目标还在", rendered.contains("按钮") || rendered.contains("第1步 tap"));
        for (int i = 1; i <= 20; i++) {
            boolean kept = rendered.contains("第" + i + "步") || rendered.contains("\n" + i + ".") || rendered.startsWith(i + ".");
            yes("第 " + i + " 步没有丢掉", kept);
        }
        no("较早步骤的坐标不进摘要", rendered.contains("88,99"));
        yes("最近步骤原文还在", rendered.contains("最近步骤") && rendered.contains("12,13"));
        int pads = count(rendered, pad);
        yes("原文垫字只留在最近几步", pads > 0 && pads <= StepHistory.RECENT_VERBATIM_STEPS);

        String speech = longHist.progressSpeech(15);
        yes("进度带步数", speech.contains("15"));
        yes("进度用摘要", speech.contains("摘要"));
        yes("进度能看见做过什么", speech.contains("点") || speech.contains("按钮"));
    }

    static void memoryAndSkills() throws Exception {
        eq("外卖包名不收成美团", UntilCheck.displayName("com.sankuai.meituan.takeoutnew"), "美团外卖");
        eq("美团外卖不再拆出美团", UntilCheck.mentionedApps("打开美团外卖").size(), 1);
        yes("口令这句话不是密钥", !Sensitive.blocked("确认时要等我说口令"));
        yes("sk- 是密钥", Sensitive.blocked("sk-abcdefghijklmnopqrstuvwxyz"));
        yes("密码两个字要拦住", Sensitive.blocked("密码是123"));

        Path dir = Files.createTempDirectory("yuanhou-playbook");
        Playbook first = Playbook.open(dir.toFile());
        StepHistory failed = new StepHistory();
        failed.action(1, "open_app", "淘宝", "淘宝", "做成了", "淘宝", "1. 打开 淘宝\n");
        failed.action(2, "tap", "搜索", "淘宝", "没做成：屏幕上没找到目标", "淘宝", "2. 点搜索没做成\n");
        first.record("在淘宝搜杯子", failed, false, "屏幕上没找到目标");
        yes("失败不记成技能", first.skills().size() == 0);

        StepHistory done = new StepHistory();
        done.action(1, "open_app", "美团", "美团", "做成了", "美团", "1. 打开 美团\n");
        done.action(2, "tap", "搜索", "美团", "做成了", "美团 · 首页", "2. 点「搜索」\n");
        done.action(3, "type", "瑞幸", "美团", "做成了", "美团 · 搜索", "3. 贴入文字\n");
        done.action(4, "tap", "120,340", "美团", "做成了", "美团", "4. 点击 120,340\n");
        done.markGoalVerified();
        String goal = "我常住杭州，习惯用美团，确认时要等我说口令。在美团点一杯瑞幸";
        first.record(goal, done, true, null);
        first.record("我的密码是sk-abcdefghijklmnopqrstuvwxyz", null, false, null);

        String meituanNow = first.relevant("再在美团点一杯瑞幸", "com.sankuai.meituan");
        yes("同一进程能看见美团经验", meituanNow.contains("做成过") && meituanNow.contains("美团"));
        yes("同一进程能看见习惯应用", meituanNow.contains("习惯用的应用") && meituanNow.contains("美团"));
        yes("相似任务载入技能提纲", meituanNow.contains("可参考的技能") && meituanNow.contains("瑞幸"));
        no("技能提纲不写坐标", meituanNow.contains("120,340") || meituanNow.contains("120"));
        yes("提纲让模型按屏幕改", meituanNow.contains("不要按坐标回放"));

        String order = first.relevant("在美团下单", "");
        yes("下单这句话带上确认习惯", order.contains("确认习惯"));

        String coffee = first.relevant("在美团搜咖啡", "");
        yes("搜咖啡仍能看见美团经验", coffee.contains("美团") && coffee.contains("做成过"));
        no("搜咖啡不带上杭州", coffee.contains("杭州"));
        no("美团任务不带淘宝经验", coffee.contains("淘宝"));

        String delivery = first.relevant("看看附近的外卖", "");
        yes("外卖带上常去的城市", delivery.contains("杭州"));
        yes("外卖带上习惯应用的经验", delivery.contains("美团"));

        String wechat = first.relevant("给张三发微信说明天开会", "com.tencent.mm");
        no("不相似的微信任务不载入瑞幸技能", wechat.contains("瑞幸") || wechat.contains("可参考的技能"));
        no("微信任务不带淘宝", wechat.contains("淘宝"));

        Playbook second = Playbook.open(dir.toFile());
        String fromDiskCity = second.relevant("看看附近的外卖", "");
        yes("重启后仍能读到偏好", fromDiskCity.contains("常去的城市") && fromDiskCity.contains("杭州"));
        String fromDisk = second.relevant("再在美团点一杯瑞幸", "");
        yes("重启后仍能读到技能", fromDisk.contains("可参考的技能") && fromDisk.contains("打开美团"));
        eq("重复的办成路线只留一条技能", second.skills().size(), 1);

        StepHistory again = new StepHistory();
        again.action(1, "open_app", "美团", "美团", "做成了", "美团", "1. 打开 美团\n");
        again.action(2, "tap", "奶茶", "美团", "做成了", "美团", "2. 点「奶茶」\n");
        again.markGoalVerified();
        second.record("我常住杭州，习惯用美团，确认时要等我说口令。在美团点一杯瑞幸", again, true, null);
        eq("几乎同一目标刷新提纲而不是再加一条", Playbook.open(dir.toFile()).skills().size(), 1);
        yes("刷新后的提纲是新路线", Playbook.open(dir.toFile()).relevant("再在美团点一杯瑞幸", "").contains("奶茶"));

        String memoryJson = Files.readString(dir.resolve("memory.json"), StandardCharsets.UTF_8);
        String skillJson = Files.readString(dir.resolve("skills.json"), StandardCharsets.UTF_8);
        no("记事文件没有 sk-", memoryJson.contains("sk-") || skillJson.contains("sk-"));
        no("记事文件没有密码二字", memoryJson.contains("密码") || skillJson.contains("密码"));
        no("技能文件没有坐标", skillJson.contains("120,340"));
        yes("用户消息把本机记得和步骤分开", PromptText.actionUser("点咖啡", "第1步 tap", "树", "本机记得：杭州").contains("本机记得：杭州"));
        yes("步骤原文原样放进用户消息", PromptText.actionUser("点咖啡", "第1步 tap 「搜索」", null, "").contains("第1步 tap 「搜索」"));
    }

    static void goalCheck() {
        GoalCheck.Verdict premature = GoalCheck.parse("{\"action\":\"done\",\"met\":true,\"say\":\"好了\"}");
        no("done 不能当成核对通过", premature.met);
        yes("done 被认成跳过", premature.say.contains("goal_check"));

        GoalCheck.Verdict stop = GoalCheck.parse("{\"action\":\"stop\",\"say\":\"先停\"}");
        no("stop 不是核对通过", stop.met);

        GoalCheck.Verdict missing = GoalCheck.parse("{\"action\":\"goal_check\",\"say\":\"还在首页\"}");
        no("没写 met 不算达成", missing.met);

        GoalCheck.Verdict noMet = GoalCheck.parse("{\"action\":\"goal_check\",\"met\":false,\"say\":\"还停在美团首页\"}");
        no("核对说没达成", noMet.met);
        yes("没达成的原因留着", noMet.say.contains("美团首页"));

        GoalCheck.Verdict yesMet = GoalCheck.parse("看完了 {\"action\":\"goal_check\",\"met\":true,\"say\":\"订单页已经显示下单成功\"}");
        yes("核对说达成", yesMet.met);
        yes("达成原因留着", yesMet.say.contains("下单成功"));

        GoalCheck.Verdict empty = GoalCheck.parse(null);
        no("没有核对结论不算达成", empty.met);

        eq("done 且核对未过继续", DoneGate.decide("done", false), DoneGate.Decision.CONTINUE);
        eq("done 且核对通过才接受", DoneGate.decide("done", true), DoneGate.Decision.ACCEPT);
        eq("stop 立刻停，不管核对结果", DoneGate.decide("stop", true), DoneGate.Decision.STOP);
        eq("自己写的 goal_check 不能结束", DoneGate.decide("goal_check", true), DoneGate.Decision.CONTINUE);

        no("提前 done 配上假核对不能结束", wouldFinish("done", "{\"action\":\"done\",\"met\":true}"));
        no("核对否定时不能结束", wouldFinish("done", "{\"action\":\"goal_check\",\"met\":false,\"say\":\"还没到\"}"));
        yes("核对肯定时可以结束", wouldFinish("done", "{\"action\":\"goal_check\",\"met\":true,\"say\":\"已经办成\"}"));
        yes("stop 不经过达成", DoneGate.decide("stop", false) == DoneGate.Decision.STOP);

        String prompt = GoalCheck.userPrompt("在美团点一杯瑞幸", "第1步 打开美团", "前台 美团");
        yes("核对能看见原话", prompt.contains("在美团点一杯瑞幸"));
        yes("核对能看见步骤", prompt.contains("打开美团"));
        yes("核对能看见当前屏幕", prompt.contains("前台 美团"));
    }

    static boolean wouldFinish(String action, String checkResponse) {
        if (!"done".equals(action)) return false;
        return DoneGate.decide(action, GoalCheck.parse(checkResponse).met) == DoneGate.Decision.ACCEPT;
    }

    static void sources() throws Exception {
        String gradle = Files.readString(Path.of("../app/build.gradle"), StandardCharsets.UTF_8);
        yes("versionName 0.1.21", gradle.contains("versionName '0.1.21'"));
        yes("versionCode 22", gradle.contains("versionCode 22"));

        String ts = Files.readString(Path.of("../app/src/main/java/com/yuanhou/hand/session/TaskSession.java"), StandardCharsets.UTF_8);
        no("不再硬截断", ts.contains("更早的步骤已省略") || ts.contains("pruneHist"));
        yes("done 走核对", ts.contains("acceptDone") && ts.contains("GoalCheck.parse") && ts.contains("markGoalVerified"));
        int stop = ts.indexOf("\"stop\".equals(a.type)");
        int stopEnd = ts.indexOf("End.MODEL_STOP", stop);
        String stopBlock = ts.substring(stop, stopEnd);
        yes("stop 用 DoneGate", stopBlock.contains("DoneGate.decide"));
        no("stop 不做目标核对", stopBlock.contains("askGoal") || stopBlock.contains("GoalCheck"));
        yes("付款仍交本人", ts.contains("付款交本人") && ts.contains("neverExecuted()"));
        yes("找不到目标也记成没做成", ts.contains("这一步没做成") && ts.contains("hardMiss"));

        String prompt = Files.readString(Path.of("../app/src/main/java/com/yuanhou/hand/model/ModelClient.java"), StandardCharsets.UTF_8);
        yes("系统提示写了摘要", prompt.contains("结构化") || prompt.contains("第N步"));
        yes("系统提示写了核对", prompt.contains("目标核对"));
        yes("系统提示仍不代付", prompt.contains("付款永远不做"));

        String readme = Files.readString(Path.of("../README.md"), StandardCharsets.UTF_8);
        yes("说明书写了 0.1.20", readme.contains("0.1.20"));
        yes("说明书写了摘要", readme.contains("结构化摘要"));
        yes("说明书写了记事", readme.contains("本机记得") || readme.contains("偏好"));
        yes("说明书写了技能", readme.contains("技能"));
        yes("说明书写了目标核对", readme.contains("目标核对"));
    }

    static int count(String s, String part) {
        int n = 0, from = 0;
        while (from <= s.length() - part.length()) {
            int i = s.indexOf(part, from);
            if (i < 0) break;
            n++;
            from = i + part.length();
        }
        return n;
    }
}
