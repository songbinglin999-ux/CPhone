package com.yuanhou.hand.memory;

import java.util.regex.Pattern;

/**
 * 本机记事不能落下的内容：API Key、口令串、密码、令牌、长串密钥。
 * 只用来拒绝写入，不把原文打进日志。
 */
public final class Sensitive {
    private static final Pattern LONG_HEX = Pattern.compile("[0-9a-fA-F]{24,}");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{16,}");

    private Sensitive() {}

    public static boolean blocked(String s) {
        if (s == null || s.isEmpty()) return false;
        String l = s.toLowerCase();
        if (l.contains("sk-")) return true;
        if (l.contains("api_key") || l.contains("apikey") || l.contains("api key")) return true;
        if (l.contains("bearer") || l.contains("authorization")) return true;
        if (l.contains("password") || l.contains("passwd") || l.contains("secret")) return true;
        if (l.contains("token")) return true;
        if (s.contains("密码") || s.contains("验证码") || s.contains("密钥")) return true;
        if (LONG_HEX.matcher(s).find()) return true;
        if (LONG_DIGITS.matcher(s).find()) return true;
        return false;
    }
}
