package com.yuanhou.hand.port;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.yuanhou.hand.hand.HandExecutor;
import com.yuanhou.hand.hand.SuiteState;
import com.yuanhou.hand.screen.ReadonlyAccessibilityService;
import com.yuanhou.hand.screen.ScreenSnapshot;
import com.yuanhou.hand.util.AppLog;

import java.util.ArrayList;
import java.util.List;

/** 生产用的端口组装：AppAccessScreenPort( MainScreenEsp32Port(只读无障碍, ESP32 手, 本应用启动器) )。 */
public final class MainScreenPorts {
    private MainScreenPorts() {}

    public static ScreenPort create(Context ctx, HandExecutor hand, SuiteState suite) {
        Context app = ctx.getApplicationContext();
        TreeSource tree = new TreeSource() {
            @Override public ScreenSnapshot read() { return ReadonlyAccessibilityService.snapshot(); }
            @Override public boolean running() { return ReadonlyAccessibilityService.isRunning(); }
        };
        AppLauncher launcher = new AppLauncher() {
            @Override
            public String launch(String pkg) {
                Intent i = app.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return pkg + " 没有安装";
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    app.startActivity(i);   // 主屏，默认 display；不指定 displayId，不经 shell
                    AppLog.i("显式 Intent 打开 " + pkg);
                    return null;
                } catch (Exception e) {
                    return "系统没让打开：" + e.getClass().getSimpleName();
                }
            }

            @Override
            public List<String[]> listApps() {
                List<String[]> out = new ArrayList<>();
                PackageManager pm = app.getPackageManager();
                for (String p : AppAccessPolicy.defaults().allowedOpen) {
                    try {
                        CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(p, 0));
                        out.add(new String[]{p, l == null ? p : l.toString()});
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // 没装
                    }
                }
                return out;
            }
        };
        ScreenPort base = new MainScreenEsp32Port(tree, new Esp32HandAdapter(hand, suite), launcher, Sleeper.REAL);
        return new AppAccessScreenPort(base, AppAccessPolicy.defaults());
    }
}
