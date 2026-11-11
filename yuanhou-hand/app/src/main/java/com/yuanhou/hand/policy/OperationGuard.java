package com.yuanhou.hand.policy;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 每次任务一份的操作守卫（借鉴 Mika core/guard/OperationGuards.kt + GuardRules.kt 的思路）。
 * 只在动作确实没有效果、反复回到同一界面或连续读不出东西时先提醒模型、再叫停；叫停后整个任务结束、如实汇报，
 * 不换输入方式、不改间隔、不加抖动再试。HID 失败不经过这里：HID 一次失败即停（设计 4/6.2）。
 */
public final class OperationGuard {

    public static final class Verdict {
        public final boolean stop;
        public final String code;
        public final String message;
        Verdict(boolean stop, String code, String message) { this.stop = stop; this.code = code; this.message = message; }
    }

    /** 连续多少个动作界面没变：先提醒，再叫停（Mika NO_EFFECT_LADDER 3/6/9/12，远手 2 提醒、4 停）。 */
    public static final int NO_EFFECT_WARN = 2, NO_EFFECT_STOP = 4;
    /** 同一个动作签名在最近窗口里出现多少次叫停（Mika 0.1.38 已不单凭签名叫停；远手只在上一步也没效果时才算，3 提醒、5 停）。 */
    public static final int REPEAT_WARN = 3, REPEAT_STOP = 5, REPEAT_WINDOW = 8;
    /** 回到同一界面状态多少次叫停（按 Mika sameState 上限档：5 提醒、10 停；不再按 20 步短任务收紧）。 */
    public static final int SAME_STATE_WARN = 5, SAME_STATE_STOP = 10;
    /** 界面读不出内容（空白/加载中）连续多少次叫停。 */
    public static final int BLANK_STOP = 3;

    private Long lastFp;
    private int unchanged;
    private int blank;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Map<Long, Integer> visits = new HashMap<>();

    /** 动作签名：坐标按 50 像素取整，滑动只记方向（同 Mika actionSignature）。 */
    public static String signature(String type, int x, int y, int x2, int y2, String text) {
        StringBuilder b = new StringBuilder(type == null ? "" : type);
        if ("swipe".equals(type)) {
            int dx = x2 - x, dy = y2 - y;
            boolean v = Math.abs(dy) >= Math.abs(dx);
            b.append('|').append(v ? 'V' : 'H').append((v ? dy : dx) < 0 ? '-' : '+');
        } else if (x >= 0 && y >= 0) {
            b.append('|').append(x / 50).append(',').append(y / 50);
        }
        if (text != null && !text.isEmpty()) b.append('|').append(text);
        return b.toString();
    }

    /** 每读到一屏调一次（动作之后）。fp 为 null 表示读不出界面。返回提醒/叫停，或 null。 */
    public Verdict onScreen(Long fp, boolean afterAction) {
        if (fp == null) {
            blank++;
            if (blank >= BLANK_STOP) return new Verdict(true, "blank", "连续 " + blank + " 次读不出界面内容");
            return null;
        }
        blank = 0;
        Verdict v = null;
        if (afterAction && lastFp != null && lastFp.equals(fp)) {
            unchanged++;
            if (unchanged >= NO_EFFECT_STOP) v = new Verdict(true, "no_effect", "连续 " + unchanged + " 次操作后界面都没有变化");
            else if (unchanged >= NO_EFFECT_WARN) v = new Verdict(false, "no_effect", "上 " + unchanged + " 次操作界面都没变，换个目标或说明为什么停");
        } else if (!fp.equals(lastFp)) {
            unchanged = 0;
        }
        lastFp = fp;
        int n = visits.merge(fp, 1, Integer::sum);
        if (v == null || !v.stop) {
            if (n >= SAME_STATE_STOP) v = new Verdict(true, "same_state", "反复回到同一个界面 " + n + " 次");
            else if (v == null && n >= SAME_STATE_WARN) v = new Verdict(false, "same_state", "这个界面已经回来过 " + n + " 次，别兜圈");
        }
        return v;
    }

    /** 每执行一个动作前调一次。 */
    public Verdict beforeAction(String sig) {
        recent.addLast(sig);
        while (recent.size() > REPEAT_WINDOW) recent.removeFirst();
        int c = 0;
        for (String s : recent) if (s.equals(sig)) c++;
        // Mika 的教训：相同的点击/滚动可以逐页读到新内容，单凭签名重复不算停滞；只有上一步也没效果时才算。
        if (unchanged == 0) return null;
        if (c >= REPEAT_STOP) return new Verdict(true, "repeating_action", "同一个操作反复做了 " + c + " 次仍没有效果");
        if (c >= REPEAT_WARN) return new Verdict(false, "repeating_action", "同一个操作已经做了 " + c + " 次，上一次没有效果");
        return null;
    }
}
