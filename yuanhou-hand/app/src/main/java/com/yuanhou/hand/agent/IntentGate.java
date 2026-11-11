package com.yuanhou.hand.agent;

/**
 * 意图闸（思路同 Mika core/agent/IntentGate.kt）：先把用户这句话分成 讨论 / 读取 / 操作 三类。
 * 只有 ACT 才进入「读屏 → 动作 → 执行 → 等稳定 → 再读屏」的循环；DISCUSS 只回话，READ 只读一屏回答、不动手。
 *
 * 判定顺序：模型（只给这句话，不给屏幕）→ 解析不出或没有模型时用本地关键词兜底。拿不准时取更保守的一类：
 * 本地兜底只在出现明确动手词时判 ACT。
 */
public final class IntentGate {

    public enum Intent { DISCUSS, READ, ACT }

    /** 小模型判断：返回原始文字；失败返回 null。 */
    public interface Judge {
        String complete(String system, String user);
    }

    public static final String SYSTEM = "你只做一件事：判断用户这句话是想（1）讨论或问问题、不需要看手机，"
            + "（2）只看/读取手机屏幕上的信息、不动手，还是（3）要在手机上动手操作（点、输入、发送、打开应用、下单等）。"
            + "只回答一个词：DISCUSS、READ 或 ACT，不要输出别的。";

    private final Judge judge;

    public IntentGate(Judge judge) { this.judge = judge; }

    public Intent assess(String utterance) {
        if (judge != null) {
            try {
                Intent i = parse(judge.complete(SYSTEM, "用户这句话：" + utterance));
                if (i != null) return i;
            } catch (RuntimeException ignored) {
                // 落到本地兜底
            }
        }
        return local(utterance);
    }

    public static Intent parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        while (s.startsWith("`")) s = s.substring(1);
        while (s.endsWith("`")) s = s.substring(0, s.length() - 1);
        s = s.trim().toUpperCase();
        switch (s) {
            case "DISCUSS": return Intent.DISCUSS;
            case "READ": return Intent.READ;
            case "ACT": return Intent.ACT;
            default: return null;
        }
    }

    private static final String[] ACT_WORDS = {
            "发给", "发消息", "发微信", "发送", "回复", "回他", "回她", "告诉", "打开", "点击", "点开", "点一下", "输入", "搜", "买", "下单",
            "预订", "订票", "删", "撤回", "提交", "付款", "支付", "转账", "评论", "关注", "加购", "收藏", "填", "登录", "退出"
    };
    private static final String[] READ_WORDS = {
            "看看", "看一下", "读", "屏幕上", "有没有", "多少钱", "几条", "什么消息", "未读", "查一下", "查查", "念"
    };

    /** 本地兜底：明确的动手词 → ACT；看/读类 → READ；其余 DISCUSS。 */
    public static Intent local(String t) {
        if (t == null) return Intent.DISCUSS;
        for (String w : ACT_WORDS) if (t.contains(w)) return Intent.ACT;
        for (String w : READ_WORDS) if (t.contains(w)) return Intent.READ;
        return Intent.DISCUSS;
    }
}
