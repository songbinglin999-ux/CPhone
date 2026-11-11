package com.yuanhou.hand.model;

import com.yuanhou.hand.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI / DeepSeek 的 chat/completions 客户端（两家都是这个接口）。只在 ModelConfig.usable() 时调用。
 * 先用主模型；额度不够、Key 被拒、找不到模型、限流重试仍失败、或网络不通时，若备用已配好则自动改用备用。
 * Key 来自对应槽位（应用设置，其次本机运行时环境），只放 Authorization 头，不进日志、不进消息内容。
 * 没有 Key 时不发请求：返回 stop，并说明「请在设置里填写」。
 */
public final class ModelClient {
    private final ModelConfig cfg;
    private ModelConfig.Slot active;

    /** 最近一次失败的用户可见原因（不含 Key、不含响应体）；成功则为 null。 */
    private String lastError;
    private boolean failedOver;

    public ModelClient(ModelConfig cfg) {
        this.cfg = cfg;
        this.active = cfg.preferred();
    }

    public String lastError() {
        return lastError;
    }

    public String lastErrorOr(String fallback) {
        return lastError != null ? lastError : fallback;
    }

    private String hostLabel(ModelConfig.Slot s) {
        try {
            return new URL(s.effectiveBaseUrl()).getHost();
        } catch (Exception e) {
            return s == null || s.provider == null ? "模型服务" : s.label();
        }
    }

    private String unreachable(ModelConfig.Slot s, Exception e) {
        String kind = e instanceof java.net.SocketTimeoutException ? "已经超时"
                : e instanceof java.net.UnknownHostException ? "解析不到地址"
                : "网络打不开";
        return "连不上模型服务，" + kind + "。当前接口是 " + hostLabel(s)
                + "（" + s.label() + "），这台备用机打不开。";
    }

    static final String SYSTEM = "你是家里备用手机上的操作助手「远手」，在手机主屏上一步一步替用户办一件事。"
            + "只输出一个 JSON 对象，不要输出其他文字。字段：action（tap|long_press|type|clear|scroll|swipe|enter|back|home|open_app|wait|do_steps|done|stop），"
            + "ref（目标元素在界面树里的编号 #n；tap/long_press/type/clear/scroll 优先填 ref，本机会点它的中心；type 不填 ref 表示当前有焦点的输入框），"
            + "x,y（没有合适 ref 时才填屏幕像素坐标，取目标中心；swipe 的起点），x2,y2（swipe 终点，直线），"
            + "text（type 要输入的完整文字；scroll 填 down=往下看更多 或 up=往回看），app（open_app：wechat|taobao|xianyu|alipay|meituan|meituanwaimai），"
            + "gate（这一步若会发出消息/评论填 send，删除或撤回填 delete，提交表单填 submit，下单填 order，付款填 pay，"
            + "屏上有应付金额时的其他确认填 other，否则 null），"
            + "details（gate 时必填：to 或 target（对象，发送时是标题栏上的名字）、text（要发/要提交/要删的内容原文）、"
            + "order 还要 shop,item,qty,address；amount（屏上应付金额，带数字；屏上有应付金额时 submit/delete 也要填）），"
            + "say（给用户的一句简短中文说明）。"
            + "已知接下来好几步且还不需要看新一屏时，用 action=do_steps，steps 为 1–20 个同样字段的动作；整批只算一轮。"
            + "子步可带 expect（屏上出现这些字）、expect_gone、expect_app（wechat|taobao|xianyu|alipay|meituan|meituanwaimai）、"
            + "wait_stable、timeout_s（默认 8 秒、最多 20 秒）：本机读屏等到成立再做下一步，不必为加载再问你。"
            + "屏幕会变之后不要再用这一屏的 ref；用 expect 等到后结束这一批，下一轮再根据新界面树继续。"
            + "发送、删除、提交、下单、付款不要放进 do_steps，在那一步之前结束这一批，再单独给出带 gate 的一步。"
            + "type 会先清空输入框再贴入并核对，核对不一致会失败，不要重复 type 同一段；不要往密码框里 type。"
            + "做法：先浏览、先起草——要发消息时先 type 把正文写进输入框，下一步再 tap 发送按钮并标 gate=send，本机会把对象、内容、金额念给用户，"
            + "听到口令并核对输入框里的字一致才点；删除、提交、下单同理，停在最后一步前标 gate。付款永远不做，遇到就 stop。"
            + "规则：遇到密码、验证码、支付密码、解锁页，或应用提示环境异常、拒绝自动操作，一律返回 stop 并说明；"
            + "已做过的步骤里出现「提醒」时，说明上一步没效果或在兜圈，换别的办法或 stop，不要重复同一个动作；"
            + "不要群发、不要批量下单、不要自己付款；看不清就返回 stop，不要猜坐标。任务完成返回 done，say 里念出结果。"
            + "本机会强制再做一轮目标核对，对照用户原话和当前屏幕；没核对通过不会结束，步骤里会出现「目标核对未通过」，这时要继续办，不要连着重复 done。"
            + "不要自己返回 goal_check。stop 会立刻停下，不再做目标核对。用户说取消也会立刻停下。"
            + "已做过的步骤变长后，较早的步骤收成「第N步 动作 目标或应用 结果 界面」的摘要，最近几步仍是原文。摘要里的每一步都做过，不要当成没发生，也不要重复已经做成的关键步。"
            + "若前面有「本机记得」或「可参考的技能」，那是这台手机上保存的偏好、应用经验和办成过的路线提纲，只按当前屏幕改着用，不要按坐标回放，也不要把提纲里没有发生的事当成已经做成。"
            + "广告、红包、领券、直播间和跳出当前应用由本机代码处理，你不要点「开红包」「立即领取」「摇一摇」或广告上的按钮。"
            + "进应用时按技能里的进入路线，从搜索或「我的」「订单」进，不要从首页信息流进。"
            + "步数没有短任务上限，长一点的事要一直做到核对通过的 done 或必须停下，不要因为步骤多就提前 stop。";

    static final String ANSWER = "你是家里备用手机上的助手「远手」。用一两句简短中文口语回答用户，会被念给他听。"
            + "如果给了屏幕内容，只根据屏幕内容回答，看不到的就说看不到；不要编造。不要建议你去操作手机，这一轮只回答。";

    /** 阻塞调用；失败返回 action=stop。 */
    public ModelAction nextAction(String intent, String history, String screenText, String jpegBase64) {
        return nextAction(intent, history, screenText, jpegBase64, null);
    }

    /** [prior] 是本机记得和可参考技能，只放和这件事有关的几条；可以为空。 */
    public ModelAction nextAction(String intent, String history, String screenText, String jpegBase64, String prior) {
        String miss = cfg.missing();
        if (miss != null) return stop(miss + "，先停下。");
        try {
            String userText = PromptText.actionUser(intent, history, screenText, prior);
            if (active == null) active = cfg.preferred();
            String firstErr = null;
            Result r = post(active, buildActionMsgs(userText, jpegBase64, active), 45000);
            if (r.error != null) {
                firstErr = r.error;
                if (tryFailover(firstErr)) {
                    r = post(active, buildActionMsgs(userText, jpegBase64, active), 45000);
                    if (r.error != null) {
                        r.error = joinErrors(firstErr, r.error);
                        lastError = r.error;
                    }
                } else {
                    r.error = finishError(firstErr);
                    lastError = r.error;
                }
            }
            if (r.error != null) return stop(r.error);
            return ModelAction.parse(r.content);
        } catch (Exception e) {
            AppLog.i("模型调用失败: " + e.getClass().getSimpleName());
            lastError = finishError(unreachable(active, e));
            return stop(lastError);
        }
    }

    private JSONArray buildActionMsgs(String userText, String jpegBase64, ModelConfig.Slot slot) throws Exception {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", SYSTEM));
        boolean useVision = jpegBase64 != null && cfg.vision && !ModelKeys.DEEPSEEK.equals(slot.provider);
        if (useVision) {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", "text").put("text", userText));
            parts.put(new JSONObject().put("type", "image_url").put("image_url",
                    new JSONObject().put("url", "data:image/jpeg;base64," + jpegBase64)));
            msgs.put(new JSONObject().put("role", "user").put("content", parts));
        } else {
            msgs.put(new JSONObject().put("role", "user").put("content", userText));
        }
        return msgs;
    }

    /** 通用一问一答（意图闸、讨论、读屏回答用）。失败或没配置返回 null，不抛异常。 */
    public String complete(String system, String user) {
        if (cfg.missing() != null) return null;
        try {
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", system));
            msgs.put(new JSONObject().put("role", "user").put("content", user));
            Result r = call(msgs, 30000);
            if (r.error != null) {
                lastError = r.error;
                return null;
            }
            return r.content;
        } catch (Exception e) {
            AppLog.i("模型调用失败: " + e.getClass().getSimpleName());
            lastError = finishError(unreachable(active, e));
            return null;
        }
    }

    /**
     * 先试当前接口能不能通（GET /models，不带 Key，以免占用额度；不把响应体写入日志）。
     * 主模型网络通（含 401/403/429）返回 true；不通且备用已配好时改探备用。
     * 都失败返回 false，并写 lastError。
     */
    public boolean probe() {
        lastError = null;
        String miss = cfg.missing();
        if (miss != null) {
            lastError = miss + "。";
            return false;
        }
        ModelConfig.Slot first = cfg.preferred();
        active = first;
        if (probeSlot(first)) return true;
        String firstErr = lastError;
        if (tryFailover(firstErr)) {
            if (probeSlot(active)) {
                lastError = null;
                return true;
            }
            lastError = joinErrors(firstErr, lastError);
            return false;
        }
        lastError = finishError(firstErr);
        return false;
    }

    private boolean probeSlot(ModelConfig.Slot slot) {
        HttpURLConnection c = null;
        try {
            String base = slot.effectiveBaseUrl();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            c = (HttpURLConnection) new URL(base + "/models").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code >= 500) {
                AppLog.i("模型探测 HTTP " + code + "（" + slot.label() + "）");
                lastError = "模型服务返回错误 " + code + "，先停下。";
                return false;
            }
            return true;
        } catch (Exception e) {
            AppLog.i("模型探测失败: " + e.getClass().getSimpleName() + "（" + slot.label() + "）");
            lastError = unreachable(slot, e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 讨论 / 读取：只回答，不出动作。screenText 为 null 表示不看屏幕。 */
    public String answer(String utterance, String screenText) {
        String u = "用户说：" + utterance + (screenText == null ? "" : "\n当前屏幕（无障碍只读）：\n" + screenText);
        return complete(ANSWER, u);
    }

    private static final class Result {
        String content, error;
    }

    private static final class ApiErr {
        String type = "";
        String code = "";
        String message = "";
        String blob() {
            return (type + " " + code + " " + message).toLowerCase();
        }
        boolean quota() {
            String b = blob();
            return b.contains("insufficient_quota") || b.contains("exceeded your current quota");
        }
        boolean rateLimit() {
            if (quota()) return false;
            String b = blob();
            return b.contains("rate_limit") || b.contains("rate limit");
        }
    }

    /** 只取 error.type / code / message，不把响应体写入日志；疑似含 Key 的说明丢掉。 */
    private static ApiErr parseApiErr(String resp) {
        ApiErr e = new ApiErr();
        if (resp == null || resp.isEmpty()) return e;
        try {
            JSONObject err = new JSONObject(resp).optJSONObject("error");
            if (err == null) return e;
            e.type = err.optString("type", "");
            e.code = err.optString("code", "");
            String m = err.optString("message", "");
            if (m.contains("sk-") || m.contains("Bearer") || m.length() > 240) m = "";
            e.message = m;
        } catch (Exception ignored) {
        }
        return e;
    }

    private static String httpError(int code, ApiErr e) {
        if (code == 401 || code == 403) {
            return "模型服务拒绝了这个 Key（" + code + "），请在设置里检查 API Key。";
        }
        if (code == 429) {
            if (e.quota()) {
                return "模型额度不够了（429），请到该服务商后台检查账单或额度。";
            }
            if (e.rateLimit()) {
                return "模型服务暂时限流（429），请稍后再试。";
            }
            return "模型服务返回错误 429，先停下。";
        }
        if (code == 404 || "model_not_found".equals(e.code)) {
            return "模型服务找不到这个模型（" + code + "），请在设置里换一个模型名。";
        }
        return "模型服务返回错误 " + code + "，先停下。";
    }

    /** 对当前 active 发请求；失败且可切换备用时改用备用再试一次。 */
    private Result call(JSONArray msgs, int readTimeoutMs) throws Exception {
        if (active == null) active = cfg.preferred();
        Result r = post(active, msgs, readTimeoutMs);
        if (r.error == null) return r;
        if (!tryFailover(r.error)) {
            r.error = finishError(r.error);
            lastError = r.error;
            return r;
        }
        Result r2 = post(active, msgs, readTimeoutMs);
        if (r2.error != null) {
            r2.error = joinErrors(r.error, r2.error);
            lastError = r2.error;
        }
        return r2;
    }

    private boolean tryFailover(String reason) {
        if (failedOver) return false;
        if (reason != null && reason.contains("被中断")) return false;
        ModelConfig.Slot next = cfg.other(active);
        if (next == null || !next.complete()) return false;
        AppLog.i("主模型失败，改用备用 " + next.label());
        active = next;
        failedOver = true;
        return true;
    }

    private String finishError(String err) {
        ModelConfig.Slot unused = cfg.other(active != null ? active : cfg.preferred());
        if (unused == null) return err;
        // 已经在用备用、主模型本来就没配好时，不再把主模型说成「备用」。
        if (unused == cfg.primary() && !unused.complete()) return err;
        if (!unused.hasKey()) {
            return trimDot(err) + "。备用 " + unused.label() + " 还没填 Key，请在设置里填写。";
        }
        if (!unused.complete()) {
            return trimDot(err) + "。备用 " + unused.label() + " 配置不完整，请在设置里检查。";
        }
        return err;
    }

    private String joinErrors(String first, String second) {
        ModelConfig.Slot bak = active;
        String a = trimDot(first);
        String b = trimDot(second == null ? "备用模型也失败了" : second);
        return a + "。备用 " + bak.label() + " 也没成功：" + b + "。";
    }

    private static String trimDot(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("。") || t.endsWith(".") || t.endsWith("，")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    private Result post(ModelConfig.Slot slot, JSONArray msgs, int readTimeoutMs) throws Exception {
        Result res = new Result();
        String base = slot.effectiveBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL url = new URL(base + "/chat/completions");
        JSONObject body = new JSONObject();
        body.put("model", slot.effectiveModel());
        body.put("temperature", 0.1);
        body.put("messages", msgs);
        byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);

        int[] delaysMs = {0, 2000, 5000};
        lastError = null;
        for (int attempt = 0; attempt < delaysMs.length; attempt++) {
            if (delaysMs[attempt] > 0) {
                try {
                    Thread.sleep(delaysMs[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    res.error = "模型调用被中断，先停下。";
                    lastError = res.error;
                    return res;
                }
            }
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(readTimeoutMs);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + slot.effectiveKey());
                try (OutputStream os = c.getOutputStream()) {
                    os.write(out);
                }
                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String resp = read(is);
                if (code >= 400) {
                    ApiErr err = parseApiErr(resp);
                    AppLog.i("模型 HTTP " + code + "（" + slot.label() + "）");   // 不记录响应体（可能回显请求头信息）
                    if (code == 429 && err.rateLimit() && attempt < delaysMs.length - 1) {
                        AppLog.i("模型 HTTP 429 限流，将重试（" + slot.label() + "）");
                        continue;
                    }
                    res.error = httpError(code, err);
                    lastError = res.error;
                    return res;
                }
                JSONObject r = new JSONObject(resp);
                res.content = r.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "");
                return res;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        res.error = "模型服务返回错误 429，先停下。";
        lastError = res.error;
        return res;
    }

    private static ModelAction stop(String say) {
        ModelAction a = new ModelAction();
        a.type = "stop";
        a.say = say;
        return a;
    }

    private static String read(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
