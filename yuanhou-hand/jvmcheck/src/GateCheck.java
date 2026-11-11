import com.yuanhou.hand.agent.IntentGate;
import com.yuanhou.hand.gate.ConfirmationGate;
import com.yuanhou.hand.gate.ConfirmationGate.Declaration;
import com.yuanhou.hand.gate.IrrevAction;
import com.yuanhou.hand.gate.IrreversibleDetector;
import com.yuanhou.hand.model.ModelKeys;
import com.yuanhou.hand.screen.ScreenSnapshot;

import java.util.HashMap;
import java.util.Map;

/** 加严确认闸 + 意图闸 + 模型 Key 解析的桌面自检（不连手机、不调模型；Key 只用明显的假值）。 */
public class GateCheck {
    static int pass, fail;

    static void eq(String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what + ": got " + got + " want " + want); }
    }

    static ScreenSnapshot.Node node(String text, int l, int t, int r, int b, boolean editable) {
        ScreenSnapshot.Node n = new ScreenSnapshot.Node();
        n.cls = editable ? "android.widget.EditText" : "android.widget.TextView";
        n.text = text; n.desc = ""; n.hint = "";
        n.bounds.set(l, t, r, b);
        n.editable = editable;
        n.clickable = true;
        return n;
    }

    static ScreenSnapshot chat(String title, String typed) {
        ScreenSnapshot s = new ScreenSnapshot();
        s.packageName = "com.tencent.mm";
        s.screenH = 2400;
        s.nodes.add(node(title, 200, 100, 800, 200, false));
        s.nodes.add(node("昨天的消息", 100, 800, 900, 900, false));
        s.nodes.add(node(typed, 50, 2200, 850, 2300, true));
        s.nodes.add(node("发送", 900, 2200, 1050, 2300, false));
        return s;
    }

    static ScreenSnapshot order(String total) {
        ScreenSnapshot s = new ScreenSnapshot();
        s.packageName = "com.taobao.taobao"; s.screenH = 2400;
        s.nodes.add(node("某某旗舰店", 50, 400, 600, 480, false));
        s.nodes.add(node("数据线 ×1", 50, 500, 600, 580, false));
        s.nodes.add(node("优惠¥5", 50, 1800, 400, 1880, false));
        s.nodes.add(node("合计：¥" + total, 400, 2200, 800, 2300, false));
        s.nodes.add(node("提交订单", 820, 2200, 1060, 2300, false));
        return s;
    }

    public static void main(String[] args) {
        // ---- 检测 ----
        eq("提交 → SUBMIT", IrreversibleDetector.classifyLabel("提交"), IrrevAction.SUBMIT);
        eq("发送 → SEND", IrreversibleDetector.classifyLabel("发送"), IrrevAction.SEND);
        eq("删除 → DELETE", IrreversibleDetector.classifyLabel("删除"), IrrevAction.DELETE);
        eq("提交订单 → ORDER", IrreversibleDetector.classifyLabel("提交订单"), IrrevAction.ORDER);
        eq("确认支付 → PAY", IrreversibleDetector.classifyLabel("确认支付"), IrrevAction.PAY);
        eq("搜索 不拦", IrreversibleDetector.classifyLabel("搜索"), null);
        ScreenSnapshot o = order("29.90");
        eq("应付金额", IrreversibleDetector.payableAmount(o), "29.90");
        ScreenSnapshot conf = order("29.90");
        conf.nodes.add(node("确定", 820, 1000, 1060, 1100, false));
        eq("有应付金额时「确定」→ OTHER", IrreversibleDetector.beforeTap(conf, null, 900, 1050).action, IrrevAction.OTHER);
        eq("没有金额时「确定」不拦", IrreversibleDetector.beforeTap(chat("张三", ""), node("确定", 0, 0, 10, 10, false), 5, 5), null);
        eq("聊天框回车 → SEND", IrreversibleDetector.beforeEnter(chat("张三", "你好")).action, IrrevAction.SEND);
        eq("更严", IrrevAction.stricter(IrrevAction.SEND, IrrevAction.PAY), IrrevAction.PAY);
        eq("模型 gate 旧名", IrrevAction.fromModel("send_message"), IrrevAction.SEND);

        // ---- 口令：只有完整口令算同意 ----
        eq("发送", ConfirmationGate.classify(IrrevAction.SEND, "发送。"), ConfirmationGate.Reply.CONFIRM);
        eq("好的 含糊", ConfirmationGate.classify(IrrevAction.SEND, "好的"), ConfirmationGate.Reply.AMBIGUOUS);
        eq("可以 含糊", ConfirmationGate.classify(IrrevAction.DELETE, "可以"), ConfirmationGate.Reply.AMBIGUOUS);
        eq("删除≠确认删除", ConfirmationGate.classify(IrrevAction.DELETE, "删除"), ConfirmationGate.Reply.AMBIGUOUS);
        eq("确认删除", ConfirmationGate.classify(IrrevAction.DELETE, "确认删除"), ConfirmationGate.Reply.CONFIRM);
        eq("确认提交吧", ConfirmationGate.classify(IrrevAction.SUBMIT, "确认提交吧"), ConfirmationGate.Reply.CONFIRM);
        eq("不要", ConfirmationGate.classify(IrrevAction.ORDER, "不要下单"), ConfirmationGate.Reply.CANCEL);
        eq("付款没有口令", ConfirmationGate.classify(IrrevAction.PAY, "确认付款"), ConfirmationGate.Reply.AMBIGUOUS);

        // ---- 复述必须带内容和金额 ----
        Declaration sd = new Declaration(IrrevAction.SEND, "com.tencent.mm", "张三", "晚上七点到", "");
        String rb = ConfirmationGate.readback(sd);
        eq("复述含对象", rb.contains("张三"), true);
        eq("复述含内容", rb.contains("晚上七点到"), true);
        eq("复述含金额", rb.contains("金额"), true);
        eq("复述含口令", rb.contains("「发送」"), true);
        Declaration od = new Declaration(IrrevAction.ORDER, "com.taobao.taobao", "某某旗舰店", "数据线 ×1", "29.90");
        eq("下单复述金额", ConfirmationGate.readback(od).contains("29.90"), true);

        // ---- 演练下单：从语句拆带币种的金额 ----
        eq("18 元", ConfirmationGate.extractAmount("18 元"), "18元");
        eq("18元", ConfirmationGate.extractAmount("18元"), "18元");
        eq("¥18", ConfirmationGate.extractAmount("¥18"), "¥18");
        eq("￥29.90", ConfirmationGate.extractAmount("￥29.90"), "￥29.90");
        eq("一份麻辣烫不误判", ConfirmationGate.extractAmount("一份麻辣烫"), "");
        eq("光秃数字不当金额", ConfirmationGate.extractAmount("共 18 份"), "");
        String orderPhrase = "一份麻辣烫 18 元";
        String amt = ConfirmationGate.extractAmount(orderPhrase);
        String rest = ConfirmationGate.withoutAmount(orderPhrase);
        eq("拆出金额 18元", amt, "18元");
        eq("剩下是商品", rest, "一份麻辣烫");
        Declaration drill = new Declaration(IrrevAction.ORDER, "", rest, rest, amt);
        eq("拆完复述含 18", ConfirmationGate.readback(drill).contains("18"), true);
        eq("付款复述交本人", ConfirmationGate.readback(new Declaration(IrrevAction.PAY, "x", "", "", "10")).contains("不会代付"), true);

        // ---- 申报 ----
        ConfirmationGate g = new ConfirmationGate();
        eq("前台不一致", g.declare(sd, "com.taobao.taobao", chat("张三", "晚上七点到")), ConfirmationGate.Declare.MISMATCH);
        eq("付款 UNSUPPORTED", g.declare(new Declaration(IrrevAction.PAY, "com.tencent.mm", "a", "", "1"), "com.tencent.mm", null), ConfirmationGate.Declare.UNSUPPORTED);
        eq("发送缺内容", g.declare(new Declaration(IrrevAction.SEND, "com.tencent.mm", "张三", "", ""), "com.tencent.mm", chat("张三", "")), ConfirmationGate.Declare.INCOMPLETE);
        eq("下单缺金额", g.declare(new Declaration(IrrevAction.ORDER, "com.taobao.taobao", "某某旗舰店", "数据线", ""), "com.taobao.taobao", o), ConfirmationGate.Declare.INCOMPLETE);
        eq("提交时屏上有金额必须报金额", g.declare(new Declaration(IrrevAction.SUBMIT, "com.taobao.taobao", "某某旗舰店", "", ""), "com.taobao.taobao", o), ConfirmationGate.Declare.INCOMPLETE);
        eq("删除缺对象", g.declare(new Declaration(IrrevAction.DELETE, "com.tencent.mm", "", "", ""), "com.tencent.mm", chat("张三", "")), ConfirmationGate.Declare.INCOMPLETE);

        // ---- 放行：只放行紧接着、内容对得上的那一个 ----
        eq("申报", g.declare(sd, "com.tencent.mm", chat("张三", "晚上七点到")), ConfirmationGate.Declare.PENDING);
        g.decide(true, 3);
        eq("放行", g.permits(IrrevAction.SEND, chat("张三", "晚上七点到"), 3, null).allowed, true);
        eq("同意只用一次", g.permits(IrrevAction.SEND, chat("张三", "晚上七点到"), 3, null).allowed, false);
        g.declare(sd, "com.tencent.mm", chat("张三", "晚上七点到")); g.decide(true, 3);
        eq("正文不一致", g.permits(IrrevAction.SEND, chat("张三", "晚上八点到"), 3, null).allowed, false);
        g.declare(sd, "com.tencent.mm", chat("张三", "晚上七点到")); g.decide(true, 3);
        eq("对象不在标题栏", g.permits(IrrevAction.SEND, chat("李四", "晚上七点到"), 3, null).allowed, false);
        g.declare(sd, "com.tencent.mm", chat("张三", "晚上七点到")); g.decide(true, 3);
        eq("换了一类操作", g.permits(IrrevAction.DELETE, chat("张三", "晚上七点到"), 3, null).allowed, false);
        g.declare(sd, "com.tencent.mm", chat("张三", "晚上七点到")); g.decide(true, 3);
        eq("过期", g.permits(IrrevAction.SEND, chat("张三", "晚上七点到"), 3 + ConfirmationGate.MAX_STEPS + 1, null).allowed, false);
        g.declare(sd, "com.tencent.mm", chat("张三", "晚上七点到")); g.decide(true, 3);
        g.noteWrite();
        eq("中间做了别的写操作（含打字）作废", g.hasGrant(), false);
        g.declare(sd, "com.tencent.mm", chat("张三", "晚上七点到")); g.decide(false, 3);
        eq("拒绝过不再问", g.declare(sd, "com.tencent.mm", chat("张三", "晚上七点到")), ConfirmationGate.Declare.DECLINED);
        eq("MAX_STEPS", ConfirmationGate.MAX_STEPS, 3);

        ConfirmationGate og = new ConfirmationGate();
        og.declare(od, "com.taobao.taobao", o); og.decide(true, 5);
        eq("金额在屏上放行", og.permits(IrrevAction.ORDER, o, 5, null).allowed, true);
        og.declare(od, "com.taobao.taobao", o); og.decide(true, 5);
        eq("金额变了不放行", og.permits(IrrevAction.ORDER, order("39.90"), 5, null).allowed, false);
        Declaration od5 = new Declaration(IrrevAction.ORDER, "com.taobao.taobao", "某某旗舰店", "数据线", "5");
        og.declare(od5, "com.taobao.taobao", o); og.decide(true, 5);
        eq("优惠金额不算", og.permits(IrrevAction.ORDER, o, 5, null).allowed, false);

        ScreenSnapshot del = chat("张三", "");
        del.nodes.add(node("晚上七点到", 100, 1000, 900, 1100, false));
        Declaration dd = new Declaration(IrrevAction.DELETE, "com.tencent.mm", "张三", "晚上七点到", "");
        ConfirmationGate dg = new ConfirmationGate();
        eq("删除申报", dg.declare(dd, "com.tencent.mm", del), ConfirmationGate.Declare.PENDING);
        dg.decide(true, 2);
        eq("删除核对内容在屏上", dg.permits(IrrevAction.DELETE, del, 2, null).allowed, true);
        dg.declare(dd, "com.tencent.mm", del); dg.decide(true, 2);
        eq("删除内容不在屏上", dg.permits(IrrevAction.DELETE, chat("张三", ""), 2, null).allowed, false);

        // ---- 意图闸 ----
        eq("parse", IntentGate.parse("`ACT`"), IntentGate.Intent.ACT);
        eq("parse 乱", IntentGate.parse("maybe"), null);
        eq("模型判", new IntentGate((s, u) -> "READ").assess("随便"), IntentGate.Intent.READ);
        eq("模型失败兜底 ACT", new IntentGate((s, u) -> null).assess("给张三发微信说晚上七点到"), IntentGate.Intent.ACT);
        eq("兜底 READ", IntentGate.local("看看微信有没有未读"), IntentGate.Intent.READ);
        eq("兜底 DISCUSS", IntentGate.local("你觉得这个方案怎么样"), IntentGate.Intent.DISCUSS);
        eq("有一点 不算动手", IntentGate.local("我有一点累"), IntentGate.Intent.DISCUSS);

        // ---- 模型 Key：设置优先，其次运行时环境；没有就给「请在设置里填写」 ----
        Map<String, String> env = new HashMap<>();
        eq("没 Key 提示", ModelKeys.missing("openai", ModelKeys.resolveKey("openai", "", env), "m", true).startsWith("请在设置里填写"), true);
        eq("没选服务商", ModelKeys.missing("", "", "", false).startsWith("请在设置里选择"), true);
        env.put("OPENAI_API_KEY", "test-not-a-real-key");
        eq("环境变量", ModelKeys.resolveKey("openai", "", env), "test-not-a-real-key");
        eq("设置优先", ModelKeys.resolveKey("openai", "settings-fake", env), "settings-fake");
        eq("deepseek 不拿 openai 的", ModelKeys.resolveKey("deepseek", "", env), "");
        eq("官方地址", ModelKeys.resolveBaseUrl("deepseek", ""), ModelKeys.DEEPSEEK_URL);
        eq("没勾同意", ModelKeys.missing("deepseek", "x", "m", false).startsWith("请在设置里勾选"), true);
        eq("齐了", ModelKeys.missing("deepseek", "x", "m", true), null);

        System.out.println("GateCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
