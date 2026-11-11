package com.yuanhou.hand.hand;

import android.os.SystemClock;

/**
 * 两件套状态。「手」有两条通道，任一条完整就绪即可动手（本阶段优先 Wi-Fi 透传，GATT 备用）：
 * - Wi-Fi 透传：TCP 状态查询新鲜，且探头报告 BLE 已连接 + 已加密 + HID 键盘鼠标已订阅；
 * - 蓝牙 GATT：已连接 + 自定义指令通道就绪 + HID 键盘鼠标已订阅 + 心跳新鲜。
 * 另外还要已 SET_SCREEN。任何一项缺失都如实给出原因，不假装就绪。
 */
public final class SuiteState {
    public static final long HEARTBEAT_STALE_MS = 3500;
    public static final long WIFI_STALE_MS = 5000;

    // ---- GATT（备用）----
    public volatile boolean bluetoothOn = true;
    public volatile boolean bondedFound = true;
    public volatile boolean connecting;
    public volatile boolean gattConnected;
    public volatile boolean channelReady;
    public volatile String connectDetail = "尚未开始连接";
    public volatile String info = "";
    public volatile HandProtocol.Status status;
    public volatile long lastStatusAt;

    // ---- Wi-Fi 透传（本阶段主用）----
    public volatile boolean wifiEnabled = true;
    public volatile String wifiHost = "";
    public volatile HandProtocol.Status wifiStatus;
    public volatile long wifiAt;
    public volatile boolean wifiBusy;
    public volatile String wifiDetail = "尚未连接";

    public volatile boolean screenSet;
    public volatile int screenW, screenH, cpp;
    public volatile String lastFault;

    public synchronized void resetLink(String detail) {
        gattConnected = false;
        channelReady = false;
        status = null;
        lastStatusAt = 0;
        connectDetail = detail;
        if (!wifiReady()) screenSet = false;
    }

    public void onWifiStatus(HandProtocol.Status s) {
        wifiStatus = s;
        wifiAt = SystemClock.elapsedRealtime();
        wifiDetail = "已连通";
    }

    public void onWifiAlive() {
        if (wifiStatus != null) wifiAt = SystemClock.elapsedRealtime();
    }

    public void onWifiFail(String why) {
        wifiDetail = "连不上（" + why + "）";
    }

    public boolean heartbeatFresh() {
        return lastStatusAt > 0 && SystemClock.elapsedRealtime() - lastStatusAt <= HEARTBEAT_STALE_MS;
    }

    public boolean wifiFresh() {
        return wifiStatus != null && (wifiBusy || SystemClock.elapsedRealtime() - wifiAt <= WIFI_STALE_MS);
    }

    private static boolean kb(HandProtocol.Status s) { return s != null && s.connected() && s.encrypted() && s.keyboardSubscribed(); }
    private static boolean ms(HandProtocol.Status s) { return s != null && s.connected() && s.encrypted() && s.mouseSubscribed(); }

    /** Wi-Fi 透传可用：状态新鲜，且探头那头 BLE HID 键盘鼠标都已被手机订阅。 */
    public boolean wifiReady() {
        HandProtocol.Status s = wifiStatus;
        return wifiEnabled && wifiFresh() && kb(s) && ms(s);
    }

    /** GATT 备用通道可用。 */
    public boolean gattReady() {
        return gattConnected && channelReady && kb(status) && ms(status) && heartbeatFresh();
    }

    private HandProtocol.Status activeStatus() {
        return wifiReady() || (wifiFresh() && !gattConnected) ? wifiStatus : status;
    }

    public boolean keyboardReady() { return kb(activeStatus()); }
    public boolean mouseReady() { return ms(activeStatus()); }

    public boolean hidReady() {
        return wifiReady() || gattReady();
    }

    /** 至少一条通道能把动作送到探头，且 HID 已就绪（不要求已 SET_SCREEN）。 */
    public boolean linkReady() {
        return wifiReady() || gattReady();
    }

    /** 可以开始主屏操作。 */
    public boolean ready() {
        return linkReady() && screenSet;
    }

    /** 当前会用的通道名。 */
    public String transport() {
        if (wifiReady()) return "Wi-Fi 透传";
        if (gattReady()) return "蓝牙 GATT";
        return "无";
    }

    private String wifiReason() {
        if (!wifiEnabled) return "Wi-Fi 透传已关闭";
        if (wifiStatus == null) return "Wi-Fi 透传 " + wifiHost + " " + wifiDetail;
        if (!wifiFresh()) return "Wi-Fi 透传超过 5 秒没回状态（" + wifiDetail + "）";
        HandProtocol.Status s = wifiStatus;
        if (!s.connected()) return "探头报告：蓝牙 HID 未连到手机";
        if (!s.encrypted()) return "探头报告：蓝牙未加密";
        if (!kb(s) && !ms(s)) return "探头报告：手机未订阅 HID 键盘和鼠标";
        if (!kb(s)) return "探头报告：手机未订阅 HID 键盘";
        if (!ms(s)) return "探头报告：手机未订阅 HID 鼠标";
        return null;
    }

    private String gattReason() {
        if (!bluetoothOn) return "系统蓝牙未打开";
        if (!bondedFound) return "已配对列表里没有「S3 Phone Hand」";
        if (!gattConnected) return connecting ? "正在连接 S3 Phone Hand…" : ("蓝牙未连接 S3 Phone Hand（" + connectDetail + "）");
        if (!channelReady) return "自定义指令通道未就绪（" + connectDetail + "）";
        if (status == null) return "还没收到探头状态";
        if (!heartbeatFresh()) return "超过 3 秒没收到探头心跳";
        if (!kb(status) && !ms(status)) return "手机系统未订阅 HID 键盘和鼠标";
        if (!kb(status)) return "手机系统未订阅 HID 键盘";
        if (!ms(status)) return "手机系统未订阅 HID 鼠标";
        return null;
    }

    /** 未就绪原因；就绪时返回 null。 */
    public String notReadyReason() {
        if (!linkReady()) {
            String w = wifiReason(), g = gattReason();
            return (w == null ? "" : w) + "；GATT 备用：" + (g == null ? "就绪" : g);
        }
        if (!screenSet) return lastFault != null ? ("SET_SCREEN 未完成：" + lastFault) : "正在设置屏幕尺寸";
        return null;
    }

    public String title() {
        return ready() ? "远手 · 主屏控制就绪（" + transport() + "）" : "远手 · 未就绪";
    }

    public String lines() {
        StringBuilder b = new StringBuilder();
        String w = wifiReason();
        b.append("手（Wi-Fi 透传 ").append(wifiHost).append("）：").append(w == null ? "就绪" : "未就绪 — " + w).append('\n');
        String g = gattReason();
        b.append("手（蓝牙 GATT，备用）：").append(g == null ? "就绪" : "未就绪 — " + g).append('\n');
        b.append("HID 键盘：").append(keyboardReady() ? "就绪" : "未就绪")
                .append("　鼠标：").append(mouseReady() ? "就绪" : "未就绪")
                .append("　动作走：").append(transport());
        if (screenSet) b.append("　屏幕 ").append(screenW).append('x').append(screenH);
        String r = notReadyReason();
        if (r != null && linkReady()) b.append('\n').append("未就绪原因：").append(r);
        return b.toString();
    }
}
