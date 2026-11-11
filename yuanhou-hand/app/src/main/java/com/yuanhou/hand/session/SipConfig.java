package com.yuanhou.hand.session;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SIP 账号：域名、用户名、密码、传输方式。全部由本人在远手设置里自己填，应用不编造、不预填，
 * 也不去读别的应用（例如已装的 Linphone）里的账号。只存应用私有目录；密码不进日志、不在界面回显。
 */
public final class SipConfig {
    private static final String PREFS = "sip_config";
    public String domain = "";
    public String username = "";
    public String password = "";
    public String transport = "udp";

    public static SipConfig load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SipConfig s = new SipConfig();
        s.domain = p.getString("domain", "");
        s.username = p.getString("username", "");
        s.password = p.getString("password", "");
        s.transport = normTransport(p.getString("transport", "udp"));
        return s;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("domain", domain).putString("username", username)
                .putString("password", password).putString("transport", normTransport(transport)).apply();
    }

    public static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static String normTransport(String t) {
        if (t == null) return "udp";
        t = t.trim().toLowerCase();
        return t.equals("tcp") || t.equals("tls") ? t : "udp";
    }

    /** 去掉误填的 sip: 前缀和空白。 */
    public static String cleanDomain(String d) {
        if (d == null) return "";
        d = d.trim();
        if (d.toLowerCase().startsWith("sip:")) d = d.substring(4);
        if (d.toLowerCase().startsWith("sips:")) d = d.substring(5);
        return d.trim();
    }

    public boolean complete() {
        return !domain.trim().isEmpty() && !username.trim().isEmpty() && !password.isEmpty();
    }

    /** 不含密码。 */
    public String who() {
        return username + "@" + domain + "（" + transport + "）";
    }

    public String describe() {
        return complete() ? ("SIP 账号：" + who() + "，密码已保存（不显示）")
                : "SIP 账号：未配置。请在「模型与通话配置」里填你自己的 SIP 账号（可以和手机上 Linphone 用同一套，远手不会去读那个应用）";
    }
}
