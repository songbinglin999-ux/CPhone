package com.yuanhou.hand.hand;

import android.os.SystemClock;

import com.yuanhou.hand.util.AppLog;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 「手」的 Wi-Fi 透传通道（固件 1.1.0+）：手机经家里局域网 TCP 把和 GATT Action 完全相同的动作包发给 ESP32，
 * ESP32 再用 BLE HID 在主屏上执行。
 *
 * 帧（小端）：请求 [u16 len][opcode][seq][payload]；回复 [u16 len][seq][result][opcode][phase][extra]。
 * phase 0 是提交结果，ACCEPTED(8) 之后还会有 phase 1 的最终结果。opcode 0x40 只在 TCP 上有，回 12 字节状态。
 *
 * 一次一个动作、等到最终结果再断开：每个请求单独建一条短连接（固件同一时间只服务一个 TCP 客户端，
 * 不长期占着，Mac 上的 wifi_hand.py 也还能用）。动作执行中断开连接，固件会自动 CANCEL——急停就是这么做的。
 * 这里没有、也不需要任何 Wi-Fi 凭据（凭据在板子 NVS 里）。没有加密和认证，只在自己家的局域网里用。
 */
public final class WifiHandClient {
    public static final int DEFAULT_PORT = 3333;
    /** 默认用 mDNS 名，不再写死曾经的局域网 IP（DHCP 会变，而且那个地址在真机上已变成手机自己的 wlan0）。 */
    public static final String MDNS_HOST = "s3-phone-hand.local";
    public static final String DEFAULT_HOST = MDNS_HOST;
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}$");
    public static final int OP_TCP_STATUS = 0x40;
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int STATUS_TIMEOUT_MS = 1500;
    private static final long POLL_MS = 1500;

    /** TCP 连上且对端是 IPv4 时通知上层（由 OrchestratorService 写入 prefs），客户端自己不持有 Context。 */
    public interface LastGoodHostSink {
        void remember(String ipv4);
    }

    public static final class Reply {
        public final int seq, result, opcode, phase;
        public final byte[] extra;
        Reply(int seq, int result, int opcode, int phase, byte[] extra) {
            this.seq = seq; this.result = result; this.opcode = opcode; this.phase = phase; this.extra = extra;
        }
    }

    private final SuiteState suite;
    private final LastGoodHostSink sink;
    private final ReentrantLock io = new ReentrantLock();
    private volatile Socket active;
    private volatile String host;
    private volatile int port;
    private volatile String lastGoodHost;
    private volatile boolean closed;
    private Thread poller;
    private int statusSeq = 1;

    public WifiHandClient(SuiteState suite, String host, int port, String lastGoodHost, LastGoodHostSink sink) {
        this.suite = suite;
        this.sink = sink;
        apply(host, port, lastGoodHost);
    }

    /** 设置页改了主机/端口后调用：先停再按新配置启动（若原来在跑）。 */
    public synchronized void reload(String host, int port, String lastGoodHost) {
        boolean running = poller != null && !closed;
        stop();
        apply(host, port, lastGoodHost);
        if (running) start();
    }

    private void apply(String host, int port, String lastGoodHost) {
        this.host = host == null || host.trim().isEmpty() ? DEFAULT_HOST : host.trim();
        this.port = port <= 0 ? DEFAULT_PORT : port;
        this.lastGoodHost = lastGoodHost == null ? "" : lastGoodHost.trim();
        suite.wifiHost = this.host + ":" + this.port;
    }

    public String host() { return host; }

    /** 后台每 1.5 秒读一次状态当心跳；有动作在途时跳过（动作的回复本身就算心跳）。 */
    public synchronized void start() {
        if (poller != null) return;
        closed = false;
        poller = new Thread(() -> {
            Thread self = Thread.currentThread();
            int fails = 0;
            // 用 self 判断：reload 时 stop 会把 poller 置空再拉起新线程，避免旧循环在 closed 被复位后又跑起来
            while (!closed && poller == self) {
                if (io.tryLock()) {
                    try {
                        HandProtocol.Status st = statusLocked();
                        suite.onWifiStatus(st);
                        fails = 0;
                    } catch (Exception e) {
                        fails++;
                        suite.onWifiFail(describe(e));
                        if (fails == 3) AppLog.i("Wi-Fi 透传连不上 " + host + "：" + describe(e));
                    } finally {
                        io.unlock();
                    }
                }
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { break; }
            }
        }, "wifi-hand-poll");
        poller.setDaemon(true);
        poller.start();
        AppLog.i("Wi-Fi 透传：开始连 " + host + ":" + port + "（一次一个动作，短连接）");
    }

    public synchronized void stop() {
        closed = true;
        abort();
        if (poller != null) poller.interrupt();
        poller = null;
    }

    public boolean busy() { return io.isLocked(); }

    /** 急停：关掉在途连接，固件会在「动作执行中客户端断开」时自动 CANCEL。 */
    public void abort() {
        Socket s = active;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
            AppLog.i("Wi-Fi 透传：急停，已断开在途连接（固件自动 CANCEL）");
        }
    }

    private HandProtocol.Status statusLocked() throws IOException {
        int seq;
        synchronized (this) {
            seq = 200 + (statusSeq++ % 50);   // 状态查询用 200..249，和动作 seq 1..250 偶有重叠也无妨：每次都是新连接
        }
        Reply r = roundTrip(new byte[]{(byte) OP_TCP_STATUS, (byte) seq}, STATUS_TIMEOUT_MS, false);
        if (r.result != HandProtocol.RES_OK || r.extra == null || r.extra.length < 12) {
            throw new IOException("状态回复异常 result=" + HandProtocol.resultName(r.result));
        }
        return new HandProtocol.Status(r.extra);
    }

    /**
     * 发一个动作包并等到最终结果（阻塞，工作线程调用）。返回最终 result。
     * 网络错误、超时抛 IOException；不会改走 GATT 或无障碍重试，由上层把这一步记为失败。
     */
    public int exchange(byte[] pkt, int timeoutMs) throws IOException, InterruptedException {
        if (!io.tryLock(4000, TimeUnit.MILLISECONDS)) throw new IOException("Wi-Fi 透传通道忙");
        suite.wifiBusy = true;
        try {
            long t0 = SystemClock.elapsedRealtime();
            Reply r = roundTrip(pkt, timeoutMs, true);
            long dt = SystemClock.elapsedRealtime() - t0;
            suite.onWifiAlive();
            AppLog.i("Wi-Fi 透传 op=0x" + Integer.toHexString(pkt[0] & 0xff) + " seq=" + (pkt[1] & 0xff)
                    + " → " + HandProtocol.resultName(r.result) + "（phase " + r.phase + "，" + dt + "ms）");
            return r.result;
        } finally {
            suite.wifiBusy = false;
            io.unlock();
        }
    }

    private Reply roundTrip(byte[] pkt, int timeoutMs, boolean waitFinal) throws IOException {
        int seq = pkt[1] & 0xff;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(resolveEndpoint(), CONNECT_TIMEOUT_MS);
            rememberIfIpv4(s);
            s.setSoTimeout(timeoutMs);
            active = s;
            OutputStream out = s.getOutputStream();
            byte[] frame = new byte[2 + pkt.length];
            frame[0] = (byte) (pkt.length & 0xff);
            frame[1] = (byte) ((pkt.length >> 8) & 0xff);
            System.arraycopy(pkt, 0, frame, 2, pkt.length);
            out.write(frame);
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (true) {
                Reply r = readFrame(in);
                if (r.seq != seq) continue;   // 别的 seq 的回复，忽略
                if (r.phase == 1 || r.result != HandProtocol.RES_ACCEPTED || !waitFinal) return r;
            }
        } finally {
            active = null;
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static Reply readFrame(DataInputStream in) throws IOException {
        int lo = in.readUnsignedByte(), hi = in.readUnsignedByte();
        int n = lo | (hi << 8);
        if (n < 4) {
            byte[] skip = new byte[n];
            in.readFully(skip);
            throw new IOException("回复帧太短");
        }
        byte[] body = new byte[n];
        in.readFully(body);
        byte[] extra = new byte[n - 4];
        System.arraycopy(body, 4, extra, 0, extra.length);
        return new Reply(body[0] & 0xff, body[1] & 0xff, body[2] & 0xff, body[3] & 0xff, extra);
    }

    /**
     * 已是 IPv4 就直接连，不做名字解析。
     * 主机名（含 .local）先试 {@link InetAddress#getByName}；失败再用上次成功的 IP，并写明原因。
     * 本轮不实现组播 mDNS / NsdManager，也不宣称 Android 10 上 .local 已经能解析。
     */
    private InetSocketAddress resolveEndpoint() throws IOException {
        String configured = host;
        if (isIpv4(configured)) return new InetSocketAddress(configured, port);
        try {
            InetAddress addr = InetAddress.getByName(configured);
            AppLog.i("Wi-Fi 透传：主机名 " + configured + " 解析为 " + addr.getHostAddress()
                    + "（尚未在本机验证 .local 一定能解析）");
            return new InetSocketAddress(addr, port);
        } catch (UnknownHostException e) {
            String fallback = lastGoodHost;
            if (isIpv4(fallback)) {
                AppLog.i("Wi-Fi 透传：主机名 " + configured + " 解析失败（" + describe(e)
                        + "）。Android 10 上 .local 常常解析不了，改用上次成功连上的地址 " + fallback);
                return new InetSocketAddress(fallback, port);
            }
            throw new IOException("无法解析主机 " + configured
                    + "，也没有上次成功的地址可兜底。请在设置里填写板子的 IPv4。", e);
        }
    }

    private void rememberIfIpv4(Socket s) {
        InetAddress ia = s.getInetAddress();
        if (ia == null) return;
        String ip = ia.getHostAddress();
        if (!isIpv4(ip)) return;
        if (ip.equals(lastGoodHost)) return;   // 轮询心跳每次都连，只在地址变化时记一次
        lastGoodHost = ip;
        AppLog.i("Wi-Fi 透传：已连上 " + ip + "，记下作为主机名解析失败时的兜底");
        LastGoodHostSink sk = sink;
        if (sk != null) sk.remember(ip);
    }

    public static boolean isIpv4(String s) {
        return s != null && IPV4.matcher(s).matches();
    }

    static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : "：" + m);
    }
}
