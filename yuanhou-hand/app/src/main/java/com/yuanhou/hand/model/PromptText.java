package com.yuanhou.hand.model;

/** 拼给模型的用户消息（不含 Key）。本机记得和步骤摘要由调用方放进 prior / history。 */
public final class PromptText {
    private PromptText() {}

    public static String actionUser(String intent, String history, String screenText, String prior) {
        StringBuilder u = new StringBuilder();
        u.append("用户意图：").append(intent == null ? "" : intent).append('\n');
        if (prior != null && !prior.trim().isEmpty()) {
            u.append(prior.trim()).append('\n');
        }
        u.append("已做过的步骤：\n");
        if (history == null || history.isEmpty()) u.append("（无）");
        else u.append(history);
        u.append("\n当前屏幕（无障碍只读）：\n");
        u.append(screenText == null ? "（无界面树）" : screenText);
        return u.toString();
    }
}
