package com.yuanhou.hand.memory;

import com.yuanhou.hand.policy.UntilCheck;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨任务的本机记事：用户偏好，以及某个应用里做成过 / 没做成的经验。
 * 只在用户原话写明偏好时记偏好；应用经验只来自办成的路线和明确失败。不记密钥。
 */
public final class AgentMemory {
    static final String FILE = "memory.json";
    private static final int MAX_NOTES_PER_APP = 6;
    private static final int MAX_APPS = 8;

    private static final Pattern CITY = Pattern.compile(
            "(?:常住|人在|我住在|所在城市(?:是|为)?|默认城市(?:是|为)?)[:：\\s]*([\\u4e00-\\u9fa5]{2,8}?)(?:市)?(?![\\u4e00-\\u9fa5])");
    private static final Pattern USUAL = Pattern.compile(
            "(?:习惯用|常用|一般用|默认用)(美团外卖|微信|淘宝|闲鱼|支付宝|美团)");
    private static final Pattern CONFIRM = Pattern.compile(
            "确认时(?:要|请|必须)?(等我说口令|先念内容再等口令|念完整口令)");

    static final class Pref {
        String key;
        String value;
    }

    static final class Note {
        String app;
        String outcome;
        String note;
    }

    private final List<Pref> prefs = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();

    AgentMemory() {}

    public static AgentMemory load(File dir) {
        AgentMemory m = new AgentMemory();
        String text = PlaybookFiles.read(dir, FILE);
        if (text == null || text.isEmpty()) return m;
        try {
            JSONObject o = new JSONObject(text);
            JSONArray prefs = o.optJSONArray("prefs");
            if (prefs != null) {
                for (int i = 0; i < prefs.length(); i++) {
                    JSONObject p = prefs.optJSONObject(i);
                    if (p == null) continue;
                    m.putPref(p.optString("key", ""), p.optString("value", ""));
                }
            }
            JSONArray notes = o.optJSONArray("apps");
            if (notes != null) {
                for (int i = 0; i < notes.length(); i++) {
                    JSONObject n = notes.optJSONObject(i);
                    if (n == null) continue;
                    m.offerApp(n.optString("outcome", ""), n.optString("app", ""), n.optString("note", ""));
                }
            }
        } catch (Exception ignored) {
            m.prefs.clear();
            m.notes.clear();
        }
        return m;
    }

    public void save(File dir) {
        try {
            JSONArray prefs = new JSONArray();
            for (Pref p : this.prefs) {
                prefs.put(new JSONObject().put("key", p.key).put("value", p.value));
            }
            JSONArray apps = new JSONArray();
            for (Note n : notes) {
                apps.put(new JSONObject().put("app", n.app).put("outcome", n.outcome).put("note", n.note));
            }
            JSONObject o = new JSONObject().put("v", 1).put("prefs", prefs).put("apps", apps);
            PlaybookFiles.write(dir, FILE, o.toString());
        } catch (Exception ignored) {
            // 记事写失败不影响任务结局
        }
    }

    /** 只从原话里的明确说法记偏好。 */
    public void learnUtterance(String utterance) {
        if (utterance == null || utterance.isEmpty()) return;
        Matcher city = CITY.matcher(utterance);
        if (city.find()) {
            String c = city.group(1);
            if (c.endsWith("市") && c.length() > 2) c = c.substring(0, c.length() - 1);
            if (plausibleCity(c)) putPref("city", c);
        }
        Matcher usual = USUAL.matcher(utterance);
        if (usual.find()) {
            String app = UntilCheck.displayName(usual.group(1));
            if (app != null) putPref("usual_app", app);
        }
        Matcher confirm = CONFIRM.matcher(utterance);
        if (confirm.find()) putPref("confirm_style", confirm.group(1));
    }

    public void learnSuccess(List<StepHistory.Turn> turns) {
        if (turns == null) return;
        List<String> apps = RouteText.apps(turns);
        for (String app : apps) {
            List<StepHistory.Turn> mine = new ArrayList<>();
            for (StepHistory.Turn t : turns) {
                if (t != null && app.equals(t.app)) mine.add(t);
            }
            List<String> bits = RouteText.outline(mine, 6);
            if (bits.isEmpty()) continue;
            StringBuilder note = new StringBuilder("办成过：");
            for (int i = 0; i < bits.size(); i++) {
                if (i > 0) note.append("，");
                note.append(bits.get(i));
            }
            offerApp("worked", app, note.toString());
        }
    }

    public void learnFailure(String failLabel, List<StepHistory.Turn> turns) {
        if (failLabel == null || failLabel.isEmpty() || Sensitive.blocked(failLabel)) return;
        String app = "";
        String detail = "";
        if (turns != null) {
            for (int i = turns.size() - 1; i >= 0; i--) {
                StepHistory.Turn t = turns.get(i);
                if (!"action".equals(t.kind)) continue;
                if (app.isEmpty() && t.app != null && !t.app.isEmpty()) app = t.app;
                if (detail.isEmpty()) {
                    String p = RouteText.phrase(t);
                    if (!p.isEmpty()) detail = p;
                }
                if (!app.isEmpty() && !detail.isEmpty() && t.result != null && t.result.startsWith("没做成")) break;
            }
            if (app.isEmpty()) {
                List<String> apps = RouteText.apps(turns);
                if (!apps.isEmpty()) app = apps.get(apps.size() - 1);
            }
        }
        if (UntilCheck.displayName(app) == null) return;
        String note = "没办成（" + failLabel + "）" + (detail.isEmpty() ? "" : "：" + detail);
        offerApp("failed", app, note);
    }

    /** 广告打断写回应用经验。homeFeed 为真时记下「从首页进弹窗多」。不写密钥。 */
    public void learnInterruption(String app, boolean homeFeed) {
        String note = homeFeed
                ? "从首页进弹窗多，下次从搜索或「我的」「订单」进入"
                : "出现过广告或红包弹窗，下次从搜索或「我的」「订单」进入，不从首页信息流进";
        offerApp("note", app, note);
    }

    /**
     * 只拼和这句话、当前前台应用有关的几条。无关应用的经验不放进去。
     */
    public String forIntent(String intent, String packageName) {
        String text = intent == null ? "" : intent;
        List<String> apps = new ArrayList<>(UntilCheck.mentionedApps(text));
        String fg = UntilCheck.displayName(packageName);
        if (fg != null && !apps.contains(fg)) apps.add(fg);
        Pref usual = pref("usual_app");
        if (usual != null && (text.contains("外卖") || text.contains(usual.value)) && !apps.contains(usual.value)) {
            apps.add(usual.value);
        }
        StringBuilder b = new StringBuilder();
        for (Pref p : prefs) {
            if (!relevantPref(p, text, apps)) continue;
            b.append("- ").append(prefLabel(p.key)).append("：").append(p.value).append('\n');
        }
        for (Note n : notes) {
            if (!apps.contains(n.app)) continue;
            b.append("- ").append(n.app);
            b.append("worked".equals(n.outcome) ? "（做成过）" : "failed".equals(n.outcome) ? "（没做成）" : "（留意）");
            b.append("：").append(n.note).append('\n');
        }
        if (b.length() == 0) return "";
        return "本机记得（只和这件事有关，不要当成当前屏幕上的事实）：\n" + b;
    }

    private boolean relevantPref(Pref p, String text, List<String> apps) {
        if ("city".equals(p.key)) {
            return text.contains(p.value) || text.contains("外卖") || text.contains("附近")
                    || text.contains("本地") || text.contains("天气") || text.contains("到店") || text.contains("城市");
        }
        if ("usual_app".equals(p.key)) return apps.contains(p.value) || text.contains(p.value);
        if ("confirm_style".equals(p.key)) {
            return text.contains("发") || text.contains("下单") || text.contains("提交")
                    || text.contains("删除") || text.contains("确认") || text.contains("买") || text.contains("评论");
        }
        return false;
    }

    private static String prefLabel(String key) {
        if ("city".equals(key)) return "常去的城市";
        if ("usual_app".equals(key)) return "习惯用的应用";
        if ("confirm_style".equals(key)) return "确认习惯";
        return key;
    }

    private Pref pref(String key) {
        for (Pref p : prefs) if (key.equals(p.key)) return p;
        return null;
    }

    private void putPref(String key, String value) {
        if (value == null) return;
        String v = value.trim();
        if (v.isEmpty() || Sensitive.blocked(v)) return;
        if (!"city".equals(key) && !"usual_app".equals(key) && !"confirm_style".equals(key)) return;
        if ("city".equals(key) && !plausibleCity(v)) return;
        if ("usual_app".equals(key) && UntilCheck.displayName(v) == null) return;
        for (Pref p : prefs) {
            if (key.equals(p.key)) {
                p.value = v;
                return;
            }
        }
        Pref p = new Pref();
        p.key = key;
        p.value = v;
        prefs.add(p);
    }

    private static boolean plausibleCity(String city) {
        if (city == null) return false;
        String c = city.trim();
        if (c.endsWith("市") && c.length() > 2) c = c.substring(0, c.length() - 1);
        if (c.length() < 2 || c.length() > 6) return false;
        if (UntilCheck.displayName(c) != null) return false;
        if (c.contains("美团") || c.contains("淘宝") || c.contains("微信") || c.contains("支付宝") || c.contains("闲鱼")) {
            return false;
        }
        return !Sensitive.blocked(c);
    }

    private void offerApp(String outcome, String app, String note) {
        String shown = UntilCheck.displayName(app);
        if (shown == null) return;
        if (!"worked".equals(outcome) && !"failed".equals(outcome) && !"note".equals(outcome)) return;
        if (note == null) return;
        String n = note.trim();
        if (n.isEmpty() || Sensitive.blocked(n)) return;
        if (n.length() > 200) n = n.substring(0, 200);
        for (Note have : notes) {
            if (shown.equals(have.app) && outcome.equals(have.outcome) && n.equals(have.note)) return;
        }
        int count = 0;
        for (Note have : notes) if (shown.equals(have.app)) count++;
        if (count >= MAX_NOTES_PER_APP) {
            for (int i = 0; i < notes.size(); i++) {
                if (shown.equals(notes.get(i).app)) {
                    notes.remove(i);
                    break;
                }
            }
        }
        if (!hasApp(shown) && distinctApps() >= MAX_APPS) {
            String oldest = notes.get(0).app;
            for (int i = notes.size() - 1; i >= 0; i--) {
                if (oldest.equals(notes.get(i).app)) notes.remove(i);
            }
        }
        Note noteObj = new Note();
        noteObj.app = shown;
        noteObj.outcome = outcome;
        noteObj.note = n;
        notes.add(noteObj);
    }

    private boolean hasApp(String app) {
        for (Note n : notes) if (app.equals(n.app)) return true;
        return false;
    }

    private int distinctApps() {
        List<String> seen = new ArrayList<>();
        for (Note n : notes) if (!seen.contains(n.app)) seen.add(n.app);
        return seen.size();
    }
}
