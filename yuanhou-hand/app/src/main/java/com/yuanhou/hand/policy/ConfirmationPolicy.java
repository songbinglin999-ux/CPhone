package com.yuanhou.hand.policy;

import com.yuanhou.hand.screen.ScreenSnapshot;

import java.util.Map;

/**
 * 0.1.5 起：任务里的确认流程改由 gate/ConfirmationGate（加严：发送/删除/提交/下单都要复述内容和金额并等口令）执行，
 * 这里只剩按钮词表 classifyLabel / inferGate 被 gate/IrreversibleDetector 复用；Gate.readback / classify 是 0.1.4 的旧口令规则，
 * 只留给 jvmcheck 对照，不再被任务调用。
 *
 * 关键操作确认规则（设计 6.4）。
 * 发微信：复述对象和正文，口令「发送」；下单：复述店家/卖家、商品、数量、收货要点、应付金额，口令「确认下单」；
 * 付款：复述金额和收款方/支付方式，口令「确认付款」。「取消」「不对」立即取消；含糊视为取消。
 * 第一期付款由本人自己操作：即使听到「确认付款」也不代付。删除/撤回第一期不做，遇到就停。
 *
 * 按钮词表借鉴 Mika core/gate/IrreversibleDetector.kt（只认按钮名，不做语义理解；拿不准宁可多拦）：
 * 强词出现在短名字里就算，但「待/已」开头或「成功/记录/明细/方式…」结尾的是订单状态或入口，不算；
 * 发送类等于词或只多出几个字（「发送(1)」）；评论/回复只认等于或后跟标点；付款方式选项（支付宝、支付方式）先去掉再判。
 * 与 Mika 不同：Mika 询问模式只拦花钱的一步，发消息不问；远手按设计 6.4，对外发送也必须口头确认。
 */
public final class ConfirmationPolicy {
    private ConfirmationPolicy() {}

    public enum Gate {
        SEND_MESSAGE("发送"), PLACE_ORDER("确认下单"), PAYMENT("确认付款"),
        /** 删除、撤回、清空、退群：第一期不做，没有口令，遇到即停。 */
        DELETE(null);
        public final String phrase;
        Gate(String p) { phrase = p; }

        /** 这一类永远不由远手执行（付款交本人；删除第一期不做）。 */
        public boolean neverExecuted() { return this == PAYMENT || this == DELETE; }

        public static Gate fromModel(String s) {
            if (s == null) return null;
            switch (s.trim().toLowerCase()) {
                case "send_message": case "send": return SEND_MESSAGE;
                case "place_order": case "order": return PLACE_ORDER;
                case "payment": case "pay": return PAYMENT;
                case "delete": return DELETE;
                default: return null;
            }
        }
    }

    public enum Reply { CONFIRM, CANCEL, AMBIGUOUS }

    // ---- 按钮词表（Mika IrreversibleDetector 的子集，按远手三道门闩归类） ----
    private static final String[][] STRONG = {
            {"支付", "PAY"}, {"付款", "PAY"}, {"转账", "PAY"},
            {"删除", "DELETE"}, {"撤回", "DELETE"},
            {"下单", "ORDER"}, {"购买", "ORDER"}, {"结算", "ORDER"}, {"结账", "ORDER"},
            {"取消订单", "ORDER"}, {"确认收货", "ORDER"}, {"退款", "ORDER"},
            {"转发", "SEND"},
    };
    private static final int STRONG_MAX_LEN = 12;
    private static final String[] STATUS_SUFFIXES = {"成功", "记录", "售后", "明细", "方式", "列表", "中心", "入口", "码"};
    /** 只认按钮本身，或前面是「立即」「确认」「去」。 */
    private static final String[] EXACT_PAY = {"收银台", "买单", "充值", "储值", "提现", "转出", "打赏", "缴费", "捐赠", "捐款", "红包", "发红包", "塞钱进红包"};
    private static final String[] EXACT_PREFIXES = {"", "立即", "确认", "去"};
    private static final String[] SEND_PREFIX_WORDS = {"发送", "发出", "传送", "发布", "发表", "提交评论"};
    private static final String[] DELETE_PREFIX_WORDS = {"清空", "移除", "注销", "退出群聊"};
    private static final int PREFIX_SLACK = 4;
    private static final String[] PUNCT_SEND = {"评论", "回复"};
    private static final String[] SUBMIT_EXACT = {"提交", "确认提交", "送出", "submit"};
    /** 先从名字里去掉再判：付款方式的选项不是付款动作。 */
    private static final String[] EXCLUDED = {"支付宝", "支付方式", "支付设置"};
    private static final String[][] ENGLISH = {
            {"send", "SEND"}, {"post", "SEND"}, {"reply", "SEND"},
            {"pay", "PAY"}, {"donate", "PAY"},
            {"delete", "DELETE"}, {"remove", "DELETE"}, {"clear all", "DELETE"},
            {"place order", "ORDER"}, {"buy now", "ORDER"}, {"checkout", "ORDER"},
    };

    /** 按钮名 → 门闩；不是关键按钮返回 null。纯函数。 */
    public static Gate classifyLabel(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase().replaceAll("\\s+", " ");
        for (String[] e : ENGLISH) {
            if (lower.equals(e[0]) || lower.startsWith(e[0] + " ")) return gateOf(e[1]);
        }
        String c = t.replaceAll("\\s", "");
        for (String w : SUBMIT_EXACT) if (c.equalsIgnoreCase(w)) return Gate.SEND_MESSAGE;
        for (String w : EXACT_PAY) for (String p : EXACT_PREFIXES) if (c.equals(p + w)) return Gate.PAYMENT;
        for (String w : SEND_PREFIX_WORDS) if (c.startsWith(w) && c.length() <= w.length() + PREFIX_SLACK) return Gate.SEND_MESSAGE;
        for (String w : DELETE_PREFIX_WORDS) if (c.startsWith(w) && c.length() <= w.length() + PREFIX_SLACK) return Gate.DELETE;
        // 远手旧词表里的闲鱼「我想要」（进入下单）
        if (c.equals("我想要")) return Gate.PLACE_ORDER;
        if (c.startsWith("提交订单") && c.length() <= 4 + PREFIX_SLACK) return Gate.PLACE_ORDER;
        for (String w : PUNCT_SEND) {
            if (c.equals(w) || (c.startsWith(w) && c.substring(w.length()).matches("[\\p{Punct}！？。，、]+"))) return Gate.SEND_MESSAGE;
        }
        if (c.length() > STRONG_MAX_LEN) return null;
        String s = c;
        for (String x : EXCLUDED) s = s.replace(x, "");
        for (String[] e : STRONG) {
            int i = s.indexOf(e[0]);
            if (i < 0) continue;
            if (i > 0 && (s.charAt(i - 1) == '待' || s.charAt(i - 1) == '已')) continue;
            String after = s.substring(i + e[0].length());
            boolean status = false;
            for (String suf : STATUS_SUFFIXES) if (after.startsWith(suf)) { status = true; break; }
            if (status) continue;
            return gateOf(e[1]);
        }
        return null;
    }

    private static Gate gateOf(String k) {
        switch (k) {
            case "PAY": return Gate.PAYMENT;
            case "ORDER": return Gate.PLACE_ORDER;
            case "DELETE": return Gate.DELETE;
            default: return Gate.SEND_MESSAGE;
        }
    }

    /** 本地兜底：模型没标闸门但点的是发送/下单/付款/删除按钮时也强制门闩。 */
    public static Gate inferGate(ScreenSnapshot s, int x, int y) {
        if (s == null) return null;
        ScreenSnapshot.Node n = s.nodeAt(x, y);
        if (n == null) return null;
        Gate g = classifyLabel(n.label());
        if (g == null && n.viewId != null) {
            // 资源 ID 按 _ - . / 和驼峰拆词（Mika RESOURCE_WORDS；submit、post 太常见，不认）
            String id = n.viewId.substring(n.viewId.indexOf('/') + 1).replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
            for (String w : id.split("[_\\-.]")) {
                switch (w) {
                    case "send": case "reply": return Gate.SEND_MESSAGE;
                    case "pay": return Gate.PAYMENT;
                    case "delete": case "remove": return Gate.DELETE;
                    case "checkout": return Gate.PLACE_ORDER;
                    default: break;
                }
            }
        }
        return g;
    }

    public static String readback(Gate g, Map<String, String> d) {
        switch (g) {
            case SEND_MESSAGE:
                return "要发给" + v(d, "to") + "，内容是：" + v(d, "text")
                        + "。确认请说「发送」，不发请说「取消」。";
            case PLACE_ORDER:
                return "要下单：" + v(d, "shop") + "的" + v(d, "item") + "，数量" + v(d, "qty")
                        + "，收货" + v(d, "address") + "，应付" + v(d, "amount")
                        + "。确认请说「确认下单」，不下请说「取消」。";
            case DELETE:
                return "这一步会删除或撤回内容，第一期我不做，先停下。";
            case PAYMENT:
            default:
                return "这一步是付款：金额" + v(d, "amount") + "，收款方或支付方式" + v(d, "payee")
                        + "。付款需要你本人来操作，我不会代付。";
        }
    }

    private static String v(Map<String, String> d, String k) {
        String s = d == null ? null : d.get(k);
        return s == null || s.trim().isEmpty() ? "（屏幕上没读到）" : s.trim();
    }

    public static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s，。,.!！?？、~～「」\"']", "");
    }

    public static Reply classify(Gate g, String utterance) {
        String u = normalize(utterance);
        if (u.isEmpty() || g.phrase == null) return Reply.AMBIGUOUS;
        if (u.contains("取消") || u.contains("不对") || u.contains("不要") || u.contains("别")) return Reply.CANCEL;
        if (u.equals(g.phrase) || u.equals(g.phrase + "吧")) return Reply.CONFIRM;
        return Reply.AMBIGUOUS;
    }

    public static boolean isCancelWord(String utterance) {
        String u = normalize(utterance);
        return u.equals("取消") || u.equals("不对") || u.equals("停") || u.equals("停下") || u.equals("急停");
    }
}
