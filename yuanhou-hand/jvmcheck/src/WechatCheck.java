import com.yuanhou.hand.gate.ConfirmationGate;
import com.yuanhou.hand.gate.IrrevAction;
import com.yuanhou.hand.wechat.MiniJson;
import com.yuanhou.hand.wechat.WechatApi;
import com.yuanhou.hand.wechat.WechatPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 0.1.12 微信官方 Bot 通道纯 Java 部分的桌面自检（假的传输层，不联网）：
 * 路径/请求头/请求体与 Mika 一致、只收本人私聊文字、路由（同 /say：在办时非取消不交）、确认走微信但付款永不执行、
 * 401/403 要重新绑定、外发文字打码、源码里不把令牌写进日志。
 */
public class WechatCheck {
    static int pass, fail;

    static void eq(String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what + ": got " + got + " want " + want); }
    }

    static void yes(String what, boolean b) { eq(what, b, true); }

    static final class Call { String method, url, body; Map<String, String> headers; }

    static final class Fake implements WechatApi.Transport {
        final List<Call> calls = new ArrayList<>();
        final List<WechatApi.Response> replies = new ArrayList<>();
        @Override public WechatApi.Response exec(String m, String u, Map<String, String> h, byte[] b) {
            Call c = new Call();
            c.method = m; c.url = u; c.headers = h; c.body = b == null ? null : new String(b, StandardCharsets.UTF_8);
            calls.add(c);
            return replies.remove(0);
        }
    }

    public static void main(String[] a) throws Exception {
        final String TOKEN = "tok-SECRET-abcdef123456";
        Fake f = new Fake();
        WechatApi api = new WechatApi(f, "0.1.12");

        // 扫码
        f.replies.add(new WechatApi.Response(200, "{\"ret\":0,\"qrcode\":\"qr-key\",\"qrcode_img_content\":\"https://example.test/qr\"}"));
        WechatApi.QrStart q = api.startLogin();
        Call c = f.calls.get(0);
        eq("startLogin 方法", c.method, "POST");
        eq("startLogin URL", c.url, "https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3");
        eq("startLogin 无令牌", c.headers.get("Authorization"), null);
        eq("App-Id", c.headers.get("iLink-App-Id"), "bot");
        eq("ClientVersion 编码（Mika encodeVersion）", c.headers.get("iLink-App-ClientVersion"), "00000070");
        eq("qrcode", q.qrcode, "qr-key");
        eq("qrcode_img_content", q.qrContent, "https://example.test/qr");

        f.replies.add(new WechatApi.Response(200, "{\"status\":\"wait\"}"));
        WechatApi.QrStatus st = api.pollLogin("qr key");
        eq("pollLogin URL", f.calls.get(1).url, "https://ilinkai.weixin.qq.com/ilink/bot/get_qrcode_status?qrcode=qr+key");
        eq("pollLogin GET", f.calls.get(1).method, "GET");
        eq("wait 不是账号", st.account(), null);
        f.replies.add(new WechatApi.Response(200, "{\"status\":\"confirmed\",\"bot_token\":\"" + TOKEN
                + "\",\"ilink_bot_id\":\"bot1\",\"ilink_user_id\":\"owner1\",\"baseurl\":\"\"}"));
        WechatApi.Account acct = api.pollLogin("qr-key").account();
        yes("confirmed 得到账号", acct != null);
        eq("baseurl 空 → 默认", acct.baseUrl, "https://ilinkai.weixin.qq.com");
        eq("owner", acct.ownerId, "owner1");
        yes("账号 toString 不含令牌", !acct.toString().contains(TOKEN));
        f.replies.add(new WechatApi.Response(200, "{\"status\":\"confirmed\",\"ilink_user_id\":\"owner1\"}"));
        eq("confirmed 缺令牌不算", api.pollLogin("qr-key").account(), null);

        // 长轮询
        f.replies.add(new WechatApi.Response(200, "{\"ret\":0,\"get_updates_buf\":\"cur2\",\"msgs\":["
                + "{\"message_id\":123,\"from_user_id\":\"owner1\",\"message_type\":1,\"context_token\":\"ctx1\",\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\" 读屏 \"}}]},"
                + "{\"message_id\":\"124\",\"from_user_id\":\"stranger\",\"message_type\":1,\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"打开微信\"}}]},"
                + "{\"message_id\":\"125\",\"from_user_id\":\"owner1\",\"message_type\":1,\"group_id\":\"g1\",\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"群里的话\"}}]},"
                + "{\"message_id\":\"126\",\"from_user_id\":\"owner1\",\"message_type\":1,\"item_list\":[{\"type\":2,\"image_item\":{}}]},"
                + "{\"message_id\":\"127\",\"from_user_id\":\"owner1\",\"message_type\":2,\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"bot 自己发的\"}}]}"
                + "]}"));
        WechatApi.Updates u = api.updates(acct, "cur1");
        c = f.calls.get(4);
        eq("getupdates URL", c.url, "https://ilinkai.weixin.qq.com/ilink/bot/getupdates");
        eq("getupdates 正文", c.body, "{\"get_updates_buf\":\"cur1\",\"base_info\":{\"channel_version\":\"0.1.12\"}}");
        eq("AuthorizationType", c.headers.get("AuthorizationType"), "ilink_bot_token");
        eq("Authorization", c.headers.get("Authorization"), "Bearer " + TOKEN);
        yes("X-WECHAT-UIN", c.headers.get("X-WECHAT-UIN") != null && !c.headers.get("X-WECHAT-UIN").isEmpty());
        eq("游标", u.cursor, "cur2");
        eq("消息数", u.msgs.size(), 5);
        eq("数字 message_id", u.msgs.get(0).messageId, "123");
        eq("本人私聊文字", WechatPolicy.ownerText(u.msgs.get(0), "owner1"), "读屏");
        eq("context_token", u.msgs.get(0).contextToken, "ctx1");
        eq("别人发的不收", WechatPolicy.ownerText(u.msgs.get(1), "owner1"), null);
        eq("群消息不收", WechatPolicy.ownerText(u.msgs.get(2), "owner1"), null);
        eq("图片不收", WechatPolicy.ownerText(u.msgs.get(3), "owner1"), null);
        eq("非用户消息不收", WechatPolicy.ownerText(u.msgs.get(4), "owner1"), null);
        eq("没绑定 owner 不收", WechatPolicy.ownerText(u.msgs.get(0), ""), null);

        // 发送
        f.replies.add(new WechatApi.Response(200, "{\"ret\":0}"));
        api.sendText(acct, "owner1", "cid-1", "要发送给\"张三\"\n内容", "ctx1");
        c = f.calls.get(5);
        eq("sendmessage URL", c.url, "https://ilinkai.weixin.qq.com/ilink/bot/sendmessage");
        eq("sendmessage 正文", c.body, "{\"msg\":{\"from_user_id\":\"\",\"to_user_id\":\"owner1\",\"client_id\":\"cid-1\","
                + "\"message_type\":2,\"message_state\":2,\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"要发送给\\\"张三\\\"\\n内容\"}}],"
                + "\"context_token\":\"ctx1\"},\"base_info\":{\"channel_version\":\"0.1.12\"}}");
        Map<String, Object> back = MiniJson.obj(MiniJson.parse(c.body));
        eq("正文能解析回来", MiniJson.str(MiniJson.obj(back.get("msg")), "to_user_id"), "owner1");

        // 错误
        f.replies.add(new WechatApi.Response(401, ""));
        try { api.updates(acct, ""); fail++; System.out.println("FAIL 401 应抛"); }
        catch (WechatApi.HttpException e) { yes("401 需重新绑定", e.needsRebind()); }
        f.replies.add(new WechatApi.Response(403, "x"));
        try { api.updates(acct, ""); fail++; } catch (WechatApi.HttpException e) { yes("403 需重新绑定", e.needsRebind()); }
        f.replies.add(new WechatApi.Response(500, ""));
        try { api.updates(acct, ""); fail++; } catch (WechatApi.HttpException e) { yes("500 不要求重绑", !e.needsRebind()); }
        f.replies.add(new WechatApi.Response(200, "{\"ret\":-14,\"errmsg\":\"session\"}"));
        try { api.updates(acct, ""); fail++; } catch (WechatApi.ApiException e) { eq("ret!=0 → ApiException", e.code, -14); }
        f.replies.add(new WechatApi.Response(200, "  "));
        try { api.updates(acct, ""); fail++; } catch (IOException e) { pass++; }
        f.replies.add(new WechatApi.Response(200, "{bad"));
        try { api.updates(acct, ""); fail++; } catch (IOException e) { pass++; }
        try { new WechatApi(f, "1", "http://plain.example").startLogin(); fail++; } catch (IllegalArgumentException e) { pass++; }

        // 路由：同局域网 /say
        eq("空闲 → 新任务", WechatPolicy.route("打开美团", false, false, false), WechatPolicy.Route.START_TASK);
        eq("在办 + 非取消 → 不交", WechatPolicy.route("打开淘宝", true, false, true), WechatPolicy.Route.BUSY);
        eq("在办 + 取消 → 立即交", WechatPolicy.route("取消", true, false, true), WechatPolicy.Route.CANCEL);
        eq("在办 + 不对 → 立即交", WechatPolicy.route("不对", true, false, false), WechatPolicy.Route.CANCEL);
        eq("微信的任务在等确认 → 口令交进去", WechatPolicy.route("发送", true, true, true), WechatPolicy.Route.CONFIRM_REPLY);
        eq("微信的任务在等确认 → 确认下单交进去", WechatPolicy.route("确认下单", true, true, true), WechatPolicy.Route.CONFIRM_REPLY);
        eq("别处的任务在等确认 → 不替它确认", WechatPolicy.route("发送", true, true, false), WechatPolicy.Route.CONFIRM_NOT_OURS);
        eq("别处的任务在等确认 → 取消可以", WechatPolicy.route("取消", true, true, false), WechatPolicy.Route.CANCEL);
        eq("空闲 + 取消", WechatPolicy.route("取消", false, false, false), WechatPolicy.Route.IDLE_CANCEL);
        eq("空闲 + 光秃口令不当任务", WechatPolicy.route("确认付款", false, false, false), WechatPolicy.Route.STRAY_CONFIRM);
        eq("空闲 + 发送 不当任务", WechatPolicy.route("发送吧", false, false, false), WechatPolicy.Route.STRAY_CONFIRM);
        yes("BUSY 有回话", WechatPolicy.replyFor(WechatPolicy.Route.BUSY) != null);

        // 确认走微信；付款永不执行
        yes("发送可在微信确认", WechatPolicy.confirmsOverWechat(IrrevAction.SEND));
        yes("下单可在微信确认", WechatPolicy.confirmsOverWechat(IrrevAction.ORDER));
        yes("付款不能在微信确认", !WechatPolicy.confirmsOverWechat(IrrevAction.PAY));
        yes("付款永不执行", IrrevAction.PAY.neverExecuted());
        yes("付款没有口令", IrrevAction.PAY.phrase == null);
        yes("「确认付款」不算付款同意", ConfirmationGate.classify(IrrevAction.PAY, "确认付款") != ConfirmationGate.Reply.CONFIRM);
        eq("微信回「发送」= 同意", ConfirmationGate.classify(IrrevAction.SEND, "发送"), ConfirmationGate.Reply.CONFIRM);
        eq("微信回「确认下单」= 同意", ConfirmationGate.classify(IrrevAction.ORDER, "确认下单"), ConfirmationGate.Reply.CONFIRM);
        eq("微信回「好的」= 含糊", ConfirmationGate.classify(IrrevAction.ORDER, "好的"), ConfirmationGate.Reply.AMBIGUOUS);
        eq("微信回「不对」= 取消", ConfirmationGate.classify(IrrevAction.SEND, "不对"), ConfirmationGate.Reply.CANCEL);

        // 外发文字
        String rb = ConfirmationGate.readback(new ConfirmationGate.Declaration(IrrevAction.SEND, "", "张三", "你好", ""));
        String w = WechatPolicy.forWechat(rb);
        yes("复述保留对象", w.contains("张三") && w.contains("你好"));
        yes("「请说」改成「请回」", w.contains("请回「发送」") && !w.contains("请说"));
        String pay = WechatPolicy.forWechat(ConfirmationGate.readback(new ConfirmationGate.Declaration(IrrevAction.PAY, "", "", "", "¥18")));
        yes("付款提示本人操作", pay.contains("不会代付") && pay.contains("微信里回什么都不会付款"));
        String human = WechatPolicy.forWechat("这一屏需要你本人处理：密码输入。");
        yes("需要本人处理时提示亲手处理", human.contains("亲手处理"));
        String lan = "0123456789abcdef0123456789abcdef0123456789abcdef";
        String red = WechatPolicy.forWechat("口令 " + lan + " 令牌 " + TOKEN + " Bearer abcdefghijk key sk-abcdefghijklmnopqrstu", TOKEN);
        yes("局域网口令打码", !red.contains(lan));
        yes("Bot 令牌打码", !red.contains(TOKEN));
        yes("Bearer 打码", !red.contains("abcdefghijk "));
        yes("API Key 打码", !red.contains("sk-abcdefghijklmnopqrstu"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) big.append('字');
        yes("外发长度有上限", WechatPolicy.forWechat(big.toString()).length() <= WechatPolicy.MAX_OUT + 1);

        // 源码：微信通道不把令牌/游标/context_token/用户 id 写进日志
        Path dir = Paths.get("../app/src/main/java/com/yuanhou/hand/wechat");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) { s.forEach(files::add); }
        for (Path p : files) {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (!line.contains("AppLog.i(") && !line.contains("Log.")) continue;
                String l = line.toLowerCase();
                boolean bad = l.contains("token") || l.contains("cursor") || l.contains("ownerid") || l.contains("qrcontent")
                        || l.contains("content)") || l.contains("+ text") || l.contains("acct") || l.contains("account");
                if (bad) { fail++; System.out.println("FAIL 日志可能带机密：" + p.getFileName() + ": " + line.trim()); }
                else pass++;
            }
        }

        System.out.println("WechatCheck: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
