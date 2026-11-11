package com.yuanhou.hand.wechat;

import com.yuanhou.hand.gate.ConfirmationGate;
import com.yuanhou.hand.gate.IrrevAction;
import com.yuanhou.hand.policy.ConfirmationPolicy;

import java.util.regex.Pattern;

/**
 * 微信通道的纯规则（不碰安卓，jvmcheck 覆盖）：
 * - 只收绑定本人的私聊文字（from_user_id == ilink_user_id；群消息、图片语音、别人发的一律不收）。
 * - 进来的话怎么交给 TaskSession：和局域网 /say 一样，有任务在办时非「取消」的话不交（回一句「还在办」）；「取消」随时立即交。
 * - 与 Mika 不同（Mika WechatChannel.confirms = false）：远手的微信通道可以回答确认闸——发微信回「发送」、下单回「确认下单」，
 *   但只回答由微信交来的那件事；付款（PAY）没有口令，永远不代付，微信里回什么都不会付款。
 * - 发回微信的话：适配措辞（「说」→「回」），付款/需要本人处理时补一句提示，并把口令、令牌类字串打码。
 */
public final class WechatPolicy {
    private WechatPolicy() {}

    /** 微信通道可以回答确认闸（Mika channel.confirms 的远手版）：对 SEND/ORDER/SUBMIT/DELETE/OTHER 为 true，PAY 永远 false。 */
    public static boolean confirmsOverWechat(IrrevAction a) {
        return a != null && !a.neverExecuted() && a.phrase != null;
    }

    /** 一条发给 Bot 的私聊里取出要交给远手的文字；不是绑定本人发来的私聊文字返回 null。 */
    public static String ownerText(WechatApi.Message m, String ownerId) {
        if (m == null || ownerId == null || ownerId.isEmpty()) return null;
        if (!ownerId.equals(m.fromUserId)) return null;
        String t = m.userText();
        if (t == null) return null;
        t = t.trim();
        if (t.isEmpty()) return null;
        return t.length() > MAX_IN ? t.substring(0, MAX_IN) : t;
    }

    /** 一句话最多收这么多字（同局域网 /say）。 */
    public static final int MAX_IN = 2000;
    /** 一条发回微信的消息最多这么多字。 */
    public static final int MAX_OUT = 1800;

    public enum Route {
        /** 空闲：开始一件新事（交给 LocalTextChannel.deliver）。 */
        START_TASK,
        /** 有任务在办：「取消/不对/停」立即交进去。 */
        CANCEL,
        /** 微信交来的事正在等确认：把回答交进去（发送 / 确认下单 / 取消 / 不对）。 */
        CONFIRM_REPLY,
        /** 等确认的事不是从微信交来的：不在微信里替它确认。 */
        CONFIRM_NOT_OURS,
        /** 有任务在办、不是取消：不交（同局域网 /say 409）。 */
        BUSY,
        /** 空闲时说取消。 */
        IDLE_CANCEL,
        /** 空闲时只发了一个确认口令（发送/确认下单/确认付款）：没有在等确认，不当新任务办。 */
        STRAY_CONFIRM
    }

    public static Route route(String text, boolean running, boolean awaitingConfirm, boolean wechatOwnsTask) {
        boolean cancel = ConfirmationPolicy.isCancelWord(text);
        if (running && awaitingConfirm) {
            if (wechatOwnsTask) return Route.CONFIRM_REPLY;
            return cancel ? Route.CANCEL : Route.CONFIRM_NOT_OURS;
        }
        if (running) return cancel ? Route.CANCEL : Route.BUSY;
        if (cancel) return Route.IDLE_CANCEL;
        if (isBareConfirmPhrase(text)) return Route.STRAY_CONFIRM;
        return Route.START_TASK;
    }

    static boolean isBareConfirmPhrase(String text) {
        String u = ConfirmationGate.normalize(text);
        if (u.endsWith("吧")) u = u.substring(0, u.length() - 1);
        if (u.equals("确认付款") || u.equals("付款") || u.equals("支付") || u.equals("确认支付")) return true;
        for (IrrevAction a : IrrevAction.values()) if (a.phrase != null && u.equals(a.phrase)) return true;
        return false;
    }

    /** 各路由对应直接回到微信的一句话（START_TASK / CANCEL / CONFIRM_REPLY 不在这里回，由任务自己的话回）。 */
    public static String replyFor(Route r) {
        switch (r) {
            case BUSY: return "上一件事还在办，这句先不办。回「取消」可以停下。";
            case CONFIRM_NOT_OURS: return "现在等确认的那件事不是从微信交来的，微信里不替它确认；回「取消」可以停下。";
            case IDLE_CANCEL: return "现在没有在办的事。";
            case STRAY_CONFIRM: return "现在没有在等确认的事，这句不当新任务办。付款永远要你本人在手机上操作。";
            default: return null;
        }
    }

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=\\-]{6,}");
    /** 局域网口令格式：48 位十六进制（LanProtocol.newToken）。更长的十六进制串也一并打码。 */
    private static final Pattern LONG_HEX = Pattern.compile("(?i)\\b[0-9a-f]{32,}\\b");
    private static final Pattern API_KEY = Pattern.compile("\\b(sk|xai|gsk|AKIA)[-_A-Za-z0-9]{16,}\\b");

    /** 把已知机密原样出现的地方和像令牌的字串都打码。secrets 里短于 6 个字的忽略（免得把普通字打掉）。 */
    public static String redact(String text, String... secrets) {
        if (text == null) return "";
        String t = text;
        if (secrets != null) {
            for (String s : secrets) {
                if (s != null && s.length() >= 6) t = t.replace(s, "***");
            }
        }
        t = BEARER.matcher(t).replaceAll("$1***");
        t = LONG_HEX.matcher(t).replaceAll("***");
        t = API_KEY.matcher(t).replaceAll("***");
        return t;
    }

    /** 远手说的一句话 → 发回微信的文字。 */
    public static String forWechat(String line, String... secrets) {
        if (line == null) return "";
        String t = line.trim();
        t = t.replace("请说「", "请回「").replace("说「取消」", "回「取消」").replace("说「不对」", "回「不对」")
                .replace("等你说口令", "等你回口令");
        if (t.contains("付款需要你本人来操作") || t.contains("付款交本人") || t.contains("付款留给你本人")) {
            if (!t.contains("微信里回什么都不会付款")) t += "（微信里回什么都不会付款，要付请你本人在手机上操作。）";
        }
        if (t.contains("需要你本人处理")) {
            t += "（登录、验证码、密码这类页面我不会填，请你在家里那台手机上亲手处理。）";
        }
        t = redact(t, secrets);
        if (t.length() > MAX_OUT) t = t.substring(0, MAX_OUT) + "…";
        return t;
    }
}
