package com.yuanhou.hand.session;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 0.1.13：首页「正在办」的步骤日志。挂在 LocalTextChannel 的对话上（服务启动时接上），
 * 记最近一件事里远手说的话和本人中途补的话（带 HH:mm）。只在内存里，不落盘、不写日志。
 * 纯 Java：没有 Android 依赖。
 */
public final class TaskLog implements LocalTextChannel.Transcript {
    public static final class Entry {
        public final String time, who, text;
        Entry(String time, String who, String text) { this.time = time; this.who = who; this.text = text; }
    }

    private static final TaskLog INSTANCE = new TaskLog();
    public static TaskLog get() { return INSTANCE; }

    static final int MAX = 60;
    private final List<Entry> entries = new ArrayList<>();
    private volatile int version;
    /** 服务上的 TaskSession 是否在办（用于判断「你」的一句是不是新任务）。 */
    public interface Running { boolean running(); }
    private volatile Running running;

    public void attach(LocalTextChannel local, Running r) {
        running = r;
        local.removeTranscript(this);
        local.addTranscript(this);
    }

    @Override
    public void onLine(String who, String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        Running r = running;
        boolean busy = r != null && r.running();
        synchronized (this) {
            // 「你」在空闲时说的一句 = 新的一件事：清掉上一件的日志
            if ("你".equals(who) && !busy) entries.clear();
            entries.add(new Entry(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()), who, t));
            while (entries.size() > MAX) entries.remove(0);
            version++;
        }
    }

    public int version() { return version; }

    public synchronized List<Entry> snapshot() { return new ArrayList<>(entries); }
}
