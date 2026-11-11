package com.yuanhou.hand.lan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 局域网指令通道（0.1.9）的纯 Java 部分：HTTP 请求解析、口令校验、局域网来源判断、极简 JSON。
 * 不依赖 Android，桌面 JVM 自检（jvmcheck/LanCheck）直接跑。
 * 只在家里局域网使用：不做端口转发、不暴露到公网；每个请求（包括 /status）都要带口令。
 */
public final class LanProtocol {
    private LanProtocol() {}

    /** 3333 是 ESP32 的 Wi-Fi 透传，远手用 3334。端口被占时不换端口。 */
    public static final int PORT = 3334;
    public static final String NSD_NAME = "yuanhou-hand";
    public static final String NSD_TYPE = "_yuanhou._tcp";
    public static final String TOKEN_HEADER = "x-yuanhou-token";
    public static final int MAX_HEAD = 8192;
    public static final int MAX_BODY = 16384;
    public static final int TOKEN_BYTES = 24;

    /** 解析失败时带 HTTP 状态码。 */
    public static final class BadRequest extends IOException {
        public final int code;
        public BadRequest(int code, String msg) { super(msg); this.code = code; }
    }

    public static final class Request {
        public String method = "";
        public String path = "";
        public String query = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
        public String bodyText() { return new String(body, StandardCharsets.UTF_8); }

        /** 查询串里的一个参数（已 URL 解码）；没有返回 null。 */
        public String queryParam(String key) {
            if (query == null || query.isEmpty()) return null;
            for (String kv : query.split("&")) {
                int i = kv.indexOf('=');
                String k = i < 0 ? kv : kv.substring(0, i);
                if (k.equals(key)) return i < 0 ? "" : urlDecode(kv.substring(i + 1));
            }
            return null;
        }
    }

    /** 读一个 HTTP/1.x 请求（头最多 8 KB，正文最多 16 KB，只认 Content-Length，不支持 chunked）。 */
    public static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;   // 找 \r\n\r\n 或 \n\n
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (head.size() == 0) throw new BadRequest(400, "空请求");
                throw new BadRequest(400, "请求头不完整");
            }
            head.write(b);
            if (head.size() > MAX_HEAD) throw new BadRequest(431, "请求头太长");
            if (b == '\n') {
                if (state == 1) break;
                state = 1;
            } else if (b != '\r') {
                state = 0;
            }
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.UTF_8).split("\r?\n");
        Request r = new Request();
        String[] rl = lines[0].trim().split(" ");
        if (rl.length != 3 || !rl[2].startsWith("HTTP/")) throw new BadRequest(400, "请求行不对");
        r.method = rl[0].toUpperCase(Locale.ROOT);
        String target = rl[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        r.query = q < 0 ? "" : target.substring(q + 1);
        if (!r.path.startsWith("/")) throw new BadRequest(400, "路径不对");
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.isEmpty()) continue;
            int c = l.indexOf(':');
            if (c <= 0) continue;
            r.headers.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
        }
        String te = r.header("transfer-encoding");
        if (te != null && !te.equalsIgnoreCase("identity")) throw new BadRequest(411, "不支持 chunked，请带 Content-Length");
        String cl = r.header("content-length");
        if (cl != null) {
            int n;
            try { n = Integer.parseInt(cl.trim()); } catch (NumberFormatException e) { throw new BadRequest(400, "Content-Length 不对"); }
            if (n < 0) throw new BadRequest(400, "Content-Length 不对");
            if (n > MAX_BODY) throw new BadRequest(413, "正文太长");
            byte[] body = new byte[n];
            int off = 0;
            while (off < n) {
                int k = in.read(body, off, n - off);
                if (k < 0) throw new BadRequest(400, "正文不完整");
                off += k;
            }
            r.body = body;
        }
        return r;
    }

    // ---------------- 口令 ----------------

    /** 新口令：24 字节 SecureRandom → 48 位小写十六进制。 */
    public static String newToken(SecureRandom rnd) {
        byte[] b = new byte[TOKEN_BYTES];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    /** 存着的口令是否像样（至少 32 位十六进制）；不像样就重新生成。 */
    public static boolean tokenWellFormed(String t) {
        return t != null && t.length() >= 32 && t.matches("[0-9a-f]+");
    }

    /** 请求带来的口令：X-Yuanhou-Token 优先，其次 Authorization: Bearer。 */
    public static String presentedToken(Request r) {
        String t = r.header(TOKEN_HEADER);
        if (t != null && !t.trim().isEmpty()) return t.trim();
        String a = r.header("authorization");
        if (a != null && a.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String v = a.substring(7).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    /** 常量时间比较；任何一边为空都不通过。 */
    public static boolean tokenMatches(String expected, String presented) {
        if (expected == null || presented == null || expected.isEmpty() || presented.isEmpty()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    /** 日志/界面以外的地方只露最后 4 位。 */
    public static String mask(String t) {
        if (t == null || t.length() < 8) return "（无）";
        return "…" + t.substring(t.length() - 4);
    }

    // ---------------- 局域网来源 ----------------

    /** 私有/链路本地 IPv4：10/8、172.16/12、192.168/16、169.254/16。只接受这些来源。 */
    public static boolean isPrivateIpv4(String s) {
        int[] o = octets(s);
        if (o == null) return false;
        if (o[0] == 10) return true;
        if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return true;
        if (o[0] == 192 && o[1] == 168) return true;
        return o[0] == 169 && o[1] == 254;
    }

    private static int[] octets(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("\\.", -1);
        if (p.length != 4) return null;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            if (p[i].isEmpty() || p[i].length() > 3 || !p[i].matches("[0-9]+")) return null;
            o[i] = Integer.parseInt(p[i]);
            if (o[i] > 255) return null;
        }
        return o;
    }

    // ---------------- /say 正文 ----------------

    /**
     * /say 要交给会话的那句话：JSON {"text":"读屏"}、表单 text=…、或纯文本正文。
     * 解析不了返回 null。
     */
    public static String sayText(Request r) {
        String ct = r.header("content-type");
        ct = ct == null ? "" : ct.toLowerCase(Locale.ROOT);
        String body = r.bodyText();
        String trimmed = body.trim();
        if (ct.contains("json") || (trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            Map<String, String> m = parseFlatJson(trimmed);
            if (m != null && m.containsKey("text")) return m.get("text");
            if (ct.contains("json")) return null;
        }
        if (ct.contains("x-www-form-urlencoded") && trimmed.startsWith("text=")) {
            return urlDecode(trimmed.substring(5));
        }
        return body;
    }

    /** /say 的 timeout（秒）：JSON 里的 "timeout" 或查询串 ?timeout=，限制在 5–120，默认 60。 */
    public static int sayTimeoutSec(Request r) {
        String v = r.queryParam("timeout");
        if (v == null) {
            Map<String, String> m = parseFlatJson(r.bodyText().trim());
            if (m != null) v = m.get("timeout");
        }
        int t = 60;
        if (v != null) {
            try { t = (int) Double.parseDouble(v.trim()); } catch (NumberFormatException ignored) {}
        }
        return Math.max(5, Math.min(120, t));
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 只认一层的 JSON 对象：值为字符串、数字、true/false/null（都转成字符串，null 存 null）。
     * 有嵌套或格式不对返回 null。
     */
    public static Map<String, String> parseFlatJson(String s) {
        if (s == null) return null;
        int[] i = {0};
        Map<String, String> out = new LinkedHashMap<>();
        try {
            ws(s, i);
            if (i[0] >= s.length() || s.charAt(i[0]) != '{') return null;
            i[0]++;
            ws(s, i);
            if (i[0] < s.length() && s.charAt(i[0]) == '}') { i[0]++; ws(s, i); return i[0] == s.length() ? out : null; }
            while (true) {
                ws(s, i);
                String k = str(s, i);
                ws(s, i);
                if (s.charAt(i[0]) != ':') return null;
                i[0]++;
                ws(s, i);
                char c = s.charAt(i[0]);
                String v;
                if (c == '"') {
                    v = str(s, i);
                } else if (c == '{' || c == '[') {
                    return null;
                } else {
                    int st = i[0];
                    while (i[0] < s.length() && ",} \t\r\n".indexOf(s.charAt(i[0])) < 0) i[0]++;
                    String lit = s.substring(st, i[0]);
                    if (lit.equals("null")) v = null;
                    else if (lit.equals("true") || lit.equals("false") || lit.matches("-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?")) v = lit;
                    else return null;
                }
                out.put(k, v);
                ws(s, i);
                char d = s.charAt(i[0]++);
                if (d == '}') break;
                if (d != ',') return null;
            }
            ws(s, i);
            return i[0] == s.length() ? out : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void ws(String s, int[] i) {
        while (i[0] < s.length() && " \t\r\n".indexOf(s.charAt(i[0])) >= 0) i[0]++;
    }

    private static String str(String s, int[] i) {
        if (s.charAt(i[0]) != '"') throw new IllegalArgumentException("不是字符串");
        i[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i[0]++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i[0]++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16));
                    i[0] += 4;
                    break;
                default: throw new IllegalArgumentException("转义不对");
            }
        }
    }

    // ---------------- JSON 输出 ----------------

    public static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 顺序固定的小 JSON 对象构造器。 */
    public static final class Json {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;

        private Json key(String k) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonString(k)).append(':');
            return this;
        }

        public Json put(String k, String v) { key(k).sb.append(jsonString(v)); return this; }
        public Json put(String k, long v) { key(k).sb.append(v); return this; }
        public Json put(String k, boolean v) { key(k).sb.append(v); return this; }
        public Json putRaw(String k, String rawJson) { key(k).sb.append(rawJson == null ? "null" : rawJson); return this; }

        public Json putArray(String k, java.util.List<String> items) {
            key(k).sb.append('[');
            for (int j = 0; j < items.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(jsonString(items.get(j)));
            }
            sb.append(']');
            return this;
        }

        @Override public String toString() { return sb + "}"; }
    }

    public static String error(String code, String message) {
        return new Json().put("ok", false).put("error", code).put("message", message).toString();
    }

    // ---------------- 响应 ----------------

    public static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }

    /** 整个响应（头 + 正文），一次请求一个连接，Connection: close。 */
    public static byte[] response(int code, String contentType, byte[] body) {
        if (body == null) body = new byte[0];
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        if (code == 401) h.append("WWW-Authenticate: Bearer realm=\"yuanhou\"\r\n");
        h.append("Connection: close\r\n\r\n");
        byte[] hb = h.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[hb.length + body.length];
        System.arraycopy(hb, 0, out, 0, hb.length);
        System.arraycopy(body, 0, out, hb.length, body.length);
        return out;
    }

    public static byte[] json(int code, String json) {
        return response(code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] text(int code, String text) {
        return response(code, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }
}
