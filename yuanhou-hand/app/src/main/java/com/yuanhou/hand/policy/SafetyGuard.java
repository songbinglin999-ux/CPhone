package com.yuanhou.hand.policy;

import com.yuanhou.hand.screen.ScreenSnapshot;

/**
 * 安全闸：密码/解锁/验证码/支付密码页立即停止自动输入，不送进模型；
 * 应用提示环境异常或拒绝自动输入时立即停并如实汇报——不改间隔、不改环境、不换输入方式、不加抖动再试。
 *
 * 分类借鉴 Mika core/uistate/ScreenDiagnosis.kt（captcha / payment / login(密码框、短信验证码) 需要本人处理；
 * 没有可读元素为 BLANK）。区别：Mika 会挂起任务等本人在手机上接管再交回；远手的人在电话另一头，
 * 第一期一律停下并说明，不等接管、不把这一屏发给模型。
 */
public final class SafetyGuard {
    private SafetyGuard() {}

    public enum Kind { OK, SENSITIVE_INPUT, PAYMENT_PAGE, CAPTCHA, ANOMALY, FORBIDDEN_APP, LOCKED }

    public static final class Verdict {
        public final Kind kind;
        public final String reason;
        Verdict(Kind k, String r) { kind = k; reason = r; }
        public boolean stop() { return kind != Kind.OK; }
    }

    private static final String[] SENSITIVE = {
            "输入密码", "登录密码", "请输入密码", "交易密码", "验证码", "短信验证", "校验码",
            "获取验证码", "发送验证码", "短信验证码", "verification code", "get code", "send code",
            "解锁图案", "绘制图案", "图形密码", "锁屏密码", "人脸验证", "安全键盘"
    };
    /** Mika ScreenDiagnosis.PAYMENT + 远手原有的生物支付字样。 */
    private static final String[] PAYMENT = {
            "支付密码", "确认支付", "确认付款", "立即支付", "输入密码支付", "密码支付", "指纹支付", "面容支付", "刷脸",
            "payment password", "confirm payment", "pay now"
    };
    /** Mika ScreenDiagnosis.CAPTCHA。 */
    private static final String[] CAPTCHA = {
            "captcha", "人机验证", "安全验证", "滑块验证", "拖动滑块", "向右滑动完成", "请完成验证", "verify you are human", "not a robot"
    };
    private static final String[] ANOMALY = {
            "环境异常", "操作频繁", "异常操作", "检测到异常", "存在风险", "账号异常",
            "请稍后再试", "暂时无法使用", "禁止自动", "自动化工具", "外挂", "非正常", "系统繁忙"
    };
    /** 未经用户同意不打开、不安装。美团已由本人同意浏览；仍禁止饿了么，直到本人明确同意。 */
    public static final String[] FORBIDDEN_PACKAGES = { "me.ele" };

    public static final String OWN_PACKAGE = "com.yuanhou.hand";

    public static Verdict check(ScreenSnapshot s) {
        if (s == null) return new Verdict(Kind.OK, null);
        for (String p : FORBIDDEN_PACKAGES) {
            if (p.equals(s.packageName)) return new Verdict(Kind.FORBIDDEN_APP, "当前是未授权的应用 " + p);
        }
        if ("com.android.systemui".equals(s.packageName)) {
            for (ScreenSnapshot.Node n : s.nodes) {
                if (n.password || n.label().contains("解锁") || n.label().contains("锁屏")) {
                    return new Verdict(Kind.LOCKED, "屏幕处于锁屏/解锁界面");
                }
            }
        }
        if (OWN_PACKAGE.equals(s.packageName)) {
            // 远手自己的界面上写着「确认付款」「发送」这类快捷回答按钮和说明文字，不是付款页；只看有没有密码框
            for (ScreenSnapshot.Node n : s.nodes) if (n.password) return new Verdict(Kind.SENSITIVE_INPUT, "出现密码输入框");
            return new Verdict(Kind.OK, null);
        }
        StringBuilder all = new StringBuilder();
        for (ScreenSnapshot.Node n : s.nodes) {
            if (n.password) return new Verdict(Kind.SENSITIVE_INPUT, "出现密码输入框");
            all.append(n.label()).append('\n');
            if (n.hint != null) all.append(n.hint).append('\n');
        }
        String t = all.toString().toLowerCase();
        // 顺序同 Mika：captcha → payment → 密码框（上面已判）→ 短信验证码；再看环境异常
        String k;
        if ((k = hit(t, CAPTCHA)) != null) return new Verdict(Kind.CAPTCHA, "出现「" + k + "」验证");
        if ((k = hit(t, PAYMENT)) != null) return new Verdict(Kind.PAYMENT_PAGE, "出现「" + k + "」付款页");
        if ((k = hit(t, SENSITIVE)) != null) return new Verdict(Kind.SENSITIVE_INPUT, "出现「" + k + "」页面");
        if ((k = hit(t, ANOMALY)) != null) return new Verdict(Kind.ANOMALY, "应用提示「" + k + "」");
        return new Verdict(Kind.OK, null);
    }

    private static String hit(String text, String[] words) {
        for (String w : words) if (text.contains(w.toLowerCase())) return w;
        return null;
    }

    /** 界面读不出东西（没有窗口或没有带字元素），Mika ScreenState.BLANK。 */
    public static boolean isBlank(ScreenSnapshot s) {
        return s == null || s.readableCount() == 0;
    }

    public static boolean isForbiddenPackage(String pkg) {
        for (String p : FORBIDDEN_PACKAGES) if (p.equals(pkg)) return true;
        return false;
    }
}
