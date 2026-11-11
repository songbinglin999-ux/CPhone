package com.yuanhou.hand.session;

import com.yuanhou.hand.util.AppLog;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 应用内文字指令入口，用于桌上自测（不上电话）。
 * 0.1.2 起不再调用系统 TTS：复述只写在界面和日志里，手机不出声。对炳霖的语音播报只走 SIP 通话。
 */
public final class LocalTextChannel implements CallChannel {
    public interface Transcript {
        void onLine(String who, String text);
    }

    private volatile Listener listener;
    private final CopyOnWriteArrayList<Transcript> transcripts = new CopyOnWriteArrayList<>();

    public void addTranscript(Transcript t) { transcripts.add(t); }
    public void removeTranscript(Transcript t) { transcripts.remove(t); }

    @Override public String name() { return "应用内文字（桌上自测，不出声）"; }
    /** 桌上有人在打字自测，算在场；确认口令也从界面按钮/输入框来。 */
    @Override public boolean active() { return true; }
    @Override public void setListener(Listener l) { listener = l; }

    /** 界面上「发出」一句话。 */
    public void deliver(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String t = text.trim();
        AppLog.i("你（文字）：" + t);
        for (Transcript tr : transcripts) tr.onLine("你", t);
        Listener l = listener;
        if (l != null) l.onUtterance(t);
    }

    /** 通话里听写出来的话也显示到界面对话框（只显示，不再转交）。 */
    public void show(String who, String text) {
        for (Transcript tr : transcripts) tr.onLine(who, text);
    }

    @Override
    public void speak(String text) {
        AppLog.i("远手（文字，不出声）：" + text);
        for (Transcript tr : transcripts) tr.onLine("远手", text);
    }
}
