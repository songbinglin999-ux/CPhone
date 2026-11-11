package com.yuanhou.hand.gate;

import com.yuanhou.hand.screen.ScreenSnapshot;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加严的确认闸（思路同 Mika core/gate/ConfirmationGate.kt，Java 重写）。每个任务一份。
 *
 * 与 Mika 默认「询问模式」的区别：Mika 只问花钱的一步，发消息/删除默认不问；远手对
 * 发送、删除、提交、下单（以及屏上有应付金额时的泛泛「确定」）一律先复述对象、内容和金额，等本人说出口令才执行。
 * 付款（PAY）永远 UNSUPPORTED：交给本人亲手操作，不代付，也不问。
 *
 * 流程：
 * 1. declare：模型/本地申报（App、对象、内容、金额）。前台 App 对不上 → MISMATCH；该填的没填 → INCOMPLETE；
 *    本任务里本人已拒绝过同 App 同类操作 → DECLINED（不再问）。
 * 2. readback：念给本人：要做什么、对谁、内容、金额，以及口令。
 * 3. classify + decide：只有说出口令（「发送」「确认删除」「确认提交」「确认下单」「确认执行」，可带「吧」）才算同意；
 *    「好的」「嗯」「可以」都算含糊 = 不同意。
 * 4. permits：执行那一步之前用新读的一屏核对；同意只放行紧接着的那一个动作，有同意时不论放不放行都用掉。
 *    核对：同一类、同一前台 App、没过期（MAX_STEPS）、对象在屏上（发送只认顶部标题栏）、
 *    发送时输入框里的字 = 申报正文、提交/删除申报了内容就要在屏上、下单/OTHER 金额必须在屏上、
 *    提交/删除时屏上有应付金额则金额也必须申报且对得上。
 * 5. noteWrite：同意之后、执行之前做了任何别的写操作（包括打字），同意作废。
 */
public final class ConfirmationGate {

    public enum Declare { PENDING, MISMATCH, DECLINED, UNSUPPORTED, INCOMPLETE }

    public enum Reply { CONFIRM, CANCEL, AMBIGUOUS }

    public static final class Permit {
        public final boolean allowed;
        public final String reason;
        Permit(boolean a, String r) { allowed = a; reason = r; }
        static final Permit OK = new Permit(true, null);
        public static Permit no(String r) { return new Permit(false, r); }
    }

    /** 申报。字段都会截到 200 字。 */
    public static final class Declaration {
        public final IrrevAction action;
        public final String app, target, content, amount;

        public Declaration(IrrevAction action, String app, String target, String content, String amount) {
            this.action = action;
            this.app = clip(app);
            this.target = clip(target);
            this.content = clip(content);
            this.amount = clip(amount);
        }

        /** 模型 details：to / shop / item / target / text / content / amount。 */
        public static Declaration from(IrrevAction action, String app, Map<String, String> d) {
            String target = first(d, "to", "target", "shop", "item");
            if (action == IrrevAction.ORDER) {
                String shop = first(d, "shop"), item = first(d, "item");
                target = shop.isEmpty() ? item : shop;
            }
            String content = first(d, "text", "content");
            if (action == IrrevAction.ORDER && content.isEmpty()) {
                String item = first(d, "item"), qty = first(d, "qty");
                content = item + (qty.isEmpty() ? "" : " ×" + qty);
            }
            return new Declaration(action, app, target, content, first(d, "amount"));
        }

        private static String first(Map<String, String> d, String... keys) {
            if (d == null) return "";
            for (String k : keys) {
                String v = d.get(k);
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
            return "";
        }

        private static String clip(String s) {
            if (s == null) return "";
            s = s.trim();
            return s.length() > 200 ? s.substring(0, 200) : s;
        }
    }

    /** 同意后最多再过这么多步（Mika MAX_STEPS = 3）。远手实际在申报的那一步就用掉。 */
    public static final int MAX_STEPS = 3;
    private static final int MATCH_CHARS = 20;
    private static final Pattern CURRENCY = Pattern.compile("[¥￥$＄]\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*元");
    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");

    /**
     * 从语句里拆出带币种的金额。只认 ¥ / ￥ / $ / ＄ / 元，不把没有币种的光秃数字当金额。
     * 格式统一：后缀写成「18元」，前缀符号写成「¥18」「￥29.90」「$18」（符号和数字之间不留空）。
     * 语句里没有金额时返回空串（复述仍可以是「金额：无」）。
     */
    public static String extractAmount(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = CURRENCY.matcher(text);
        if (!m.find()) return "";
        if (m.group(1) != null) return text.charAt(m.start()) + m.group(1);
        return m.group(2) + "元";
    }

    /** 去掉语句里第一处带币种的金额，剩下作为对象/内容；没有金额则原样（去首尾空白）。 */
    public static String withoutAmount(String text) {
        if (text == null) return "";
        Matcher m = CURRENCY.matcher(text);
        if (!m.find()) return text.trim();
        return (text.substring(0, m.start()) + " " + text.substring(m.end())).replaceAll("\\s+", " ").trim();
    }
    private static final String[] DISCOUNTS = {"优惠", "减", "抵扣", "运费", "券", "红包", "已省"};

    private Declaration pending;
    private Declaration grant;
    private int grantStep;
    private String problem;
    private final Set<String> declined = new HashSet<>();

    /** [screen] 是申报时这一屏，用来判断提交/删除是否要绑定金额。 */
    public synchronized Declare declare(Declaration d, String foregroundApp, ScreenSnapshot screen) {
        problem = null;
        pending = null;
        if (d.action.neverExecuted()) { problem = "付款交给你本人"; return Declare.UNSUPPORTED; }
        if (declined.contains(key(d))) { problem = "这件事本次任务里你已经说过不要"; return Declare.DECLINED; }
        if (foregroundApp == null || foregroundApp.isEmpty() || !foregroundApp.equals(d.app)) {
            problem = "申报的应用 " + d.app + " 不是前台 " + foregroundApp;
            return Declare.MISMATCH;
        }
        String missing = missing(d, screen);
        if (missing != null) { problem = missing; return Declare.INCOMPLETE; }
        pending = d;
        return Declare.PENDING;
    }

    /** 上一次 declare 不是 PENDING 时的原因。 */
    public synchronized String problem() { return problem; }

    private static String missing(Declaration d, ScreenSnapshot s) {
        switch (d.action) {
            case SEND:
                if (d.target.isEmpty()) return "没说清发给谁";
                if (d.content.isEmpty()) return "没读到要发的内容";
                return null;
            case DELETE:
                if (d.target.isEmpty() && d.content.isEmpty()) return "没说清要删什么";
                break;
            case SUBMIT:
                if (d.target.isEmpty() && d.content.isEmpty()) return "没说清要提交什么";
                break;
            case ORDER:
                if (d.target.isEmpty()) return "没说清店家或商品";
                if (d.amount.isEmpty()) return "没读到应付金额";
                return null;
            case OTHER:
                if (d.amount.isEmpty()) return "没读到金额";
                return null;
            default:
                return null;
        }
        if (IrreversibleDetector.payableAmount(s) != null && d.amount.isEmpty()) return "屏上有应付金额，但没有申报金额";
        return null;
    }

    /** 复述：动作、对象、内容、金额、口令。 */
    public static String readback(Declaration d) {
        if (d.action == IrrevAction.PAY) {
            return "这一步是付款" + (d.amount.isEmpty() ? "" : "，金额 " + d.amount)
                    + (d.target.isEmpty() ? "" : "，收款方 " + d.target) + "。付款需要你本人来操作，我不会代付。";
        }
        StringBuilder b = new StringBuilder("要").append(d.action.label);
        if (!d.target.isEmpty()) b.append(d.action == IrrevAction.SEND ? "给" : "：").append(d.target);
        b.append("，内容是「").append(d.content.isEmpty() ? "（无）" : d.content).append("」");
        b.append("，金额").append(d.amount.isEmpty() ? "：无" : " " + d.amount);
        b.append("。确认请说「").append(d.action.phrase).append("」，不做请说「取消」。");
        return b.toString();
    }

    public static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\s，。,.!！?？、~～「」\"']", "");
    }

    /** 只有完整口令才是同意；带否定词即取消；其余含糊（含「好」「可以」「嗯」）。 */
    public static Reply classify(IrrevAction a, String utterance) {
        String u = normalize(utterance);
        if (u.isEmpty() || a.phrase == null) return Reply.AMBIGUOUS;
        if (u.contains("取消") || u.contains("不对") || u.contains("不要") || u.contains("别") || u.contains("不用")) return Reply.CANCEL;
        if (u.equals(a.phrase) || u.equals(a.phrase + "吧")) return Reply.CONFIRM;
        return Reply.AMBIGUOUS;
    }

    public synchronized void decide(boolean approved, int step) {
        Declaration d = pending;
        pending = null;
        if (d == null) return;
        if (!approved) { declined.add(key(d)); return; }
        grant = d;
        grantStep = step;
    }

    public synchronized boolean hasGrant() { return grant != null; }

    /** 执行前核对；有同意时不论结果都用掉。[typing] 是回车时焦点框里的字（没有为 null）。 */
    public synchronized Permit permits(IrrevAction hit, ScreenSnapshot s, int step, String typing) {
        Declaration d = grant;
        if (d == null) return Permit.no("没有同意");
        grant = null;
        if (hit == null) return Permit.no("这一步不是你同意的那类操作");
        if (hit.neverExecuted()) return Permit.no("付款永远不由我执行");
        if (step - grantStep > MAX_STEPS) return Permit.no("同意已过期（超过 " + MAX_STEPS + " 步）");
        if (hit != d.action) return Permit.no("你同意的是「" + d.action.label + "」，这一步却是「" + hit.label + "」");
        if (s == null) return Permit.no("读不到界面，没法核对");
        if (!d.app.equals(s.packageName)) return Permit.no("同意时在 " + d.app + "，现在屏上是 " + s.packageName);
        if (!d.target.isEmpty() && !targetShown(d, s)) {
            return Permit.no("屏上" + (d.action == IrrevAction.SEND ? "标题栏" : "") + "没有「" + d.target + "」");
        }
        switch (d.action) {
            case SEND:
                if (!fieldMatches(d.content, s, typing)) return Permit.no("输入框里的字和你同意的内容不一样");
                break;
            case SUBMIT:
            case DELETE:
                if (!d.content.isEmpty() && !contentShown(d.content, s)) return Permit.no("屏上没有你同意的内容「" + d.content + "」");
                if (IrreversibleDetector.payableAmount(s) != null && !amountShown(d.amount, s)) {
                    return Permit.no("屏上的应付金额和你同意的 " + (d.amount.isEmpty() ? "（没有申报）" : d.amount) + " 对不上");
                }
                break;
            case ORDER:
            case OTHER:
                if (!amountShown(d.amount, s)) return Permit.no("屏上没有你同意的金额 " + d.amount);
                break;
            default:
                break;
        }
        return Permit.OK;
    }

    /** 同意之后执行之前做了别的写操作（点别处、打字、滑动、返回、打开 App），同意作废：只放行紧接着的那一个动作。 */
    public synchronized void noteWrite() { grant = null; }

    public synchronized void reset() {
        pending = null;
        grant = null;
        problem = null;
        declined.clear();
    }

    private static String key(Declaration d) { return d.action + "|" + d.app; }

    private static String norm(String s) { return s == null ? "" : s.replaceAll("\\s", "").toLowerCase(); }

    private static boolean targetShown(Declaration d, ScreenSnapshot s) {
        String t = norm(d.target);
        if (t.length() > MATCH_CHARS) t = t.substring(0, MATCH_CHARS);
        if (t.isEmpty()) return false;
        int band = s.titleBandBottom();
        for (ScreenSnapshot.Node n : s.nodes) {
            if (n.editable) continue;
            if (!norm(n.label()).contains(t)) continue;
            if (d.action != IrrevAction.SEND || (band > 0 && n.bounds.top <= band)) return true;
        }
        return false;
    }

    private static boolean fieldMatches(String content, ScreenSnapshot s, String typing) {
        String c = norm(content);
        if (c.isEmpty()) return false;
        if (typing != null) return norm(typing).equals(c);
        for (ScreenSnapshot.Node n : s.nodes) if (n.editable && norm(n.typedText()).equals(c)) return true;
        return false;
    }

    private static boolean contentShown(String content, ScreenSnapshot s) {
        String c = norm(content);
        if (c.length() > MATCH_CHARS) c = c.substring(0, MATCH_CHARS);
        for (ScreenSnapshot.Node n : s.nodes) {
            if (norm(n.label()).contains(c) || norm(n.typedText()).contains(c)) return true;
        }
        return false;
    }

    /** 申报金额要等于屏上（非输入框）某个带币种的数，紧挨优惠/减/运费/券字样的不算。 */
    static boolean amountShown(String amount, ScreenSnapshot s) {
        Matcher dm = NUMBER.matcher(amount == null ? "" : amount.replace(",", ""));
        if (!dm.find()) return false;
        BigDecimal declared = new BigDecimal(dm.group());
        for (ScreenSnapshot.Node n : s.nodes) {
            if (n.editable) continue;
            String t = n.label().replaceAll("(?<=\\d),(?=\\d{3})", "");
            Matcher m = CURRENCY.matcher(t);
            while (m.find()) {
                String before = t.substring(Math.max(0, m.start() - 4), m.start());
                boolean discount = before.trim().endsWith("-");
                for (String w : DISCOUNTS) if (before.contains(w)) discount = true;
                if (discount) continue;
                String v = m.group(1) != null ? m.group(1) : m.group(2);
                if (new BigDecimal(v).compareTo(declared) == 0) return true;
            }
        }
        return false;
    }
}
