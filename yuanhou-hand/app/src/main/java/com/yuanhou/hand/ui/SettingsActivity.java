package com.yuanhou.hand.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.yuanhou.hand.hand.HandExecutor;
import com.yuanhou.hand.hand.SuiteState;
import com.yuanhou.hand.hand.WifiHandClient;
import com.yuanhou.hand.lan.LanProtocol;
import com.yuanhou.hand.lan.LanServer;
import com.yuanhou.hand.model.ModelConfig;
import com.yuanhou.hand.screen.ReadonlyAccessibilityService;
import com.yuanhou.hand.screen.ScreenCapture;
import com.yuanhou.hand.service.OrchestratorService;
import com.yuanhou.hand.session.SipCallChannel;
import com.yuanhou.hand.session.SipConfig;
import com.yuanhou.hand.util.AppLog;
import com.yuanhou.hand.util.Timing;
import com.yuanhou.hand.wechat.WechatBridge;
import com.yuanhou.hand.wechat.WechatControl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 设置（0.1.13 按简一方案收纳）：首页拿掉的东西都在这里——
 * 微信 → 服务（启动/停止、重连、急停）→ 口令（默认圆点，可显示/复制/重新生成）→ 手 → 网口（地址可复制，不含口令）
 * → 模型 → SIP → 鼠标换算 → 手的 Wi-Fi 透传 → 调试（控制自测、桌面自测、截图授权、详细状态、日志）。
 * 已保存的模型 Key、SIP 密码、局域网口令、微信 Bot 令牌默认都不明文显示，也不写日志。
 * 首页连接条点进来时带 EXTRA_SECTION（wechat / hand / lan / debug）滚到对应一项；--ez selftest true 由首页转到这里跑。
 */
public class SettingsActivity extends Activity {
    public static final String EXTRA_SECTION = "section";
    public static final String EXTRA_SELFTEST = "selftest";
    private static final int REQ_CAPTURE = 7;

    private RadioButton rbDeepseek, rbOpenai;
    private EditText openaiBaseUrl, openaiModel, openaiKey, deepseekBaseUrl, deepseekModel, deepseekKey;
    private EditText sipDomain, sipUser, sipPass, cpp, maxStep, wifiHost, wifiPort;
    private CheckBox consent, vision;
    private RadioButton rbUdp, rbTcp, rbTls;
    private TextView sipStatus;
    private TextView wechatStatus;
    private TextView wechatBind, wechatRecv, wechatUnbind;
    private TextView serviceStatus, serviceBtn, tokenValue, tokenToggle, handView, lanAddr;
    private boolean tokenShown;
    private ScrollView scroll;
    private LinearLayout page;
    private final Map<String, View> anchors = new HashMap<>();
    // 调试
    private LinearLayout debugBody;
    private TextView debugToggle;
    private TextView suiteView, lanView, readView, modelView, selfTestView, logView, ctrlCount, reportView;
    private int ctrlClicks;
    private EditText testBox;
    private volatile int boxTouchUps;
    private volatile int lastTouchX = -1, lastTouchY = -1;
    private volatile boolean selfTestRunning;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            showSipStatus();
            showWechat();
            showService();
            if (debugBody != null && debugBody.getVisibility() == View.VISIBLE) refreshDebug();
            main.postDelayed(this, 1000);
        }
    };
    private final Runnable logWatcher = () -> main.post(this::refreshLog);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppLog.init(this);
        Style.lightBars(this, false);
        ModelConfig m = ModelConfig.load(this);
        SipConfig s = SipConfig.load(this);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Style.BG);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(8), dp(20), dp(32));
        scroll.addView(page);
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Style.text(this, 22, true, Style.TEXT);
        title.setText("设置");
        titleBar.addView(title);
        page.addView(titleBar, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout root;

        // ---------- 微信（官方 Bot） ----------
        root = section("微信", "wechat");
        wechatStatus = Style.text(this, 16, false, Style.TEXT);
        root.addView(wechatStatus);
        root.addView(note("用随身微信扫码绑定后，给这个 Bot 发一句要办的事，家里这台手机用远手和手在主屏上办，结果发回微信。"
                + "发微信、下单前会把对象、内容、金额发回来，你回「发送」/「确认下单」才继续，回「取消」「不对」就停；付款永远要你本人在手机上操作。"
                + "只收你本人的私聊文字。随「启动服务/停止服务」一起开停。绑定信息用密钥库加密，只存本机。"));
        wechatBind = button("扫码绑定", true, v -> WechatQrDialog.show(this, this::showWechat));
        root.addView(wechatBind);
        wechatRecv = button("暂停接收微信任务", false, v -> {
            WechatControl.setReceiving(this, !WechatControl.receiving(this));
            showWechat();
        });
        root.addView(wechatRecv);
        wechatUnbind = button("解除绑定…", false, v -> new AlertDialog.Builder(this)
                .setTitle("解除微信绑定？")
                .setMessage("远手将停止接收微信消息，并删除保存在本机的绑定信息。以后要用需重新扫码。")
                .setNegativeButton("取消", null)
                .setPositiveButton("解除绑定", (d, w) -> { WechatControl.unbind(this); showWechat(); Toast.makeText(this, "已解除微信绑定", Toast.LENGTH_SHORT).show(); })
                .show());
        wechatUnbind.setTextColor(Style.FAULT);
        root.addView(wechatUnbind);
        showWechat();

        // ---------- 服务 ----------
        root = section("服务", "service");
        serviceStatus = Style.text(this, 16, false, Style.TEXT);
        root.addView(serviceStatus);
        serviceBtn = button("启动服务", true, v -> toggleService());
        root.addView(serviceBtn);
        LinearLayout two = new LinearLayout(this);
        TextView rc = button("重连", false, v -> {
            OrchestratorService os = OrchestratorService.get();
            if (os != null) os.reconnectNow(); else toast("先启动服务");
        });
        TextView es = button("急停", false, v -> {
            OrchestratorService os = OrchestratorService.get();
            if (os != null) os.emergencyStop(); else toast("服务没在运行");
        });
        es.setTextColor(Style.FAULT);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        l1.rightMargin = dp(12);
        two.addView(rc, l1);
        two.addView(es, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(12);
        root.addView(two, tl);

        // ---------- 口令（默认圆点） ----------
        root = section("口令", "token");
        LinearLayout tokenLine = new LinearLayout(this);
        tokenLine.setGravity(Gravity.CENTER_VERTICAL);
        tokenValue = Style.text(this, 16, false, Style.TEXT);
        tokenValue.setTypeface(Typeface.MONOSPACE);
        tokenValue.setSingleLine(true);
        tokenValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tokenLine.addView(tokenValue, new LinearLayout.LayoutParams(0, -2, 1f));
        tokenToggle = Style.link(this, "显示", 16, false, Style.ACCENT, v -> { tokenShown = !tokenShown; renderToken(); });
        tokenToggle.setPadding(dp(12), 0, dp(12), 0);
        tokenLine.addView(tokenToggle);
        TextView copy = Style.link(this, "复制", 16, false, Style.ACCENT, v -> copyToken());
        copy.setPadding(dp(12), 0, 0, 0);
        tokenLine.addView(copy);
        root.addView(tokenLine);
        root.addView(note("家里电脑用它连这台手机（端口 " + LanProtocol.PORT + "）。只在家里网用，不要做端口转发。"));
        TextView regen = Style.link(this, "重新生成口令…", 16, false, Style.FAULT, v -> confirmRegenerate());
        root.addView(regen);

        // ---------- 手 ----------
        root = section("手（ESP32）", "hand");
        handView = Style.text(this, 16, false, Style.TEXT);
        handView.setTextIsSelectable(true);
        root.addView(handView);
        root.addView(note("主机和端口在下面「手的 Wi-Fi 透传」里改。"));

        // ---------- 网口 ----------
        root = section("网口", "lan");
        lanAddr = Style.text(this, 16, false, Style.TEXT);
        lanAddr.setTypeface(Typeface.MONOSPACE);
        root.addView(lanAddr);
        root.addView(Style.link(this, "复制地址", 16, false, Style.ACCENT, v -> copyLanAddr()));
        root.addView(note("家里电脑的 Goji 用这个地址加上面的口令连远手。只在家里局域网用。"));

        root = section("模型", "model");
        root.addView(note("OpenAI 和 DeepSeek 可以同时填。平时用主模型，额度不够或连不上就自动改用备用。"
                + "Key 由你自己在这里填，只存本机，不显示、不写日志；没填不会调用云端模型。"));
        root.addView(note("主模型"));
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        rbOpenai = new RadioButton(this);
        rbOpenai.setText("OpenAI");
        rbDeepseek = new RadioButton(this);
        rbDeepseek.setText("DeepSeek");
        g.addView(rbOpenai);
        g.addView(rbDeepseek);
        if ("deepseek".equals(m.provider)) rbDeepseek.setChecked(true);
        else rbOpenai.setChecked(true);
        root.addView(g);
        root.addView(note("OpenAI（默认官方地址 " + ModelConfig.HINT_OPENAI_URL + "）"));
        openaiModel = field(root, "模型名（留空 = " + ModelConfig.HINT_OPENAI_MODEL + "）", m.openai.model, false);
        openaiKey = field(root, m.openai.hasSettingsKey() ? "OpenAI API Key：已保存（不显示；留空表示不改）" : "OpenAI API Key（只存本机应用私有目录）", "", true);
        openaiBaseUrl = field(root, "Base URL（选填，留空 = 官方地址）", m.openai.baseUrl, false);
        root.addView(note("DeepSeek（默认官方地址 " + ModelConfig.HINT_DEEPSEEK_URL + "，模型名留空 = " + ModelConfig.HINT_DEEPSEEK_MODEL + "）"));
        deepseekModel = field(root, "模型名（留空 = " + ModelConfig.HINT_DEEPSEEK_MODEL + "）", m.deepseek.model, false);
        deepseekKey = field(root, m.deepseek.hasSettingsKey() ? "DeepSeek API Key：已保存（不显示；留空表示不改）" : "DeepSeek API Key（只存本机应用私有目录）", "", true);
        deepseekBaseUrl = field(root, "Base URL（选填，留空 = 官方地址）", m.deepseek.baseUrl, false);
        consent = new CheckBox(this);
        consent.setText("我同意把当前一屏的界面文字（和截图）发给正在使用的服务商");
        consent.setChecked(m.cloudConsent);
        root.addView(consent);
        vision = new CheckBox(this);
        vision.setText("模型能看图（不勾就只发界面文字；DeepSeek 始终只发文字）");
        vision.setChecked(m.vision);
        root.addView(vision);

        root = section("SIP 通话（语音，已放下）", "sip");
        TextView note = new TextView(this);
        note.setText("远手内嵌了 Linphone 的 SIP 引擎，自己注册、自己接电话。请在这里填你自己的 SIP 账号"
                + "（可以和手机上 Linphone 应用用同一套，也可以另开一个）；远手不会读取或改动 Linphone 应用里的账号。"
                + "同一账号同时登在两处时，来电可能两边都响，建议给远手单独一个账号。密码只存本机应用私有目录，不显示、不写日志。");
        styleNote(note);
        root.addView(note);
        sipDomain = field(root, "SIP 域名（如 sip.example.com 或 sip.example.com:5060）", s.domain, false);
        sipUser = field(root, "SIP 用户名", s.username, false);
        sipPass = field(root, s.password.isEmpty() ? "SIP 密码" : "SIP 密码：已保存（不显示；留空表示不改）", "", true);
        RadioGroup tg = new RadioGroup(this);
        tg.setOrientation(RadioGroup.HORIZONTAL);
        rbUdp = new RadioButton(this); rbUdp.setText("UDP");
        rbTcp = new RadioButton(this); rbTcp.setText("TCP");
        rbTls = new RadioButton(this); rbTls.setText("TLS");
        tg.addView(rbUdp); tg.addView(rbTcp); tg.addView(rbTls);
        ("tls".equals(s.transport) ? rbTls : "tcp".equals(s.transport) ? rbTcp : rbUdp).setChecked(true);
        root.addView(tg);
        sipStatus = new TextView(this);
        styleNote(sipStatus);
        root.addView(sipStatus);
        root.addView(button("清除 SIP 账号", false, v -> {
            SipConfig.clear(this);
            sipDomain.setText("");
            sipUser.setText("");
            sipPass.setText("");
            sipPass.setHint("SIP 密码");
            OrchestratorService os = OrchestratorService.get();
            if (os != null) os.sip().reload();
            AppLog.i("SIP 账号已清除");
        }));

        root = section("鼠标换算（桌上标定用）", "mouse");
        int cv = getSharedPreferences(OrchestratorService.PREFS, MODE_PRIVATE).getInt("cpp", OrchestratorService.DEFAULT_CPP);
        cpp = field(root, "counts_per_px_milli（默认 " + OrchestratorService.DEFAULT_CPP + "，真机标定）", String.valueOf(cv), false);
        cpp.setInputType(InputType.TYPE_CLASS_NUMBER);
        int ms = getSharedPreferences(OrchestratorService.PREFS, MODE_PRIVATE).getInt("max_step", OrchestratorService.DEFAULT_MAX_STEP);
        maxStep = field(root, "定位每份报告最大计数（默认 " + OrchestratorService.DEFAULT_MAX_STEP + "，越小越慢越准）", String.valueOf(ms), false);
        maxStep.setInputType(InputType.TYPE_CLASS_NUMBER);

        root = section("手的 Wi-Fi 透传", "wifi");
        root.addView(note("板子在家里局域网的 TCP；这里只填主机和端口，不填 Wi-Fi 密码，也不写 SSID。"));
        TextView wifiNote = new TextView(this);
        wifiNote.setText("不要再写死旧的局域网 IP：DHCP 会变，而且曾经的默认地址在真机上已经是手机自己的 wlan0。"
                + "请填 s3-phone-hand.local 或板子当前的 IPv4。留空则用默认名 "
                + WifiHandClient.DEFAULT_HOST + "。"
                + "这台荣耀 Android 10 上普通域名解析很可能解析不了 .local，本应用还没有做完整的组播 mDNS，也不宣称 .local 一定能用；"
                + "解析失败时会改用下面记下的「上次成功地址」。若连不上，请直接填当前 IP。");
        styleNote(wifiNote);
        root.addView(wifiNote);
        android.content.SharedPreferences sp = getSharedPreferences(OrchestratorService.PREFS, MODE_PRIVATE);
        wifiHost = field(root, "Wi-Fi 透传主机（s3-phone-hand.local 或 IPv4；留空 = " + WifiHandClient.DEFAULT_HOST + "）",
                sp.getString("wifi_host", ""), false);
        int wp = sp.getInt("wifi_port", WifiHandClient.DEFAULT_PORT);
        wifiPort = field(root, "Wi-Fi 透传端口（默认 " + WifiHandClient.DEFAULT_PORT + "）", String.valueOf(wp), false);
        wifiPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        TextView lastGood = new TextView(this);
        String last = sp.getString("wifi_last_good_host", "");
        lastGood.setText(last.isEmpty()
                ? "还没有记下上次成功连上的地址。若主机名解析失败，请在上面填写板子的 IPv4。"
                : ("上次成功连上的地址：" + last + "（主机名解析失败时的兜底，可在上面改成当前 IP）"));
        styleNote(lastGood);
        root.addView(lastGood);

        root = section(null, null);
        root.addView(button("保存模型、SIP、鼠标、Wi-Fi 透传", true, v -> save()));
        root.addView(button("清除模型 Key", false, v -> {
            ModelConfig mm = ModelConfig.load(this);
            mm.clearKeys();
            mm.save(this);
            openaiKey.setText("");
            deepseekKey.setText("");
            openaiKey.setHint("OpenAI API Key（只存本机应用私有目录）");
            deepseekKey.setHint("DeepSeek API Key（只存本机应用私有目录）");
            Toast.makeText(this, "已清除两个服务商的 Key", Toast.LENGTH_SHORT).show();
        }));

        // ---------- 调试（默认折叠） ----------
        debugToggle = Style.link(this, "调试 ▸", 16, true, Style.SUB, v -> showDebug(debugBody.getVisibility() != View.VISIBLE));
        LinearLayout.LayoutParams dtp = new LinearLayout.LayoutParams(-2, dp(44));
        dtp.topMargin = dp(16);
        page.addView(debugToggle, dtp);
        anchors.put("debug", debugToggle);
        debugBody = new LinearLayout(this);
        debugBody.setOrientation(LinearLayout.VERTICAL);
        debugBody.setVisibility(View.GONE);
        buildDebug(debugBody);
        page.addView(debugBody);

        setContentView(scroll);
        showService();
        renderToken();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String sec = i.getStringExtra(EXTRA_SECTION);
        if (sec != null) {
            i.removeExtra(EXTRA_SECTION);
            if ("debug".equals(sec)) showDebug(true);
            View a = anchors.get(sec);
            if (a != null) scroll.post(() -> scroll.scrollTo(0, Math.max(0, topIn(a) - dp(8))));
        }
        if (i.getBooleanExtra(EXTRA_SELFTEST, false)) {
            i.removeExtra(EXTRA_SELFTEST);
            if (OrchestratorService.get() == null) OrchestratorService.start(this);
            waitReadyThen(30000, this::runSelfTest);
        }
    }

    private int topIn(View v) {
        int y = 0;
        while (v != null && v != scroll) {
            y += v.getTop();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return y;
    }

    // ---------------- 分组样式 ----------------

    /** 一组设置：白卡片（圆角 20、内边 20），标题 16sp Bold。title 为 null 时只有卡片。 */
    private LinearLayout section(String title, String anchor) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Style.rounded(this, Style.SURFACE, 20));
        c.setPadding(dp(20), dp(16), dp(20), dp(16));
        if (title != null) {
            TextView t = Style.text(this, 16, true, Style.TEXT);
            t.setText(title);
            t.setPadding(0, 0, 0, dp(8));
            c.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        page.addView(c, lp);
        if (anchor != null) anchors.put(anchor, c);
        return c;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        styleNote(t);
        return t;
    }

    private void styleNote(TextView t) {
        t.setTextSize(13);
        t.setLineHeight(Style.spPx(this, 18));
        t.setTextColor(Style.SUB);
        t.setPadding(0, dp(6), 0, dp(6));
    }

    private TextView button(String s, boolean primary, View.OnClickListener l) {
        TextView b = Style.button(this, s, primary ? Style.ACCENT : Style.INPUT, primary ? 0xFFFFFFFF : Style.TEXT, 16, l);
        b.setLayoutParams(lp48());
        return b;
    }

    private LinearLayout.LayoutParams lp48() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.topMargin = dp(8);
        return p;
    }

    private int dp(float v) { return Style.dp(this, v); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------- 服务 / 手 / 网口 / 口令 ----------------

    private void toggleService() {
        if (OrchestratorService.get() == null) OrchestratorService.start(this);
        else stopService(new Intent(this, OrchestratorService.class));
        main.postDelayed(this::showService, 300);
    }

    private void showService() {
        if (serviceStatus == null) return;
        OrchestratorService s = OrchestratorService.get();
        serviceStatus.setText(s == null ? "服务没启动（局域网、手、微信都停着）" : "服务在运行 · " + s.session().stateText());
        serviceBtn.setText(s == null ? "启动服务" : "停止服务");
        SuiteState st = s == null ? null : s.suite();
        handView.setText(st == null ? "未连（服务没启动）" : (st.linkReady() ? "已连" : "未连") + "\n" + st.lines());
        LanServer lan = s == null ? null : s.lan();
        lanAddr.setText(lan != null && lan.listening() ? lan.listenAddr() + ":" + lan.port() : "未开启");
        renderToken();
    }

    private void copyLanAddr() {
        OrchestratorService s = OrchestratorService.get();
        LanServer lan = s == null ? null : s.lan();
        if (lan == null || !lan.listening()) { toast("网口没开"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("远手网口", lan.listenAddr() + ":" + lan.port()));
        toast("地址已复制");
    }

    /** 服务在跑时用 LanServer 的口令；没跑时读同一个 lan_token 偏好（服务启动时会用它）。不写日志。 */
    private String currentToken() {
        OrchestratorService s = OrchestratorService.get();
        String t = s != null && s.lan() != null ? s.lan().token() : null;
        if (t == null) {
            String p = getSharedPreferences(OrchestratorService.PREFS, MODE_PRIVATE).getString(LanServer.PREF_TOKEN, "");
            if (LanProtocol.tokenWellFormed(p)) t = p;
        }
        return t;
    }

    private void renderToken() {
        if (tokenValue == null) return;
        String t = currentToken();
        if (t == null) {
            tokenValue.setText("启动服务后生成");
            tokenValue.setTextColor(Style.SUB);
            tokenToggle.setVisibility(View.GONE);
            return;
        }
        tokenToggle.setVisibility(View.VISIBLE);
        tokenToggle.setText(tokenShown ? "隐藏" : "显示");
        tokenValue.setTextColor(Style.TEXT);
        tokenValue.setText(tokenShown ? t : "••••••••••••");
    }

    private void copyToken() {
        String t = currentToken();
        if (t == null) { toast("启动服务后才有口令"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("远手口令", t));
        toast("口令已复制");
    }

    private void confirmRegenerate() {
        OrchestratorService s = OrchestratorService.get();
        if (s == null || s.lan() == null) { toast("先启动服务"); return; }
        new AlertDialog.Builder(this)
                .setTitle("重新生成口令？")
                .setMessage("旧口令立即作废，家里电脑要换成新口令才能再连。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重新生成", (dlg, w) -> {
                    OrchestratorService s2 = OrchestratorService.get();
                    if (s2 != null && s2.lan() != null) s2.lan().regenerateToken();
                    tokenShown = false;
                    renderToken();
                    toast("已换新口令");
                })
                .show();
    }

    private void save() {
        ModelConfig m = ModelConfig.load(this);
        m.provider = rbDeepseek.isChecked() ? "deepseek" : rbOpenai.isChecked() ? "openai" : "";
        m.openai.model = openaiModel.getText().toString().trim();
        m.openai.baseUrl = openaiBaseUrl.getText().toString().trim();
        String ok = openaiKey.getText().toString().trim();
        if (!ok.isEmpty()) m.openai.apiKey = ok;
        m.deepseek.model = deepseekModel.getText().toString().trim();
        m.deepseek.baseUrl = deepseekBaseUrl.getText().toString().trim();
        String dk = deepseekKey.getText().toString().trim();
        if (!dk.isEmpty()) m.deepseek.apiKey = dk;
        m.cloudConsent = consent.isChecked();
        m.vision = vision.isChecked();
        m.save(this);
        openaiKey.setText("");
        deepseekKey.setText("");
        if (m.openai.hasSettingsKey()) openaiKey.setHint("OpenAI API Key：已保存（不显示；留空表示不改）");
        if (m.deepseek.hasSettingsKey()) deepseekKey.setHint("DeepSeek API Key：已保存（不显示；留空表示不改）");

        SipConfig s = SipConfig.load(this);
        s.domain = SipConfig.cleanDomain(sipDomain.getText().toString());
        s.username = sipUser.getText().toString().trim();
        String p = sipPass.getText().toString();
        if (!p.isEmpty()) s.password = p;
        s.transport = rbTls.isChecked() ? "tls" : rbTcp.isChecked() ? "tcp" : "udp";
        s.save(this);
        sipPass.setText("");
        if (!s.password.isEmpty()) sipPass.setHint("SIP 密码：已保存（不显示；留空表示不改）");
        OrchestratorService os = OrchestratorService.get();
        if (os != null) os.sip().reload();

        int c = OrchestratorService.DEFAULT_CPP;
        try { c = Integer.parseInt(cpp.getText().toString().trim()); } catch (Exception ignored) {}
        if (c < 100 || c > 8000) c = OrchestratorService.DEFAULT_CPP;
        int ms = OrchestratorService.DEFAULT_MAX_STEP;
        try { ms = Integer.parseInt(maxStep.getText().toString().trim()); } catch (Exception ignored) {}
        if (ms < 1 || ms > 16) ms = OrchestratorService.DEFAULT_MAX_STEP;
        String host = wifiHost.getText().toString().trim();
        int wp = WifiHandClient.DEFAULT_PORT;
        try { wp = Integer.parseInt(wifiPort.getText().toString().trim()); } catch (Exception ignored) {}
        if (wp < 1 || wp > 65535) wp = WifiHandClient.DEFAULT_PORT;
        getSharedPreferences(OrchestratorService.PREFS, MODE_PRIVATE).edit()
                .putInt("cpp", c)
                .putInt("max_step", ms)
                .putString("wifi_host", host)
                .putInt("wifi_port", wp)
                .apply();
        if (os != null) os.reloadWifi();

        AppLog.i("配置已保存：" + m + "；SIP " + (s.complete() ? "已填" : "未填")
                + "；cpp=" + c + " max_step=" + ms
                + "；wifi=" + (host.isEmpty() ? WifiHandClient.DEFAULT_HOST : host) + ":" + wp);
        Toast.makeText(this, os == null ? "已保存。前台服务没在运行，启动后才会注册 SIP、使用新的 Wi-Fi 主机" : "已保存，正在注册 SIP；已重新加载 Wi-Fi 透传",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.watch(logWatcher);
        main.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppLog.unwatch(logWatcher);
        main.removeCallbacks(poll);
        tokenShown = false;   // 离开页面就把口令收起来
        renderToken();
    }

    private void showWechat() {
        if (wechatStatus == null) return;
        WechatBridge.State st = WechatControl.state(this);
        boolean bound = WechatControl.bound(this);
        wechatStatus.setText("状态：" + st.label);
        wechatBind.setText(bound || st == WechatBridge.State.NEEDS_REBIND ? "重新扫码绑定" : "扫码绑定");
        wechatRecv.setEnabled(bound && st != WechatBridge.State.NEEDS_REBIND);
        wechatRecv.setAlpha(wechatRecv.isEnabled() ? 1f : 0.4f);
        wechatRecv.setText(WechatControl.receiving(this) ? "暂停接收微信任务" : "开始接收微信任务");
        wechatUnbind.setEnabled(bound || st == WechatBridge.State.NEEDS_REBIND);
        wechatUnbind.setAlpha(wechatUnbind.isEnabled() ? 1f : 0.4f);
    }

    private void showSipStatus() {
        OrchestratorService os = OrchestratorService.get();
        SipCallChannel sc = os == null ? null : os.sip();
        sipStatus.setText(sc == null ? "SIP 状态：前台服务未运行" : ("SIP 状态：" + sc.regStatus() + "\n" + sc.sdkStatus()));
    }

    private EditText field(LinearLayout root, String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(16);
        e.setTextColor(Style.TEXT);
        e.setHintTextColor(Style.WEAK);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    // ---------------- 调试：原来首页上的控制自测、桌面自测、截图授权、详细状态、日志，逻辑不变 ----------------

    private void buildDebug(LinearLayout d) {
        LinearLayout c = debugCard(d, "控制自测");
        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView ctrlBtn = Style.button(this, "自测按钮", Style.ACCENT_SOFT, Style.ACCENT, 16, v -> {
            ctrlClicks++;
            ctrlCount.setText("自测按钮已点 " + ctrlClicks + " 次");
            AppLog.i("自测按钮被点了（第 " + ctrlClicks + " 次）");
        });
        ctrlRow.addView(ctrlBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        ctrlCount = Style.text(this, 16, false, Style.TEXT);
        ctrlCount.setText("自测按钮已点 0 次");
        ctrlCount.setPadding(dp(12), 0, 0, 0);
        ctrlRow.addView(ctrlCount, new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(ctrlRow);

        testBox = new EditText(this);
        testBox.setHint("自测输入框");
        testBox.setTextSize(16);
        testBox.setSingleLine(true);
        testBox.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                boxTouchUps++;
                lastTouchX = (int) ev.getRawX();
                lastTouchY = (int) ev.getRawY();
                AppLog.i("自测框收到 UP 源=0x" + Integer.toHexString(ev.getSource())
                        + " 屏坐标=" + lastTouchX + "," + lastTouchY);
            }
            return false;
        });
        LinearLayout.LayoutParams tb = new LinearLayout.LayoutParams(-1, -2);
        tb.topMargin = dp(8);
        c.addView(testBox, tb);
        c.addView(button("开始桌面自测（点自测框 + 剪贴板粘贴）", false, v -> runSelfTest()));
        selfTestView = mono();
        c.addView(selfTestView);

        c = debugCard(d, "看屏幕");
        readView = Style.text(this, 16, false, Style.TEXT);
        c.addView(readView);
        c.addView(button("申请本次运行的截图授权", false, v -> requestCapture()));
        c.addView(note("会弹系统确认框，需要本人同意。授权后，首页「等你确认」会显示那一刻的主屏缩略图。"));

        c = debugCard(d, "详细状态");
        suiteView = mono();
        c.addView(suiteView);
        c.addView(debugTitle("局域网通道"));
        lanView = mono();
        lanView.setTextIsSelectable(true);
        c.addView(lanView);
        c.addView(debugTitle("模型（可选）与语音（已放下）"));
        modelView = mono();
        c.addView(modelView);

        c = debugCard(d, "最近一次本地意图记录");
        reportView = mono();
        c.addView(reportView);
        c.addView(debugTitle("日志"));
        logView = mono();
        c.addView(logView);
    }

    private LinearLayout debugCard(LinearLayout d, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Style.rounded(this, Style.SURFACE, 20));
        c.setPadding(dp(20), dp(16), dp(20), dp(16));
        c.addView(debugTitle(title));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        d.addView(c, lp);
        return c;
    }

    private TextView debugTitle(String s) {
        TextView t = Style.text(this, 16, true, Style.TEXT);
        t.setText(s);
        t.setPadding(0, dp(8), 0, dp(6));
        return t;
    }

    private TextView mono() {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTextColor(0xFF3A3A38);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private void showDebug(boolean show) {
        debugBody.setVisibility(show ? View.VISIBLE : View.GONE);
        debugToggle.setText(show ? "调试 ▾" : "调试 ▸");
        if (show) { refreshDebug(); refreshLog(); }
    }

    /** 自测要用 HID 鼠标点自测框：先展开调试区，把自测框滚到屏幕中部。 */
    private void revealTestBox() {
        showDebug(true);
        scroll.post(() -> scroll.scrollTo(0, Math.max(0, topIn(testBox) - scroll.getHeight() / 3)));
    }

    private void refreshDebug() {
        OrchestratorService s = OrchestratorService.get();
        boolean a11yOn = ReadonlyAccessibilityService.isRunning();
        String en = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean a11yEnabled = en != null && en.contains(getPackageName());
        if (s == null) {
            suiteView.setText("前台服务未运行\n两件套：未就绪（服务没启动）");
        } else {
            SuiteState st = s.suite();
            suiteView.setText(st.title() + "\n" + st.lines()
                    + (st.info.isEmpty() ? "" : "\n固件：" + st.info)
                    + "\n任务：" + s.session().stateText()
                    + "\n" + s.sip().line()
                    + "\n微信：" + WechatControl.state(this).label);
            String rp = s.session().lastLocalReport();
            reportView.setText(rp.isEmpty() ? "（还没有）" : rp);
        }
        lanView.setText(lanText(s));
        readView.setText("只读无障碍：" + (a11yOn ? "已开启（只读界面树，不执行操作）"
                : a11yEnabled ? "已在设置里开启，正在连接…"
                : "未开启。需要本人明确同意后自己在系统设置里开启；应用不会自动引导。没开时仍可连手、看状态、做桌面自测。")
                + "\n截图：" + (ScreenCapture.available() ? "本次运行已授权" : "未授权"));
        SipCallChannel sc = s == null ? null : s.sip();
        modelView.setText(ModelConfig.load(this).describe() + "\n" + SipConfig.load(this).describe()
                + "\n" + (sc == null ? "语音通路：前台服务未运行" : sc.line())
                + "\n语音/SIP 本阶段已放下：没填 SIP 账号时不启动、不注册。");
    }

    /** 局域网详情：地址、端口、mDNS。口令不在这里显示，只在上面「口令」里按需显示。 */
    private String lanText(OrchestratorService s) {
        LanServer lan = s == null ? null : s.lan();
        if (lan == null) {
            String last = getSharedPreferences(OrchestratorService.PREFS, MODE_PRIVATE).getString(LanServer.PREF_LAST_ADDR, "");
            return "前台服务未运行，局域网通道关闭。点「启动服务」后开启。"
                    + (last.isEmpty() ? "" : "\n上次监听：" + last);
        }
        StringBuilder b = new StringBuilder();
        b.append("状态：").append(lan.state()).append('\n');
        b.append("地址：").append(lan.listening() ? "http://" + lan.listenAddr() + ":" + lan.port() : "（未监听）").append('\n');
        b.append("mDNS：").append(lan.nsdName() == null ? "未广播（直接用上面的 IPv4）" : lan.nsdName() + "." + LanProtocol.NSD_TYPE).append('\n');
        b.append("口令头：X-Yuanhou-Token（口令见上面「口令」）\n");
        b.append("只在家里局域网用，不要做端口转发，不要暴露到公网。");
        return b.toString();
    }

    private void refreshLog() {
        if (logView == null || debugBody.getVisibility() != View.VISIBLE) return;
        List<String> lines = AppLog.snapshot();
        int from = Math.max(0, lines.size() - 80);
        logView.setText(TextUtils.join("\n", lines.subList(from, lines.size())));
    }

    private void requestCapture() {
        MediaProjectionManager mp = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mp.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE) {
            if (res == RESULT_OK && data != null && OrchestratorService.get() != null) {
                MediaProjectionManager mp = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                ScreenCapture.setProjection(mp.getMediaProjection(res, data));
                AppLog.i("截图已授权（本次运行）");
            } else {
                AppLog.i(OrchestratorService.get() == null ? "截图授权需要前台服务在运行" : "截图未授权");
            }
            refreshDebug();
        }
    }

    private void waitReadyThen(long timeoutMs, Runnable r) {
        long until = System.currentTimeMillis() + timeoutMs;
        main.post(new Runnable() {
            @Override
            public void run() {
                OrchestratorService s = OrchestratorService.get();
                if (s != null && s.suite().ready()) {
                    main.postDelayed(r, 800);
                } else if (System.currentTimeMillis() > until) {
                    r.run();   // 让自测如实报告未就绪原因
                } else {
                    main.postDelayed(this, 500);
                }
            }
        });
    }

    // 记录 HID 鼠标在本窗口里的真实位置，用于自测核对落点（只读，不注入任何事件）
    private volatile int hoverX = -1, hoverY = -1, downX = -1, downY = -1, upX = -1, upY = -1;

    private static boolean isMouse(MotionEvent ev) {
        return (ev.getSource() & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE
                || ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (isMouse(ev)) {
            hoverX = (int) ev.getRawX();
            hoverY = (int) ev.getRawY();
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isMouse(ev)) {
            int x = (int) ev.getRawX(), y = (int) ev.getRawY();
            hoverX = x;
            hoverY = y;
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = x; downY = y; AppLog.i("鼠标按下 " + x + "," + y); }
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) { upX = x; upY = y; AppLog.i("鼠标松开 " + x + "," + y); }
            if (selfTestRunning && ev.getActionMasked() == MotionEvent.ACTION_DOWN && !inside(testBox, x, y)) {
                // 自测期间落点不在自测框内：吞掉这次点击，避免误触按钮（例如急停）
                AppLog.i("自测：落点不在自测框内，忽略这次点击");
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private static boolean inside(View v, int x, int y) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return x >= l[0] && y >= l[1] && x < l[0] + v.getWidth() && y < l[1] + v.getHeight();
    }

    // ---------- 桌面自测 ----------
    private void runSelfTest() {
        OrchestratorService s = OrchestratorService.get();
        if (s == null) {
            selfTestView.setText("自测：前台服务未运行");
            return;
        }
        if (selfTestRunning) return;
        String reason = s.suite().notReadyReason();
        if (reason != null) {
            String msg = "自测未执行：两件套未就绪 — " + reason;
            selfTestView.setText(msg);
            AppLog.i(msg);
            writeResult(msg + "\n");
            return;
        }
        selfTestRunning = true;
        revealTestBox();
        selfTestView.setText("自测进行中…");
        HandExecutor hand = s.hand();
        s.worker().submit(() -> {
            StringBuilder rep = new StringBuilder();
            int pass = 0, fail = 0;
            try {
                hand.clearStop();
                onMain(() -> {
                    testBox.setText("");
                    testBox.clearFocus();
                    boxTouchUps = 0;
                });
                Thread.sleep(600);   // 等调试区展开、自测框滚到屏幕上再取坐标
                int[] c = onMainGet(() -> centerOf(testBox));
                int ups0 = boxTouchUps;
                downX = downY = upX = upY = -1;
                try {
                    hand.homeCursor();
                    Thread.sleep(300);
                    rep.append("INFO  HOME 后光标=").append(hoverX).append(',').append(hoverY).append('\n');
                    hand.click(c[0], c[1]);
                    Thread.sleep(Timing.hitVerify());
                    boolean hit = boxTouchUps > ups0;
                    boolean focus = onMainGet(() -> new int[]{testBox.hasFocus() ? 1 : 0})[0] == 1;
                    String d = "目标中心=" + c[0] + "," + c[1] + " 鼠标按下=" + downX + "," + downY
                            + " 松开=" + upX + "," + upY
                            + " 框收到点击=" + hit + " 焦点=" + focus;
                    if (hit && focus) { pass++; rep.append("PASS  HID_CLICK  ").append(d).append('\n'); }
                    else { fail++; rep.append("FAIL  HID_CLICK  ").append(d).append('\n'); }
                } catch (HandExecutor.HandException e) {
                    fail++;
                    rep.append("FAIL  HID_CLICK  ").append(e.getMessage()).append('\n');
                }
                // 焦点兜底只用本应用自己的 requestFocus（不是无障碍），以便单独验证粘贴通道
                onMain(() -> {
                    if (!testBox.hasFocus()) {
                        testBox.requestFocus();
                        AppLog.i("自测：点击未让框获得焦点，改用本应用 requestFocus 继续验证粘贴");
                    }
                    testBox.setText("");
                });
                Thread.sleep(400);
                String en = "Hello Yuanhou 123";
                pass += pasteCheck(hand, en, en, rep, "PASTE_EN") ? 1 : 0;
                Thread.sleep(Timing.betweenOps());
                pass += pasteCheck(hand, "你好", en + "你好", rep, "PASTE_CN") ? 1 : 0;
            } catch (InterruptedException e) {
                rep.append("FAIL  中断\n");
            } catch (Exception e) {
                rep.append("FAIL  异常 ").append(e).append('\n');
            }
            int total = 3;
            int failCount = 0;
            for (String line : rep.toString().split("\n")) if (line.startsWith("FAIL")) failCount++;
            String head = "远手 桌面自测 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())
                    + "\n通过 " + (total - failCount) + " / " + total + "\n";
            String body = head + rep;
            AppLog.i(body.replace('\n', ' '));
            writeResult(body);
            main.post(() -> selfTestView.setText(body));
            selfTestRunning = false;
        });
    }

    private boolean pasteCheck(HandExecutor hand, String clip, String expect, StringBuilder rep, String name)
            throws InterruptedException {
        try {
            hand.paste(clip);
            Thread.sleep(Timing.hitVerify());
            String got = onMainGetStr(() -> testBox.getText().toString());
            boolean ok = got.equals(expect);
            rep.append(ok ? "PASS  " : "FAIL  ").append(name).append("  期望=[").append(expect)
                    .append("] 实际=[").append(got).append("]\n");
            return ok;
        } catch (HandExecutor.HandException e) {
            rep.append("FAIL  ").append(name).append("  ").append(e.getMessage()).append('\n');
            return false;
        }
    }

    private void writeResult(String body) {
        AppLog.writeFile(this, "selftest.txt", body);
    }

    private interface IntsSupplier { int[] get(); }
    private interface StrSupplier { String get(); }

    private void onMain(Runnable r) throws InterruptedException {
        CountDownLatch l = new CountDownLatch(1);
        main.post(() -> { try { r.run(); } finally { l.countDown(); } });
        l.await(3, TimeUnit.SECONDS);
    }

    private int[] onMainGet(IntsSupplier s) throws InterruptedException {
        final int[][] out = {new int[]{0, 0}};
        onMain(() -> out[0] = s.get());
        return out[0];
    }

    private String onMainGetStr(StrSupplier s) throws InterruptedException {
        final String[] out = {""};
        onMain(() -> out[0] = s.get());
        return out[0];
    }

    private static int[] centerOf(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new int[]{loc[0] + v.getWidth() / 2, loc[1] + v.getHeight() / 2};
    }

}
