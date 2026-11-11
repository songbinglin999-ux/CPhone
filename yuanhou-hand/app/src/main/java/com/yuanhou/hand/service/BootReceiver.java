package com.yuanhou.hand.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.yuanhou.hand.lan.LanServer;
import com.yuanhou.hand.util.AppLog;

/**
 * 开机后「试着」启动前台服务（连带局域网通道）。只在本人以前手动启动过服务（已有局域网口令）时才试。
 * HarmonyOS 的自启动管理可能直接不给这条广播或不让起服务；这里不做任何绕过。
 * 起不来时的办法：打开远手，点一次「启动前台服务」。
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        AppLog.init(c);
        String t = c.getSharedPreferences(OrchestratorService.PREFS, Context.MODE_PRIVATE).getString(LanServer.PREF_TOKEN, "");
        if (t == null || t.isEmpty()) {
            AppLog.i("开机：还没手动启动过前台服务，不自动启动");
            return;
        }
        if (OrchestratorService.get() != null) return;
        try {
            OrchestratorService.start(c);
            AppLog.i("开机：已尝试启动前台服务（HarmonyOS 可能不允许；不行就打开远手点「启动前台服务」）");
        } catch (Exception e) {
            AppLog.i("开机：启动前台服务失败 " + e.getMessage() + "；请打开远手点「启动前台服务」");
        }
    }
}
