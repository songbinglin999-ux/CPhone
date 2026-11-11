package com.yuanhou.hand.session;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.yuanhou.hand.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通话用的语音合成：只用 TextToSpeech.synthesizeToFile 把一句话合成到应用私有目录的 WAV，
 * 再转成通话采样率的单声道 16 位 WAV，交给 Liblinphone 在 SIP 通话里播放。
 * 这里从不调用 TextToSpeech.speak()，手机扬声器/听筒不出声；没有通话时根本不会被调用。
 */
public final class CallVoice {
    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile String status = "通话语音合成：初始化中";
    private final AtomicInteger ids = new AtomicInteger();
    private final ConcurrentHashMap<String, CountDownLatch> waits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> ok = new ConcurrentHashMap<>();
    private final File dir;

    public CallVoice(Context c) {
        dir = new File(c.getCacheDir(), "call-voice");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        tts = new TextToSpeech(c.getApplicationContext(), st -> {
            if (st != TextToSpeech.SUCCESS) {
                status = "通话语音合成：不可用（引擎初始化失败）";
                AppLog.i(status);
                return;
            }
            int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                status = "通话语音合成：引擎 " + tts.getDefaultEngine() + " 不支持简体中文";
            } else {
                status = "通话语音合成：就绪（引擎 " + tts.getDefaultEngine() + "，只合成到文件，不外放）";
                ready = true;
            }
            AppLog.i(status);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { finish(id, true); }
                @Override public void onError(String id) { finish(id, false); }
            });
        });
    }

    private void finish(String id, boolean success) {
        ok.put(id, success);
        CountDownLatch l = waits.remove(id);
        if (l != null) l.countDown();
    }

    public boolean ready() { return ready; }

    public String status() { return status; }

    /**
     * 合成一句话到文件（阻塞，工作线程调用）。返回已转成 [rate] Hz 单声道 16 位的 WAV；失败返回 null。
     */
    public File synthesize(String text, int rate) {
        if (!ready || text == null || text.trim().isEmpty()) return null;
        String id = "c" + ids.incrementAndGet();
        File raw = new File(dir, id + "-raw.wav");
        File out = new File(dir, id + ".wav");
        CountDownLatch l = new CountDownLatch(1);
        waits.put(id, l);
        int r = tts.synthesizeToFile(text, new Bundle(), raw, id);
        if (r != TextToSpeech.SUCCESS) {
            waits.remove(id);
            AppLog.i("合成语音失败（synthesizeToFile 返回 " + r + "）");
            return null;
        }
        try {
            if (!l.await(Math.min(30000, 4000 + text.length() * 400L), TimeUnit.MILLISECONDS)) {
                AppLog.i("合成语音超时");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!Boolean.TRUE.equals(ok.remove(id))) {
            AppLog.i("合成语音失败（引擎报错）");
            raw.delete();
            return null;
        }
        try {
            Wav w = Wav.read(raw);
            short[] mono = w.mono();
            short[] res = Wav.resample(mono, w.rate, rate);
            Wav.writeMono16(out, res, rate);
            return out;
        } catch (IOException e) {
            AppLog.i("合成语音转换失败：" + e.getMessage());
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    public void cleanup() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    public void shutdown() {
        if (tts != null) tts.shutdown();
        tts = null;
        ready = false;
        cleanup();
    }

    /** 最小 WAV 读写（PCM 16 位）。 */
    static final class Wav {
        int rate, channels, bits;
        byte[] data;

        static Wav read(File f) throws IOException {
            byte[] all;
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                all = bo.toByteArray();
            }
            if (all.length < 12 || all[0] != 'R' || all[1] != 'I' || all[2] != 'F' || all[3] != 'F')
                throw new IOException("不是 WAV");
            Wav w = new Wav();
            int p = 12;
            int dataOff = -1, dataLen = 0;
            while (p + 8 <= all.length) {
                String id = new String(all, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
                int len = le32(all, p + 4);
                if (id.equals("fmt ")) {
                    int fmt = le16(all, p + 8);
                    w.channels = le16(all, p + 10);
                    w.rate = le32(all, p + 12);
                    w.bits = le16(all, p + 22);
                    if (fmt != 1 && fmt != 0xFFFE) throw new IOException("WAV 不是 PCM");
                } else if (id.equals("data")) {
                    dataOff = p + 8;
                    // 有的引擎写入时长度未知（0 或 -1），按文件实际长度算
                    dataLen = (len <= 0 || dataOff + len > all.length) ? all.length - dataOff : len;
                    break;
                }
                if (len < 0) break;
                p += 8 + len + (len & 1);
            }
            if (dataOff < 0 || w.rate <= 0 || w.channels <= 0) throw new IOException("WAV 头不完整");
            if (w.bits != 16) throw new IOException("只支持 16 位 PCM，实际 " + w.bits);
            w.data = new byte[dataLen];
            System.arraycopy(all, dataOff, w.data, 0, dataLen);
            return w;
        }

        short[] mono() {
            int frames = data.length / (2 * channels);
            short[] m = new short[frames];
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int o = (i * channels + c) * 2;
                    sum += (short) ((data[o] & 0xFF) | (data[o + 1] << 8));
                }
                m[i] = (short) (sum / channels);
            }
            return m;
        }

        /** 线性插值重采样（语音播报够用）。 */
        static short[] resample(short[] in, int from, int to) {
            if (from == to || in.length == 0) return in;
            int n = (int) ((long) in.length * to / from);
            short[] out = new short[n];
            double step = (double) from / to;
            for (int i = 0; i < n; i++) {
                double pos = i * step;
                int a = (int) pos;
                int b = Math.min(a + 1, in.length - 1);
                double f = pos - a;
                out[i] = (short) Math.round(in[a] * (1 - f) + in[b] * f);
            }
            return out;
        }

        static void writeMono16(File f, short[] s, int rate) throws IOException {
            int dataLen = s.length * 2;
            byte[] h = header(rate, 1, dataLen);
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(h);
                byte[] b = new byte[dataLen];
                for (int i = 0; i < s.length; i++) {
                    b[2 * i] = (byte) s[i];
                    b[2 * i + 1] = (byte) (s[i] >> 8);
                }
                o.write(b);
            }
        }

        static byte[] header(int rate, int ch, int dataLen) {
            byte[] h = new byte[44];
            put(h, 0, "RIFF");
            le32(h, 4, 36 + dataLen);
            put(h, 8, "WAVE");
            put(h, 12, "fmt ");
            le32(h, 16, 16);
            le16(h, 20, 1);
            le16(h, 22, ch);
            le32(h, 24, rate);
            le32(h, 28, rate * ch * 2);
            le16(h, 32, ch * 2);
            le16(h, 34, 16);
            put(h, 36, "data");
            le32(h, 40, dataLen);
            return h;
        }

        private static void put(byte[] b, int o, String s) {
            for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i);
        }

        static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
        static int le32(byte[] b, int o) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
        }
        static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
        static void le32(byte[] b, int o, int v) {
            b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
        }
    }
}
