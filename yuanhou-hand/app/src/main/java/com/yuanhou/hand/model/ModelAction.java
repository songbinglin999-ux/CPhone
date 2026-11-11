package com.yuanhou.hand.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 模型返回的动作：单步，或一次多步（do_steps）。 */
public final class ModelAction {
    public String type = "stop";   // tap | long_press | type | clear | scroll | swipe | enter | back | home | open_app | wait | do_steps | done | stop（paste = type 的旧名）
    /** 目标元素编号（界面树里的 #n）；&gt;0 时优先于 x,y，由本机换算成元素中心。 */
    public int ref;
    public int x, y, x2, y2;
    public String text = "";
    public String app = "";
    public String gate;            // send | delete | submit | order | pay | other | null（旧名 send_message/place_order/payment 也认）
    public final Map<String, String> details = new HashMap<>();
    public String say = "";
    public String raw = "";

    /** 一次多步里的子动作（仅 type=do_steps）。 */
    public List<ModelAction> steps = Collections.emptyList();
    /** 子步做完后的等待（都可不给）：屏上出现 / 消失 / 前台 App，以及先等界面稳定。 */
    public String expect = "";
    public String expectGone = "";
    public String expectApp = "";
    public boolean waitStable;
    public int timeoutSec;

    public static ModelAction parse(String content) {
        ModelAction a = new ModelAction();
        a.raw = content == null ? "" : content;
        try {
            String s = a.raw.trim();
            int i = s.indexOf('{'), j = s.lastIndexOf('}');
            if (i < 0 || j <= i) throw new IllegalArgumentException("no json");
            fill(a, new JSONObject(s.substring(i, j + 1)), true);
        } catch (Exception e) {
            a.type = "stop";
            a.say = "模型返回的内容看不懂，先停下。";
            a.steps = Collections.emptyList();
        }
        return a;
    }

    static void fill(ModelAction a, JSONObject o, boolean top) throws Exception {
        a.type = o.optString("action", o.optString("tool", "stop"));
        if ("paste".equals(a.type)) a.type = "type";
        a.ref = o.optInt("ref", 0);
        a.x = o.optInt("x", -1);
        a.y = o.optInt("y", -1);
        a.x2 = o.optInt("x2", -1);
        a.y2 = o.optInt("y2", -1);
        a.text = o.optString("text", "");
        a.app = o.optString("app", "");
        String g = o.optString("gate", "");
        a.gate = g.isEmpty() || "null".equals(g) ? null : g;
        a.say = o.optString("say", "");
        a.expect = o.optString("expect", "");
        a.expectGone = o.optString("expect_gone", "");
        a.expectApp = o.optString("expect_app", "");
        a.waitStable = o.optBoolean("wait_stable", false);
        a.timeoutSec = o.optInt("timeout_s", 0);
        JSONObject d = o.optJSONObject("details");
        if (d != null) {
            Iterator<String> it = d.keys();
            while (it.hasNext()) {
                String k = it.next();
                a.details.put(k, d.optString(k, ""));
            }
        }
        if (top && "do_steps".equals(a.type)) {
            JSONArray arr = o.optJSONArray("steps");
            List<ModelAction> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject so = arr.optJSONObject(i);
                    if (so == null) continue;
                    ModelAction s = new ModelAction();
                    fill(s, so, false);
                    list.add(s);
                }
            }
            a.steps = list;
        }
    }
}
