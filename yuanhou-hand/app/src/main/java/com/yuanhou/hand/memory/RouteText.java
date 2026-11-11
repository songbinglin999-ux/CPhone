package com.yuanhou.hand.memory;

import com.yuanhou.hand.policy.UntilCheck;

import java.util.ArrayList;
import java.util.List;

/** 从结构化步骤抽出路线提纲。坐标不作为依据；对不上文字的点击记成「再看屏幕」。 */
public final class RouteText {
    private RouteText() {}

    public static String safeTarget(String target) {
        if (target == null) return "";
        String t = target.trim();
        if (t.isEmpty()) return "";
        if (t.matches("\\d+\\s*,\\s*\\d+")) return "";
        if (Sensitive.blocked(t)) return "";
        return t;
    }

    public static String phrase(StepHistory.Turn t) {
        if (t == null || !"action".equals(t.kind)) return "";
        if ("do_steps".equals(t.action)) return "";
        String target = safeTarget(t.target);
        String p;
        switch (t.action == null ? "" : t.action) {
            case "tap": p = target.isEmpty() ? "点屏幕上的目标" : "点「" + target + "」"; break;
            case "long_press": p = target.isEmpty() ? "长按屏幕上的目标" : "长按「" + target + "」"; break;
            case "type": p = target.isEmpty() || "输入框".equals(target) ? "输入文字" : "输入「" + target + "」"; break;
            case "clear": p = "清空输入框"; break;
            case "scroll": p = "往回".equals(target) ? "往回翻" : "往下翻"; break;
            case "swipe": p = "滑动"; break;
            case "enter": p = "按回车"; break;
            case "back": p = "返回"; break;
            case "home": p = "回到桌面"; break;
            case "open_app":
                String name = target.isEmpty() ? safeTarget(t.app) : target;
                String shown = UntilCheck.displayName(name);
                p = "打开" + (shown != null ? shown : name);
                break;
            case "wait": p = "等页面"; break;
            default:
                p = t.action == null ? "" : t.action;
        }
        if (p.matches(".*\\d{2,}\\s*,\\s*\\d{2,}.*")) return "";
        return p;
    }

    public static List<String> outline(List<StepHistory.Turn> turns, int max) {
        List<String> all = new ArrayList<>();
        if (turns == null) return all;
        for (StepHistory.Turn t : turns) {
            String p = phrase(t);
            if (p.isEmpty() || Sensitive.blocked(p)) continue;
            if (!all.isEmpty() && all.get(all.size() - 1).equals(p)) continue;
            all.add(p);
        }
        if (max <= 0 || all.size() <= max) return all;
        int head = Math.max(1, max / 2);
        int tail = Math.max(1, max - head - 1);
        if (head + tail >= all.size()) return all;
        List<String> cut = new ArrayList<>();
        cut.addAll(all.subList(0, head));
        cut.add("中间还有 " + (all.size() - head - tail) + " 步，按当时的屏幕改");
        cut.addAll(all.subList(all.size() - tail, all.size()));
        return cut;
    }

    public static List<String> apps(List<StepHistory.Turn> turns) {
        List<String> out = new ArrayList<>();
        if (turns == null) return out;
        for (StepHistory.Turn t : turns) {
            if (t == null || !"action".equals(t.kind)) continue;
            String app = t.app == null ? "" : t.app.trim();
            if (app.isEmpty() && "open_app".equals(t.action)) app = safeTarget(t.target);
            String shown = UntilCheck.displayName(app);
            if (shown != null) app = shown;
            if (app.isEmpty() || Sensitive.blocked(app) || out.contains(app)) continue;
            if (UntilCheck.displayName(app) == null) continue;
            out.add(app);
        }
        return out;
    }

    public static String preconditions(List<String> apps) {
        String base = "在主屏上按当前界面做；发送、删除、提交、下单仍要口令，付款不代付。";
        if (apps == null || apps.isEmpty()) return base;
        StringBuilder b = new StringBuilder(base).append("涉及");
        for (int i = 0; i < apps.size(); i++) {
            if (i > 0) b.append("、");
            b.append(apps.get(i));
        }
        return b.append("。").toString();
    }

    public static String goalPattern(String utterance) {
        if (utterance == null) return "";
        String t = utterance.trim().replaceAll("\\s+", " ");
        if (Sensitive.blocked(t)) return "";
        if (t.length() > 80) t = t.substring(0, 80);
        return t;
    }
}
