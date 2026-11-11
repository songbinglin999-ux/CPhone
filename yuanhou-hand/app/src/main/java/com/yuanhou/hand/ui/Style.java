package com.yuanhou.hand.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

/**
 * 0.1.13：简一主界面方案（/workspace/yuanhou-ui/SPEC.md）的颜色、字号、圆角。首页和设置页共用这一套，不另起视觉系统。
 * 字号只用 13/16/20/22/28 sp，行高 18/24/28/28/36；不要投影、不要渐变；按压用强调色 20% 的系统 ripple。
 */
final class Style {
    private Style() {}

    static final int BG = 0xFFF5F5F2;
    static final int SURFACE = 0xFFFFFFFF;
    static final int TEXT = 0xFF1A1A18;
    static final int SUB = 0xFF6B6A66;
    static final int WEAK = 0xFF9A9A94;
    static final int LINE = 0xFFE6E5E0;
    static final int ACCENT = 0xFF2F6B5A;
    static final int ACCENT_SOFT = 0xFFE8F0ED;
    static final int INPUT = 0xFFEEEEEA;
    static final int CONFIRM = 0xFFB86A1C;
    static final int CONFIRM_SOFT = 0xFFFBF3E8;
    static final int FAULT = 0xFFB42318;
    static final int DOT_OFF = 0xFFC5C4BE;
    /** 「未连」用的琥珀色：和确认色同一色相（SPEC 只给了确认色 #B86A1C）。 */
    static final int AMBER = CONFIRM;
    static final int RIPPLE = 0x332F6B5A;

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** 浅色状态栏/导航栏，颜色跟页背景。内容由调用方按 WindowInsets 留边。 */
    static void lightBars(Activity a, boolean edgeToEdge) {
        Window w = a.getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (edgeToEdge) f |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        w.getDecorView().setSystemUiVisibility(f);
    }

    /** 文字：sp 只能是 13/16/20/22/28。bold=true 用粗体，medium=true 用 sans-serif-medium。 */
    static TextView text(Context c, int sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineHeight(spPx(c, lineHeight(sp)));
        t.setTypeface(bold ? Typeface.create("sans-serif", Typeface.BOLD) : Typeface.create("sans-serif", Typeface.NORMAL));
        return t;
    }

    static int lineHeight(int sp) {
        switch (sp) {
            case 13: return 18;
            case 16: return 24;
            case 20: case 22: return 28;
            case 28: return 36;
            default: throw new IllegalArgumentException("字号只用 13/16/20/22/28");
        }
    }

    static int spPx(Context c, float sp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, c.getResources().getDisplayMetrics());
    }

    static GradientDrawable rounded(Context c, int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable dot(Context c, int color, float sizeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setSize(dp(c, sizeDp), dp(c, sizeDp));
        return g;
    }

    static Drawable ripple(Context c, int fill, float radiusDp) {
        Drawable content = fill == 0 ? null : rounded(c, fill, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, rounded(c, 0xFF000000, radiusDp));
    }

    /** 按钮：16sp Bold，高由调用方给（48）。 */
    static TextView button(Context c, String s, int fill, int textColor, float radiusDp, View.OnClickListener l) {
        TextView b = text(c, 16, true, textColor);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackground(ripple(c, fill, radiusDp));
        b.setOnClickListener(l);
        return b;
    }

    /** 文字按钮（设置、停止这件事、复制…）：热区至少 44。 */
    static TextView link(Context c, String s, int sp, boolean bold, int color, View.OnClickListener l) {
        TextView b = text(c, sp, bold, color);
        b.setText(s);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(c, 44));
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackground(ripple(c, 0, 10));
        b.setOnClickListener(l);
        return b;
    }
}
