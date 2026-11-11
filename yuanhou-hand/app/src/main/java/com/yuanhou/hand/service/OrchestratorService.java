package com.yuanhou.hand.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;

import com.yuanhou.hand.hand.HandExecutor;
import com.yuanhou.hand.hand.HandGattClient;
import com.yuanhou.hand.hand.HandProtocol;
import com.yuanhou.hand.hand.SuiteState;
import com.yuanhou.hand.hand.WifiHandClient;
import com.yuanhou.hand.lan.LanServer;
import com.yuanhou.hand.session.LocalTextChannel;
import com.yuanhou.hand.session.SipCallChannel;
import com.yuanhou.hand.session.SipConfig;
import com.yuanhou.hand.session.TaskSession;
import com.yuanhou.hand.ui.MainActivity;
import com.yuanhou.hand.util.AppLog;
import com.yuanhou.hand.util.Timing;
import com.yuanhou.hand.wechat.WechatBridge;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务：常驻通知显示两件套状态，承载「主屏控制」任务会话。
 * 「手」本阶段优先走 ESP32 的 Wi-Fi 透传（家里局域网 TCP 3333），蓝牙 GATT 保留为备用；两条都只把动作交给 ESP32 的 BLE HID。
 * 语音/SIP 已放下：SipCallChannel 的代码还在、能编译，但没填 SIP 账号时不启动 Liblinphone、不注册、不挡启动。
 * 0.1.9：服务里同时跑局域网指令通道（LanServer，Wi-Fi IPv4:3334，需口令），随服务起停；adb DebugReceiver 仍保留为 USB 备用。
 * 0.1.12：微信官方 Bot 通道（WechatBridge）也归这个服务：已绑定且开着「接收微信任务」时随服务起停，一个开关管局域网 + 手 + 微信。
 */
public final class OrchestratorService extends Service implements HandGattClient.Listener, TaskSession.Host {
    public static final String ACTION_STOP = "com.yuanhou.hand.STOP";
    public static final String ACTION_ESTOP = "com.yuanhou.hand.ESTOP";
    public static final String PREFS = "hand_prefs";
    private static final String CHANNEL = "suite";
    private static final int NOTIF_ID = 1;
    private static final long CHANNEL_SETUP_TIMEOUT_MS = 20000;
    /**
     * 定位移动每份报告最多 2 个计数（10 ms 一份 ≈ 200 计数/秒），低于本机指针加速阈值 500。
     * 真机实测：固件默认 4 时，从左上角移到 (540,1146) 实际落到约 (720,1520)，放大约 1.33 倍。
     * 这是为了点得准（识别稳定），不是拟人或规避检测。
     */
    public static final int DEFAULT_MAX_STEP = 2;
    /** 真机标定：max_step=2 时 1 计数 ≈ 1.053 px（cpp=1000 时 540,1334 落到 570,1403；cpp=950 落到 549,1356）。 */
    public static final int DEFAULT_CPP = 950;

    private static volatile OrchestratorService instance;
    private static final CopyOnWriteArrayList<Runnable> WATCHERS = new CopyOnWriteArrayList<>();

    public static OrchestratorService get() { return instance; }
    public static void watch(Runnable r) { WATCHERS.add(r); }
    public static void unwatch(Runnable r) { WATCHERS.remove(r); }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, OrchestratorService.class));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SuiteState suite = new SuiteState();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "hand-worker"));
    private HandGattClient client;
    private WifiHandClient wifi;
    private HandExecutor hand;
    private LocalTextChannel local;
    private SipCallChannel sip;
    private TaskSession session;
    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private String lastNotifText = "";
    private boolean stopping;
    private Runnable reconnectTask;
    private long connectedAt;
    private long lastSetupAt;
    private volatile boolean setupRunning;
    private int lastLed = -1;
    private ConnectivityManager.NetworkCallback wifiCb;
    private volatile boolean wifiLost;
    private LanServer lan;
    private int lanTicks;
    private WechatBridge wechat;

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "两件套状态", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("远手与 S3 Phone Hand 的连接状态");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        startForeground(NOTIF_ID, buildNotification());
        instance = this;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yuanhou:orchestrator");
        wake.setReferenceCounted(false);
        wake.acquire();

        client = new HandGattClient(this, this);
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        suite.wifiEnabled = sp.getBoolean("wifi_enabled", true);
        wifi = new WifiHandClient(suite,
                sp.getString("wifi_host", ""),
                sp.getInt("wifi_port", WifiHandClient.DEFAULT_PORT),
                sp.getString("wifi_last_good_host", ""),
                this::rememberWifiHost);
        hand = new HandExecutor(this, client, wifi, suite);
        local = new LocalTextChannel();
        sip = new SipCallChannel(this, sipHost);
        session = new TaskSession(this, local);
        // 0.1.13：首页「正在办」的步骤日志（只在内存）
        com.yuanhou.hand.session.TaskLog.get().attach(local, session::running);
        if (suite.wifiEnabled) wifi.start();
        // 语音/SIP 已放下：只有本人填了 SIP 账号才启动 Liblinphone
        if (SipConfig.load(this).complete()) sip.start();
        else AppLog.i("语音/SIP 已放下：没填 SIP 账号，不启动 Liblinphone、不注册");
        watchWifi();
        lan = new LanServer(this);
        lan.start();
        wechat = new WechatBridge(this);
        wechat.start();
        AppLog.i("前台服务启动");
        connect();
        main.postDelayed(tick, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            AppLog.i("从通知停止前台服务");
            stopSelf();
        } else if (ACTION_ESTOP.equals(a)) {
            emergencyStop();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        instance = null;
        main.removeCallbacksAndMessages(null);
        if (session != null) session.cancel();
        if (lan != null) lan.stop();
        if (wechat != null) wechat.stop();
        if (client != null) client.close();
        if (wifi != null) wifi.stop();
        worker.shutdownNow();
        if (sip != null) sip.shutdown();
        if (wifiCb != null) {
            try { ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(wifiCb); } catch (Exception ignored) {}
        }
        if (wake != null && wake.isHeld()) wake.release();
        AppLog.i("前台服务已停止");
        notifyWatchers();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- 对外 ----
    public SuiteState suite() { return suite; }
    public HandExecutor hand() { return hand; }
    public ExecutorService worker() { return worker; }
    public Context context() { return this; }
    public TaskSession session() { return session; }
    public LocalTextChannel local() { return local; }
    public SipCallChannel sip() { return sip; }
    public WifiHandClient wifi() { return wifi; }
    public LanServer lan() { return lan; }
    public WechatBridge wechat() { return wechat; }

    /** 局域网通道状态变了：刷新主界面（任意线程可调）。 */
    public void lanChanged() {
        main.post(this::notifyWatchers);
    }

    /** 设置页改了 Wi-Fi 透传主机/端口后调用，重新加载客户端；不涉及 Wi-Fi 密码或 SSID。 */
    public void reloadWifi() {
        if (wifi == null) return;
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        wifi.reload(sp.getString("wifi_host", ""),
                sp.getInt("wifi_port", WifiHandClient.DEFAULT_PORT),
                sp.getString("wifi_last_good_host", ""));
        AppLog.i("已按设置重新加载 Wi-Fi 透传客户端");
    }

    private void rememberWifiHost(String ipv4) {
        if (!WifiHandClient.isIpv4(ipv4)) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("wifi_last_good_host", ipv4).apply();
    }

    /** SIP 通道回调：两件套判定、状态刷新、来电接通/挂断时切换会话通道。 */
    private final SipCallChannel.Host sipHost = new SipCallChannel.Host() {
        @Override
        public String handNotReadyReason() {
            // 蓝牙已连 + 指令通道 + HID 键盘鼠标 + 心跳；SET_SCREEN 由看门狗随后补上
            return suite.linkReady() ? null : suite.notReadyReason();
        }

        @Override
        public void onSipChanged() {
            main.post(OrchestratorService.this::changed);
        }

        @Override
        public void onCallActive(boolean active) {
            if (session == null) return;
            // 有人在通话里：听和说都走通话；挂断后回到桌上文字（不出声）
            session.setChannel(active ? sip : local);
            AppLog.i("会话通道 → " + (active ? sip.name() : local.name()));
        }

        @Override
        public void onTranscript(String who, String text) {
            if (local != null) local.show(who, text);
        }
    };

    /** Wi-Fi 断了又回来：已配置账号则重注册 SIP（设计第 45 行）。 */
    private void watchWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        wifiCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                if (wifiLost) {
                    wifiLost = false;
                    sip.onNetworkBack();
                }
                if (lan != null) lan.checkNetwork();
                if (wechat != null) wechat.networkChanged();
            }

            @Override
            public void onLost(Network n) {
                wifiLost = true;
                AppLog.i("Wi-Fi 断开");
                if (lan != null) lan.checkNetwork();
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCb);
        } catch (Exception e) {
            AppLog.i("监听 Wi-Fi 失败：" + e.getMessage());
            wifiCb = null;
        }
    }

    public void emergencyStop() {
        AppLog.i("急停");
        if (session != null) session.cancel();
        if (hand != null) hand.emergencyStop();
    }

    public void reconnectNow() {
        AppLog.i("手动重连");
        client.close();
        suite.resetLink("手动重连");
        connect();
    }

    // ---- 连接 ----
    private void connect() {
        if (stopping) return;
        suite.connecting = true;
        suite.bluetoothOn = true;
        suite.bondedFound = true;
        suite.connectDetail = "正在连接";
        changed();
        client.connect();
    }

    private void scheduleReconnect() {
        if (stopping || reconnectTask != null) return;
        int d = Timing.bleReconnect();
        AppLog.i(d + "ms 后重连");
        reconnectTask = () -> {
            reconnectTask = null;
            if (!suite.gattConnected && !suite.connecting) connect();
        };
        main.postDelayed(reconnectTask, d);
    }

    @Override
    public void onLog(String line) {
        AppLog.i(line);
    }

    @Override
    public void onConnectionChanged(boolean connected, String detail) {
        if (connected) {
            suite.gattConnected = true;
            suite.connecting = false;
            suite.connectDetail = detail;
            connectedAt = SystemClock.elapsedRealtime();
        } else {
            suite.connecting = false;
            suite.resetLink(detail);
            if (detail != null && detail.contains("蓝牙未打开")) suite.bluetoothOn = false;
            if (detail != null && detail.contains("已配对列表里没有")) suite.bondedFound = false;
            hand.failPending();
            lastLed = -1;
            scheduleReconnect();
        }
        AppLog.i("连接: " + (connected ? "已连接" : "未连接") + " — " + detail);
        changed();
    }

    @Override
    public void onServices(boolean found, String info) {
        if (found) {
            suite.channelReady = true;
            suite.info = info;
            suite.connectDetail = "指令通道就绪";
            AppLog.i("自定义指令通道就绪 — " + info);
            setupScreenAsync();
        } else {
            AppLog.i("自定义指令通道不可用 — " + info);
            client.close();
            suite.resetLink(info);
            scheduleReconnect();
        }
        changed();
    }

    @Override
    public void onStatus(HandProtocol.Status status, boolean fromNotify) {
        HandProtocol.Status prev = suite.status;
        suite.status = status;
        suite.lastStatusAt = SystemClock.elapsedRealtime();
        if (prev == null || prev.flags != status.flags || prev.state != status.state) {
            AppLog.i("探头状态: " + status);
            changed();
        }
    }

    @Override
    public void onActionAck(int seq, int opcode, int result) {
        hand.onAck(seq, opcode, result);
    }

    private void setupScreenAsync() {
        if (setupRunning) return;
        setupRunning = true;
        lastSetupAt = SystemClock.elapsedRealtime();
        worker.submit(() -> {
            try {
                long until = SystemClock.elapsedRealtime() + 6000;
                while (!suite.linkReady() && SystemClock.elapsedRealtime() < until) Thread.sleep(200);
                if (!suite.linkReady()) {
                    suite.lastFault = suite.notReadyReason();
                    AppLog.i("暂不 SET_SCREEN：" + suite.lastFault);
                    return;
                }
                Point p = realSize();
                int cpp = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("cpp", DEFAULT_CPP);
                int maxStep = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("max_step", DEFAULT_MAX_STEP);
                hand.setScreen(p.x, p.y, cpp, maxStep);
                AppLog.i("SET_SCREEN 完成 " + p.x + "x" + p.y + (cpp == 0 ? "（cpp 用固件默认 1000）" : " cpp=" + cpp)
                        + " max_step=" + maxStep);
            } catch (HandExecutor.HandException e) {
                suite.lastFault = e.getMessage();
                AppLog.i("SET_SCREEN 失败: " + e.getMessage());
            } catch (InterruptedException ignored) {
            } finally {
                setupRunning = false;
                main.post(this::changed);
            }
        });
    }

    private Point realSize() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        d.getRealSize(p);   // 本机 1080x2400；不要用 heightPixels=2322
        return p;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopping) return;
            long now = SystemClock.elapsedRealtime();
            if (suite.gattConnected && suite.channelReady && !suite.heartbeatFresh() && !hand.isBusy()) {
                client.readStatus();
            }
            if (suite.gattConnected && !suite.channelReady && now - connectedAt > CHANNEL_SETUP_TIMEOUT_MS) {
                AppLog.i("指令通道 20 秒未就绪，断开重连");
                client.close();
                suite.resetLink("指令通道 20 秒未就绪");
                scheduleReconnect();
            }
            if (!suite.gattConnected && !suite.connecting) scheduleReconnect();
            if (suite.linkReady() && !suite.screenSet && !hand.isBusy() && now - lastSetupAt > 5000) {
                setupScreenAsync();
            }
            if (!suite.linkReady() && suite.screenSet && !suite.heartbeatFresh()) {
                // 掉心跳：要求重新 SET_SCREEN，避免带着旧状态继续操作
                suite.screenSet = false;
            }
            int led = suite.ready() ? 1 : 0;
            if (suite.channelReady && led != lastLed && !hand.isBusy()) {
                if (client.writeLed(led)) lastLed = led;
            }
            // 局域网通道：每 5 秒看一次 Wi-Fi IPv4 是否变了（变了重绑；端口被占时按原端口再试）
            if (lan != null && ++lanTicks % 5 == 0) lan.checkNetwork();
            changed();
            main.postDelayed(this, 1000);
        }
    };

    private void changed() {
        if (nm == null) return;
        String text = notifText();
        if (!text.equals(lastNotifText)) {
            lastNotifText = text;
            nm.notify(NOTIF_ID, buildNotification());
            AppLog.i("通知: " + suite.title() + " | " + text.replace("\n", " | "));
        }
        notifyWatchers();
    }

    private void notifyWatchers() {
        for (Runnable r : WATCHERS) main.post(r);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OrchestratorService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent estop = PendingIntent.getService(this, 2,
                new Intent(this, OrchestratorService.class).setAction(ACTION_ESTOP), PendingIntent.FLAG_IMMUTABLE);
        String text = notifText();
        String first = text.split("\n")[0];
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(suite.title())
                .setContentText(suite.ready() ? first : ("未就绪：" + nz(suite.notReadyReason())))
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "急停", estop).build())
                .addAction(new Notification.Action.Builder(null, "停止服务", stop).build())
                .build();
    }

    private String notifText() {
        return "主屏控制 · " + suite.lines() + "\n" + (sip == null ? "语音/SIP：已放下" : sip.line())
                + (wechat == null ? "" : "\n微信：" + wechat.state().label);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
