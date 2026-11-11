package com.yuanhou.hand.wechat;

import android.content.Context;

import com.yuanhou.hand.service.OrchestratorService;
import com.yuanhou.hand.util.AppLog;

/**
 * 给界面用的最小接口（0.1.12）：新首页（简一方案）以后直接调这里，不必碰 WechatBridge / WechatStore 内部。
 * 不返回任何令牌、用户 id、游标或二维码内容以外的机密。
 */
public final class WechatControl {
    private WechatControl() {}

    /** 给人看的状态：未绑定 / 已暂停接收 / 服务没启动 / 连接中 / 已连接 / 连接中（重试）/ 需重新绑定。 */
    public static WechatBridge.State state(Context c) {
        WechatStore st = WechatStore.get(c);
        if (st.needsRebind()) return WechatBridge.State.NEEDS_REBIND;
        if (!st.bound()) return WechatBridge.State.UNBOUND;
        if (!st.enabled()) return WechatBridge.State.PAUSED;
        OrchestratorService s = OrchestratorService.get();
        if (s == null || s.wechat() == null) return WechatBridge.State.STOPPED;
        return s.wechat().state();
    }

    public static boolean bound(Context c) { return WechatStore.get(c).bound(); }
    public static boolean receiving(Context c) { return WechatStore.get(c).enabled(); }

    /** 开始扫码绑定；回调里拿二维码内容自己画（见 ui/WechatQrDialog）。返回的对象可 cancel()。 */
    public static WechatLogin startLogin(Context c, WechatLogin.Callback cb) {
        WechatLogin l = new WechatLogin(c, cb);
        l.start();
        return l;
    }

    /** 开/关「接收微信任务」。关掉立即停长轮询；开着且服务在跑就开始。 */
    public static void setReceiving(Context c, boolean on) {
        WechatStore st = WechatStore.get(c);
        if (on && !st.bound()) return;
        st.setEnabled(on);
        AppLog.i("微信通道：" + (on ? "开始" : "暂停") + "接收");
        restartIfRunning();
    }

    /** 解除绑定：停接收，删除本机保存的全部绑定信息（令牌、游标、context_token）。 */
    public static void unbind(Context c) {
        OrchestratorService s = OrchestratorService.get();
        if (s != null && s.wechat() != null) s.wechat().stop();
        WechatStore.get(c).clear();
        AppLog.i("微信通道：已解除绑定，本机绑定信息已删除");
        if (s != null && s.wechat() != null) s.wechat().restart();
    }

    private static void restartIfRunning() {
        OrchestratorService s = OrchestratorService.get();
        if (s != null && s.wechat() != null) s.wechat().restart();
    }
}
