package com.yuanhou.hand.port;

/**
 * 一次屏幕动作的结果（对照 Mika core/uistate/ScreenPort.kt 的 ActionResult.Ok / Failed，用 Java 重写）。
 * 端口只报事实：发出去了没有、事后核对得上没有；不做策略判断（确认闸在 gate/，App 范围在 AppAccessScreenPort）。
 */
public final class ActionResult {

    public enum Status {
        /** 动作已经由 ESP32 执行（或读屏成功）。 */
        OK,
        /** HID / Wi-Fi 透传 / 启动失败。不会换别的方式重发。 */
        FAILED,
        /** 打字：清空后没清干净、贴后读回与要打的字不完全一致、或读不回输入框。没有再贴第二次。 */
        VERIFY_FAILED,
        /** 这个端口不做的动作（双击、多指手势、systemCommand 之类本来就没有）。 */
        UNSUPPORTED,
        /** 用的是旧一屏的编号：先重新 snapshot 再选。 */
        REF_EXPIRED,
        /** 被 AppAccessScreenPort 的 App 范围策略拦下，动作没有发出。 */
        BLOCKED,
        /** 密码框 / 需要本人处理：端口不点、不贴、不探测。 */
        NEEDS_HUMAN,
        /** 两件套（ESP32 手 / 只读无障碍）没就绪，动作没有发出。 */
        NOT_READY
    }

    /** 打字后读回输入框的核对结果；没核对为 null。 */
    public enum Verification { MATCH, MISMATCH, UNKNOWN }

    public final Status status;
    public final String reason;
    public final Verification verification;
    /** 动作前后界面指纹是否变化；没读为 null。 */
    public final Boolean changed;
    /** 动作后前台已不是动作前那个包时，新的包名；否则 null。 */
    public final String appSwitched;

    private ActionResult(Status s, String reason, Verification v, Boolean changed, String appSwitched) {
        this.status = s;
        this.reason = reason;
        this.verification = v;
        this.changed = changed;
        this.appSwitched = appSwitched;
    }

    public boolean isOk() { return status == Status.OK; }

    public static ActionResult ok(Boolean changed, String appSwitched) {
        return new ActionResult(Status.OK, null, null, changed, appSwitched);
    }

    public static ActionResult ok() { return ok(null, null); }

    public static ActionResult typed(Boolean changed) {
        return new ActionResult(Status.OK, null, Verification.MATCH, changed, null);
    }

    public static ActionResult verifyFailed(String reason, Verification v) {
        return new ActionResult(Status.VERIFY_FAILED, reason, v, null, null);
    }

    public static ActionResult of(Status s, String reason) {
        return new ActionResult(s, reason, null, null, null);
    }

    public static ActionResult failed(String reason) { return of(Status.FAILED, reason); }

    public static ActionResult unsupported(String what) {
        return of(Status.UNSUPPORTED, what + "：主屏+ESP32 端口不支持");
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(status.name());
        if (reason != null) b.append('（').append(reason).append('）');
        if (verification != null) b.append(" 核对=").append(verification);
        if (changed != null) b.append(changed ? " 界面有变化" : " 界面没变");
        if (appSwitched != null) b.append(" 前台变为 ").append(appSwitched);
        return b.toString();
    }
}
