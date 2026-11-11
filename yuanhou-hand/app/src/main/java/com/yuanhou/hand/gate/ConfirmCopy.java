package com.yuanhou.hand.gate;

/**
 * 0.1.13：首页「等你确认」的文案，全部来自确认闸的真实申报（ConfirmationGate.Declaration），不编菜名、价格。
 * 纯 Java，jvmcheck 覆盖。付款不走这里（付款永远不出确认页）。
 */
public final class ConfirmCopy {
    private ConfirmCopy() {}

    /** 首页能不能为这一步显示确认页：付款永远不能；没有口令的也不能。 */
    public static boolean showable(ConfirmationGate.Declaration d) {
        return d != null && d.action != null && !d.action.neverExecuted() && d.action.phrase != null;
    }

    /** 点「确认」时交给会话的那句口令（发送 / 确认下单 / …），与确认闸要求的完全一致。 */
    public static String confirmWord(ConfirmationGate.Declaration d) {
        return showable(d) ? d.action.phrase : null;
    }

    /** 问句：确认在美团下单？/ 确认发给张三？等。 */
    public static String question(ConfirmationGate.Declaration d) {
        if (d == null || d.action == null) return "";
        String app = appName(d.app);
        switch (d.action) {
            case SEND: return d.target.isEmpty() ? "确认发送这条消息？" : "确认发给" + d.target + "？";
            case ORDER: return app == null ? "确认下单？" : "确认在" + app + "下单？";
            case SUBMIT: return "确认提交？";
            case DELETE: return "确认删除？";
            case OTHER: return "确认执行这一步？";
            default: return "";
        }
    }

    /** 卡片第一行：内容（消息正文 / 商品），没有就用对象。 */
    public static String primary(ConfirmationGate.Declaration d) {
        if (d == null) return "";
        if (!d.content.isEmpty()) return d.content;
        return d.target;
    }

    /** 卡片第二行左边：金额（没有为空串）。 */
    public static String amount(ConfirmationGate.Declaration d) {
        return d == null ? "" : d.amount;
    }

    /** 卡片第二行右边：下单时的店家/卖家（和第一行不同才写）；发送时对象已经在问句里，不重复。 */
    public static String secondary(ConfirmationGate.Declaration d) {
        if (d == null || d.action == IrrevAction.SEND) return "";
        if (d.target.isEmpty() || d.target.equals(primary(d))) return "";
        return d.target;
    }

    public static String appName(String pkg) {
        if (pkg == null) return null;
        switch (pkg) {
            case "com.sankuai.meituan": return "美团";
            case "com.sankuai.meituan.takeoutnew": return "美团外卖";
            case "com.taobao.taobao": return "淘宝";
            case "com.taobao.idlefish": return "闲鱼";
            case "com.tencent.mm": return "微信";
            default: return null;
        }
    }
}
