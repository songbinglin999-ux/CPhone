package com.yuanhou.hand.wechat;

import android.content.Context;

import com.yuanhou.hand.service.OrchestratorService;
import com.yuanhou.hand.util.AppLog;

import java.net.SocketTimeoutException;

/**
 * 扫码绑定（Mika WechatRuntime.startLogin / awaitLogin）：get_bot_qrcode 拿二维码内容 → 界面本机画成二维码 →
 * 本人用随身微信扫码确认 → get_qrcode_status 轮询到 confirmed → 加密保存账号，开启接收；服务在跑就立即开始长轮询。
 * 二维码内容只用来画图，不写日志；拿到的令牌只交给 WechatStore。
 */
public final class WechatLogin {
    public interface Callback {
        /** 界面线程以外调用：拿到要画成二维码的内容。 */
        void onQr(String content);
        /** 进度：等待扫码 / 已扫码，请在微信里确认。 */
        void onStatus(String text);
        /** 结束：ok=true 绑定成功；否则 message 是给人看的原因（不含机密）。 */
        void onDone(boolean ok, String message);
    }

    /** 最多等这么久没扫完就算过期。 */
    private static final long MAX_WAIT_MS = 8 * 60_000L;

    private final Context ctx;
    private final Callback cb;
    private final WechatApi api;
    private volatile boolean cancelled;
    private Thread thread;

    public WechatLogin(Context c, Callback cb) {
        this.ctx = c.getApplicationContext();
        this.cb = cb;
        this.api = new WechatApi(new WechatApi.UrlTransport(), WechatBridge.versionName(ctx));
    }

    public void start() {
        thread = new Thread(this::run, "wechat-login");
        thread.setDaemon(true);
        thread.start();
    }

    public void cancel() {
        cancelled = true;
        Thread t = thread;
        if (t != null) t.interrupt();
        Thread c = new Thread(api::cancelAll, "wechat-login-cancel");
        c.setDaemon(true);
        c.start();
    }

    private void run() {
        try {
            WechatApi.QrStart q = api.startLogin();
            if (cancelled) return;
            if (WechatApi.blank(q.qrcode) || WechatApi.blank(q.qrContent)) {
                cb.onDone(false, "没拿到二维码，请稍后重试。");
                return;
            }
            AppLog.i("微信绑定：已获取二维码，等本人扫码");
            cb.onQr(q.qrContent);
            cb.onStatus("等待扫码…");
            long until = System.currentTimeMillis() + MAX_WAIT_MS;
            String last = "";
            while (!cancelled && System.currentTimeMillis() < until) {
                WechatApi.QrStatus st;
                try {
                    st = api.pollLogin(q.qrcode);
                } catch (SocketTimeoutException e) {
                    continue;   // 服务端长挂起超时：接着等
                }
                if (cancelled) return;
                WechatApi.Account a = st.account();
                if (a != null) {
                    WechatStore store = WechatStore.get(ctx);
                    try {
                        store.saveAccount(a);
                    } catch (IllegalStateException e) {
                        cb.onDone(false, "绑定信息没能加密保存（密钥库不可用），请重试。");
                        return;
                    }
                    AppLog.i("微信绑定：成功（只收本人私聊文字）");
                    OrchestratorService svc = OrchestratorService.get();
                    if (svc != null && svc.wechat() != null) svc.wechat().restart();
                    cb.onDone(true, svc == null ? "已绑定。点「启动服务」后开始接收微信任务。" : "已绑定，开始接收微信任务。");
                    return;
                }
                if ("expired".equals(st.status)) {
                    cb.onDone(false, "二维码已过期，请重新扫码绑定。");
                    return;
                }
                if ("confirmed".equals(st.status)) {
                    cb.onDone(false, "微信返回的绑定信息不完整，请重试。");
                    return;
                }
                if (!st.status.equals(last)) {
                    last = st.status;
                    if (!"wait".equals(st.status)) cb.onStatus("已扫码，请在微信里点确认…");
                }
                Thread.sleep("wait".equals(st.status) ? 300 : 1000);
            }
            if (!cancelled) cb.onDone(false, "等太久没扫码，请重新扫码绑定。");
        } catch (InterruptedException e) {
            // 取消
        } catch (WechatApi.HttpException e) {
            if (!cancelled) cb.onDone(false, "微信接口返回 HTTP " + e.httpCode + "，请稍后重试。");
        } catch (WechatApi.ApiException e) {
            if (!cancelled) cb.onDone(false, "微信接口报错（ret " + e.code + "），请稍后重试。");
        } catch (Exception e) {
            if (!cancelled) cb.onDone(false, "连不上微信接口（" + e.getClass().getSimpleName() + "），请检查网络后重试。");
        }
    }
}
