package com.yuanhou.hand.memory;

import org.json.JSONObject;

/**
 * done 之前的强制核对。只有本机这一轮问出来的 goal_check 且 met 为真，才算目标达成。
 * 模型在普通回合里返回的 done、stop，或在核对回合里再写一个 done，都不能代替这次核对。
 */
public final class GoalCheck {
    public static final String SYSTEM = "你是家里备用手机上的操作助手「远手」。这一轮只核对目标，不动手机。"
            + "对照用户原话、已做步骤和当前屏幕，判断原话里的那件事是否已经在屏幕上办成。"
            + "只输出一个 JSON 对象，不要输出其他文字。字段：action 必须是 goal_check，met 为 true 或 false，say 为一句简短中文。"
            + "只有屏幕上已经能看出原话的目标达成，met 才能是 true。还在半路、只做了准备工作、看不清、或需要猜测，都是 false。"
            + "付款页、支付密码、验证码、还停在确认前，都不是办完。不要用 done 或 stop 代替 goal_check。";

    public static final class Verdict {
        public final boolean met;
        public final String say;
        /** 模型确实返回了可解析的 JSON。met 仍只有 action=goal_check 且 met=true 才为真。 */
        public final boolean parsed;

        Verdict(boolean met, String say, boolean parsed) {
            this.met = met;
            this.say = say == null ? "" : say;
            this.parsed = parsed;
        }
    }

    private GoalCheck() {}

    public static String userPrompt(String goal, String history, String screen) {
        return "用户原话：" + (goal == null ? "" : goal)
                + "\n已做步骤：\n" + (history == null || history.isEmpty() ? "（无）" : history)
                + "\n当前屏幕：\n" + (screen == null ? "（无界面树）" : screen)
                + "\n只回答目标是否已经达成。";
    }

    public static Verdict parse(String content) {
        Verdict missing = new Verdict(false, "核对没有给出明确结论，先不算办完。", false);
        if (content == null || content.trim().isEmpty()) return missing;
        try {
            String s = content.trim();
            int i = s.indexOf('{');
            int j = s.lastIndexOf('}');
            if (i < 0 || j <= i) return missing;
            JSONObject o = new JSONObject(s.substring(i, j + 1));
            String action = o.optString("action", "");
            String say = o.optString("say", "").trim();
            if (say.length() > 80) say = say.substring(0, 80);
            if (Sensitive.blocked(say)) say = "核对结论不能采用。";
            if (!"goal_check".equals(action)) {
                String why = action.isEmpty() ? "核对必须是 goal_check。" : "核对必须是 goal_check，不能用 " + action + " 跳过。";
                return new Verdict(false, why, true);
            }
            boolean met = o.has("met") && o.optBoolean("met", false);
            if (say.isEmpty()) say = met ? "目标已经达成。" : "目标还没达成。";
            return new Verdict(met, say, true);
        } catch (Exception e) {
            return missing;
        }
    }
}
