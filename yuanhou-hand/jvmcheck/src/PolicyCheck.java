import com.yuanhou.hand.policy.ConfirmationPolicy;
import com.yuanhou.hand.policy.ConfirmationPolicy.Gate;
import com.yuanhou.hand.policy.OperationGuard;
import com.yuanhou.hand.policy.SafetyGuard;
import com.yuanhou.hand.screen.ScreenSnapshot;


/** 桌面 JVM 上的策略自检（不连手机、不动 HID）。用法见 jvmcheck/run.sh。 */
public class PolicyCheck {
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

    public static void main(String[] args) {
        // 按钮词表（Mika IrreversibleDetector 子集）
        eq("发送", ConfirmationPolicy.classifyLabel("发送"), Gate.SEND_MESSAGE);
        eq("发送(1)", ConfirmationPolicy.classifyLabel("发送(1)"), Gate.SEND_MESSAGE);
        eq("评论(23) 是入口", ConfirmationPolicy.classifyLabel("评论(23)"), null);
        eq("立即购买", ConfirmationPolicy.classifyLabel("立即购买"), Gate.PLACE_ORDER);
        eq("提交订单", ConfirmationPolicy.classifyLabel("提交订单"), Gate.PLACE_ORDER);
        eq("确认支付", ConfirmationPolicy.classifyLabel("确认支付"), Gate.PAYMENT);
        eq("待付款 是状态", ConfirmationPolicy.classifyLabel("待付款"), null);
        eq("付款方式 是入口", ConfirmationPolicy.classifyLabel("付款方式"), null);
        eq("支付宝 是选项", ConfirmationPolicy.classifyLabel("支付宝"), null);
        eq("购买记录", ConfirmationPolicy.classifyLabel("购买记录"), null);
        eq("删除", ConfirmationPolicy.classifyLabel("删除"), Gate.DELETE);
        eq("撤回", ConfirmationPolicy.classifyLabel("撤回"), Gate.DELETE);
        eq("充值中心 是入口", ConfirmationPolicy.classifyLabel("充值中心"), null);
        eq("立即充值", ConfirmationPolicy.classifyLabel("立即充值"), Gate.PAYMENT);
        eq("Send message", ConfirmationPolicy.classifyLabel("Send message"), Gate.SEND_MESSAGE);
        eq("搜索 不拦", ConfirmationPolicy.classifyLabel("搜索"), null);
        eq("我想要", ConfirmationPolicy.classifyLabel("我想要"), Gate.PLACE_ORDER);

        // 口令
        eq("口令 发送", ConfirmationPolicy.classify(Gate.SEND_MESSAGE, "发送。"), ConfirmationPolicy.Reply.CONFIRM);
        eq("口令 好的 含糊", ConfirmationPolicy.classify(Gate.SEND_MESSAGE, "好的"), ConfirmationPolicy.Reply.AMBIGUOUS);
        eq("口令 不对", ConfirmationPolicy.classify(Gate.PLACE_ORDER, "不对"), ConfirmationPolicy.Reply.CANCEL);
        eq("口令 下单≠确认下单", ConfirmationPolicy.classify(Gate.PLACE_ORDER, "下单"), ConfirmationPolicy.Reply.AMBIGUOUS);

        // 一次性同意的核对已移到 GateCheck（gate/ConfirmationGate）
        ScreenSnapshot order = new ScreenSnapshot();
        order.packageName = "com.taobao.taobao"; order.screenH = 2400;
        order.nodes.add(node("某某旗舰店", 50, 400, 600, 480, false));
        order.nodes.add(node("优惠¥5", 50, 1800, 400, 1880, false));
        order.nodes.add(node("合计：¥29.90", 400, 2200, 800, 2300, false));
        order.nodes.add(node("提交订单", 820, 2200, 1060, 2300, false));
        eq("inferGate 提交订单", ConfirmationPolicy.inferGate(order, 900, 2250), Gate.PLACE_ORDER);

        // 安全闸
        ScreenSnapshot pw = chat("张三", "");
        pw.nodes.get(2).password = true;
        eq("密码框停", SafetyGuard.check(pw).kind, SafetyGuard.Kind.SENSITIVE_INPUT);
        ScreenSnapshot cap = chat("请完成验证", "");
        eq("验证停", SafetyGuard.check(cap).kind, SafetyGuard.Kind.CAPTCHA);
        ScreenSnapshot pay = chat("请输入支付密码", "");
        eq("付款页停", SafetyGuard.check(pay).kind, SafetyGuard.Kind.PAYMENT_PAGE);
        ScreenSnapshot an = chat("操作频繁，请稍后再试", "");
        eq("环境异常停", SafetyGuard.check(an).kind, SafetyGuard.Kind.ANOMALY);
        eq("普通聊天页", SafetyGuard.check(chat("张三", "你好")).kind, SafetyGuard.Kind.OK);
        eq("空白", SafetyGuard.isBlank(new ScreenSnapshot()), true);

        // 模型文本与 ref
        ScreenSnapshot c = chat("张三", "");
        String txt = c.toModelText(150);
        eq("ref 写入", c.byRef(4) != null && "发送".equals(c.byRef(4).label()), true);
        eq("标题栏标记", txt.contains("「张三」 @500,150 可点 标题栏"), true);

        // 操作守卫
        OperationGuard og2 = new OperationGuard();
        long fp = c.fingerprint();
        og2.onScreen(fp, false);
        eq("同签名首次不管", og2.beforeAction("tap|1,1"), null);
        OperationGuard.Verdict v = null;
        for (int i = 0; i < OperationGuard.NO_EFFECT_STOP; i++) v = og2.onScreen(fp, true);
        eq("无效果叫停", v != null && v.stop && "no_effect".equals(v.code), true);
        OperationGuard og3 = new OperationGuard();
        for (int i = 0; i < 6; i++) { eq("滚动有新内容不停 " + i, og3.beforeAction("scroll|10,20"), null); og3.onScreen((long) i, true); }
        OperationGuard og4 = new OperationGuard();
        OperationGuard.Verdict b = null;
        for (int i = 0; i < OperationGuard.BLANK_STOP; i++) b = og4.onScreen(null, true);
        eq("连续空白叫停", b != null && b.stop, true);

        System.out.println("PolicyCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
