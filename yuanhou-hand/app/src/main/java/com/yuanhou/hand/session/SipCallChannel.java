package com.yuanhou.hand.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.yuanhou.hand.util.AppLog;
import com.yuanhou.hand.util.Timing;

import org.linphone.core.Account;
import org.linphone.core.AccountParams;
import org.linphone.core.Address;
import org.linphone.core.AuthInfo;
import org.linphone.core.Call;
import org.linphone.core.CallParams;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.ErrorInfo;
import org.linphone.core.Factory;
import org.linphone.core.LogLevel;
import org.linphone.core.PayloadType;
import org.linphone.core.Player;
import org.linphone.core.PlayerListener;
import org.linphone.core.Reason;
import org.linphone.core.RegistrationState;
import org.linphone.core.TransportType;
import org.linphone.core.Transports;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 远手自己的 SIP 用户代理（内嵌 Liblinphone 5.5.39）。对炳霖的主语音通路：他开车打进来，在通话里听和说。
 *
 * 音频走法（不碰房间麦克风和扬声器）：
 * - Core 设成「用文件代替声卡」（setUseFiles）：对方的声音写进应用私有目录的 WAV → CallTranscriber 边写边听写；
 *   上行默认送一段静音文件。
 * - 要说的话：CallVoice 只合成到文件 → 通话播放器（Call.getPlayer）在同一通通话里播放给对方。
 * - 不响铃（setRing(null)），手机本身不出声。
 *
 * 来电：先看两件套（蓝牙 + 指令通道 + HID 键盘鼠标 + 心跳）。就绪才进入办事；不就绪也先接通，说明「两件套不完整」再挂断。
 * 接通后、问候前等 300–700 ms 再确认一次 HID 还在。
 * 账号只用本人在远手设置里填的；没填就不注册，状态写「SIP 账号未配置」，不算故障。
 */
public final class SipCallChannel implements CallChannel {

    public interface Host {
        /** 两件套（蓝牙 + 指令通道 + HID 键盘鼠标 + 心跳）未就绪的原因；就绪返回 null。 */
        String handNotReadyReason();
        /** 注册/通话状态变了（刷新通知和界面）。 */
        void onSipChanged();
        /** 有人在通话里、可以办事（true）/ 通话结束（false）。 */
        void onCallActive(boolean active);
        /** 通话里的一句话（显示到界面对话框）。 */
        void onTranscript(String who, String text);
    }

    private final Context app;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CallVoice voice;
    private final Object speakLock = new Object();
    private volatile Listener listener;

    private Core core;
    private Account account;
    private volatile String sdkStatus = "Liblinphone 未启动";
    private volatile boolean sdkOk;
    private volatile String regStatus = "SIP 账号未配置";
    private volatile boolean registered;
    private volatile String callStatus = "空闲";

    // 当前通话（只在主线程改）
    private volatile Call current;
    private volatile boolean streams;      // 已接通、媒体在跑
    private volatile boolean refuse;       // 两件套不完整：说明后挂断
    private volatile boolean inCall;       // 有人在通话里，可以办事
    private volatile int streamRate = 8000;
    private File recFile;
    private CallTranscriber stt;
    private File silence;

    public SipCallChannel(Context c, Host host) {
        app = c.getApplicationContext();
        this.host = host;
        voice = new CallVoice(app);
    }

    // ---------------- 状态 ----------------
    public boolean sdkAvailable() { return sdkOk; }
    public boolean registered() { return registered; }
    public boolean inCall() { return inCall; }
    public String sdkStatus() { return sdkStatus; }
    public String regStatus() { return regStatus; }
    public String callStatus() { return callStatus; }
    public String voiceStatus() { return voice.status(); }

    /** 通知/主界面用的一行：语音通路状态。 */
    public String line() {
        if (core == null && !sdkOk && !started) return "语音/SIP：已放下（本阶段不用；没填账号不启动）";
        if (!sdkOk) return "语音通路：" + sdkStatus;
        return "语音通路（SIP）：" + regStatus + "；" + callStatus;
    }

    // ---------------- 启动 / 注册 ----------------

    /** 主线程调用：建 Core、按 SipConfig 注册（没填就不注册）。 */
    private boolean started;

    public void start() {
        if (core != null) return;
        started = true;
        try {
            Factory f = Factory.instance();
            f.setDebugMode(false, "YuanHandSip");
            f.getLoggingService().setLogLevel(LogLevel.Warning);
            silence = makeSilence();
            // 配置文件传 null：不落地 linphonerc，账号密码只在远手自己的私有 SharedPreferences 里
            core = f.createCore(null, null, app);
            core.setUserAgent("YuanHand", "0.1.2");
            core.setUseFiles(true);
            if (silence != null) core.setPlayFile(silence.getAbsolutePath());
            core.setRing(null);
            core.setRingback(null);
            core.setNativeRingingEnabled(false);
            core.setRingDuringIncomingEarlyMedia(false);
            core.setEchoCancellationEnabled(false);
            core.setVideoCaptureEnabled(false);
            core.setVideoDisplayEnabled(false);
            core.setMaxCalls(1);
            core.setKeepAliveEnabled(true);
            // 同一台机上还装着 Linphone：本地 SIP/RTP 端口随机，避免和它抢 5060/7078
            Transports t = core.getTransports();
            t.setUdpPort(-1);
            t.setTcpPort(-1);
            t.setTlsPort(-1);
            core.setTransports(t);
            core.setAudioPort(-1);
            core.addListener(coreListener);
            int r = core.start();
            sdkOk = r == 0;
            sdkStatus = sdkOk ? ("Liblinphone " + core.getVersion() + " 已启动") : ("Liblinphone 启动失败（" + r + "）");
            AppLog.i(sdkStatus);
        } catch (Throwable e) {
            sdkOk = false;
            core = null;
            sdkStatus = "Liblinphone 加载失败：" + e.getClass().getSimpleName() + " " + e.getMessage();
            AppLog.i(sdkStatus);
        }
        CallTranscriber.preload(app);
        applyAccount();
    }

    /** 设置页保存后调用。 */
    public void reload() {
        main.post(() -> {
            if (core == null && !started) {
                if (SipConfig.load(app).complete()) start();
                else host.onSipChanged();
                return;
            }
            applyAccount();
        });
    }

    /** Wi-Fi 恢复后：已配置则重注册。 */
    public void onNetworkBack() {
        main.post(() -> {
            if (core == null || account == null) return;
            AppLog.i("Wi-Fi 恢复，重注册 SIP");
            core.refreshRegisters();
        });
    }

    private void applyAccount() {
        if (core == null) {
            regStatus = SipConfig.load(app).complete() ? "Liblinphone 没起来，无法注册" : "SIP 账号未配置";
            host.onSipChanged();
            return;
        }
        core.clearAccounts();
        core.clearAllAuthInfo();
        account = null;
        registered = false;
        SipConfig cfg = SipConfig.load(app);
        if (!cfg.complete()) {
            regStatus = "SIP 账号未配置（在设置里填你自己的账号）";
            AppLog.i("SIP 账号未配置，不注册");
            host.onSipChanged();
            return;
        }
        Factory f = Factory.instance();
        String domain = SipConfig.cleanDomain(cfg.domain);
        String user = cfg.username.trim();
        Address identity = f.createAddress("sip:" + user + "@" + domain);
        Address server = f.createAddress("sip:" + domain);
        if (identity == null || server == null) {
            regStatus = "SIP 注册失败：域名或用户名格式不对";
            AppLog.i(regStatus);
            host.onSipChanged();
            return;
        }
        TransportType tt = "tls".equals(cfg.transport) ? TransportType.Tls
                : "tcp".equals(cfg.transport) ? TransportType.Tcp : TransportType.Udp;
        server.setTransport(tt);
        AuthInfo ai = f.createAuthInfo(user, null, cfg.password, null, null, identity.getDomain());
        core.addAuthInfo(ai);
        AccountParams p = core.createAccountParams();
        p.setIdentityAddress(identity);
        p.setServerAddress(server);
        p.setRegisterEnabled(true);
        account = core.createAccount(p);
        if (account == null || core.addAccount(account) != 0) {
            regStatus = "SIP 注册失败：Liblinphone 不接受这个账号配置";
            AppLog.i(regStatus);
            account = null;
            host.onSipChanged();
            return;
        }
        core.setDefaultAccount(account);
        regStatus = "SIP 正在注册 " + cfg.who();
        AppLog.i(regStatus);   // who() 不含密码
        host.onSipChanged();
    }

    public void shutdown() {
        main.post(() -> {
            if (core != null) {
                try {
                    if (current != null) current.terminate();
                    core.removeListener(coreListener);
                    core.stop();
                } catch (Throwable ignored) {}
                core = null;
            }
            stopStt();
            voice.shutdown();
        });
    }

    private final CoreListenerStub coreListener = new CoreListenerStub() {
        @Override
        public void onAccountRegistrationStateChanged(Core c, Account a, RegistrationState st, String msg) {
            if (a != account) return;
            String who = SipConfig.load(app).who();
            registered = st == RegistrationState.Ok;
            if (st == RegistrationState.Ok) regStatus = "SIP 已注册 " + who;
            else if (st == RegistrationState.Progress || st == RegistrationState.Refreshing) regStatus = "SIP 正在注册 " + who;
            else if (st == RegistrationState.Failed) regStatus = "SIP 注册失败：" + reason(a.getErrorInfo(), msg);
            else if (st == RegistrationState.Cleared) regStatus = "SIP 已注销";
            else regStatus = "SIP 未注册";
            AppLog.i(regStatus);
            host.onSipChanged();
        }

        @Override
        public void onNetworkReachable(Core c, boolean reachable) {
            AppLog.i("SIP 网络" + (reachable ? "可达" : "不可达"));
        }

        @Override
        public void onCallStateChanged(Core c, Call call, Call.State st, String msg) {
            onCallState(call, st, msg);
        }
    };

    private static String reason(ErrorInfo ei, String msg) {
        String phrase = ei == null ? null : ei.getPhrase();
        int code = ei == null ? 0 : ei.getProtocolCode();
        String r = (phrase != null && !phrase.isEmpty()) ? phrase : (msg == null || msg.isEmpty() ? "未知原因" : msg);
        return code > 0 ? (r + "（" + code + "）") : r;
    }

    // ---------------- 通话 ----------------

    private void onCallState(Call call, Call.State st, String msg) {
        if (st == Call.State.IncomingReceived) {
            if (current != null && current != call) {
                AppLog.i("又来一通电话，当前已在通话，回忙");
                call.decline(Reason.Busy);
                return;
            }
            current = call;
            streams = false;
            inCall = false;
            String who = remote(call);
            String why = host.handNotReadyReason();
            refuse = why != null;
            callStatus = "来电 " + who;
            AppLog.i("SIP 来电 " + who + (refuse ? "；两件套不完整：" + why + "，接通后说明再挂断" : "；两件套就绪，接听"));
            recFile = new File(app.getCacheDir(), "call-down-" + System.currentTimeMillis() + ".wav");
            //noinspection ResultOfMethodCallIgnored
            recFile.delete();
            core.setRecordFile(recFile.getAbsolutePath());
            main.post(() -> {
                if (current != call) return;
                CallParams p = core.createCallParams(call);
                if (p != null) p.setVideoEnabled(false);
                int r = p == null ? call.accept() : call.acceptWithParams(p);
                if (r != 0) AppLog.i("接听失败（" + r + "）");
            });
            host.onSipChanged();
            return;
        }
        if (call != current) return;
        if (st == Call.State.StreamsRunning && !streams) {
            streams = true;
            streamRate = guessRate(call);
            callStatus = "通话中 " + remote(call);
            AppLog.i("SIP 通话接通，编解码 " + codec(call) + "，按 " + streamRate + " Hz 合成/听写");
            host.onSipChanged();
            new Thread(this::afterConnected, "call-greet").start();
            return;
        }
        if (st == Call.State.End || st == Call.State.Error || st == Call.State.Released) {
            if (st == Call.State.Error) AppLog.i("SIP 通话出错：" + reason(call.getErrorInfo(), msg));
            endCall();
        }
    }

    /** 接通后：等 300–700 ms，再确认 HID 两件套，然后问候或说明后挂断。工作线程。 */
    private void afterConnected() {
        try {
            Thread.sleep(Timing.uniform(300, 700));
        } catch (InterruptedException e) {
            return;
        }
        if (current == null) return;
        String why = host.handNotReadyReason();
        if (refuse || why != null) {
            String r = why != null ? why : "来电时两件套未就绪";
            AppLog.i("两件套不完整（" + r + "），说明后挂断");
            speakInCall("两件套不完整：" + r + "。这次不能操作手机，先挂断。");
            hangup();
            return;
        }
        if (!CallTranscriber.modelReady()) {
            AppLog.i("听写模型没就绪（" + CallTranscriber.modelStatus() + "），说明后挂断");
            speakInCall("远手的听写模型没装好，听不见你说话，先挂断。");
            hangup();
            return;
        }
        File f = recFile;
        stt = new CallTranscriber(f, streamRate, text -> {
            host.onTranscript("你（通话）", text);
            Listener l = listener;
            if (l != null) l.onUtterance(text);
        });
        stt.start();
        inCall = true;
        host.onCallActive(true);
        host.onSipChanged();
        speak("远手在，两件套就绪。请说要办的事，随时可以说取消。");
    }

    private void endCall() {
        Call was = current;
        if (was == null) return;
        current = null;
        boolean wasActive = inCall;
        inCall = false;
        streams = false;
        refuse = false;
        stopStt();
        callStatus = "空闲";
        AppLog.i("SIP 通话结束");
        if (wasActive) {
            Listener l = listener;
            if (l != null) l.onCallEnded();
            host.onCallActive(false);
        }
        File f = recFile;
        recFile = null;
        if (f != null) //noinspection ResultOfMethodCallIgnored
            f.delete();   // 通话录音只用于实时听写，挂断即删
        voice.cleanup();
        host.onSipChanged();
    }

    private void stopStt() {
        CallTranscriber s = stt;
        stt = null;
        if (s != null) s.stop();
    }

    public void hangup() {
        main.post(() -> {
            Call c = current;
            if (c != null) c.terminate();
        });
    }

    private static String remote(Call call) {
        try {
            Address a = call.getRemoteAddress();
            return a == null ? "（未知号码）" : a.asStringUriOnly();
        } catch (Throwable e) {
            return "（未知号码）";
        }
    }

    private static String codec(Call call) {
        try {
            PayloadType pt = call.getCurrentParams().getUsedAudioPayloadType();
            return pt == null ? "?" : pt.getMimeType() + "/" + pt.getClockRate();
        } catch (Throwable e) {
            return "?";
        }
    }

    private static int guessRate(Call call) {
        try {
            PayloadType pt = call.getCurrentParams().getUsedAudioPayloadType();
            if (pt == null) return 8000;
            if ("G722".equalsIgnoreCase(pt.getMimeType())) return 16000; // G.722 时钟写 8000，实际 16 kHz
            int r = pt.getClockRate();
            return r > 0 ? r : 8000;
        } catch (Throwable e) {
            return 8000;
        }
    }

    // ---------------- CallChannel ----------------

    @Override public String name() { return "SIP 通话"; }

    /** 有人在通话里、两件套就绪、听写在跑。 */
    @Override public boolean active() { return inCall && current != null; }

    @Override public void setListener(Listener l) { listener = l; }

    /** 没有通话就不出声（只记日志）；有通话就注入同一通通话的上行。 */
    @Override
    public void speak(String text) {
        if (!active()) {
            AppLog.i("[没有通话，不出声] " + text);
            host.onTranscript("远手（未说出：没有通话）", text);
            return;
        }
        speakInCall(text);
    }

    /** 合成 → 在当前通话里播放，阻塞到播完。工作线程调用。 */
    private void speakInCall(String text) {
        synchronized (speakLock) {
            Call c = current;
            if (c == null || !streams) {
                AppLog.i("[通话已断，没说出] " + text);
                return;
            }
            AppLog.i("远手（通话）：" + text);
            host.onTranscript("远手（通话）", text);
            File wav = voice.synthesize(text, streamRate);
            if (wav == null) {
                AppLog.i("这句没在通话里说出：合成失败（" + voice.status() + "）");
                return;
            }
            CallTranscriber s = stt;
            if (s != null) s.setMuted(true, 0);
            final Player[] player = {null};
            final int[] rc = {-1};
            final CountDownLatch eof = new CountDownLatch(1);
            final PlayerListener pl = p -> eof.countDown();
            CountDownLatch opened = new CountDownLatch(1);
            main.post(() -> {
                try {
                    if (current != c) return;
                    Player p = c.getPlayer();
                    if (p == null) return;
                    player[0] = p;
                    p.addListener(pl);
                    rc[0] = p.open(wav.getAbsolutePath());
                    if (rc[0] == 0) rc[0] = p.start();
                } catch (Throwable e) {
                    AppLog.i("通话播放器出错：" + e.getMessage());
                } finally {
                    opened.countDown();
                }
            });
            try {
                opened.await(3, TimeUnit.SECONDS);
                if (rc[0] != 0) {
                    AppLog.i("这句没在通话里说出：通话播放器打不开（" + rc[0] + "）");
                } else {
                    long ms = (wav.length() - 44) * 1000L / (2L * streamRate);
                    if (!eof.await(Math.min(60000, ms + 1500), TimeUnit.MILLISECONDS)) {
                        AppLog.i("通话播放没收到结束回调，按时长结束");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                main.post(() -> {
                    Player p = player[0];
                    if (p != null) {
                        try { p.removeListener(pl); p.close(); } catch (Throwable ignored) {}
                    }
                });
                if (s != null) s.setMuted(false, 400);
                //noinspection ResultOfMethodCallIgnored
                wav.delete();
            }
            try {
                Thread.sleep(Timing.betweenSpeech());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 上行默认送的静音（2 秒，16 kHz）。 */
    private File makeSilence() {
        File f = new File(app.getFilesDir(), "silence-16k.wav");
        if (f.length() > 44) return f;
        try {
            CallVoice.Wav.writeMono16(f, new short[32000], 16000);
            return f;
        } catch (IOException e) {
            AppLog.i("静音文件写入失败：" + e.getMessage());
            return null;
        }
    }
}
