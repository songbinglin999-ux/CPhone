package com.yuanhou.hand.model;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Map;

/**
 * 模型配置：OpenAI 和 DeepSeek 两个槽位可同时填写，{@link #provider} 指定主模型。
 * 主模型额度不够或连不上时，客户端改用已填好的备用槽。Key 全部由本人在设置里填，
 * 应用不预填 Key；只存在应用私有目录（SharedPreferences, MODE_PRIVATE）。
 * 设置里没填时才看本机运行时环境变量 OPENAI_API_KEY / DEEPSEEK_API_KEY（见 ModelKeys）。
 * Key 不写日志、不进 toString/describe、不出现在发给模型的内容里（只放在 HTTP Authorization 头）。源码里没有任何 Key。
 */
public final class ModelConfig {
    private static final String PREFS = "model_config";

    public static final String HINT_DEEPSEEK_URL = "https://api.deepseek.com";
    public static final String HINT_OPENAI_URL = "https://api.openai.com/v1";
    public static final String HINT_DEEPSEEK_MODEL = "deepseek-chat";
    public static final String HINT_OPENAI_MODEL = "gpt-6.1-terra";

    /** 主模型服务商："deepseek" 或 "openai"，空=未选。 */
    public String provider = "";
    /** 本人同意把当前一屏的界面文字（和截图）发给正在使用的服务商。默认不同意。 */
    public boolean cloudConsent;
    /** 模型能看图；不能看图、或当前用的是 DeepSeek 时，只发界面文字。 */
    public boolean vision;

    public final Slot openai = new Slot(ModelKeys.OPENAI);
    public final Slot deepseek = new Slot(ModelKeys.DEEPSEEK);

    /** 兼容旧代码：始终与主模型槽同步，不要只改这两个而不改对应 Slot。 */
    public String baseUrl = "";
    public String model = "";
    public String apiKey = "";

    public static final class Slot {
        public final String provider;
        public String baseUrl = "";
        public String model = "";
        public String apiKey = "";

        public Slot(String provider) {
            this.provider = provider;
        }

        public String label() {
            return ModelKeys.OPENAI.equals(provider) ? "OpenAI" : "DeepSeek";
        }

        public boolean hasSettingsKey() {
            return apiKey != null && !apiKey.trim().isEmpty();
        }

        /** 实际要用的 Key：设置里的优先，其次本机运行时环境变量。不要打印它。 */
        public String effectiveKey() {
            Map<String, String> env;
            try { env = System.getenv(); } catch (SecurityException e) { env = null; }
            return ModelKeys.resolveKey(provider, apiKey, env);
        }

        public boolean hasKey() {
            return !effectiveKey().isEmpty();
        }

        public String effectiveBaseUrl() {
            return ModelKeys.resolveBaseUrl(provider, baseUrl);
        }

        /** 设置里没填模型名时，用该服务商常用名（只作缺省，不写进偏好）。 */
        public String effectiveModel() {
            if (model != null && !model.trim().isEmpty()) return model.trim();
            if (ModelKeys.DEEPSEEK.equals(provider)) return HINT_DEEPSEEK_MODEL;
            if (ModelKeys.OPENAI.equals(provider)) return HINT_OPENAI_MODEL;
            return "";
        }

        public boolean complete() {
            return (ModelKeys.DEEPSEEK.equals(provider) || ModelKeys.OPENAI.equals(provider))
                    && !effectiveBaseUrl().isEmpty()
                    && !effectiveModel().isEmpty()
                    && hasKey();
        }
    }

    public static ModelConfig load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ModelConfig m = new ModelConfig();
        m.provider = p.getString("provider", "");
        m.cloudConsent = p.getBoolean("cloud_consent", false);
        m.vision = p.getBoolean("vision", false);
        m.openai.apiKey = p.getString("openai_key", "");
        m.openai.model = p.getString("openai_model", "");
        m.openai.baseUrl = p.getString("openai_base_url", "");
        m.deepseek.apiKey = p.getString("deepseek_key", "");
        m.deepseek.model = p.getString("deepseek_model", "");
        m.deepseek.baseUrl = p.getString("deepseek_base_url", "");

        // 旧版只有一套 provider / api_key / model / base_url，迁进对应槽（新槽已有值则不覆盖）。
        String legacyKey = p.getString("api_key", "");
        String legacyModel = p.getString("model", "");
        String legacyUrl = p.getString("base_url", "");
        Slot dest = ModelKeys.DEEPSEEK.equals(m.provider) ? m.deepseek : m.openai;
        if (!isEmpty(legacyKey) && !dest.hasSettingsKey()) dest.apiKey = legacyKey;
        if (!isEmpty(legacyModel) && isEmpty(dest.model)) dest.model = legacyModel;
        if (!isEmpty(legacyUrl) && isEmpty(dest.baseUrl)) dest.baseUrl = legacyUrl;

        m.syncLegacy();
        return m;
    }

    public void save(Context c) {
        syncLegacy();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("provider", provider)
                .putString("base_url", baseUrl)
                .putString("model", model)
                .putString("api_key", apiKey)
                .putBoolean("cloud_consent", cloudConsent)
                .putBoolean("vision", vision)
                .putString("openai_key", openai.apiKey)
                .putString("openai_model", openai.model)
                .putString("openai_base_url", openai.baseUrl)
                .putString("deepseek_key", deepseek.apiKey)
                .putString("deepseek_model", deepseek.model)
                .putString("deepseek_base_url", deepseek.baseUrl)
                .apply();
    }

    public void clearKeys() {
        openai.apiKey = "";
        deepseek.apiKey = "";
        apiKey = "";
    }

    /** 发给微信前要打码的 Key（两个槽都算，不含环境变量）。 */
    public void addSecrets(Collection<String> dest) {
        if (openai.hasSettingsKey()) dest.add(openai.apiKey);
        if (deepseek.hasSettingsKey()) dest.add(deepseek.apiKey);
    }

    /** 主模型槽；未选服务商时，优先已配好的 OpenAI，其次 DeepSeek。 */
    public Slot primary() {
        if (ModelKeys.DEEPSEEK.equals(provider)) return deepseek;
        if (ModelKeys.OPENAI.equals(provider)) return openai;
        if (openai.complete()) return openai;
        if (deepseek.complete()) return deepseek;
        return openai;
    }

    /** 另一个槽。不管它是否配好。 */
    public Slot other(Slot a) {
        if (a == null) return null;
        return ModelKeys.DEEPSEEK.equals(a.provider) ? openai : deepseek;
    }

    /** 已配好的备用槽；没填则 null。 */
    public Slot backup() {
        Slot b = other(primary());
        return (b != null && b.complete()) ? b : null;
    }

    /** 这一轮先试谁：主模型配好了就用主模型，否则用已配好的备用。 */
    public Slot preferred() {
        Slot p = primary();
        if (p.complete()) return p;
        Slot b = backup();
        return b != null ? b : p;
    }

    public boolean hasSettingsKey() {
        return openai.hasSettingsKey() || deepseek.hasSettingsKey();
    }

    public boolean hasKey() {
        return openai.hasKey() || deepseek.hasKey();
    }

    public boolean complete() {
        return preferred().complete();
    }

    /** 能否调用云端：至少一套配好且本人已同意。没有 Key 绝不调用。 */
    public boolean usable() {
        return complete() && cloudConsent;
    }

    /** 还差什么（给本人的提示，形如「请在设置里填写 …」）；null = 可以调用。 */
    public String missing() {
        if (!cloudConsent) {
            if (complete() || hasKey()) {
                return "请在设置里勾选同意把屏幕文字发给所选服务商";
            }
        }
        if (preferred().complete()) {
            return cloudConsent ? null : "请在设置里勾选同意把屏幕文字发给所选服务商";
        }
        Slot p = primary();
        String who = p.label();
        if (!p.hasKey()) return "请在设置里填写 " + who + " 的 API Key，或改填备用模型";
        return ModelKeys.missing(p.provider, p.effectiveKey(), p.effectiveModel(), cloudConsent);
    }

    /** 给界面看的状态，不含 Key。 */
    public String describe() {
        if (!hasKey()) return "模型：未配置 Key（不会调用云端模型）。请在设置里填写";
        Slot p = primary();
        Slot b = other(p);
        String main = p.label() + " / " + p.effectiveModel() + (p.complete() ? "" : "（未就绪）");
        String bak;
        if (b == null) bak = "无";
        else if (b.complete()) bak = b.label() + " / " + b.effectiveModel();
        else if (b.hasKey()) bak = b.label() + " / " + b.effectiveModel() + "（不完整）";
        else bak = b.label() + "（未填 Key）";
        if (!complete()) return "模型：配置不完整（不会调用云端模型）。主模型：" + main + "；备用：" + bak + "。" + missing();
        if (!cloudConsent) return "主模型：" + main + "；备用：" + bak + "，尚未同意把屏幕内容发给服务商";
        return "主模型：" + main + "；备用：" + bak + (vision ? "（看图）" : "（只发界面文字）");
    }

    @Override
    public String toString() {
        Slot p = primary();
        Slot b = other(p);
        return "ModelConfig{primary=" + p.provider + "/" + p.effectiveModel()
                + " key=" + (p.hasKey() ? "已填" : "空")
                + ", backup=" + (b == null ? "无" : (b.provider + " key=" + (b.hasKey() ? "已填" : "空")))
                + "}";
    }

    private void syncLegacy() {
        Slot p = primary();
        this.baseUrl = p.baseUrl;
        this.model = p.model;
        this.apiKey = p.apiKey;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
