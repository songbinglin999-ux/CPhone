package com.yuanhou.hand.port;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 远手可以在哪些 App 上动手（思路同 Mika core/access/AppAccessPolicy，独立于 ESP32 端口，由 AppAccessScreenPort 套在外面）。
 * - denied：未经本人同意不打开、不操作（饿了么……）。美团已由本人同意用于搜索和浏览；下单仍走确认闸，付款永远交本人。
 * - allowedOpen：openApp 只开这几个（微信、淘宝、闲鱼、支付宝、美团、美团外卖，加上远手自己）。支付宝只能看和走到付款前，付款永远交本人（确认闸负责）。
 * - 读屏不拦（只读），写操作前核对前台包不在 denied 里。
 */
public final class AppAccessPolicy {
    public final Set<String> denied;
    public final Set<String> allowedOpen;

    public AppAccessPolicy(Set<String> denied, Set<String> allowedOpen) {
        this.denied = Collections.unmodifiableSet(new HashSet<>(denied));
        this.allowedOpen = Collections.unmodifiableSet(new HashSet<>(allowedOpen));
    }

    public static final String OWN = "com.yuanhou.hand";

    public static AppAccessPolicy defaults() {
        Set<String> d = new HashSet<>();
        Collections.addAll(d, "me.ele");
        Set<String> o = new HashSet<>();
        Collections.addAll(o, "com.tencent.mm", "com.taobao.taobao", "com.taobao.idlefish", "com.eg.android.AlipayGphone",
                "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", OWN);
        return new AppAccessPolicy(d, o);
    }

    /** 写操作前：null = 可以；否则原因。 */
    public String checkWrite(String foreground) {
        if (foreground != null && denied.contains(foreground)) return "前台是不允许操作的应用 " + foreground;
        return null;
    }

    public String checkOpen(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "没给包名";
        if (denied.contains(pkg)) return pkg + " 不允许打开";
        if (!allowedOpen.contains(pkg)) return pkg + " 不在允许打开的范围里";
        return null;
    }
}
