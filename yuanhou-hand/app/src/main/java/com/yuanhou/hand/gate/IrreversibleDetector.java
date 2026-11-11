package com.yuanhou.hand.gate;

import com.yuanhou.hand.policy.ConfirmationPolicy;
import com.yuanhou.hand.policy.SafetyGuard;
import com.yuanhou.hand.screen.ScreenSnapshot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每次点击 / 回车 / 按键前判断这一步是不是不可逆操作（思路同 Mika core/gate/IrreversibleDetector.kt，Java 重写）。
 * 只认按钮名、资源 ID、焦点框类型、屏上应付金额，不做语义理解；拿不准宁可多拦。
 * 按钮词表复用 ConfirmationPolicy.classifyLabel（已对照 Mika 规则），这里再细分出 SUBMIT、补上「带应付金额的确认」(OTHER)。
 */
public final class IrreversibleDetector {
    private IrreversibleDetector() {}

    public static final class Detection {
        public final IrrevAction action;
        public final String why;
        Detection(IrrevAction a, String why) { action = a; this.why = why; }
    }

    private static final String[] SUBMIT_WORDS = {"提交", "确认提交", "送出", "submit", "提交申请", "提交评价", "立即提交"};
    /** 屏上有应付金额时，这些泛泛的确认按钮也按 OTHER 拦（Mika：submit/other 在有应付金额时绑定金额）。 */
    private static final String[] GENERIC_CONFIRM = {"确定", "确认", "完成", "同意", "好的", "继续", "ok", "确认并继续"};
    private static final String[] PAYABLE_WORDS = {"合计", "实付", "应付", "需付", "总计", "待支付", "实付款", "共计", "总价"};
    private static final Pattern CURRENCY = Pattern.compile("[¥￥$＄]\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*元");

    public static IrrevAction classifyLabel(String raw) {
        if (raw == null) return null;
        String c = raw.replaceAll("\\s", "");
        for (String w : SUBMIT_WORDS) if (c.equalsIgnoreCase(w)) return IrrevAction.SUBMIT;
        ConfirmationPolicy.Gate g = ConfirmationPolicy.classifyLabel(raw);
        return fromGate(g);
    }

    static IrrevAction fromGate(ConfirmationPolicy.Gate g) {
        if (g == null) return null;
        switch (g) {
            case SEND_MESSAGE: return IrrevAction.SEND;
            case PLACE_ORDER: return IrrevAction.ORDER;
            case PAYMENT: return IrrevAction.PAY;
            case DELETE: return IrrevAction.DELETE;
            default: return null;
        }
    }

    /** 点击 (x,y)（或 ref 指的 target）前：按钮名 → 资源 ID → 付款页 → 带应付金额的确认。 */
    public static Detection beforeTap(ScreenSnapshot s, ScreenSnapshot.Node target, int x, int y) {
        if (s == null) return null;
        ScreenSnapshot.Node n = target != null ? target : s.nodeAt(x, y);
        IrrevAction a = n == null ? null : classifyLabel(n.label());
        String why = a == null ? null : "按钮「" + n.label() + "」";
        IrrevAction byId = fromGate(ConfirmationPolicy.inferGate(s, x, y));
        if (byId != null && IrrevAction.stricter(a, byId) == byId && byId != a) {
            a = byId;
            why = "按钮/资源 ID 判为" + byId.label;
        }
        SafetyGuard.Verdict v = SafetyGuard.check(s);
        if (v.kind == SafetyGuard.Kind.PAYMENT_PAGE) {
            a = IrrevAction.PAY;
            why = v.reason;
        }
        if (a == null && n != null && payableAmount(s) != null) {
            String c = n.label().replaceAll("\\s", "").toLowerCase();
            for (String w : GENERIC_CONFIRM) {
                if (c.equals(w)) { a = IrrevAction.OTHER; why = "屏上有应付金额时点「" + n.label() + "」"; break; }
            }
        }
        return a == null ? null : new Detection(a, why);
    }

    /** 回车前：只有明确的搜索框不算；其余输入框（含读不到焦点框）一律按发送拦（Mika 同）。 */
    public static Detection beforeEnter(ScreenSnapshot s) {
        ScreenSnapshot.Node f = s == null ? null : s.focusedField();
        if (ScreenSnapshot.isSearchField(f)) return null;
        return new Detection(IrrevAction.SEND, f == null ? "读不到焦点输入框时的回车" : "非搜索框里的回车");
    }

    /** 屏上（非输入框）紧挨「合计/实付/应付…」的金额；没有返回 null。 */
    public static String payableAmount(ScreenSnapshot s) {
        if (s == null) return null;
        for (ScreenSnapshot.Node n : s.nodes) {
            if (n.editable) continue;
            String t = n.label();
            boolean payable = false;
            for (String w : PAYABLE_WORDS) if (t.contains(w)) { payable = true; break; }
            if (!payable) continue;
            Matcher m = CURRENCY.matcher(t.replaceAll("(?<=\\d),(?=\\d{3})", ""));
            if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }
}
