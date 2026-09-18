package com.dsha.spark;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台服务（保活）。
 *
 * 目的只有一个：让 App 进程别被系统回收。
 * 进程一旦被回收，无障碍服务就断开，定时任务会落进"服务不在"的空窗期 ——
 * 真机上「无障碍明明开着却提示要打开无障碍」就是这么来的。
 *
 * 通知是 IMPORTANCE_MIN，不响不弹，只是占一行。
 */
public class KeepAliveService extends Service {

    private static final int ID = 1004;

    public static void start(Context c) {
        Intent i = new Intent(c, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Store.addLog(c, "", false, "保活服务启动失败：" + t);
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForeground(ID, Notify.buildQuiet(this, "火花助手",
                    "正在守护定时任务（点这里可以调整）"));
        } catch (Throwable t) {
            Store.addLog(this, "", false, "保活通知显示失败：" + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Store.addLogSync(this, "", true, "保活服务被停止（进程可能很快被系统回收）");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
