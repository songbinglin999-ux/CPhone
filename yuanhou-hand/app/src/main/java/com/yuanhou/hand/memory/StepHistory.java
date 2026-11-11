package com.yuanhou.hand.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次任务里给模型看的步骤。短的时候整段原文保留；变长以后把较早的步骤收成结构化摘要
 * （步号、动作、目标或应用、结果、界面），最近几步仍是原文。不删步，也不用「已省略」把开头裁掉。
 */
public final class StepHistory {
    /** 和 0.1.19 的硬截断同一量级：原文超过这个字数才把较早步骤收成摘要。 */
    public static final int COMPRESS_AFTER_CHARS = 8000;
    public static final int RECENT_VERBATIM_STEPS = 6;
    public static final int RECENT_CHAR_BUDGET = 2800;

    public static final class Turn {
        public final int step;
        /** action 或 note。 */
        public final String kind;
        public final String action;
        public final String target;
        public final String app;
        public final String result;
        public final String screen;
        public final String verbatim;

        Turn(int step, String kind, String action, String target, String app, String result, String screen, String verbatim) {
            this.step = step;
            this.kind = kind;
            this.action = action;
            this.target = target;
            this.app = app;
            this.result = result;
            this.screen = screen;
            this.verbatim = verbatim;
        }
    }

    private final List<Turn> turns = new ArrayList<>();
    private boolean goalVerified;

    public void action(int step, String action, String target, String app, String result, String screen, String verbatim) {
        turns.add(new Turn(step, "action", nz(action), nz(target), nz(app), nz(result), nz(screen), withNl(verbatim)));
    }

    public void note(int step, String text) {
        String body = withNl(text);
        turns.add(new Turn(step, "note", "提醒", "", "", body.trim(), "", body));
    }

    public void markGoalVerified() { goalVerified = true; }

    public boolean goalVerified() { return goalVerified; }

    public List<Turn> turns() { return Collections.unmodifiableList(turns); }

    /** 给模型的「已做过的步骤」。 */
    public String forModel() {
        String all = verbatimAll();
        if (all.length() <= COMPRESS_AFTER_CHARS) return all;
        int start = recentStart();
        if (start <= 0) return all;
        StringBuilder b = new StringBuilder();
        b.append("较早步骤的结构化摘要（每一步都保留：步号、动作、目标或应用、结果、界面）：\n");
        for (int i = 0; i < start; i++) {
            b.append(structured(turns.get(i))).append('\n');
        }
        b.append("最近步骤（原文）：\n");
        for (int i = start; i < turns.size(); i++) {
            b.append(turns.get(i).verbatim);
        }
        return b.toString();
    }

    /** 每 15 步念给用户的进度，用结构化摘要，不念整段原文。 */
    public String progressSpeech(int step) {
        StringBuilder b = new StringBuilder();
        b.append("还在办，已经走了 ").append(step).append(" 步。");
        List<Turn> actions = new ArrayList<>();
        for (Turn t : turns) {
            if ("action".equals(t.kind) && !"do_steps".equals(t.action)) actions.add(t);
        }
        if (actions.isEmpty()) return b.toString();
        int recent = Math.min(3, actions.size());
        int older = actions.size() - recent;
        if (older > 0) {
            b.append("较早 ").append(older).append(" 步已收成摘要，做过：");
            int shown = 0;
            for (int i = 0; i < older && shown < 3; i++) {
                String p = RouteText.phrase(actions.get(i));
                if (p.isEmpty()) continue;
                if (shown > 0) b.append("、");
                b.append(p);
                shown++;
            }
            if (shown == 0) b.append("若干操作");
            b.append("。");
        }
        b.append("最近：");
        int from = actions.size() - recent;
        for (int i = from; i < actions.size(); i++) {
            if (i > from) b.append("、");
            String p = RouteText.phrase(actions.get(i));
            b.append(p.isEmpty() ? "一步操作" : p);
        }
        b.append("。");
        return clip(b.toString(), 180);
    }

    private int recentStart() {
        int start = turns.size();
        int chars = 0;
        int kept = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            int n = turns.get(i).verbatim == null ? 0 : turns.get(i).verbatim.length();
            if (kept >= RECENT_VERBATIM_STEPS) break;
            if (kept > 0 && chars + n > RECENT_CHAR_BUDGET) break;
            start = i;
            chars += n;
            kept++;
        }
        return start;
    }

    private String verbatimAll() {
        StringBuilder b = new StringBuilder();
        for (Turn t : turns) b.append(t.verbatim);
        return b.toString();
    }

    private static String structured(Turn t) {
        if ("note".equals(t.kind)) {
            return "第" + t.step + "步 提醒 → " + oneLine(t.result);
        }
        StringBuilder b = new StringBuilder();
        b.append("第").append(t.step).append("步 ").append(t.action);
        String target = RouteText.safeTarget(t.target);
        if (!target.isEmpty()) b.append(" 「").append(target).append("」");
        if (t.app != null && !t.app.isEmpty()) b.append(" @").append(oneLine(t.app));
        b.append(" → ").append(t.result == null || t.result.isEmpty() ? "已记录" : oneLine(t.result));
        if (t.screen != null && !t.screen.isEmpty()) b.append("；屏：").append(oneLine(t.screen));
        return b.toString();
    }

    static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        if (Sensitive.blocked(t)) return "（已略过敏感内容）";
        if (t.length() > 80) t = t.substring(0, 80) + "…";
        return t;
    }

    private static String clip(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String withNl(String s) {
        String v = s == null ? "" : s;
        return v.endsWith("\n") ? v : v + "\n";
    }
}
