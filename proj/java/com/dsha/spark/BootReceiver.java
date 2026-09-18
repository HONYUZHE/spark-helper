package com.dsha.spark;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后重新排闹钟。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Scheduler.rescheduleAll(context);
        Store.addLog(context, "", true, "开机/更新后已重新排定定时任务"
                + "（下一个：" + Scheduler.describeNext(context) + "）");
    }
}
