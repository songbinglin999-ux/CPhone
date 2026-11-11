package com.yuanhou.hand.session;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地意图（不需要模型 Key）：把一句话拆成几步主屏操作。纯 Java，可在桌面 JVM 上自检。
 *
 * 支持（可用「然后」「接着」「，」「；」串起来；带标点的文字请用「」括起来）：
 *   点击 XXX / 点 XXX        —— 点屏幕上写着 XXX 的按钮或文字（按界面树里的文字找，不猜坐标）
 *   点击 #12                  —— 点界面树编号 12 的元素（「读屏」会列编号）
 *   点击 540,1200             —— 点坐标（联调用）
 *   在 XXX 输入 YYY           —— 先点提示语/名字是 XXX 的输入框，再把 YYY 贴进去
 *   输入 YYY / 粘贴 YYY       —— 贴到当前有焦点的输入框（剪贴板 + Ctrl+V，中英文都一样）
 *   上滑 / 往下翻；下滑 / 往上翻 —— 鼠标滚轮
 *   返回；主页 / 回到桌面     —— HID 返回键、主页键
 *   读屏                       —— 读一次界面树，报摘要（不动手）
 *   等一下                     —— 等页面加载
 * 「停」「取消」由会话直接处理。看不懂的句子返回 null（交给模型；没 Key 就如实说办不了）。
 */
public final class LocalIntent {
    private LocalIntent() {}

    public enum Kind { CLICK_TEXT, CLICK_REF, CLICK_XY, PASTE, PASTE_INTO, SCROLL_DOWN, SCROLL_UP, BACK, HOME, READ, WAIT }

    public static final class Step {
        public final Kind kind;
        public String target = "";
        public String text = "";
        public int ref, x, y;

        Step(Kind k) { kind = k; }

        public boolean needsHand() {
            return kind != Kind.READ && kind != Kind.WAIT;
        }

        public String describe() {
            switch (kind) {
                case CLICK_TEXT: return "点「" + target + "」";
                case CLICK_REF: return "点 #" + ref;
                case CLICK_XY: return "点坐标 " + x + "," + y;
                case PASTE: return "贴入「" + clip(text) + "」";
                case PASTE_INTO: return "在「" + target + "」里贴入「" + clip(text) + "」";
                case SCROLL_DOWN: return "上滑（往下翻）";
                case SCROLL_UP: return "下滑（往上翻）";
                case BACK: return "返回";
                case HOME: return "回到桌面";
                case READ: return "读屏";
                default: return "等一下";
            }
        }

        private static String clip(String s) {
            return s.length() <= 20 ? s : s.substring(0, 20) + "…";
        }
    }

    private static final String CLICK_VERB = "(?:点击|点一下|点按|单击|点开|按一下|按|点)";
    private static final Pattern REF = Pattern.compile("^" + CLICK_VERB + "\\s*(?:#|＃|第)\\s*(\\d{1,3})\\s*(?:个|号)?$");
    private static final Pattern XY = Pattern.compile("^" + CLICK_VERB + "\\s*(\\d{1,4})\\s*[,，\\s]\\s*(\\d{1,4})$");
    private static final Pattern CLICK = Pattern.compile("^" + CLICK_VERB + "\\s*(.+)$");
    /** 「在 XXX 输入 YYY」：动词前要有空格或「里/中/上」，免得把「自测输入框」里的「输入」当成动词。 */
    private static final Pattern INTO = Pattern.compile("^在\\s*(.+?)(?:\\s*(?:里面|里|中|上)\\s*|\\s+)(?:输入|粘贴|填写|填|打)\\s*[：:]?\\s*(.+)$");
    /** 没空格时（「在搜索框输入你好」）取最后一个动词。 */
    private static final Pattern INTO_TIGHT = Pattern.compile("^在\\s*(.+)(?:输入|粘贴)\\s*[：:]?\\s*(.+)$");
    private static final Pattern PASTE = Pattern.compile("^(?:输入|粘贴|填写|打字|贴上|贴)\\s*[：:]?\\s*(.+)$");

    /** 整句拆成步骤；有任何一段看不懂就返回 null。 */
    public static List<Step> parse(String utterance) {
        if (utterance == null) return null;
        List<String> clauses = split(utterance.trim());
        if (clauses.isEmpty()) return null;
        List<Step> out = new ArrayList<>();
        for (String c : clauses) {
            Step s = parseClause(c);
            if (s == null) {
                // 「输入 你好，世界」：逗号后面那段看不懂，就当成上一段要贴的文字的一部分
                Step last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && (last.kind == Kind.PASTE || last.kind == Kind.PASTE_INTO)) {
                    last.text = last.text + "，" + c;
                    continue;
                }
                return null;
            }
            out.add(s);
        }
        return out.isEmpty() ? null : out;
    }

    public static String summary(List<Step> steps) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) b.append("；");
            if (steps.size() > 1) b.append(i + 1).append(") ");
            b.append(steps.get(i).describe());
        }
        return b.toString();
    }

    static Step parseClause(String raw) {
        String c = raw.trim();
        for (String p : new String[]{"请", "帮我", "麻烦", "再", "先", "最后"}) {
            if (c.startsWith(p) && c.length() > p.length()) c = c.substring(p.length()).trim();
        }
        if (c.isEmpty()) return null;
        switch (c) {
            case "上滑": case "往上滑": case "向上滑": case "往下翻": case "向下翻": case "下一页": case "往下滚":
                return new Step(Kind.SCROLL_DOWN);
            case "下滑": case "往下滑": case "向下滑": case "往上翻": case "向上翻": case "上一页": case "往上滚":
                return new Step(Kind.SCROLL_UP);
            case "返回": case "后退": case "返回上一页":
                return new Step(Kind.BACK);
            case "主页": case "回到桌面": case "回桌面": case "桌面": case "回到主屏": case "回主屏":
                return new Step(Kind.HOME);
            case "读屏": case "看屏幕": case "看看屏幕": case "读界面": case "读一下屏幕": case "看一下屏幕":
                return new Step(Kind.READ);
            case "等一下": case "等等": case "等待": case "等一会": case "等一会儿":
                return new Step(Kind.WAIT);
            default:
                break;
        }
        Matcher m;
        if ((m = REF.matcher(c)).matches()) {
            Step s = new Step(Kind.CLICK_REF);
            s.ref = Integer.parseInt(m.group(1));
            return s.ref > 0 ? s : null;
        }
        if ((m = XY.matcher(c)).matches()) {
            Step s = new Step(Kind.CLICK_XY);
            s.x = Integer.parseInt(m.group(1));
            s.y = Integer.parseInt(m.group(2));
            return s;
        }
        if ((m = INTO.matcher(c)).matches() || (m = INTO_TIGHT.matcher(c)).matches()) {
            Step s = new Step(Kind.PASTE_INTO);
            s.target = unquote(m.group(1));
            s.text = unquote(m.group(2));
            return s.target.isEmpty() || s.text.isEmpty() ? null : s;
        }
        if ((m = PASTE.matcher(c)).matches()) {
            Step s = new Step(Kind.PASTE);
            s.text = unquote(m.group(1));
            return s.text.isEmpty() ? null : s;
        }
        if ((m = CLICK.matcher(c)).matches()) {
            Step s = new Step(Kind.CLICK_TEXT);
            s.target = unquote(m.group(1));
            return s.target.isEmpty() ? null : s;
        }
        return null;
    }

    static String unquote(String t) {
        String s = t == null ? "" : t.trim();
        while (s.length() >= 2) {
            char a = s.charAt(0), z = s.charAt(s.length() - 1);
            if ((a == '「' && z == '」') || (a == '『' && z == '』') || (a == '“' && z == '”') || (a == '"' && z == '"')) {
                s = s.substring(1, s.length() - 1).trim();
            } else break;
        }
        return s;
    }

    /** 按「然后」「接着」和 ，；; 。 切开；引号里的不切；数字之间的逗号（坐标）不切。 */
    static List<String> split(String t) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char open = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (open != 0) {
                cur.append(ch);
                if (ch == close(open)) open = 0;
                continue;
            }
            if (ch == '「' || ch == '『' || ch == '“' || ch == '"') {
                open = ch;
                cur.append(ch);
                continue;
            }
            boolean punct = ch == '，' || ch == ',' || ch == '；' || ch == ';' || ch == '。' || ch == '\n';
            if (punct && (ch == ',' || ch == '，') && i > 0 && i + 1 < t.length()
                    && Character.isDigit(prevNonSpace(t, i)) && Character.isDigit(nextNonSpace(t, i))) {
                cur.append(ch);
                continue;
            }
            if (punct) {
                flush(out, cur);
                continue;
            }
            if (t.startsWith("然后", i) || t.startsWith("接着", i)) {
                flush(out, cur);
                i += 1;
                continue;
            }
            cur.append(ch);
        }
        flush(out, cur);
        return out;
    }

    private static char close(char open) {
        switch (open) {
            case '「': return '」';
            case '『': return '』';
            case '“': return '”';
            default: return '"';
        }
    }

    private static char prevNonSpace(String t, int i) {
        for (int j = i - 1; j >= 0; j--) if (!Character.isWhitespace(t.charAt(j))) return t.charAt(j);
        return ' ';
    }

    private static char nextNonSpace(String t, int i) {
        for (int j = i + 1; j < t.length(); j++) if (!Character.isWhitespace(t.charAt(j))) return t.charAt(j);
        return ' ';
    }

    private static void flush(List<String> out, StringBuilder cur) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) out.add(s);
        cur.setLength(0);
    }
}
