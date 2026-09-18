package com.dsha.spark;

import android.app.Application;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 记录进程生死 + 兜住崩溃。
 *
 * 加这个是因为真机上出现过「无障碍明明开着，却提示要打开无障碍」——
 * 那个症状意味着 App 进程被系统回收过。这里：
 *   · 每次进程启动都写一条日志（于是日志里能看出进程重启的时间点）
 *   · 未捕获异常先落盘再让系统按原流程处理（于是崩溃不再是"无声消失"）
 */
public class SparkApp extends Application {

    public static final String VERSION = "1.0.4";

    @Override
    public void onCreate() {
        super.onCreate();

        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String s = sw.toString();
                    if (s.length() > 1800) s = s.substring(0, 1800);
                    Store.addLogSync(SparkApp.this, "", false,
                            "💥 程序崩溃（线程 " + t.getName() + "），这就是「无障碍突然断开」最可能的原因：\n" + s);
                } catch (Throwable ignored) {
                }
                if (def != null) def.uncaughtException(t, e);
            }
        });

        Store.addLogSync(this, "", true, "App 进程启动（v" + VERSION + "）");

        if (Store.keepAlive(this)) {
            KeepAliveService.start(this);
        }
    }
}
