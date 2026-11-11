import com.yuanhou.hand.lan.LanProtocol;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

/** 局域网指令通道（0.1.9）纯 Java 部分的桌面自检：请求解析、口令、来源网段、/say 正文、JSON。不开端口、不连手机。 */
public class LanCheck {
    static int pass, fail;

    static void eq(String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what + ": got " + got + " want " + want); }
    }

    static LanProtocol.Request req(String raw) throws Exception {
        return LanProtocol.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    static int code(String raw) {
        try { req(raw); return 0; } catch (LanProtocol.BadRequest e) { return e.code; } catch (Exception e) { return -1; }
    }

    static String post(String ct, String body) {
        int n = body.getBytes(StandardCharsets.UTF_8).length;
        return "POST /say HTTP/1.1\r\nHost: x\r\nX-Yuanhou-Token: abc\r\n" + (ct == null ? "" : "Content-Type: " + ct + "\r\n")
                + "Content-Length: " + n + "\r\n\r\n" + body;
    }

    public static void main(String[] a) throws Exception {
        eq("端口", LanProtocol.PORT, 3334);
        eq("NSD 名", LanProtocol.NSD_NAME, "yuanhou-hand");
        eq("NSD 类型", LanProtocol.NSD_TYPE, "_yuanhou._tcp");

        // 请求解析
        LanProtocol.Request r = req("GET /status?x=1&timeout=30 HTTP/1.1\r\nHost: 1.2.3.4\r\nX-Yuanhou-Token:  tok123 \r\n\r\n");
        eq("方法", r.method, "GET");
        eq("路径", r.path, "/status");
        eq("查询参数", r.queryParam("timeout"), "30");
        eq("头名不分大小写", r.header("X-YUANHOU-TOKEN"), "tok123");
        eq("口令头", LanProtocol.presentedToken(r), "tok123");
        eq("LF 结尾也能读", req("GET /tree HTTP/1.0\nHost: a\n\n").path, "/tree");
        eq("Bearer", LanProtocol.presentedToken(req("GET / HTTP/1.1\r\nAuthorization: Bearer zz9\r\n\r\n")), "zz9");
        eq("没口令", LanProtocol.presentedToken(req("GET / HTTP/1.1\r\nAuthorization: Basic eDp5\r\n\r\n")), null);
        eq("空请求 400", code(""), 400);
        eq("坏请求行 400", code("HELLO\r\n\r\n"), 400);
        eq("相对路径 400", code("GET status HTTP/1.1\r\n\r\n"), 400);
        eq("chunked 411", code("POST /say HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"), 411);
        eq("正文太长 413", code("POST /say HTTP/1.1\r\nContent-Length: 999999\r\n\r\n"), 413);
        StringBuilder big = new StringBuilder("GET / HTTP/1.1\r\n");
        for (int i = 0; i < 400; i++) big.append("X-Pad-").append(i).append(": aaaaaaaaaaaaaaaaaaaaaaaaa\r\n");
        eq("头太长 431", code(big + "\r\n"), 431);
        eq("正文不完整 400", code("POST /say HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"), 400);

        // 口令
        SecureRandom rnd = new SecureRandom();
        String t1 = LanProtocol.newToken(rnd), t2 = LanProtocol.newToken(rnd);
        eq("口令 48 位", t1.length(), 48);
        eq("口令像样", LanProtocol.tokenWellFormed(t1), true);
        eq("两次不同", t1.equals(t2), false);
        eq("空口令不像样", LanProtocol.tokenWellFormed(""), false);
        eq("短口令不像样", LanProtocol.tokenWellFormed("abcd"), false);
        eq("对的口令", LanProtocol.tokenMatches(t1, t1), true);
        eq("错的口令", LanProtocol.tokenMatches(t1, t2), false);
        eq("没带口令", LanProtocol.tokenMatches(t1, null), false);
        eq("前缀不算", LanProtocol.tokenMatches(t1, t1.substring(0, 47)), false);
        eq("期望为空也不放行", LanProtocol.tokenMatches("", ""), false);
        eq("打码只露 4 位", LanProtocol.mask(t1), "…" + t1.substring(44));
        eq("打码不含全文", LanProtocol.mask(t1).contains(t1.substring(0, 8)), false);

        // 来源网段
        eq("192.168", LanProtocol.isPrivateIpv4("192.168.1.20"), true);
        eq("10/8", LanProtocol.isPrivateIpv4("10.0.0.5"), true);
        eq("172.16", LanProtocol.isPrivateIpv4("172.16.3.4"), true);
        eq("172.32 不是", LanProtocol.isPrivateIpv4("172.32.0.1"), false);
        eq("169.254", LanProtocol.isPrivateIpv4("169.254.9.9"), true);
        eq("公网", LanProtocol.isPrivateIpv4("8.8.8.8"), false);
        eq("回环不算", LanProtocol.isPrivateIpv4("127.0.0.1"), false);
        eq("坏地址", LanProtocol.isPrivateIpv4("192.168.1"), false);
        eq("越界", LanProtocol.isPrivateIpv4("192.168.1.256"), false);
        eq("空", LanProtocol.isPrivateIpv4(null), false);

        // /say 正文
        eq("JSON", LanProtocol.sayText(req(post("application/json", "{\"text\":\"读屏\"}"))), "读屏");
        eq("JSON 转义", LanProtocol.sayText(req(post("application/json; charset=utf-8",
                "{\"text\":\"在 搜索框 输入 \\u5976\\u8336\\n\",\"timeout\":30}"))), "在 搜索框 输入 奶茶\n");
        eq("JSON 没 text", LanProtocol.sayText(req(post("application/json", "{\"x\":\"1\"}"))), null);
        eq("坏 JSON", LanProtocol.sayText(req(post("application/json", "{\"text\":"))), null);
        eq("纯文本", LanProtocol.sayText(req(post("text/plain; charset=utf-8", "打开美团"))), "打开美团");
        eq("没 Content-Type", LanProtocol.sayText(req(post(null, "读屏"))), "读屏");
        eq("没 Content-Type 但像 JSON", LanProtocol.sayText(req(post(null, "{\"text\":\"取消\"}"))), "取消");
        eq("curl -d 表单", LanProtocol.sayText(req(post("application/x-www-form-urlencoded",
                "text=%E8%AF%BB%E5%B1%8F"))), "读屏");
        eq("timeout 来自 JSON", LanProtocol.sayTimeoutSec(req(post("application/json", "{\"text\":\"读屏\",\"timeout\":30}"))), 30);
        eq("timeout 默认 60", LanProtocol.sayTimeoutSec(req(post("text/plain", "读屏"))), 60);
        eq("timeout 上限 120", LanProtocol.sayTimeoutSec(req("POST /say?timeout=9999 HTTP/1.1\r\n\r\n")), 120);
        eq("timeout 下限 5", LanProtocol.sayTimeoutSec(req("POST /say?timeout=1 HTTP/1.1\r\n\r\n")), 5);

        // 极简 JSON
        Map<String, String> m = LanProtocol.parseFlatJson("{ \"a\" : \"x\\\"y\", \"n\": 3, \"b\": true, \"z\": null }");
        eq("解析 a", m == null ? null : m.get("a"), "x\"y");
        eq("解析 n", m == null ? null : m.get("n"), "3");
        eq("解析 b", m == null ? null : m.get("b"), "true");
        eq("解析 null", m != null && m.containsKey("z") && m.get("z") == null, true);
        eq("嵌套不认", LanProtocol.parseFlatJson("{\"a\":{\"b\":1}}"), null);
        eq("尾巴多余不认", LanProtocol.parseFlatJson("{\"a\":1} x"), null);
        eq("空对象", LanProtocol.parseFlatJson("{}") != null, true);
        String js = new LanProtocol.Json().put("ok", true).put("s", "引\"号\n换行").put("n", 7L).putRaw("r", null)
                .putArray("l", java.util.Arrays.asList("a", "b")).toString();
        eq("JSON 输出", js, "{\"ok\":true,\"s\":\"引\\\"号\\n换行\",\"n\":7,\"r\":null,\"l\":[\"a\",\"b\"]}");
        eq("控制字符转义", LanProtocol.jsonString("\u0001"), "\"\\u0001\"");

        // 响应
        String resp = new String(LanProtocol.json(401, LanProtocol.error("unauthorized", "x")), StandardCharsets.UTF_8);
        eq("401 状态行", resp.startsWith("HTTP/1.1 401 Unauthorized\r\n"), true);
        eq("401 带 WWW-Authenticate", resp.contains("WWW-Authenticate: Bearer"), true);
        eq("Connection close", resp.contains("Connection: close\r\n\r\n"), true);
        byte[] tb = LanProtocol.text(200, "树");
        eq("Content-Length 按字节", new String(tb, StandardCharsets.UTF_8).contains("Content-Length: 3\r\n"), true);
        eq("404 原因", LanProtocol.reason(404), "Not Found");

        System.out.println("LanCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
