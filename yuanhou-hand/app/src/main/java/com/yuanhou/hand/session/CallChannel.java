package com.yuanhou.hand.session;

/**
 * 通话会话抽象：听写意图 → 短复述 → 确认 → 回传结果。
 * 第一期声音走备用机自身的 SIP 通话通路（远手内嵌 Liblinphone 自己做 SIP 用户代理），不经过 ESP32。
 * - SipCallChannel：对用户的主通道。听 = 通话下行音频写文件 → 离线听写；说 = 合成到文件 → 在同一通通话的上行里播放。
 * - LocalTextChannel：桌上文字自测入口，只在界面/日志显示，不出声。
 * 不用系统 TTS 外放、不用房间麦克风当对用户的通路。
 */
public interface CallChannel {
    interface Listener {
        /** 听写/输入得到的一句话。 */
        void onUtterance(String text);
        void onCallEnded();
    }

    String name();

    /** 此刻有人在这条通道上（能听到复述、能回答确认）。 */
    boolean active();

    void setListener(Listener l);

    /** 把一句话说给对方（阻塞到说完或超时）。工作线程调用。 */
    void speak(String text);
}
