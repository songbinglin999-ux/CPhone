package com.yuanhou.hand.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.yuanhou.hand.gate.ConfirmCopy;
import com.yuanhou.hand.gate.ConfirmationGate;
import com.yuanhou.hand.hand.SuiteState;
import com.yuanhou.hand.lan.LanServer;
import com.yuanhou.hand.screen.ReadonlyAccessibilityService;
import com.yuanhou.hand.service.OrchestratorService;
import com.yuanhou.hand.session.TaskLog;
import com.yuanhou.hand.session.TaskSession;
import com.yuanhou.hand.util.AppLog;
import com.yuanhou.hand.wechat.WechatBridge;
import com.yuanhou.hand.wechat.WechatControl;

import java.util.List;

/**
 * 0.1.13：按简一方案（/workspace/yuanhou-ui/SPEC.md）重做的首页——给炳霖一个人看的「状态 + 对话」页，不是调试台。
 * 0.1.14：按简一真机意见收紧底边安全区（max(navInset+12, 20) dp，不加在 root 上）和中间纵向节奏，「设置」热区至少 44 dp。结构和颜色不变。
 * 固定架子：状态栏（WindowInsets）→ 标题栏 56（远手 / 设置）→ 连接条 36（微信 / 手 / 网口）→ 弹性对话区 → 底部操作区。
 * 四种状态：已就绪 / 未就绪 / 正在办 / 等你确认。口令、服务开关、重连、急停、网口地址、调试、自测都在设置页。
 * 指令仍走 LocalTextChannel.deliver（和局域网 /say、微信同一条路）；确认按钮交的就是确认闸要的口令。
 */
public class MainActivity extends Activity {
    public static final String EXTRA_SELFTEST = "selftest";

    private enum Mode { READY, NOT_READY, WORKING, CONFIRM }

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView wxItem, handItem, lanItem;
    private View wxDot, handDot, lanDot;
    // 已就绪 / 未就绪
    private LinearLayout centerView;
    private View bigDot;
    private TextView headline, subtitle, primaryBtn;
    private Runnable primaryAction;
    // 正在办
    private LinearLayout workingView, logList;
    private ScrollView logScroll;
    private View workDot;
    private TextView workTitle;
    private ObjectAnimator pulse;
    // 等你确认
    private LinearLayout confirmView;
    private TextView confirmQuestion, confirmPrimary, confirmAmount, confirmSecondary, previewLabel;
    private ImageView previewImg;
    // 底部
    private LinearLayout bottom, inputRow, confirmRow;
    private TextView stopBtn, correctLink;
    private EditText input;
    private boolean correctionOpen;
    private String confirmWord;
    /** 若 WindowInsets 在 buildBottom 之前到达，先记下底 inset，建好后再垫。 */
    private int pendingBottomInset = -1;

    private String lastSig = "";
    private int lastLogVersion = -1;
    private Bitmap shownPreview;

    private final Runnable refresh = this::refresh;
    /** 每秒看一次状态，但只有显示内容真的变了才动视图，免得 uiautomator 等不到 idle。 */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppLog.init(this);
        Style.lightBars(this, true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Style.BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            // 左右和顶 inset 垫在 root 上；底 inset 只垫在底部操作区，避免和底垫叠两次。
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                    ins.getSystemWindowInsetRight(), 0);
            applyBottomInset(ins.getSystemWindowInsetBottom());
            return ins.consumeSystemWindowInsets();
        });

        // ---------- 标题栏 56 ----------
        LinearLayout title = row();
        title.setPadding(dp(20), 0, dp(20), 0);
        TextView name = Style.text(this, 22, true, Style.TEXT);
        name.setText("远手");
        title.addView(name, weight());
        TextView settings = Style.link(this, "设置", 16, false, Style.SUB, v -> openSettings(null));
        settings.setPadding(dp(12), 0, 0, 0);
        settings.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        settings.setMinWidth(dp(44));
        settings.setMinHeight(dp(44));
        title.addView(settings, new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(56)));

        // ---------- 连接条 36 ----------
        LinearLayout bar = row();
        bar.setPadding(dp(20), 0, dp(20), 0);
        View[] d = new View[1];
        wxItem = connItem(bar, "微信", d, "wechat"); wxDot = d[0];
        bar.addView(new View(this), weight());
        handItem = connItem(bar, "手", d, "hand"); handDot = d[0];
        bar.addView(new View(this), weight());
        lanItem = connItem(bar, "网口", d, "lan"); lanDot = d[0];
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(36)));

        // ---------- 弹性对话区 ----------
        FrameLayout area = new FrameLayout(this);
        buildCenter(area);
        buildWorking(area);
        buildConfirm(area);
        root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---------- 底部操作区 ----------
        buildBottom();
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        refresh();
        handleIntent(getIntent());
    }

    private TextView connItem(LinearLayout bar, String label, View[] dotOut, String section) {
        LinearLayout item = row();
        item.setClickable(true);
        item.setBackground(Style.ripple(this, 0, 8));
        item.setOnClickListener(v -> openSettings(section));
        View dot = new View(this);
        dot.setBackground(Style.dot(this, Style.DOT_OFF, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
        lp.rightMargin = dp(8);
        item.addView(dot, lp);
        TextView t = Style.text(this, 13, false, Style.SUB);
        t.setSingleLine(true);
        item.addView(t);
        item.setContentDescription(label);
        bar.addView(item, new LinearLayout.LayoutParams(-2, dp(36)));
        dotOut[0] = dot;
        t.setTag(label);
        return t;
    }

    private void buildCenter(FrameLayout area) {
        centerView = new LinearLayout(this);
        centerView.setOrientation(LinearLayout.VERTICAL);
        centerView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        centerView.setPadding(dp(20), 0, dp(20), dp(56));   // 略偏上
        LinearLayout hl = row();
        hl.setGravity(Gravity.CENTER);
        bigDot = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
        lp.rightMargin = dp(8);
        hl.addView(bigDot, lp);
        headline = Style.text(this, 28, true, Style.TEXT);
        // 只收紧这一组：28sp 标题默认行高 36，多余 leading 会把可见间距撑大。行高改成 28sp，字贴齐字形。
        headline.setLineHeight(Style.spPx(this, 28));
        hl.addView(headline);
        centerView.addView(hl);
        subtitle = Style.text(this, 16, false, Style.SUB);
        subtitle.setGravity(Gravity.CENTER);
        // 副标题较短，行高收成 16sp，标题到副标题、副标题到主按钮的 topMargin 就是可见间距。
        subtitle.setLineHeight(Style.spPx(this, 16));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(10);
        centerView.addView(subtitle, sp);
        primaryBtn = Style.button(this, "", Style.ACCENT, 0xFFFFFFFF, 16, v -> { if (primaryAction != null) primaryAction.run(); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(200), dp(48));
        bp.topMargin = dp(28);
        centerView.addView(primaryBtn, bp);
        area.addView(centerView, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildWorking(FrameLayout area) {
        workingView = new LinearLayout(this);
        workingView.setOrientation(LinearLayout.VERTICAL);
        workingView.setPadding(dp(20), dp(16), dp(20), 0);
        workDot = new View(this);
        workingView.addView(kicker("正在办", Style.ACCENT, workDot));
        workTitle = Style.text(this, 20, true, Style.TEXT);
        workTitle.setMaxLines(3);
        workTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(8);
        workingView.addView(workTitle, tp);
        logScroll = new ScrollView(this);
        logScroll.setVerticalScrollBarEnabled(false);
        logList = new LinearLayout(this);
        logList.setOrientation(LinearLayout.VERTICAL);
        logList.setPadding(0, dp(16), 0, dp(16));
        logScroll.addView(logList);
        workingView.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        pulse = ObjectAnimator.ofFloat(workDot, View.ALPHA, 1f, 0.25f, 1f);
        pulse.setDuration(1200);
        area.addView(workingView, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildConfirm(FrameLayout area) {
        confirmView = new LinearLayout(this);
        confirmView.setOrientation(LinearLayout.VERTICAL);
        confirmView.setPadding(dp(20), dp(16), dp(20), 0);
        confirmView.addView(kicker("等你确认", Style.CONFIRM, new View(this)));
        confirmQuestion = Style.text(this, 20, true, Style.TEXT);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2);
        qp.topMargin = dp(8);
        confirmView.addView(confirmQuestion, qp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Style.rounded(this, Style.SURFACE, 20));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        confirmPrimary = Style.text(this, 16, false, Style.SUB);
        confirmPrimary.setMaxLines(4);
        confirmPrimary.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(confirmPrimary);
        LinearLayout l2 = row();
        confirmAmount = Style.text(this, 16, true, Style.TEXT);
        confirmAmount.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        confirmAmount.setFontFeatureSettings("tnum");
        l2.addView(confirmAmount);
        confirmSecondary = Style.text(this, 16, false, Style.SUB);
        confirmSecondary.setSingleLine(true);
        confirmSecondary.setEllipsize(TextUtils.TruncateAt.END);
        l2.addView(confirmSecondary, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(-1, -2);
        l2p.topMargin = dp(4);
        card.addView(l2, l2p);
        FrameLayout preview = new FrameLayout(this);
        preview.setBackground(Style.rounded(this, Style.INPUT, 12));
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12)); }
        });
        preview.setClipToOutline(true);
        previewImg = new ImageView(this);
        previewImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.addView(previewImg, new FrameLayout.LayoutParams(-1, -1));
        previewLabel = Style.text(this, 13, false, Style.WEAK);
        previewLabel.setText("当前屏幕");
        preview.addView(previewLabel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(120));
        pp.topMargin = dp(16);
        card.addView(preview, pp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(24);
        confirmView.addView(card, cp);
        area.addView(confirmView, new FrameLayout.LayoutParams(-1, -1));
    }

    private LinearLayout kicker(String s, int color, View dot) {
        LinearLayout k = row();
        dot.setBackground(Style.dot(this, color, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
        lp.rightMargin = dp(8);
        k.addView(dot, lp);
        TextView t = Style.text(this, 13, true, color);
        t.setText(s);
        k.addView(t);
        return k;
    }

    private void buildBottom() {
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(20), 0, dp(20), dp(20));   // 先用 20 dp 下限，等 inset 回调再按公式改
        if (pendingBottomInset >= 0) {
            applyBottomInset(pendingBottomInset);
            pendingBottomInset = -1;
        }

        // 「停止这件事」：文字按钮，钉在输入行上方（急停只在设置里）
        stopBtn = Style.link(this, "停止这件事", 16, true, Style.SUB, v -> sendUtterance("取消"));
        bottom.addView(stopBtn, new LinearLayout.LayoutParams(-2, dp(44)));

        inputRow = row();
        input = new EditText(this);
        input.setTextSize(16);
        input.setTextColor(Style.TEXT);
        input.setHintTextColor(Style.WEAK);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setBackground(Style.rounded(this, Style.INPUT, 24));
        input.setPadding(dp(18), 0, dp(18), 0);
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO) { submit(); return true; }
            return false;
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(48), 1f);
        ip.rightMargin = dp(10);
        inputRow.addView(input, ip);
        TextView go = Style.button(this, "去办", Style.ACCENT, 0xFFFFFFFF, 24, v -> submit());
        go.setMinWidth(dp(72));
        go.setPadding(dp(18), 0, dp(18), 0);
        inputRow.addView(go, new LinearLayout.LayoutParams(-2, dp(48)));
        LinearLayout.LayoutParams irp = new LinearLayout.LayoutParams(-1, -2);
        irp.topMargin = dp(4);
        bottom.addView(inputRow, irp);

        confirmRow = row();
        TextView cancel = Style.button(this, "取消", Style.INPUT, Style.TEXT, 16, v -> sendUtterance("取消"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.rightMargin = dp(12);
        confirmRow.addView(cancel, lp);
        TextView ok = Style.button(this, "确认", Style.CONFIRM, 0xFFFFFFFF, 16, v -> {
            if (confirmWord != null) sendUtterance(confirmWord);
        });
        confirmRow.addView(ok, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(-1, -2);
        crp.topMargin = dp(8);
        bottom.addView(confirmRow, crp);
        correctLink = Style.link(this, "或再说一句更正", 13, false, Style.WEAK, v -> {
            correctionOpen = true;
            lastSig = "";
            refresh();
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, 0);
        });
        correctLink.setGravity(Gravity.CENTER);
        bottom.addView(correctLink, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    // ---------------- 状态 ----------------

    private static final class Conn {
        String text; int color; int dot;
        Conn(String text, int color, int dot) { this.text = text; this.color = color; this.dot = dot; }
        String sig() { return text + color + dot; }
    }

    private static final Conn OK = new Conn("已连", Style.SUB, Style.ACCENT);
    private static final Conn OFF = new Conn("未连", Style.AMBER, Style.AMBER);

    private void refresh() {
        OrchestratorService s = OrchestratorService.get();
        TaskSession ts = s == null ? null : s.session();
        boolean a11yOn = ReadonlyAccessibilityService.isRunning();
        boolean a11yEnabled = a11yEnabledInSettings();
        LanServer lan = s == null ? null : s.lan();
        boolean lanOk = lan != null && lan.listening();
        SuiteState st = s == null ? null : s.suite();
        boolean handOk = st != null && st.linkReady();
        WechatBridge.State wx = WechatControl.state(this);
        boolean wxOk = wx == WechatBridge.State.CONNECTED;

        Conn wxC = wxOk ? OK : wx == WechatBridge.State.NEEDS_REBIND ? new Conn("需重绑", Style.FAULT, Style.FAULT) : OFF;
        Conn handC = handOk ? OK : OFF;
        Conn lanC = lanOk ? OK : OFF;

        // 整机就绪：服务在跑 + 手已连（屏幕已设好）+ 网口在听 + 只读无障碍在运行。微信不算在内。
        String reason = null, btn = null;
        Runnable act = null;
        if (s == null) { reason = "服务没启动"; btn = "启动服务"; act = () -> OrchestratorService.start(this); }
        else if (LanServer.wifiIpv4(this) == null) { reason = "手机没连上家里 Wi-Fi"; btn = "去 Wi-Fi 设置"; act = () -> startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
        else if (!handOk) { reason = st.connecting && !st.wifiFresh() ? "正在连手…" : "手没连上"; btn = "重连"; act = () -> { OrchestratorService o = OrchestratorService.get(); if (o != null) o.reconnectNow(); }; }
        else if (!lanOk) { reason = "网口还没开"; btn = "看网口"; act = () -> openSettings("lan"); }
        else if (!st.screenSet) { reason = "正在准备屏幕"; btn = "重连"; act = () -> { OrchestratorService o = OrchestratorService.get(); if (o != null) o.reconnectNow(); }; }
        else if (!a11yOn && !a11yEnabled) { reason = "只读无障碍还没开"; btn = "去系统设置"; act = () -> startSafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        else if (!a11yOn) { reason = "只读无障碍正在连接…"; btn = "去系统设置"; act = () -> startSafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        boolean ready = reason == null;

        ConfirmationGate.Declaration decl = ts == null ? null : ts.pendingConfirm();
        Mode mode;
        if (ts != null && ts.awaitingConfirm() && ConfirmCopy.showable(decl)) mode = Mode.CONFIRM;
        else if (ts != null && ts.running()) mode = Mode.WORKING;
        else mode = ready ? Mode.READY : Mode.NOT_READY;
        if (mode != Mode.CONFIRM) correctionOpen = false;

        int logV = TaskLog.get().version();
        Bitmap prev = mode == Mode.CONFIRM ? ts.confirmPreview() : null;
        String sig = mode + "|" + wxC.sig() + handC.sig() + lanC.sig() + "|" + reason + "|" + wxOk + "|" + correctionOpen
                + "|" + (ts == null ? "" : ts.taskText()) + "|" + (decl == null ? "" : ConfirmationGate.readback(decl))
                + "|" + (prev == null ? 0 : System.identityHashCode(prev)) + "|" + (mode == Mode.WORKING ? logV : 0);
        if (sig.equals(lastSig)) return;   // 没变就不动视图
        lastSig = sig;

        setConn(wxItem, wxDot, wxC);
        setConn(handItem, handDot, handC);
        setConn(lanItem, lanDot, lanC);

        centerView.setVisibility(mode == Mode.READY || mode == Mode.NOT_READY ? View.VISIBLE : View.GONE);
        workingView.setVisibility(mode == Mode.WORKING ? View.VISIBLE : View.GONE);
        confirmView.setVisibility(mode == Mode.CONFIRM ? View.VISIBLE : View.GONE);

        if (mode == Mode.READY) {
            bigDot.setBackground(Style.dot(this, Style.ACCENT, 8));
            headline.setText("已就绪");
            subtitle.setText(wxOk ? "用微信跟我说要办的事" : "本机可以下指令，微信还没连上");
            primaryBtn.setVisibility(View.GONE);
        } else if (mode == Mode.NOT_READY) {
            bigDot.setBackground(Style.dot(this, Style.FAULT, 8));
            headline.setText("未就绪");
            subtitle.setText(reason);
            primaryBtn.setText(btn);
            primaryAction = act;
            primaryBtn.setVisibility(View.VISIBLE);
        } else if (mode == Mode.WORKING) {
            workTitle.setText(ts.taskText());
            if (logV != lastLogVersion) {
                lastLogVersion = logV;
                renderLog();
                if (!pulse.isRunning()) { pulse.setRepeatCount(1); pulse.start(); }   // 有新进展时呼吸闪一下
            }
        } else {
            confirmWord = ConfirmCopy.confirmWord(decl);
            confirmQuestion.setText(ConfirmCopy.question(decl));
            String p1 = ConfirmCopy.primary(decl);
            confirmPrimary.setText(p1);
            confirmPrimary.setVisibility(p1.isEmpty() ? View.GONE : View.VISIBLE);
            String amt = ConfirmCopy.amount(decl), sec = ConfirmCopy.secondary(decl);
            confirmAmount.setText(amt);
            confirmAmount.setVisibility(amt.isEmpty() ? View.GONE : View.VISIBLE);
            confirmSecondary.setText(sec.isEmpty() ? "" : (amt.isEmpty() ? sec : "  ·  " + sec));
            ((View) confirmAmount.getParent()).setVisibility(amt.isEmpty() && sec.isEmpty() ? View.GONE : View.VISIBLE);
            if (prev != shownPreview) {
                shownPreview = prev;
                previewImg.setImageBitmap(prev);
                previewLabel.setVisibility(prev == null ? View.VISIBLE : View.GONE);
            }
        }
        if (mode != Mode.CONFIRM && shownPreview != null) { shownPreview = null; previewImg.setImageBitmap(null); previewLabel.setVisibility(View.VISIBLE); }

        // 底部：未就绪什么都不放；已就绪/正在办 放输入行；等你确认 放两个按钮（点「或再说一句更正」才出输入行）
        stopBtn.setVisibility(mode == Mode.WORKING ? View.VISIBLE : View.GONE);
        boolean showInput = mode == Mode.READY || mode == Mode.WORKING || (mode == Mode.CONFIRM && correctionOpen);
        inputRow.setVisibility(showInput ? View.VISIBLE : View.GONE);
        confirmRow.setVisibility(mode == Mode.CONFIRM ? View.VISIBLE : View.GONE);
        correctLink.setVisibility(mode == Mode.CONFIRM && !correctionOpen ? View.VISIBLE : View.GONE);
        input.setHint(mode == Mode.READY ? "在家里直接说也行" : "更正或补充");
        bottom.setVisibility(mode == Mode.NOT_READY ? View.GONE : View.VISIBLE);
    }

    private void setConn(TextView item, View dot, Conn c) {
        item.setText(item.getTag() + "  " + c.text);
        item.setTextColor(c.color);
        dot.setBackground(Style.dot(this, c.dot, 6));
    }

    private void renderLog() {
        List<TaskLog.Entry> entries = TaskLog.get().snapshot();
        logList.removeAllViews();
        boolean first = true;
        for (TaskLog.Entry e : entries) {
            if ("你".equals(e.who) && first) { first = false; continue; }   // 第一句是任务原话，已经在标题里
            first = false;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.TOP);
            TextView t = Style.text(this, 16, false, Style.SUB);
            t.setText("你".equals(e.who) ? "你：" + e.text : e.text);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView tm = Style.text(this, 13, false, Style.WEAK);
            tm.setTypeface(Typeface.MONOSPACE);
            tm.setFontFeatureSettings("tnum");
            tm.setText(e.time);
            tm.setPadding(dp(12), dp(3), 0, 0);
            r.addView(tm);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (logList.getChildCount() > 0) lp.topMargin = dp(14);
            logList.addView(r, lp);
        }
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------- 动作 ----------------

    private void submit() {
        String t = input.getText().toString();
        if (t.trim().isEmpty()) return;
        input.setText("");
        input.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        sendUtterance(t);
    }

    /** 和 0.1.12 一样：交给 LocalTextChannel.deliver → TaskSession（与局域网 /say、DEBUG_SAY、微信同一条路）。 */
    private void sendUtterance(String t) {
        OrchestratorService s = OrchestratorService.get();
        if (s == null) {
            AppLog.i("先启动前台服务");
            Toast.makeText(this, "先启动服务", Toast.LENGTH_SHORT).show();
            return;
        }
        s.local().deliver(t);
        main.postDelayed(refresh, 150);
    }

    private void openSettings(String section) {
        Intent i = new Intent(this, SettingsActivity.class);
        if (section != null) i.putExtra(SettingsActivity.EXTRA_SECTION, section);
        startActivity(i);
    }

    private void startSafely(Intent i) {
        try { startActivity(i); } catch (Exception e) { Toast.makeText(this, "打不开系统设置", Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** 桌上联调：--ez selftest true 先起服务，再到「设置 › 调试」跑桌面自测（自测框在那里）。 */
    private void handleIntent(Intent i) {
        if (i == null || !i.getBooleanExtra(EXTRA_SELFTEST, false)) return;
        i.removeExtra(EXTRA_SELFTEST);
        if (OrchestratorService.get() == null) OrchestratorService.start(this);
        Intent si = new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SECTION, "debug")
                .putExtra(SettingsActivity.EXTRA_SELFTEST, true);
        startActivity(si);
    }

    @Override
    protected void onResume() {
        super.onResume();
        OrchestratorService.watch(refresh);
        lastSig = "";
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        OrchestratorService.unwatch(refresh);
        main.removeCallbacks(tick);
        if (pulse != null) pulse.cancel();
    }

    // ---------------- 小工具 ----------------

    /** 底垫：max(navigationInset + 12dp, 20dp)。只加在 bottom 上，root 底垫保持 0。 */
    private void applyBottomInset(int insetBottom) {
        if (bottom == null) {
            pendingBottomInset = insetBottom;
            return;
        }
        // 公式与监听器里的 ins.getSystemWindowInsetBottom() 是同一数值。
        int pad = Math.max(insetBottom + dp(12), dp(20));
        bottom.setPadding(dp(20), 0, dp(20), pad);
    }

    private int dp(float v) { return Style.dp(this, v); }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1f); }

    private boolean a11yEnabledInSettings() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName());
    }
}
