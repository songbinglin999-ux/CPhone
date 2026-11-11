package com.yuanhou.hand.session;

import android.content.Context;
import android.os.SystemClock;

import com.yuanhou.hand.util.AppLog;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 通话下行音频的离线听写。Liblinphone 在「用文件代替声卡」模式下把对方（炳霖）的声音写进一个 WAV，
 * 这里边写边读，送进 Vosk 中文模型，听完一句就回调一次。不用房间麦克风，不联网，不需要 Key。
 *
 * 模型不进 APK：需要把 vosk-model-small-cn-0.22 解压到
 * /sdcard/Android/data/com.yuanhou.hand/files/vosk-model-cn/（目录里直接是 am/ conf/ graph/ ivector/ 等）。
 */
public final class CallTranscriber {
    public interface Sink {
        void onText(String text);
    }

    public static final String MODEL_DIR = "vosk-model-cn";
    /** MSFileRec 写 WAV 时先跳过 44 字节头，挂断时才回填。 */
    private static final int WAV_HEADER = 44;

    private static volatile Model model;
    private static volatile String modelStatus = "听写模型：未加载";
    private static volatile boolean loading;

    public static File modelDir(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getFilesDir();
        return new File(d, MODEL_DIR);
    }

    public static boolean modelReady() { return model != null; }

    public static String modelStatus() { return modelStatus; }

    /** 后台加载模型（几秒）。没放模型时只更新状态，不报故障。 */
    public static void preload(Context c) {
        if (model != null || loading) return;
        File dir = modelDir(c);
        if (!new File(dir, "am").isDirectory() && !new File(dir, "conf").isDirectory()) {
            modelStatus = "听写模型：未安装（需放到 " + dir.getAbsolutePath() + "）";
            AppLog.i(modelStatus);
            return;
        }
        loading = true;
        modelStatus = "听写模型：加载中";
        new Thread(() -> {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                long t0 = SystemClock.elapsedRealtime();
                model = new Model(dir.getAbsolutePath());
                modelStatus = "听写模型：已加载（Vosk 中文，离线，" + (SystemClock.elapsedRealtime() - t0) + " ms）";
            } catch (Throwable e) {
                modelStatus = "听写模型：加载失败（" + e.getClass().getSimpleName() + " " + e.getMessage() + "）";
            } finally {
                loading = false;
                AppLog.i(modelStatus);
            }
        }, "vosk-load").start();
    }

    private final File recFile;
    private final int guessRate;
    private final Sink sink;
    private volatile boolean running;
    private volatile long muteUntil;
    private volatile boolean muted;
    private Thread thread;

    public CallTranscriber(File recFile, int guessRate, Sink sink) {
        this.recFile = recFile;
        this.guessRate = guessRate;
        this.sink = sink;
    }

    /** 远手自己在说话时不听（避免把对方回声或自己的话听成指令）；说完再多等 [tailMs]。 */
    public void setMuted(boolean m, int tailMs) {
        if (m) {
            muted = true;
        } else {
            muteUntil = SystemClock.elapsedRealtime() + tailMs;
            muted = false;
        }
    }

    public void start() {
        if (model == null) return;
        running = true;
        thread = new Thread(this::loop, "call-stt");
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException ignored) {}
        }
    }

    private void loop() {
        Recognizer rec = null;
        try (RandomAccessFile f = waitOpen()) {
            if (f == null) {
                AppLog.i("听写：通话录音文件一直没出现，听不到对方");
                return;
            }
            // 先量 2 秒数据增长，确认采样率和声道（不同编解码器写出来的不一样）
            long t0 = 0, b0 = 0;
            int rate = guessRate, ch = 1;
            while (running) {
                long len = f.length();
                if (len > WAV_HEADER) {
                    long now = SystemClock.elapsedRealtime();
                    if (t0 == 0) { t0 = now; b0 = len; }
                    else if (now - t0 >= 2000) {
                        double bps = (len - b0) * 1000.0 / (now - t0);
                        int[] rc = detect(bps, guessRate);
                        rate = rc[0];
                        ch = rc[1];
                        AppLog.i("听写：下行约 " + Math.round(bps) + " B/s，按 " + rate + " Hz " + ch + " 声道处理");
                        break;
                    }
                }
                Thread.sleep(100);
            }
            if (!running) return;
            rec = new Recognizer(model, rate);
            long pos = WAV_HEADER;
            int frame = 2 * ch;
            byte[] buf = new byte[frame * (rate / 10)]; // 约 100 ms
            byte[] mono = new byte[buf.length / ch];
            boolean wasMuted = false;
            while (running) {
                long avail = f.length() - pos;
                if (avail < frame * (rate / 50)) { Thread.sleep(40); continue; }
                int n = (int) Math.min(buf.length, avail - avail % frame);
                f.seek(pos);
                f.readFully(buf, 0, n);
                pos += n;
                boolean mutedNow = muted || SystemClock.elapsedRealtime() < muteUntil;
                if (mutedNow) { wasMuted = true; continue; }
                if (wasMuted) { rec.reset(); wasMuted = false; }
                int m = toMono(buf, n, ch, mono);
                if (rec.acceptWaveForm(mono, m)) {
                    String t = clean(rec.getResult());
                    if (!t.isEmpty()) {
                        AppLog.i("听写：" + t);
                        sink.onText(t);
                    }
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable e) {
            AppLog.i("听写出错：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (rec != null) rec.close();
        }
    }

    private RandomAccessFile waitOpen() throws InterruptedException {
        long until = SystemClock.elapsedRealtime() + 10000;
        while (running && SystemClock.elapsedRealtime() < until) {
            if (recFile.exists()) {
                try {
                    return new RandomAccessFile(recFile, "r");
                } catch (Exception ignored) {}
            }
            Thread.sleep(100);
        }
        return null;
    }

    /** 按每秒字节数确定 采样率/声道。先信编解码器给的采样率，再看是单声道还是双声道；都对不上就按实测反推。 */
    static int[] detect(double bps, int guess) {
        if (guess > 0) {
            double r1 = bps / (2.0 * guess);
            if (r1 > 0.75 && r1 < 1.3) return new int[]{guess, 1};
            if (r1 > 1.6 && r1 < 2.5) return new int[]{guess, 2};
        }
        int best = 16000;
        double bestErr = Double.MAX_VALUE;
        for (int r : new int[]{8000, 16000, 24000, 32000, 44100, 48000}) {
            double e = Math.abs(bps - 2.0 * r);
            if (e < bestErr) { bestErr = e; best = r; }
        }
        return new int[]{best, 1};
    }

    private static int toMono(byte[] in, int n, int ch, byte[] out) {
        if (ch == 1) {
            System.arraycopy(in, 0, out, 0, n);
            return n;
        }
        int frames = n / (2 * ch);
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < ch; c++) {
                int o = (i * ch + c) * 2;
                sum += (short) ((in[o] & 0xFF) | (in[o + 1] << 8));
            }
            short s = (short) (sum / ch);
            out[2 * i] = (byte) s;
            out[2 * i + 1] = (byte) (s >> 8);
        }
        return frames * 2;
    }

    /** Vosk 中文结果字之间带空格，去掉。 */
    static String clean(String json) {
        try {
            String t = new JSONObject(json).optString("text", "");
            return t.replaceAll("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])", "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
