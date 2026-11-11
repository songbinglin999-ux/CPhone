package com.yuanhou.hand.memory;

/**
 * 模型这一步能不能让任务结束。done 只有目标核对 met 才接受；stop 立刻停下，不看核对结果。
 * 模型自己写的 goal_check 不能结束任务。
 */
public final class DoneGate {
    public enum Decision { ACCEPT, CONTINUE, STOP }

    private DoneGate() {}

    public static Decision decide(String action, boolean goalCheckMet) {
        if ("stop".equals(action)) return Decision.STOP;
        if ("done".equals(action) && goalCheckMet) return Decision.ACCEPT;
        return Decision.CONTINUE;
    }
}
