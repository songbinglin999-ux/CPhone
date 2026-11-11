package com.yuanhou.hand.wechat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信官方 Bot（腾讯 iLink，https://ilinkai.weixin.qq.com/ilink/bot/...）的 HTTPS JSON 客户端。
 * 照 Mika core/wechat/WechatApi.kt 移植：同样的路径、请求体、请求头和错误判定（HTTP 非 2xx / ret != 0）。
 * 只负责协议，不持有任务、不碰安卓生命周期；纯 Java（HttpURLConnection），jvmcheck 可以换成假的传输层来验证。
 * 不是个人微信的非官方协议，也不操作微信 App 的界面。
 *
 * 安全：本类从不写日志；令牌只出现在 Authorization 请求头里。toString() 不含令牌。
 */
public final class WechatApi {
    public static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";
    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 45_000;

    // ---------------- 数据 ----------------

    /** 绑定后的账号。token 是机密：只存手机（WechatStore 加密），不写日志、不进界面、不发回微信。 */
    public static final class Account {
        public final String token, baseUrl, botId, ownerId;
        public Account(String token, String baseUrl, String botId, String ownerId) {
            this.token = token;
            this.baseUrl = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_BASE_URL : baseUrl.trim();
            this.botId = botId == null ? "" : botId;
            this.ownerId = ownerId;
        }
        public boolean sameIdentity(Account o) {
            return o != null && eq(ownerId, o.ownerId) && eq(botId, o.botId);
        }
        @Override public boolean equals(Object x) {
            if (!(x instanceof Account)) return false;
            Account o = (Account) x;
            return eq(token, o.token) && eq(baseUrl, o.baseUrl) && eq(botId, o.botId) && eq(ownerId, o.ownerId);
        }
        @Override public int hashCode() { return (ownerId == null ? 0 : ownerId.hashCode()) * 31 + (botId == null ? 0 : botId.hashCode()); }
        @Override public String toString() { return "WechatAccount(**)"; }
    }

    public static final class QrStart {
        public final String qrcode, qrContent;
        QrStart(String qrcode, String qrContent) { this.qrcode = qrcode; this.qrContent = qrContent; }
        @Override public String toString() { return "QrStart(**)"; }
    }

    public static final class QrStatus {
        public final String status, token, botId, baseUrl, ownerId;
        QrStatus(String status, String token, String botId, String baseUrl, String ownerId) {
            this.status = status == null || status.isEmpty() ? "wait" : status;
            this.token = token; this.botId = botId; this.baseUrl = baseUrl; this.ownerId = ownerId;
        }
        /** 只有 confirmed 且带令牌和本人 id 才算绑定成功（与 Mika WechatQrStatus.account() 相同）。 */
        public Account account() {
            if (!"confirmed".equals(status) || blank(token) || blank(ownerId)) return null;
            return new Account(token, baseUrl, botId, ownerId);
        }
        @Override public String toString() { return "QrStatus(" + status + ")"; }
    }

    public static final class Message {
        public final String messageId, fromUserId, toUserId, groupId, contextToken;
        public final int messageType;
        /** 第一个 type==1 的 text_item 文本。 */
        public final String firstText;
        Message(String messageId, String fromUserId, String toUserId, String groupId, String contextToken,
                int messageType, String firstText) {
            this.messageId = messageId; this.fromUserId = fromUserId; this.toUserId = toUserId;
            this.groupId = groupId; this.contextToken = contextToken; this.messageType = messageType; this.firstText = firstText;
        }
        /** 只收用户私聊文本（Mika userText()：message_type=1、有 id、有发送人、第一条文字非空）；群消息一律不收。 */
        public String userText() {
            if (messageType != 1 || blank(messageId) || blank(fromUserId)) return null;
            if (!blank(groupId)) return null;
            return blank(firstText) ? null : firstText;
        }
        @Override public String toString() { return "WechatMessage(type=" + messageType + ")"; }
    }

    public static final class Updates {
        public final List<Message> msgs;
        public final String cursor;
        Updates(List<Message> msgs, String cursor) { this.msgs = msgs; this.cursor = cursor == null ? "" : cursor; }
    }

    public static class ApiException extends IOException {
        public final int code;
        public ApiException(int code, String m) { super(m); this.code = code; }
    }

    public static class HttpException extends IOException {
        public final int httpCode;
        public HttpException(int c) { super("WeChat HTTP " + c); httpCode = c; }
        /** 401/403：登录失效，要重新扫码（Mika 同样处理）。 */
        public boolean needsRebind() { return httpCode == 401 || httpCode == 403; }
    }

    // ---------------- 传输层（可替换，jvmcheck 用假的） ----------------

    public static final class Response {
        public final int code;
        public final String body;
        public Response(int code, String body) { this.code = code; this.body = body; }
    }

    public interface Transport {
        Response exec(String method, String url, Map<String, String> headers, byte[] body) throws IOException;
        /** 打断正在进行的长轮询（停止服务/换账号时）。 */
        default void cancelAll() {}
    }

    /** HttpURLConnection 实现。记下进行中的连接，cancelAll() 时断开。 */
    public static final class UrlTransport implements Transport {
        private final List<HttpURLConnection> live = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Response exec(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            live.add(c);
            try {
                c.setRequestMethod(method);
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(false);
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                if (body != null) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(body.length);
                    try (OutputStream o = c.getOutputStream()) { o.write(body); }
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = "";
                if (in != null) {
                    try (InputStream i = in) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        int total = 0;
                        while ((n = i.read(buf)) > 0) {
                            total += n;
                            if (total > 4 * 1024 * 1024) throw new IOException("WeChat response too large");
                            b.write(buf, 0, n);
                        }
                        text = new String(b.toByteArray(), StandardCharsets.UTF_8);
                    }
                }
                return new Response(code, text);
            } finally {
                live.remove(c);
                c.disconnect();
            }
        }

        @Override
        public void cancelAll() {
            List<HttpURLConnection> copy;
            synchronized (live) { copy = new ArrayList<>(live); }
            for (HttpURLConnection c : copy) { try { c.disconnect(); } catch (Exception ignored) {} }
        }
    }

    // ---------------- 客户端 ----------------

    private final Transport http;
    private final String channelVersion;
    private final String defaultBaseUrl;
    private final SecureRandom rnd = new SecureRandom();

    public WechatApi(Transport http, String channelVersion) { this(http, channelVersion, DEFAULT_BASE_URL); }

    public WechatApi(Transport http, String channelVersion, String defaultBaseUrl) {
        this.http = http;
        this.channelVersion = channelVersion;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public void cancelAll() { http.cancelAll(); }

    /** POST get_bot_qrcode?bot_type=3（空正文）。 */
    public QrStart startLogin() throws IOException {
        String url = endpoint(defaultBaseUrl, "get_bot_qrcode") + "?bot_type=3";
        Map<String, Object> m = execute("POST", url, headers(null), new byte[0]);
        return new QrStart(MiniJson.str(m, "qrcode"), MiniJson.str(m, "qrcode_img_content"));
    }

    /** GET get_qrcode_status?qrcode=... */
    public QrStatus pollLogin(String qrcode) throws IOException {
        String url = endpoint(defaultBaseUrl, "get_qrcode_status") + "?qrcode=" + URLEncoder.encode(qrcode, "UTF-8");
        Map<String, Object> m = execute("GET", url, headers(null), null);
        return new QrStatus(MiniJson.str(m, "status"), MiniJson.str(m, "bot_token"), MiniJson.str(m, "ilink_bot_id"),
                MiniJson.str(m, "baseurl"), MiniJson.str(m, "ilink_user_id"));
    }

    /** POST getupdates（长轮询）。 */
    public Updates updates(Account a, String cursor) throws IOException {
        String body = updatesBody(cursor, channelVersion);
        Map<String, Object> m = execute("POST", endpoint(a.baseUrl, "getupdates"), headers(a.token),
                body.getBytes(StandardCharsets.UTF_8));
        return parseUpdates(m);
    }

    /** POST sendmessage：一条私聊文字。contextToken 用对方最近一条消息带来的那个。 */
    public void sendText(Account a, String peerId, String clientId, String text, String contextToken) throws IOException {
        String body = sendBody(peerId, clientId, text, contextToken, channelVersion);
        execute("POST", endpoint(a.baseUrl, "sendmessage"), headers(a.token), body.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------- 纯函数（jvmcheck 覆盖） ----------------

    static String updatesBody(String cursor, String channelVersion) {
        return "{\"get_updates_buf\":" + MiniJson.quote(cursor == null ? "" : cursor)
                + ",\"base_info\":{\"channel_version\":" + MiniJson.quote(channelVersion) + "}}";
    }

    /** 与 Mika SendRequest 序列化结果一致：from_user_id 为空串、message_type=2、message_state=2、一个 type=1 的文字项。 */
    static String sendBody(String peerId, String clientId, String text, String contextToken, String channelVersion) {
        StringBuilder b = new StringBuilder("{\"msg\":{");
        b.append("\"from_user_id\":\"\"");
        b.append(",\"to_user_id\":").append(MiniJson.quote(peerId));
        b.append(",\"client_id\":").append(MiniJson.quote(clientId));
        b.append(",\"message_type\":2,\"message_state\":2");
        b.append(",\"item_list\":[{\"type\":1,\"text_item\":{\"text\":").append(MiniJson.quote(text)).append("}}]");
        if (contextToken != null) b.append(",\"context_token\":").append(MiniJson.quote(contextToken));
        b.append("},\"base_info\":{\"channel_version\":").append(MiniJson.quote(channelVersion)).append("}}");
        return b.toString();
    }

    static Updates parseUpdates(Map<String, Object> m) {
        List<Message> out = new ArrayList<>();
        List<Object> msgs = MiniJson.arr(m.get("msgs"));
        if (msgs != null) {
            for (Object o : msgs) {
                Map<String, Object> mm = MiniJson.obj(o);
                if (mm == null) continue;
                String first = null;
                List<Object> items = MiniJson.arr(mm.get("item_list"));
                if (items != null) {
                    for (Object it : items) {
                        Map<String, Object> im = MiniJson.obj(it);
                        if (im == null || MiniJson.num(im, "type", 0) != 1) continue;
                        Map<String, Object> ti = MiniJson.obj(im.get("text_item"));
                        first = ti == null ? "" : MiniJson.str(ti, "text");
                        break;
                    }
                }
                out.add(new Message(MiniJson.str(mm, "message_id"), MiniJson.str(mm, "from_user_id"),
                        MiniJson.str(mm, "to_user_id"), MiniJson.str(mm, "group_id"), MiniJson.str(mm, "context_token"),
                        (int) MiniJson.num(mm, "message_type", 0), first));
            }
        }
        return new Updates(out, MiniJson.str(m, "get_updates_buf"));
    }

    /** Mika encodeVersion：版本号里的数字取前 8 位当十进制，按 %08x 输出（0.1.12 → 00000070）。 */
    static String encodeVersion(String version) {
        StringBuilder d = new StringBuilder();
        for (char c : version.toCharArray()) if (Character.isDigit(c) && d.length() < 8) d.append(c);
        long v = d.length() == 0 ? 1 : Long.parseLong(d.toString());
        return String.format("%08x", (int) v);
    }

    static String endpoint(String base, String path) {
        String b = base;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!b.startsWith("https://")) throw new IllegalArgumentException("WeChat base URL must be https");
        return b + "/ilink/bot/" + path;
    }

    Map<String, String> headers(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("iLink-App-Id", "bot");
        h.put("iLink-App-ClientVersion", encodeVersion(channelVersion));
        if (token != null) {
            h.put("AuthorizationType", "ilink_bot_token");
            h.put("Authorization", "Bearer " + token);
            byte[] uin = new byte[4];
            rnd.nextBytes(uin);
            h.put("X-WECHAT-UIN", Base64.getEncoder().encodeToString(uin));
        }
        return h;
    }

    private Map<String, Object> execute(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
        Response r = http.exec(method, url, headers, body);
        return decode(r);
    }

    /** 与 Mika decode 相同：非 2xx 抛 HttpException；空正文抛 IOException；ret != 0 抛 ApiException。 */
    static Map<String, Object> decode(Response r) throws IOException {
        if (r.code < 200 || r.code >= 300) throw new HttpException(r.code);
        if (r.body == null || r.body.trim().isEmpty()) throw new IOException("WeChat returned an empty response");
        Map<String, Object> m;
        try {
            m = MiniJson.obj(MiniJson.parse(r.body));
        } catch (MiniJson.ParseException e) {
            throw new IOException("WeChat returned malformed JSON");
        }
        if (m == null) throw new IOException("WeChat returned non-object JSON");
        long ret = MiniJson.num(m, "ret", 0);
        if (ret != 0) {
            String em = MiniJson.str(m, "errmsg");
            throw new ApiException((int) ret, em == null ? "WeChat API error " + ret : em);
        }
        return m;
    }

    static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
}
