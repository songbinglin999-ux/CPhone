package com.yuanhou.hand.lan;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;

import com.yuanhou.hand.hand.SuiteState;
import com.yuanhou.hand.policy.ConfirmationPolicy;
import com.yuanhou.hand.screen.ReadonlyAccessibilityService;
import com.yuanhou.hand.screen.ScreenCapture;
import com.yuanhou.hand.screen.ScreenSnapshot;
import com.yuanhou.hand.service.OrchestratorService;
import com.yuanhou.hand.session.LocalTextChannel;
import com.yuanhou.hand.session.TaskSession;
import com.yuanhou.hand.util.AppLog;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 局域网指令通道（0.1.9）：前台服务里的一个极简 HTTP 服务，端口 3334，只监听手机自己的 Wi-Fi IPv4。
 * 让家里 Mac 上的 Goji 不插 USB 也能 /status、/say、/tree、/screenshot。
 *
 * - 只在家里局域网用：只绑 Wi-Fi 地址，只接受私有网段来源；不要做端口转发，不要暴露到公网。
 * - 每个请求（包括 /status）都要带口令（X-Yuanhou-Token 或 Authorization: Bearer），口令只存在本应用私有 prefs。
 * - /say 走和 adb DEBUG_SAY 完全相同的 LocalTextChannel.deliver → TaskSession；确认闸、付款不代办都不变。
 * - 没有文件读取、没有 shell、没有 systemCommand。端口被占时不换端口，只在日志和主界面说明。
 */
public final class LanServer {
    public static final String PREF_TOKEN = "lan_token";
    public static final String PREF_LAST_ADDR = "lan_last_addr";
    private static final int MAX_CONN = 4;
    private static final int READ_TIMEOUT_MS = 15000;

    private final OrchestratorService svc;
    private final ExecutorService ctl = Executors.newSingleThreadExecutor(r -> new Thread(r, "lan-ctl"));
    private final Semaphore slots = new Semaphore(MAX_CONN);
    private final ReentrantLock sayLock = new ReentrantLock();
    private final SecureRandom rnd = new SecureRandom();

    private volatile ServerSocket server;
    private volatile String listenAddr;
    private volatile String state = "未启动";
    private volatile String nsdName;
    private volatile boolean stopped;
    private volatile String token;
    private volatile int authFailures;
    private NsdManager.RegistrationListener nsdListener;
    private String lastLoggedState = "";

    public LanServer(OrchestratorService svc) {
        this.svc = svc;
    }

    // ---------------- 生命周期（前台服务调用） ----------------

    public void start() {
        stopped = false;
        ensureToken();
        ctl.submit(this::rebind);
    }

    /** Wi-Fi 变化或每隔几秒调一次：Wi-Fi IPv4 变了就重绑；端口被占时按原端口再试。 */
    public void checkNetwork() {
        if (stopped) return;
        try { ctl.submit(this::rebind); } catch (Exception ignored) {}
    }

    public void stop() {
        stopped = true;
        try {
            ctl.submit(() -> {
                closeServer();
                setState("已关闭（前台服务停止）");
            });
        } catch (Exception ignored) {}
        ctl.shutdown();
        try { ctl.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        closeServer();
    }

    // ---------------- 给界面 ----------------

    public String state() { return state; }
    public String listenAddr() { return listenAddr; }
    public int port() { return LanProtocol.PORT; }
    public String nsdName() { return nsdName; }
    public String token() { return token; }
    public boolean listening() { ServerSocket s = server; return s != null && !s.isClosed(); }

    /** 主界面「重新生成口令」：旧口令立即作废。日志里不写口令本身。 */
    public void regenerateToken() {
        String t = LanProtocol.newToken(rnd);
        prefs().edit().putString(PREF_TOKEN, t).apply();
        token = t;
        AppLog.i("局域网通道：口令已重新生成，旧口令作废");
        svc.lanChanged();
    }

    private void ensureToken() {
        String t = prefs().getString(PREF_TOKEN, "");
        if (!LanProtocol.tokenWellFormed(t)) {
            t = LanProtocol.newToken(rnd);
            prefs().edit().putString(PREF_TOKEN, t).apply();
            AppLog.i("局域网通道：第一次启动，已生成口令（只显示在主界面，不写日志）");
        }
        token = t;
    }

    private SharedPreferences prefs() {
        return svc.getSharedPreferences(OrchestratorService.PREFS, Context.MODE_PRIVATE);
    }

    private void setState(String s) {
        state = s;
        if (!s.equals(lastLoggedState)) {
            lastLoggedState = s;
            AppLog.i("局域网通道：" + s);
        }
        svc.lanChanged();
    }

    // ---------------- 绑定 ----------------

    private void rebind() {
        if (stopped) return;
        String ip = wifiIpv4(svc);
        if (ip == null) {
            if (server != null) closeServer();
            setState("等 Wi-Fi：手机现在没有 Wi-Fi IPv4，不监听");
            return;
        }
        ServerSocket cur = server;
        if (cur != null && !cur.isClosed() && ip.equals(listenAddr)) return;
        closeServer();
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName(ip), LanProtocol.PORT), 8);
        } catch (BindException e) {
            closeQuietly(ss);
            setState("端口 " + LanProtocol.PORT + " 被占用（" + e.getMessage() + "），局域网通道没起来；不换端口，稍后按原端口再试");
            return;
        } catch (IOException e) {
            closeQuietly(ss);
            setState("监听 " + ip + ":" + LanProtocol.PORT + " 失败：" + e.getMessage());
            return;
        }
        server = ss;
        listenAddr = ip;
        prefs().edit().putString(PREF_LAST_ADDR, ip + ":" + LanProtocol.PORT).apply();
        setState("监听中 " + ip + ":" + LanProtocol.PORT + "（只限家里局域网，需口令）");
        final ServerSocket accepting = ss;
        Thread t = new Thread(() -> acceptLoop(accepting), "lan-accept");
        t.setDaemon(true);
        t.start();
        registerNsd();
    }

    private void closeServer() {
        unregisterNsd();
        ServerSocket s = server;
        server = null;
        listenAddr = null;
        closeQuietly(s);
    }

    private static void closeQuietly(ServerSocket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    /** 手机自己的 Wi-Fi IPv4（只看 TRANSPORT_WIFI 网络）；没有返回 null。只用 ACCESS_NETWORK_STATE。 */
    @SuppressWarnings("deprecation")
    public static String wifiIpv4(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && LanProtocol.isPrivateIpv4(a.getHostAddress())) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            AppLog.i("局域网通道：读 Wi-Fi 地址失败 " + e.getMessage());
        }
        return null;
    }

    // ---------------- NSD（mDNS 广播，可选） ----------------

    private void registerNsd() {
        NsdManager nm = (NsdManager) svc.getSystemService(Context.NSD_SERVICE);
        if (nm == null) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(LanProtocol.NSD_NAME);
        info.setServiceType(LanProtocol.NSD_TYPE);
        info.setPort(LanProtocol.PORT);
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo i) {
                nsdName = i.getServiceName();
                AppLog.i("局域网通道：mDNS 已广播 " + nsdName + "." + LanProtocol.NSD_TYPE);
                svc.lanChanged();
            }
            @Override public void onRegistrationFailed(NsdServiceInfo i, int err) {
                nsdName = null;
                AppLog.i("局域网通道：mDNS 广播失败（" + err + "），请直接用主界面上的 IPv4:端口");
                svc.lanChanged();
            }
            @Override public void onServiceUnregistered(NsdServiceInfo i) { nsdName = null; }
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int err) {}
        };
        try {
            nm.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
            nsdListener = l;
        } catch (Exception e) {
            AppLog.i("局域网通道：mDNS 广播出错 " + e.getMessage() + "，请直接用 IPv4:端口");
        }
    }

    private void unregisterNsd() {
        NsdManager.RegistrationListener l = nsdListener;
        nsdListener = null;
        nsdName = null;
        if (l == null) return;
        try {
            NsdManager nm = (NsdManager) svc.getSystemService(Context.NSD_SERVICE);
            if (nm != null) nm.unregisterService(l);
        } catch (Exception ignored) {}
    }

    // ---------------- 连接 ----------------

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            Socket s;
            try {
                s = ss.accept();
            } catch (SocketException e) {
                break;   // 关闭
            } catch (IOException e) {
                AppLog.i("局域网通道：accept 出错 " + e.getMessage());
                continue;
            }
            if (!slots.tryAcquire()) {
                writeQuietly(s, LanProtocol.json(503, LanProtocol.error("busy", "同时的请求太多")));
                continue;
            }
            Thread t = new Thread(() -> {
                try { handle(s); } finally { slots.release(); }
            }, "lan-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void writeQuietly(Socket s, byte[] resp) {
        try {
            OutputStream o = s.getOutputStream();
            o.write(resp);
            o.flush();
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void handle(Socket s) {
        String from = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
        byte[] resp;
        try {
            if (!(s.getInetAddress() instanceof Inet4Address) || !LanProtocol.isPrivateIpv4(from)) {
                AppLog.i("局域网通道：拒绝非局域网来源 " + from);
                writeQuietly(s, LanProtocol.json(403, LanProtocol.error("forbidden", "只接受家里局域网")));
                return;
            }
            s.setSoTimeout(READ_TIMEOUT_MS);
            LanProtocol.Request r;
            try {
                r = LanProtocol.read(new BufferedInputStream(s.getInputStream()));
            } catch (LanProtocol.BadRequest e) {
                writeQuietly(s, LanProtocol.json(e.code, LanProtocol.error("bad_request", e.getMessage())));
                return;
            }
            if (!LanProtocol.tokenMatches(token, LanProtocol.presentedToken(r))) {
                authFailures++;
                AppLog.i("局域网通道：口令不对或没带，拒绝 " + r.method + " " + r.path + "（来自 " + from + "）");
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                writeQuietly(s, LanProtocol.json(401, LanProtocol.error("unauthorized", "需要 X-Yuanhou-Token")));
                return;
            }
            resp = route(r, from);
        } catch (IOException e) {
            resp = LanProtocol.json(400, LanProtocol.error("io", e.getMessage()));
        } catch (Exception e) {
            AppLog.i("局域网通道：处理请求出错 " + e);
            resp = LanProtocol.json(500, LanProtocol.error("internal", String.valueOf(e.getMessage())));
        }
        writeQuietly(s, resp);
    }

    private byte[] route(LanProtocol.Request r, String from) throws InterruptedException {
        switch (r.path) {
            case "/status":
                if (!"GET".equals(r.method)) return methodNotAllowed();
                return LanProtocol.json(200, statusJson());
            case "/say":
                if (!"POST".equals(r.method)) return methodNotAllowed();
                return say(r, from);
            case "/tree":
                if (!"GET".equals(r.method)) return methodNotAllowed();
                AppLog.i("局域网通道：读界面树（来自 " + from + "）");
                return LanProtocol.text(200, treeText(svc));
            case "/screenshot":
                if (!"GET".equals(r.method)) return methodNotAllowed();
                return screenshot(from);
            default:
                return LanProtocol.json(404, LanProtocol.error("not_found", "没有这个路径"));
        }
    }

    private static byte[] methodNotAllowed() {
        return LanProtocol.json(405, LanProtocol.error("method_not_allowed", "方法不对"));
    }

    // ---------------- /status ----------------

    private String statusJson() {
        String vn = "";
        long vc = 0;
        try {
            PackageInfo pi = svc.getPackageManager().getPackageInfo(svc.getPackageName(), 0);
            vn = pi.versionName;
            vc = pi.versionCode;
        } catch (Exception ignored) {}
        SuiteState st = svc.suite();
        TaskSession ts = svc.session();
        TaskSession.End end = ts == null ? null : ts.lastEnd();
        String a11ySetting = Settings.Secure.getString(svc.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String shortId = svc.getPackageName() + "/.screen.ReadonlyAccessibilityService";
        String fullId = svc.getPackageName() + "/" + ReadonlyAccessibilityService.class.getName();
        boolean a11yEnabled = a11ySetting != null && (a11ySetting.contains(shortId) || a11ySetting.contains(fullId));
        String wifiIp = wifiIpv4(svc);
        return new LanProtocol.Json()
                .put("ok", true)
                .put("versionName", vn)
                .put("versionCode", vc)
                .put("serviceRunning", OrchestratorService.get() != null)
                .put("listenAddress", listenAddr)
                .put("listenPort", LanProtocol.PORT)
                .put("lanState", state)
                .put("nsdName", nsdName)
                .put("nsdType", LanProtocol.NSD_TYPE)
                .put("wifiIpv4", wifiIp)
                .put("a11yRunning", ReadonlyAccessibilityService.isRunning())
                .put("a11yEnabledInSettings", a11yEnabled)
                .put("a11yServiceId", shortId)
                .put("foregroundPackage", ReadonlyAccessibilityService.foregroundPackage())
                .put("screenshotAuthorized", ScreenCapture.available())
                .put("suiteReady", st.ready())
                .put("suiteTitle", st.title())
                .put("notReadyReason", st.notReadyReason())
                .put("transport", st.transport())
                .put("wifiHandEnabled", st.wifiEnabled)
                .put("wifiHandHost", st.wifiHost)
                .put("wifiHandReady", st.wifiReady())
                .put("wifiHandDetail", st.wifiDetail)
                .put("sessionPhase", ts == null ? null : ts.phase().name())
                .put("sessionState", ts == null ? null : ts.stateText())
                .put("sessionRunning", ts != null && ts.running())
                .put("awaitingConfirm", ts != null && ts.awaitingConfirm())
                .put("lastOutcome", end == null ? null : end.name())
                .put("authFailures", authFailures)
                .toString();
    }

    // ---------------- /say ----------------

    /**
     * 和 adb DEBUG_SAY 同一条路：LocalTextChannel.deliver(text) → TaskSession。
     * 等这一件事办完（running 变 false）或者它新问了一次确认口令（AWAITING_CONFIRM，由调用方再 /say 口令），或者超时。
     */
    private byte[] say(LanProtocol.Request r, String from) throws InterruptedException {
        String text = LanProtocol.sayText(r);
        if (text == null || text.trim().isEmpty()) {
            return LanProtocol.json(400, LanProtocol.error("empty_text", "要说的话是空的；正文用纯文本或 {\"text\":\"读屏\"}"));
        }
        text = text.trim();
        if (text.length() > 2000) return LanProtocol.json(413, LanProtocol.error("too_long", "一句话最多 2000 字"));
        TaskSession ts = svc.session();
        LocalTextChannel local = svc.local();
        if (ts == null || local == null) return LanProtocol.json(503, LanProtocol.error("not_ready", "会话没准备好"));
        boolean cancelWord = ConfirmationPolicy.isCancelWord(text);

        // 「取消」类不排队：另一个 /say 正在等时也立刻交进去
        boolean locked = sayLock.tryLock();
        if (!locked && !cancelWord) {
            return LanProtocol.json(409, LanProtocol.error("busy", "上一句还在办；说「取消」可以停下"));
        }
        try {
            boolean wasRunning = ts.running();
            boolean wasAwaiting = ts.awaitingConfirm();
            if (wasRunning && !wasAwaiting && !cancelWord) {
                return LanProtocol.json(409, LanProtocol.error("busy", "上一件事还在办；说「取消」可以停下"));
            }
            int timeoutSec = LanProtocol.sayTimeoutSec(r);
            int confirm0 = ts.confirmSeq();
            int tree0 = ts.treeSeq();
            String report0 = ts.lastLocalReport();
            final List<String> spoken = new ArrayList<>();
            LocalTextChannel.Transcript tr = (who, line) -> {
                if ("远手".equals(who)) synchronized (spoken) { spoken.add(line); }
            };
            local.addTranscript(tr);
            String outcome;
            boolean timedOut = false;
            try {
                AppLog.i("局域网通道（" + from + "）交来一句：" + text);
                local.deliver(text);
                long until = System.currentTimeMillis() + timeoutSec * 1000L;
                while (true) {
                    if (!ts.running()) break;
                    if (ts.awaitingConfirm() && ts.confirmSeq() != confirm0) break;
                    if (System.currentTimeMillis() > until) { timedOut = true; break; }
                    Thread.sleep(100);
                }
                if (!ts.running()) {
                    // 空闲时的回答（「现在没有在办的事」等）是排到工作线程上的：等它说完
                    try { svc.worker().submit(() -> {}).get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
            } finally {
                local.removeTranscript(tr);
            }
            TaskSession.End end = ts.lastEnd();
            boolean startedTask = !wasRunning && !wasAwaiting && !cancelWord;
            if (timedOut) outcome = "TIMEOUT_STILL_RUNNING";
            else if (ts.running() && ts.awaitingConfirm()) outcome = "AWAITING_CONFIRM";
            else if (!wasRunning && cancelWord) outcome = "NO_TASK";
            else if (end != null) outcome = end.name();
            else outcome = "FINISHED";
            boolean ok = !timedOut && (end == null || end == TaskSession.End.DONE || "AWAITING_CONFIRM".equals(outcome)
                    || ("CANCELLED".equals(outcome) && cancelWord));
            if ("NO_TASK".equals(outcome)) ok = true;
            List<String> lines;
            synchronized (spoken) { lines = new ArrayList<>(spoken); }
            String report = ts.lastLocalReport();
            LanProtocol.Json j = new LanProtocol.Json()
                    .put("ok", ok)
                    .put("outcome", outcome)
                    .put("outcomeLabel", end == null ? null : end.label)
                    .put("startedNewTask", startedTask)
                    .put("awaitingConfirm", ts.awaitingConfirm())
                    .put("phase", ts.phase().name())
                    .put("state", ts.stateText())
                    .putArray("spoken", lines)
                    .put("tree", ts.treeSeq() != tree0 ? ts.lastTree() : null)
                    .put("report", report != null && !report.equals(report0) ? report : null)
                    .put("timeoutSec", timeoutSec);
            AppLog.i("局域网通道：这句办完 → " + outcome);
            return LanProtocol.json(200, j.toString());
        } finally {
            if (locked) sayLock.unlock();
        }
    }

    // ---------------- /tree（与 DebugReceiver DEBUG_TREE 同样的内容，同样写 tree-last.txt） ----------------

    public static String treeText(Context c) {
        ScreenSnapshot snap = ReadonlyAccessibilityService.snapshot();
        String body = snap == null
                ? "读不到界面树（只读无障碍" + (ReadonlyAccessibilityService.isRunning() ? "在运行但没有活动窗口" : "没在运行") + "）\n"
                : snap.summary(12) + "\n指纹 " + Long.toHexString(snap.fingerprint()) + "\n" + snap.toModelText(150);
        AppLog.writeFile(c, "tree-last.txt", body);
        return body;
    }

    // ---------------- /screenshot：只用本次运行本人已同意的 MediaProjection；没授权 503，不从局域网弹授权 ----------------

    private byte[] screenshot(String from) {
        if (!ScreenCapture.available()) {
            return LanProtocol.json(503, LanProtocol.error("screenshot_not_authorized",
                    "本次运行没有截图授权（MediaProjection）。只能本人在手机上点「申请本次运行的截图授权」同意；局域网请求不会弹授权。可以先用 /tree。"));
        }
        DisplayManager dm = (DisplayManager) svc.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        d.getRealSize(p);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        Bitmap b = ScreenCapture.capture(svc, p.x, p.y, m.densityDpi);
        if (b == null) {
            return LanProtocol.json(503, LanProtocol.error("capture_failed", "截图失败（授权可能已结束）"));
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bos);
        AppLog.i("局域网通道：截图 " + p.x + "x" + p.y + "（来自 " + from + "）");
        return LanProtocol.response(200, "image/png", bos.toByteArray());
    }
}
