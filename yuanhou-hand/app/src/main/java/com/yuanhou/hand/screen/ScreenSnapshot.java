package com.yuanhou.hand.screen;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读界面快照（来自无障碍树）。密码框的内容不记录。
 *
 * 借鉴 Mika（core/uistate/UiTree.kt、app/.../a11y/ScreenReader.kt）：
 * - 给模型的每个元素带短编号 ref，模型优先按 ref 选目标，由本机换算成元素中心，不让模型猜像素坐标；
 * - 太小（&lt;5px）的节点不列；密码框只写「[密码框]」，不带内容；
 * - 有一个覆盖全部可见节点的指纹，用来判断动作有没有效果、页面是否已静下来。
 * 坐标仍是主屏真实像素（远手没有虚拟屏，也不做 0–999 归一化后再换算）。
 */
public final class ScreenSnapshot {
    public static final class Node {
        public String cls, text, desc, viewId, hint;
        public Rect bounds = new Rect();
        public boolean clickable, editable, password, focused, scrollable, checked, selected;
        public boolean enabled = true;
        /** 给模型的编号（从 1 开始）；没列给模型的为 0。 */
        public int ref;

        public int cx() { return bounds.centerX(); }
        public int cy() { return bounds.centerY(); }

        public String label() {
            if (password) return "";
            if (text != null && !text.isEmpty()) return text;
            if (desc != null && !desc.isEmpty()) return desc;
            return "";
        }

        /** 输入框里用户/我们打的字（不含提示语、不含密码框）。 */
        public String typedText() {
            if (!editable || password || text == null) return "";
            if (hint != null && !hint.isEmpty() && hint.equals(text)) return "";
            return text;
        }
    }

    public String packageName = "";
    public String windowTitle = "";
    public final List<Node> nodes = new ArrayList<>();
    public long takenAt;
    /** 屏幕高度（像素）；0 表示未知，标题栏判断按节点最大下边界估。 */
    public int screenH;

    public Node nodeAt(int x, int y) {
        Node best = null;
        long bestArea = Long.MAX_VALUE;
        for (Node n : nodes) {
            if (n.bounds.contains(x, y)) {
                long a = (long) n.bounds.width() * n.bounds.height();
                if (a < bestArea && !n.label().isEmpty()) {
                    best = n;
                    bestArea = a;
                }
            }
        }
        return best;
    }

    public Node byRef(int ref) {
        if (ref <= 0) return null;
        for (Node n : nodes) if (n.ref == ref) return n;
        return null;
    }

    /** 有字的可见元素个数（Mika ScreenDiagnosis 的 readableElements）。 */
    public int readableCount() {
        int c = 0;
        for (Node n : nodes) if (!n.label().isEmpty() || n.editable) c++;
        return c;
    }

    /** 当前有输入焦点的输入框；没有为 null。 */
    public Node focusedField() {
        for (Node n : nodes) if (n.editable && n.focused) return n;
        return null;
    }

    /** 输入框明确是搜索框（提示语或资源 ID 里有 search/搜索）。Mika：其余输入框里回车一律按发送拦。 */
    public static boolean isSearchField(Node n) {
        if (n == null) return false;
        String h = ((n.hint == null ? "" : n.hint) + " " + (n.desc == null ? "" : n.desc)).toLowerCase();
        String id = n.viewId == null ? "" : n.viewId.toLowerCase();
        return h.contains("搜索") || h.contains("search") || h.contains("搜寻") || id.contains("search");
    }

    /** 屏幕顶部约 15% 的标题栏下边界（Mika ConfirmationGate.TITLE_BAND = 150/999）。 */
    public int titleBandBottom() {
        int h = screenH;
        if (h <= 0) for (Node n : nodes) h = Math.max(h, n.bounds.bottom);
        return h <= 0 ? 0 : (int) (h * 0.15);
    }

    /**
     * 界面指纹：覆盖所有可见节点的类型、文字、位置、勾选、焦点。点输入框只换了焦点也算变了。
     * 不含时间戳；只用于和上一屏比较。
     */
    public long fingerprint() {
        long h = 0xcbf29ce484222325L;
        h = mix(h, packageName);
        for (Node n : nodes) {
            h = mix(h, n.cls);
            h = mix(h, n.label());
            h = mix(h, n.typedText());
            h = mix(h, n.bounds.left + "," + n.bounds.top + "," + n.bounds.right + "," + n.bounds.bottom);
            h = mix(h, (n.checked ? "c" : "") + (n.focused ? "f" : "") + (n.selected ? "s" : ""));
        }
        return h;
    }

    private static long mix(long h, String s) {
        if (s == null) s = "";
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= 0x1f;
        h *= 0x100000001b3L;
        return h;
    }

    /**
     * 给模型的文本：每行一个可见元素，带 ref 编号与中心坐标。密码框只写「[密码框]」。
     * 会给列出的节点写上 ref，之后用 {@link #byRef} 找回。
     */
    public String toModelText(int maxLines) {
        StringBuilder b = new StringBuilder();
        b.append("前台应用: ").append(packageName).append('\n');
        int band = titleBandBottom();
        int i = 0;
        for (Node n : nodes) n.ref = 0;
        for (Node n : nodes) {
            String lab = n.password ? "[密码框]" : n.label();
            if (lab.isEmpty() && !n.clickable && !n.editable && !n.scrollable) continue;
            if (i >= maxLines) {
                b.append("…(已截断)\n");
                break;
            }
            n.ref = ++i;
            b.append('#').append(i).append(' ');
            String c = n.cls == null ? "" : n.cls.substring(n.cls.lastIndexOf('.') + 1);
            b.append(c);
            if (!lab.isEmpty()) b.append(" 「").append(trim(lab, 40)).append('」');
            if (n.editable && !n.password && n.hint != null && !n.hint.isEmpty() && n.typedText().isEmpty()) {
                b.append(" 提示「").append(trim(n.hint, 20)).append('」');
            }
            b.append(" @").append(n.cx()).append(',').append(n.cy());
            if (n.clickable) b.append(" 可点");
            if (n.editable) b.append(n.password ? " 密码" : " 可输入");
            if (n.scrollable) b.append(" 可滚动");
            if (n.focused) b.append(" 焦点");
            if (n.checked) b.append(" 已勾选");
            if (n.selected) b.append(" 已选中");
            if (!n.enabled) b.append(" 不可用");
            if (band > 0 && n.bounds.bottom <= band) b.append(" 标题栏");
            b.append('\n');
        }
        return b.toString();
    }

    /** 去掉首尾空白和引号（「」『』“”"''），用于按文字找元素。 */
    public static String cleanTarget(String t) {
        if (t == null) return "";
        String s = t.trim();
        while (s.length() >= 2) {
            char a = s.charAt(0), z = s.charAt(s.length() - 1);
            if ((a == '「' && z == '」') || (a == '『' && z == '』') || (a == '“' && z == '”')
                    || (a == '"' && z == '"') || (a == '\'' && z == '\'')) {
                s = s.substring(1, s.length() - 1).trim();
            } else break;
        }
        return s;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 按屏幕上的文字找要点的元素（本地意图用，不猜坐标）：
     * 1) 文字/描述完全相同；2) 输入框的提示语完全相同；3) 文字包含目标（取最短的那个）。
     * 同等条件下可点的优先、靠上的优先；第 3 步不考虑输入框（避免命中正在打的那句话）。找不到返回 null。
     */
    public Node findByText(String target) {
        String want = norm(cleanTarget(target));
        if (want.isEmpty()) return null;
        Node best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Node n : nodes) {
            if (n.password || !n.enabled) continue;
            String lab = norm(n.label());
            String hint = norm(n.hint);
            int score;
            if (!lab.isEmpty() && lab.equals(want) && !(n.editable && !n.typedText().isEmpty())) score = 3000;
            else if (n.editable && !hint.isEmpty() && hint.equals(want)) score = 2500;
            else if (!n.editable && !lab.isEmpty() && lab.contains(want)) score = 1000 - Math.min(900, lab.length());
            else continue;
            if (n.clickable) score += 50;
            score -= Math.min(40, n.bounds.top / 60);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    /** 找输入框：提示语、文字或描述等于/包含目标的可输入元素；目标为空时取有焦点的输入框，再退到唯一的输入框。 */
    public Node findField(String target) {
        String want = norm(cleanTarget(target));
        if (want.isEmpty()) {
            Node f = focusedField();
            if (f != null) return f;
            Node only = null;
            for (Node n : nodes) {
                if (n.editable && !n.password) {
                    if (only != null) return null;
                    only = n;
                }
            }
            return only;
        }
        Node best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Node n : nodes) {
            if (!n.editable || n.password || !n.enabled) continue;
            String hint = norm(n.hint), lab = norm(n.label()), desc = norm(n.desc), id = norm(n.viewId);
            int score;
            if (hint.equals(want) || desc.equals(want)) score = 3000;
            else if (lab.equals(want)) score = 2500;
            else if (hint.contains(want) || desc.contains(want) || id.contains(want)) score = 1000;
            else continue;
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    /** 面积最大的可滚动元素（滚轮放到它中间）；没有返回 null。 */
    public Node mainScrollable() {
        Node best = null;
        long area = 0;
        for (Node n : nodes) {
            if (!n.scrollable) continue;
            long a = (long) n.bounds.width() * n.bounds.height();
            if (a > area) {
                area = a;
                best = n;
            }
        }
        return best;
    }

    /** 给界面/结果文件看的简短摘要：前台、元素数、前若干个有字元素。 */
    public String summary(int maxLabels) {
        StringBuilder b = new StringBuilder();
        b.append("前台 ").append(packageName).append("，").append(nodes.size()).append(" 个可见节点，")
                .append(readableCount()).append(" 个有字/可输入");
        int i = 0;
        for (Node n : nodes) {
            String l = n.password ? "[密码框]" : n.label();
            if (l.isEmpty()) continue;
            b.append(i == 0 ? "：" : "、").append('「').append(trim(l, 16)).append('」');
            if (++i >= maxLabels) { b.append("…"); break; }
        }
        return b.toString();
    }

    private static String trim(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
