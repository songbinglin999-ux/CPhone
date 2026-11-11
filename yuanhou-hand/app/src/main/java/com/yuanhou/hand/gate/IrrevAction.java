package com.yuanhou.hand.gate;

/**
 * 不可逆操作种类（同 Mika core/risk/Irreversible.kt 的 IrrevAction { send, pay, delete, order, submit, other }）。
 * 远手更严：SEND / DELETE / SUBMIT / ORDER / OTHER 全部要先复述内容和金额、等本人明确说出口令才执行；PAY 永远交给本人。
 */
public enum IrrevAction {
    SEND("发送", "发送", 1),
    SUBMIT("提交", "确认提交", 2),
    DELETE("删除", "确认删除", 3),
    OTHER("确认带金额的操作", "确认执行", 4),
    ORDER("下单", "确认下单", 5),
    /** 付款：没有口令，永远不代付。 */
    PAY("付款", null, 6);

    public final String label;
    /** 本人必须说出的口令；PAY 为 null。 */
    public final String phrase;
    final int rank;

    IrrevAction(String label, String phrase, int rank) {
        this.label = label;
        this.phrase = phrase;
        this.rank = rank;
    }

    /** 付款永远不由远手执行。 */
    public boolean neverExecuted() { return this == PAY; }

    /** 两种里更严的那个。 */
    public static IrrevAction stricter(IrrevAction a, IrrevAction b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.rank > a.rank ? b : a;
    }

    /** 模型申报的 gate 字段。看不懂的返回 null（本地词表仍会兜底）。 */
    public static IrrevAction fromModel(String s) {
        if (s == null) return null;
        switch (s.trim().toLowerCase()) {
            case "send": case "send_message": case "post": case "reply": return SEND;
            case "submit": return SUBMIT;
            case "delete": case "remove": case "recall": return DELETE;
            case "order": case "place_order": case "buy": return ORDER;
            case "pay": case "payment": return PAY;
            case "other": return OTHER;
            default: return null;
        }
    }
}
