package com.yuanhou.hand.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.yuanhou.hand.wechat.WechatLogin;

import java.util.EnumMap;
import java.util.Map;

/**
 * 0.1.12 临时的扫码绑定对话框（简一会重新设计首页，这里只求能用、整洁）。
 * 二维码在本机用 ZXing 画（同 Mika QrCodeImage）；内容不写日志、不复制、不保存。
 */
final class WechatQrDialog {
    private WechatQrDialog() {}

    static void show(Activity a, Runnable onFinished) {
        int pad = dp(a, 20);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(pad, pad, pad, dp(a, 8));
        ImageView img = new ImageView(a);
        int size = dp(a, 240);
        img.setMinimumWidth(size);
        img.setMinimumHeight(size);
        box.addView(img, new LinearLayout.LayoutParams(size, size));
        TextView status = new TextView(a);
        status.setTextSize(15);
        status.setTextColor(0xFF1A1A1A);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(a, 12), 0, dp(a, 4));
        status.setText("正在获取二维码…");
        box.addView(status);
        TextView help = new TextView(a);
        help.setTextSize(13);
        help.setTextColor(0xFF6E6E6A);
        help.setText("用你随身的微信扫码并确认。绑定后打开这个 Bot 的私聊，直接发一句要办的事。二维码只用于这次绑定，不要转发。");
        box.addView(help);

        final WechatLogin[] holder = new WechatLogin[1];
        final boolean[] done = {false};
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("用微信扫码绑定")
                .setView(box)
                .setNegativeButton("取消", (d, w) -> { if (holder[0] != null) holder[0].cancel(); })
                .setCancelable(false)
                .create();
        holder[0] = new WechatLogin(a, new WechatLogin.Callback() {
            @Override public void onQr(String content) {
                Bitmap bm = render(content, size);
                a.runOnUiThread(() -> {
                    if (bm != null) img.setImageBitmap(bm);
                    else status.setText("二维码画不出来，请重试。");
                });
            }

            @Override public void onStatus(String text) {
                a.runOnUiThread(() -> status.setText(text));
            }

            @Override public void onDone(boolean ok, String message) {
                a.runOnUiThread(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    if (dlg.isShowing()) dlg.dismiss();
                    Toast.makeText(a, message, Toast.LENGTH_LONG).show();
                    if (onFinished != null) onFinished.run();
                });
            }
        });
        dlg.setOnDismissListener(d -> {
            if (!done[0]) { done[0] = true; holder[0].cancel(); if (onFinished != null) onFinished.run(); }
        });
        dlg.show();
        holder[0].start();
    }

    private static Bitmap render(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bm.setPixels(px, 0, w, 0, 0, w, h);
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }
}
