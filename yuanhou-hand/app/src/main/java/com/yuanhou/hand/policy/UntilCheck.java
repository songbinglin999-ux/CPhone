package com.yuanhou.hand.policy;

import com.yuanhou.hand.model.ModelAction;
import com.yuanhou.hand.screen.ScreenSnapshot;

/**
 * 一次多步里一步做完后的等待条件（学 Mika UntilWait 的字面比较，不问模型）。
 * 只认屏上已经出现的文字和前台包名，不猜。
 */
public final class UntilCheck {
    public static final int DEFAULT_SEC = 8;
    public static final int MAX_SEC = 20;

    private UntilCheck() {}

    public static boolean hasUntil(ModelAction a) {
        return a != null && (hasContent(a) || a.waitStable);
    }

    public static boolean hasContent(ModelAction a) {
        return a != null && (!blank(a.expect) || !blank(a.expectGone) || !blank(a.expectApp));
    }

    public static int timeoutMs(ModelAction a) {
        int s = a == null || a.timeoutSec <= 0 ? DEFAULT_SEC : a.timeoutSec;
        if (s > MAX_SEC) s = MAX_SEC;
        if (s < 1) s = 1;
        return s * 1000;
    }

    /** 条件此刻是否成立（只看给了的几项，大小写不敏感）。wait_stable 由调用方先等界面稳定。 */
    public static boolean holds(ModelAction a, ScreenSnapshot snap) {
        if (a == null) return true;
        if (snap == null) return !hasContent(a);
        if (!blank(a.expect) && !visibleContains(snap, a.expect)) return false;
        if (!blank(a.expectGone) && visibleContains(snap, a.expectGone)) return false;
        if (!blank(a.expectApp) && !appMatches(snap.packageName, a.expectApp)) return false;
        return true;
    }

    public static boolean visibleContains(ScreenSnapshot snap, String q) {
        if (snap == null || blank(q)) return false;
        String nq = q.trim().toLowerCase();
        if (contains(snap.windowTitle, nq) || contains(snap.packageName, nq)) return true;
        for (ScreenSnapshot.Node n : snap.nodes) {
            if (contains(n.label(), nq) || contains(n.text, nq) || contains(n.desc, nq) || contains(n.hint, nq)) {
                return true;
            }
        }
        return false;
    }

    public static boolean appMatches(String packageName, String expectApp) {
        if (blank(expectApp)) return true;
        if (blank(packageName)) return false;
        String want = packageOf(expectApp);
        if (want == null) want = expectApp.trim();
        return packageName.equals(want) || packageName.equalsIgnoreCase(expectApp.trim())
                || packageName.toLowerCase().contains(expectApp.trim().toLowerCase());
    }

    /** 与 TaskSession 允许打开的应用别名一致。 */
    public static String packageOf(String name) {
        if (name == null) return null;
        switch (name.trim().toLowerCase()) {
            case "微信": case "wechat": return "com.tencent.mm";
            case "淘宝": case "taobao": return "com.taobao.taobao";
            case "闲鱼": case "xianyu": return "com.taobao.idlefish";
            case "支付宝": case "alipay": return "com.eg.android.AlipayGphone";
            case "美团外卖": case "meituanwaimai": return "com.sankuai.meituan.takeoutnew";
            case "美团": case "meituan": return "com.sankuai.meituan";
            default: return null;
        }
    }

    /** 应用别名或包名 → 中文名；不认识返回 null。美团外卖不会被收成美团。 */
    public static String displayName(String nameOrPkg) {
        if (nameOrPkg == null) return null;
        switch (nameOrPkg.trim().toLowerCase()) {
            case "微信": case "wechat": case "com.tencent.mm": return "微信";
            case "淘宝": case "taobao": case "com.taobao.taobao": return "淘宝";
            case "闲鱼": case "xianyu": case "com.taobao.idlefish": return "闲鱼";
            case "支付宝": case "alipay": case "com.eg.android.alipaygphone": return "支付宝";
            case "美团外卖": case "meituanwaimai": case "com.sankuai.meituan.takeoutnew": return "美团外卖";
            case "美团": case "meituan": case "com.sankuai.meituan": return "美团";
            default: return null;
        }
    }

    /** 一句话里点到的允许应用，长名字优先（美团外卖不会再拆出美团）。 */
    public static java.util.List<String> mentionedApps(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String left = text;
        String lower = text.toLowerCase();
        if (left.contains("美团外卖") || lower.contains("meituanwaimai")) {
            out.add("美团外卖");
            left = left.replace("美团外卖", " ");
            lower = lower.replace("meituanwaimai", " ");
        }
        take(out, left, lower, "微信", "wechat");
        take(out, left, lower, "淘宝", "taobao");
        take(out, left, lower, "闲鱼", "xianyu");
        take(out, left, lower, "支付宝", "alipay");
        take(out, left, lower, "美团", "meituan");
        return out;
    }

    private static void take(java.util.List<String> out, String raw, String lower, String zh, String en) {
        if ((raw.contains(zh) || lower.contains(en)) && !out.contains(zh)) out.add(zh);
    }

    private static boolean contains(String s, String nq) {
        return s != null && s.toLowerCase().contains(nq);
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
}
