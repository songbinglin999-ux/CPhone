package com.yuanhou.hand.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** 应用日志：内存环形缓冲 + logcat + 外部私有目录 log.txt。不得写入任何密钥。 */
public final class AppLog {
    private AppLog() {}

    public static final String TAG = "YuanHand";
    private static final int MAX = 400;
    private static final ArrayDeque<String> BUF = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Runnable> WATCHERS = new CopyOnWriteArrayList<>();
    private static File file;

    public static synchronized void init(Context c) {
        if (file != null) return;
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        file = new File(dir, "log.txt");
    }

    public static void i(String line) {
        Log.i(TAG, line);
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(new Date());
        String full = ts + "  " + line;
        synchronized (AppLog.class) {
            BUF.addLast(full);
            while (BUF.size() > MAX) BUF.removeFirst();
            if (file != null) {
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write((full + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
        }
        for (Runnable r : WATCHERS) r.run();
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(BUF);
    }

    public static void watch(Runnable r) { WATCHERS.add(r); }
    public static void unwatch(Runnable r) { WATCHERS.remove(r); }

    public static void writeFile(Context c, String name, String body) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            i("写 " + name + " 失败: " + e.getMessage());
        }
    }
}
