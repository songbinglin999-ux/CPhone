package com.yuanhou.hand.port;

/** HandPort 动作失败（HID 返回错误、Wi-Fi 透传断开、急停、未就绪）。上层记为该步失败，不换方式重发。 */
public final class PortException extends Exception {
    /** 对应探头 result 码；负数为本机侧错误（与 HandExecutor.HandException.result 相同含义）。 */
    public final int code;
    public final boolean notReady;
    public final boolean cancelled;

    public PortException(String msg, int code, boolean notReady, boolean cancelled) {
        super(msg);
        this.code = code;
        this.notReady = notReady;
        this.cancelled = cancelled;
    }

    public PortException(String msg) { this(msg, -1, false, false); }
}
