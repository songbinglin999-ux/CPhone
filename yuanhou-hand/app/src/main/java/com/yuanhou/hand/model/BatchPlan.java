package com.yuanhou.hand.model;

import com.yuanhou.hand.gate.IrrevAction;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一次多步（do_steps）的校验，学 Mika BatchRunner：1–20 个已知动作，付款不能进批。 */
public final class BatchPlan {
    public static final int MIN_STEPS = 1;
    public static final int MAX_STEPS = 20;

    public static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
            "tap", "long_press", "type", "clear", "scroll", "swipe", "enter", "back", "home", "open_app", "wait"));

    private BatchPlan() {}

    /** 通过返回 null；否则是给模型看的中文原因（整批都不执行）。 */
    public static String validate(ModelAction a) {
        if (a == null || !"do_steps".equals(a.type)) return "不是一次多步";
        List<ModelAction> steps = a.steps;
        if (steps == null || steps.size() < MIN_STEPS) return "一次多步至少要有 1 个动作";
        if (steps.size() > MAX_STEPS) return "一次多步最多 20 个动作，多出的请下一轮再给";
        for (int i = 0; i < steps.size(); i++) {
            ModelAction s = steps.get(i);
            if (s == null) return "steps[" + i + "] 空了";
            if ("do_steps".equals(s.type)) return "steps[" + i + "] 不能再套一次多步";
            if ("done".equals(s.type) || "stop".equals(s.type)) {
                return "steps[" + i + "] 不要放 done/stop，办完或要停请作为单独一步";
            }
            if (!KNOWN.contains(s.type)) return "steps[" + i + "] 的动作「" + s.type + "」不认识";
            IrrevAction g = IrrevAction.fromModel(s.gate);
            if (g != null && g.neverExecuted()) return "付款不能放进一次多步，遇到付款请单独 stop";
        }
        return null;
    }
}
