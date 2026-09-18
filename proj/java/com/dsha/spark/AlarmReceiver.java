package com.dsha.spark;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** 闹钟到点：拉起运行服务；顺带把下一天排上。 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;

        if (Scheduler.ACTION_TASK.equals(action)) {
            String id = intent.getStringExtra(Scheduler.EXTRA_ID);
            Task t = id == null ? null : Store.byId(context, id);
            if (t == null) return;
            if (!t.enabled) {
                Store.addLog(context, t.title(), true, "任务已停用，跳过这次定时");
                Scheduler.cancel(context, t.id);
                return;
            }
            if (Store.today().equals(t.lastDay)) {
                Store.addLog(context, t.title(), true, "今天已经发过了，跳过重复触发");
                Scheduler.schedule(context, t);
                return;
            }
            Scheduler.schedule(context, t);              // 先把明天排上，避免中途失败丢计划
            RunService.start(context, t.id, "定时触发", false);
            return;
        }

        if (Scheduler.ACTION_CATCHUP.equals(action)) {
            catchUp(context);
        }
    }

    /** 心跳兜底：定时被系统压制/手机当时关机，都能在一个窗口内补上。 */
    static void catchUp(Context context) {
        List<Task> list = Store.tasks(context);
        long now = System.currentTimeMillis();
        for (Task t : list) {
            if (!t.enabled) continue;
            if (Store.today().equals(t.lastDay)) continue;
            long at = Scheduler.todayTrigger(t);
            if (now < at) continue;                                  // 还没到点
            if (now - at > Scheduler.CATCHUP_WINDOW_MS) continue;     // 过去太久了，不打扰
            RunService.start(context, t.id, "补跑", false);
            return;                                                   // 一次补一个，剩下的下一轮心跳再说
        }
        // 心跳顺便自愈一下闹钟
        Scheduler.rescheduleAll(context);
    }
}
