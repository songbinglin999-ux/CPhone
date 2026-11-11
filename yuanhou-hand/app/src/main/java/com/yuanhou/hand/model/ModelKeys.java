package com.yuanhou.hand.model;

import java.util.Map;

/**
 * 模型 Key 和地址的解析（纯 Java）。Key 只从两处来：① 本人在应用设置里填的（存应用私有目录）；
 * ② 本机运行时环境变量（OPENAI_API_KEY / DEEPSEEK_API_KEY，只给桌面联调用）。源码、README、配置示例里都没有任何 Key。
 * 解析出的 Key 只放 HTTP Authorization 头，不进日志、不进 toString、不进发给模型的内容。
 */
public final class ModelKeys {
    private ModelKeys() {}

    public static final String OPENAI = "openai", DEEPSEEK = "deepseek";
    public static final String OPENAI_URL = "https://api.openai.com/v1";
    public static final String DEEPSEEK_URL = "https://api.deepseek.com";

    public static String envName(String provider) {
        if (OPENAI.equals(provider)) return "OPENAI_API_KEY";
        if (DEEPSEEK.equals(provider)) return "DEEPSEEK_API_KEY";
        return null;
    }

    /** 设置里的优先；没有再看运行时环境。都没有返回空串。 */
    public static String resolveKey(String provider, String settingsKey, Map<String, String> env) {
        if (settingsKey != null && !settingsKey.trim().isEmpty()) return settingsKey.trim();
        String n = envName(provider);
        if (n == null || env == null) return "";
        String v = env.get(n);
        return v == null ? "" : v.trim();
    }

    /** 设置里没填地址时用官方地址（只按所选服务商，不猜别的）。 */
    public static String resolveBaseUrl(String provider, String settingsUrl) {
        if (settingsUrl != null && !settingsUrl.trim().isEmpty()) return settingsUrl.trim();
        if (OPENAI.equals(provider)) return OPENAI_URL;
        if (DEEPSEEK.equals(provider)) return DEEPSEEK_URL;
        return "";
    }

    /** 还差什么：null = 可以调用；否则是给本人的提示（「请在设置里填写 …」）。不会抛异常。 */
    public static String missing(String provider, String key, String model, boolean consent) {
        if (!OPENAI.equals(provider) && !DEEPSEEK.equals(provider)) return "请在设置里选择模型服务商（OpenAI 或 DeepSeek）";
        if (key == null || key.trim().isEmpty()) return "请在设置里填写 " + (OPENAI.equals(provider) ? "OpenAI" : "DeepSeek") + " 的 API Key";
        if (model == null || model.trim().isEmpty()) return "请在设置里填写模型名";
        if (!consent) return "请在设置里勾选同意把屏幕文字发给所选服务商";
        return null;
    }
}
