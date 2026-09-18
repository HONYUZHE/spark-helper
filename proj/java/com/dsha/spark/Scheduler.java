package com.dsha.spark;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

/** 定时：每个任务一个每日闹钟，外加一个 30 分钟的心跳做兜底补跑。 */
public final class Scheduler {

    public static final String ACTION_TASK = "com.dsha.spark.RUN_TASK";
    public static final String ACTION_CATCHUP = "com.dsha.spark.CATCHUP";
    public static final String EXTRA_ID = "taskId";

    private static final int REQ_CATCHUP = 900001;
    /** 闹钟晚点/被系统压制时，最多在这个窗口内补跑。 */
    public static final long CATCHUP_WINDOW_MS = 4L * 60 * 60 * 1000;

    private Scheduler() {
    }

    private static AlarmManager am(Context c) {
        return (AlarmManager) c.getApplicationContext().getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent taskPi(Context c, String id) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_TASK).putExtra(EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, id.hashCode(), i, flags);
    }

    private static PendingIntent catchupPi(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_CATCHUP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ_CATCHUP, i, flags);
    }

    public static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            return am(c).canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 今天/明天的触发时刻（若今天该时刻已过则排到明天）。 */
    public static long nextTrigger(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 3000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    /** 今天该任务的计划时刻（可能已经过去）。 */
    public static long todayTrigger(Task t) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, t.hour);
        cal.set(Calendar.MINUTE, t.minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public static void schedule(Context c, Task t) {
        AlarmManager m = am(c);
        if (m == null) return;
        long at = nextTrigger(t.hour, t.minute);
        PendingIntent pi = taskPi(c, t.id);
        boolean exact = canExact(c);
        try {
            if (exact) m.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    public static void cancel(Context c, String id) {
        AlarmManager m = am(c);
        if (m == null) return;
        m.cancel(taskPi(c, id));
    }

    /** 心跳：每 30 分钟醒一次，检查有没有漏掉的任务。 */
    public static void heartbeat(Context c) {
        AlarmManager m = am(c);
        if (m == null) return;
        try {
            m.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 10L * 60 * 1000,
                    30L * 60 * 1000, catchupPi(c));
        } catch (Throwable ignored) {
        }
    }

    public static void rescheduleAll(Context c) {
        List<Task> list = Store.tasks(c);
        for (Task t : list) {
            cancel(c, t.id);
            if (t.enabled) schedule(c, t);
        }
        heartbeat(c);
    }

    public static String describeNext(Context c) {
        List<Task> list = Store.tasks(c);
        long best = Long.MAX_VALUE;
        for (Task t : list) {
            if (!t.enabled) continue;
            long at = nextTrigger(t.hour, t.minute);
            if (at < best) best = at;
        }
        if (best == Long.MAX_VALUE) return "没有启用的任务";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(best));
    }
}
