package com.dsha.spark;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 通知：运行中的前台服务通知 + 结果通知。 */
public final class Notify {

    public static final String CH = "spark";
    public static final String CH_QUIET = "spark_alive";
    public static final int ID_RUN = 1001;
    public static final int ID_RESULT = 1002;
    public static final int ID_WARN = 1003;

    private Notify() {
    }

    public static void ensure(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "火花助手", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("定时任务的运行状态");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        // 保活通知单独一个渠道：最低优先级，不响不弹、不显示角标
        if (nm.getNotificationChannel(CH_QUIET) == null) {
            NotificationChannel q = new NotificationChannel(CH_QUIET, "后台保活",
                    NotificationManager.IMPORTANCE_MIN);
            q.setDescription("常驻通知，防止 App 被系统回收导致定时失效");
            q.setShowBadge(false);
            q.setSound(null, null);
            nm.createNotificationChannel(q);
        }
    }

    private static PendingIntent tap(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 0, i, flags);
    }

    public static Notification build(Context c, String title, String text, boolean ongoing) {
        return build(c, CH, title, text, ongoing);
    }

    /** 保活用的静默通知。 */
    public static Notification buildQuiet(Context c, String title, String text) {
        return build(c, CH_QUIET, title, text, true);
    }

    public static Notification build(Context c, String channel, String title, String text, boolean ongoing) {
        ensure(c);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, channel);
        else b = new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setContentIntent(tap(c));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    public static void show(Context c, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(id, build(c, title, text, false));
        } catch (Throwable ignored) {
        }
    }

    public static void cancel(Context c, int id) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(id);
    }
}
