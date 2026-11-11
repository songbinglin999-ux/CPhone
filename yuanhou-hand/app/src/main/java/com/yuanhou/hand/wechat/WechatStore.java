package com.yuanhou.hand.wechat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 微信 Bot 绑定信息只存在这台手机上（同 Mika WechatStore：令牌加密保存）。
 * - 账号（bot_token、baseurl、ilink_bot_id、ilink_user_id）、长轮询游标、context_token：用 Android 密钥库里的 AES-256-GCM 密钥加密，
 *   密文放本应用私有 prefs「wechat_prefs」（MODE_PRIVATE，allowBackup=false 不进备份）。密钥不可导出。
 * - 开关（接收微信任务）、需重新绑定标记、最近已处理的消息 id：不是机密，明文放同一个私有 prefs。
 * 任何一个值都不写日志、不进界面、不发回微信；解不开（密钥丢了）就当没绑定，要求重新扫码。
 */
public final class WechatStore {
    private static final String PREFS = "wechat_prefs";
    private static final String KEY_ALIAS = "yuanhou_wechat_v1";
    private static final String K_ACCOUNT = "acct_enc";
    private static final String K_CURSOR = "cursor_enc";
    private static final String K_CONTEXT = "ctx_enc";
    private static final String K_ENABLED = "enabled";
    private static final String K_REBIND = "needs_rebind";
    private static final String K_ACKED = "acked_ids";
    static final int MAX_ACKED = 256;

    private static WechatStore instance;

    /** 进程内只有一份（服务和界面共用，内存里的账号/游标不会两份对不上）。 */
    public static synchronized WechatStore get(Context c) {
        if (instance == null) instance = new WechatStore(c);
        return instance;
    }

    private final SharedPreferences sp;
    private WechatApi.Account account;
    private String cursor;
    private String contextToken;
    private final ArrayDeque<String> acked = new ArrayDeque<>();

    private WechatStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        account = readAccount();
        cursor = decryptOrNull(sp.getString(K_CURSOR, null));
        contextToken = decryptOrNull(sp.getString(K_CONTEXT, null));
        String ids = sp.getString(K_ACKED, "");
        if (!ids.isEmpty()) acked.addAll(Arrays.asList(ids.split("\n")));
    }

    public synchronized WechatApi.Account account() { return account; }
    public synchronized boolean bound() { return account != null; }
    public synchronized boolean enabled() { return sp.getBoolean(K_ENABLED, false) && account != null; }
    public synchronized boolean needsRebind() { return sp.getBoolean(K_REBIND, false); }
    public synchronized String cursor() { return cursor == null ? "" : cursor; }
    public synchronized String contextToken() { return contextToken; }

    /** 扫码成功：保存新账号。换了人/换了 Bot 就清掉旧游标、context_token 和已处理记录；同一账号重绑保留游标。 */
    public synchronized void saveAccount(WechatApi.Account a) {
        boolean same = a.sameIdentity(account);
        if (!same) {
            cursor = null;
            contextToken = null;
            acked.clear();
        }
        String json = "{\"token\":" + MiniJson.quote(a.token) + ",\"baseurl\":" + MiniJson.quote(a.baseUrl)
                + ",\"ilink_bot_id\":" + MiniJson.quote(a.botId) + ",\"ilink_user_id\":" + MiniJson.quote(a.ownerId) + "}";
        SharedPreferences.Editor e = sp.edit()
                .putString(K_ACCOUNT, encrypt(json))
                .putBoolean(K_REBIND, false)
                .putBoolean(K_ENABLED, true);
        if (!same) e.remove(K_CURSOR).remove(K_CONTEXT).remove(K_ACKED);
        e.commit();
        account = a;
    }

    /** 解除绑定：删掉本机保存的全部绑定信息。 */
    public synchronized void clear() {
        account = null;
        cursor = null;
        contextToken = null;
        acked.clear();
        sp.edit().clear().commit();
    }

    public synchronized void setEnabled(boolean v) { sp.edit().putBoolean(K_ENABLED, v).apply(); }

    /** 401/403：令牌失效。停接收、标记需重新绑定；凭证保留到用户重扫或解绑（解不开也无妨）。 */
    public synchronized void markNeedsRebind() {
        sp.edit().putBoolean(K_REBIND, true).putBoolean(K_ENABLED, false).apply();
    }

    public synchronized void saveCursor(String c) {
        if (c == null || c.equals(cursor)) return;
        cursor = c;
        try { sp.edit().putString(K_CURSOR, encrypt(c)).apply(); } catch (IllegalStateException ignored) {}
    }

    public synchronized void rememberContext(String token) {
        if (token == null || token.isEmpty() || token.equals(contextToken)) return;
        contextToken = token;
        try { sp.edit().putString(K_CONTEXT, encrypt(token)).apply(); } catch (IllegalStateException ignored) {}
    }

    /** 同一条消息只处理一次（游标回退或重启后不会再办一遍）。返回 false 表示处理过了。 */
    public synchronized boolean markHandled(String messageId) {
        if (messageId == null || acked.contains(messageId)) return false;
        acked.addLast(messageId);
        while (acked.size() > MAX_ACKED) acked.removeFirst();
        sp.edit().putString(K_ACKED, String.join("\n", new ArrayList<>(acked))).apply();
        return true;
    }

    // ---------------- 加密 ----------------

    private WechatApi.Account readAccount() {
        String json = decryptOrNull(sp.getString(K_ACCOUNT, null));
        if (json == null) return null;
        try {
            Map<String, Object> m = MiniJson.obj(MiniJson.parse(json));
            String token = MiniJson.str(m, "token");
            String owner = MiniJson.str(m, "ilink_user_id");
            if (token == null || token.isEmpty() || owner == null || owner.isEmpty()) return null;
            return new WechatApi.Account(token, MiniJson.str(m, "baseurl"), MiniJson.str(m, "ilink_bot_id"), owner);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KEY_ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static String encrypt(String plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            // 密钥库不可用时不退回明文保存：这次绑定/游标不落盘（绑定会报失败，请重试）。
            throw new IllegalStateException("密钥库加密失败");
        }
    }

    private static String decryptOrNull(String enc) {
        if (enc == null || enc.isEmpty()) return null;
        try {
            byte[] all = Base64.decode(enc, Base64.NO_WRAP);
            int n = all[0] & 0xff;
            if (n < 12 || n > 32 || all.length < 1 + n + 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 1, n));
            byte[] p = c.doFinal(all, 1 + n, all.length - 1 - n);
            return new String(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 不应出现在任何外发文字/日志里的机密（发回微信前用来打码）。 */
    public synchronized List<String> secrets() {
        List<String> s = new ArrayList<>();
        if (account != null) { s.add(account.token); }
        if (cursor != null) s.add(cursor);
        if (contextToken != null) s.add(contextToken);
        return s;
    }
}
