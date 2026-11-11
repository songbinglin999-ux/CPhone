package com.yuanhou.hand.memory;

import com.yuanhou.hand.policy.UntilCheck;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 办成并核对通过之后留下的路线提纲。下次相似的事把提纲放进上下文，由模型按当前屏幕改，不按坐标回放。
 */
public final class SkillBook {
    static final String FILE = "skills.json";
    static final double MATCH_DICE = 0.34;
    static final double MATCH_DICE_WITH_APP = 0.22;
    static final int MATCH_SHARED_WITH_APP = 2;
    static final double REPLACE_DICE = 0.72;
    private static final int MAX_SKILLS = 24;
    private static final int MAX_INJECT = 2;

    private static final Set<String> STOP = new HashSet<>();
    static {
        Collections.addAll(STOP, "帮我", "一下", "一个", "这个", "那个", "怎么", "什么", "然后", "现在", "请你", "的话", "可以", "我们");
    }

    private static final Pattern WORD = Pattern.compile("[a-z0-9]{2,}");

    static final class Skill {
        String id;
        String goal;
        final List<String> apps = new ArrayList<>();
        String preconditions;
        String entry = "";
        final List<String> outline = new ArrayList<>();
        int uses;
        long savedAt;
    }

    private final List<Skill> skills = new ArrayList<>();
    private int nextId = 1;
    private final Set<String> injected = new HashSet<>();

    public static SkillBook load(File dir) {
        SkillBook book = new SkillBook();
        String text = PlaybookFiles.read(dir, FILE);
        if (text == null || text.isEmpty()) return book;
        try {
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.optJSONArray("skills");
            if (arr == null) return book;
            int max = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Skill s = new Skill();
                s.id = o.optString("id", "");
                s.goal = o.optString("goal", "");
                s.preconditions = o.optString("preconditions", "");
                s.entry = o.optString("entry", "");
                if (s.entry.isEmpty()) s.entry = RouteText.DEFAULT_ENTRY;
                s.uses = o.optInt("uses", 0);
                s.savedAt = o.optLong("savedAt", 0);
                JSONArray apps = o.optJSONArray("apps");
                if (apps != null) {
                    for (int a = 0; a < apps.length(); a++) {
                        String name = UntilCheck.displayName(apps.optString(a, ""));
                        if (name != null && !s.apps.contains(name)) s.apps.add(name);
                    }
                }
                JSONArray outline = o.optJSONArray("outline");
                if (outline != null) {
                    for (int k = 0; k < outline.length(); k++) {
                        String line = outline.optString(k, "").trim();
                        if (line.isEmpty() || Sensitive.blocked(line) || line.matches(".*\\d{2,}\\s*,\\s*\\d{2,}.*")) continue;
                        s.outline.add(line);
                    }
                }
                if (s.goal.isEmpty() || s.outline.isEmpty() || Sensitive.blocked(s.goal)) continue;
                if (s.id.isEmpty()) s.id = "s" + (max + 1);
                book.skills.add(s);
                int n = idNum(s.id);
                if (n > max) max = n;
            }
            book.nextId = max + 1;
        } catch (Exception ignored) {
            book.skills.clear();
        }
        return book;
    }

    public void save(File dir) {
        try {
            JSONArray arr = new JSONArray();
            for (Skill s : skills) {
                JSONArray apps = new JSONArray();
                for (String a : s.apps) apps.put(a);
                JSONArray outline = new JSONArray();
                for (String line : s.outline) outline.put(line);
                arr.put(new JSONObject()
                        .put("id", s.id)
                        .put("goal", s.goal)
                        .put("apps", apps)
                        .put("preconditions", s.preconditions == null ? "" : s.preconditions)
                        .put("entry", s.entry == null ? "" : s.entry)
                        .put("outline", outline)
                        .put("uses", s.uses)
                        .put("savedAt", s.savedAt));
            }
            PlaybookFiles.write(dir, FILE, new JSONObject().put("v", 1).put("skills", arr).toString());
        } catch (Exception ignored) {
        }
    }

    public int size() { return skills.size(); }

    /** 核对通过的任务才记。提纲是动作说明，不写点击坐标。 */
    public void recordVerified(String utterance, List<StepHistory.Turn> turns) {
        String goal = RouteText.goalPattern(utterance);
        if (goal.isEmpty()) return;
        List<String> outline = clean(RouteText.outline(turns, 12));
        if (outline.isEmpty()) return;
        List<String> apps = RouteText.apps(turns);
        if (apps.isEmpty()) apps = UntilCheck.mentionedApps(utterance);
        String pre = RouteText.preconditions(apps);
        String entry = RouteText.entryRoute(turns);
        for (Skill s : skills) {
            if (dice(goal, s.goal) >= REPLACE_DICE) {
                s.goal = goal;
                s.apps.clear();
                s.apps.addAll(apps);
                s.preconditions = pre;
                s.entry = entry;
                s.outline.clear();
                s.outline.addAll(outline);
                s.savedAt = System.currentTimeMillis();
                return;
            }
        }
        Skill s = new Skill();
        s.id = "s" + nextId++;
        s.goal = goal;
        s.apps.addAll(apps);
        s.preconditions = pre;
        s.entry = entry;
        s.outline.addAll(outline);
        s.savedAt = System.currentTimeMillis();
        skills.add(s);
        while (skills.size() > MAX_SKILLS) dropLeastUsed();
    }

    public String forIntent(String intent) {
        List<Skill> hits = match(intent, MAX_INJECT);
        if (hits.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        b.append("可参考的技能（按当前屏幕改，不要按坐标回放；按「进入」走，不要从首页信息流进；发送、删除、提交、下单仍要口令，付款不代付）：\n");
        for (Skill s : hits) {
            if (injected.add(s.id)) s.uses++;
            b.append("- 目标：").append(s.goal).append('\n');
            if (!s.apps.isEmpty()) {
                b.append("  应用：");
                for (int i = 0; i < s.apps.size(); i++) {
                    if (i > 0) b.append("、");
                    b.append(s.apps.get(i));
                }
                b.append('\n');
            }
            b.append("  前提：").append(s.preconditions).append('\n');
            b.append("  进入：").append(s.entry == null || s.entry.isEmpty() ? RouteText.DEFAULT_ENTRY : s.entry).append('\n');
            b.append("  提纲：");
            for (int i = 0; i < s.outline.size(); i++) {
                if (i > 0) b.append(" → ");
                b.append(s.outline.get(i));
            }
            b.append('\n');
        }
        return b.toString();
    }

    /** 最相近的一条技能的进入路线；没有则是默认的搜索 / 我的 / 订单。 */
    public String entryFor(String intent) {
        List<Skill> hits = match(intent, 1);
        if (hits.isEmpty()) return RouteText.DEFAULT_ENTRY;
        String entry = hits.get(0).entry;
        return entry == null || entry.isEmpty() ? RouteText.DEFAULT_ENTRY : entry;
    }

    List<Skill> match(String intent, int limit) {
        List<Skill> scored = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        String text = intent == null ? "" : intent;
        for (Skill s : skills) {
            double d = dice(text, s.goal);
            int shared = sharedCount(text, s.goal);
            boolean shareApp = shareApp(text, s);
            boolean ok = d >= MATCH_DICE || (shareApp && d >= MATCH_DICE_WITH_APP && shared >= MATCH_SHARED_WITH_APP);
            if (!ok) continue;
            double score = d + (shareApp ? 0.05 : 0);
            int at = 0;
            while (at < scores.size() && scores.get(at) >= score) at++;
            scored.add(at, s);
            scores.add(at, score);
        }
        if (scored.size() > limit) return new ArrayList<>(scored.subList(0, limit));
        return scored;
    }

    private void dropLeastUsed() {
        int worst = 0;
        for (int i = 1; i < skills.size(); i++) {
            if (skills.get(i).uses < skills.get(worst).uses) worst = i;
            else if (skills.get(i).uses == skills.get(worst).uses && skills.get(i).savedAt < skills.get(worst).savedAt) worst = i;
        }
        skills.remove(worst);
    }

    private static List<String> clean(List<String> outline) {
        List<String> out = new ArrayList<>();
        if (outline == null) return out;
        for (String line : outline) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty() || Sensitive.blocked(t) || t.matches(".*\\d{2,}\\s*,\\s*\\d{2,}.*")) continue;
            out.add(t);
        }
        return out;
    }

    private static boolean shareApp(String intent, Skill s) {
        List<String> mentioned = UntilCheck.mentionedApps(intent);
        for (String a : s.apps) if (mentioned.contains(a)) return true;
        return false;
    }

    static double dice(String a, String b) {
        Set<String> ga = grams(a);
        Set<String> gb = grams(b);
        if (ga.isEmpty() || gb.isEmpty()) return 0;
        int inter = 0;
        for (String x : ga) if (gb.contains(x)) inter++;
        return 2.0 * inter / (ga.size() + gb.size());
    }

    static int sharedCount(String a, String b) {
        Set<String> ga = grams(a);
        Set<String> gb = grams(b);
        int n = 0;
        for (String x : ga) if (gb.contains(x)) n++;
        return n;
    }

    private static Set<String> grams(String s) {
        Set<String> g = new LinkedHashSet<>();
        if (s == null || s.isEmpty()) return g;
        String lower = s.toLowerCase();
        Matcher m = WORD.matcher(lower);
        while (m.find()) g.add(m.group());
        String cjk = lower.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i + 1 < cjk.length(); i++) {
            String bg = cjk.substring(i, i + 2);
            if (!STOP.contains(bg)) g.add(bg);
        }
        return g;
    }

    private static int idNum(String id) {
        if (id == null || !id.startsWith("s")) return 0;
        try {
            return Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
