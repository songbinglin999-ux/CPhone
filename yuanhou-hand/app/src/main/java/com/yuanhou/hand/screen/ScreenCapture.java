package com.yuanhou.hand.screen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import com.yuanhou.hand.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 系统允许时的截图补充：Android 10 只能走 MediaProjection，需要本人在系统弹窗里同意，
 * 每次应用重启后要重新授权。截图只给本人配置并同意的模型服务商，不经 BLE 传输。
 */
public final class ScreenCapture {
    private static volatile MediaProjection projection;
    private static HandlerThread thread;

    public static synchronized void setProjection(MediaProjection p) {
        if (projection != null && projection != p) {
            try { projection.stop(); } catch (Exception ignored) {}
        }
        projection = p;
        if (p != null) {
            p.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (projection == p) projection = null;
                    AppLog.i("截图授权已结束");
                }
            }, null);
        }
    }

    public static boolean available() {
        return projection != null;
    }

    public static synchronized void release() {
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
        }
        projection = null;
    }

    /** 阻塞截一张主屏图；没有授权返回 null。只能在工作线程调用。 */
    public static Bitmap capture(Context ctx, int w, int h, int dpi) {
        MediaProjection p = projection;
        if (p == null) return null;
        synchronized (ScreenCapture.class) {
            if (thread == null) {
                thread = new HandlerThread("capture");
                thread.start();
            }
        }
        Handler h2 = new Handler(thread.getLooper());
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        CountDownLatch l = new CountDownLatch(1);
        final Bitmap[] out = {null};
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null || out[0] != null) return;
                Image.Plane pl = img.getPlanes()[0];
                ByteBuffer buf = pl.getBuffer();
                int rowPad = pl.getRowStride() - pl.getPixelStride() * w;
                Bitmap bmp = Bitmap.createBitmap(w + rowPad / pl.getPixelStride(), h, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(buf);
                out[0] = Bitmap.createBitmap(bmp, 0, 0, w, h);
                l.countDown();
            } catch (Exception e) {
                AppLog.i("截图解码失败: " + e.getMessage());
            } finally {
                if (img != null) img.close();
            }
        }, h2);
        VirtualDisplay vd = null;
        try {
            vd = p.createVirtualDisplay("yuanhou-cap", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h2);
            l.await(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            AppLog.i("截图失败: " + e.getMessage());
        } finally {
            if (vd != null) vd.release();
            reader.close();
        }
        return out[0];
    }

    public static String toJpegBase64(Bitmap b, int targetWidth) {
        if (b == null) return null;
        int tw = Math.min(targetWidth, b.getWidth());
        int th = b.getHeight() * tw / b.getWidth();
        Bitmap s = Bitmap.createScaledBitmap(b, tw, th, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, 70, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }
}
