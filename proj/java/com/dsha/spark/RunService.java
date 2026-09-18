package com.dsha.spark;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 运行期的前台服务：一是把进程优先级顶上去（别跑到一半被系统回收），
 * 二是让用户能在通知栏看到「正在做什么」。
 *
 * 真正干活的还是 {@link SparkService}（无障碍服务），这里只负责等待它、把请求交给它。
 */
public class RunService extends Service implements Engine.Progress {

    private static final long WATCHDOG_MS = 5 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean finished;
    private int steps;

    public static void start(Context c, String taskId, String reason, boolean dryRun) {
        Intent i = new Intent(c, RunService.class)
                .putExtra("taskId", taskId)
                .putExtra("reason", reason == null ? "" : reason)
                .putExtra("dry", dryRun);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Store.addLog(c, taskId, false, "启动运行服务失败：" + t
                    + "（可能是系统限制了后台启动，请到设置里允许「自启动 / 后台弹出界面」）");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.ensure(this);
        try {
            startForeground(Notify.ID_RUN, Notify.build(this, "火花助手", "正在准备…", true));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String taskId = intent.getStringExtra("taskId");
        final String reason = intent.getStringExtra("reason");
        final boolean dry = intent.getBooleanExtra("dry", false);

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!finished) {
                    Store.addLog(RunService.this, taskId, false, "运行超时（5 分钟），已中止");
                    update("运行超时，已中止");
                    stopSoon(4000);
                }
            }
        }, WATCHDOG_MS);

        new Thread(new Runnable() {
            @Override
            public void run() {
                Task t = Store.byId(RunService.this, taskId);
                if (t == null) {
                    Store.addLog(RunService.this, taskId, false, "任务不存在（可能已被删除）");
                    update("任务不存在");
                    stopSoon(3000);
                    return;
                }
                // 实测：App 进程被系统回收后，无障碍服务会断开几秒到几十秒再自动重连。
                // 所以「找不到服务」不等于「用户没开无障碍」，这里分两步处理：
                //   ① 先等 20 秒；还没有服务，就把这次请求挂起，再等 90 秒；
                //   ② 重连上来时由 SparkService.onServiceConnected() 领取挂起请求并执行。
                SparkService svc = waitForService(20000);
                boolean delegated = false;
                if (svc == null) {
                    Store.setPendingRun(RunService.this, taskId, dry, reason);
                    update("无障碍服务暂时不在（App 进程可能刚被系统回收），正在等它重连…");
                    svc = waitForService(90000);
                    delegated = true;
                }
                if (svc == null) {
                    Store.clearPendingRun(RunService.this);
                    boolean accOn = SparkService.enabledInSettings(RunService.this);
                    String msg = accOn
                            ? "无障碍服务在系统设置里是开着的，但 App 连不上它（进程被回收后没能自动重连）。"
                              + "请到「设置 → 无障碍」把「火花助手」关掉再打开一次；并建议打开 App 里的「常驻通知保活」。"
                            : "无障碍服务没有开启，本次没执行。请到「设置 → 无障碍 → 已安装的服务」里打开「火花助手」。";
                    Store.addLog(RunService.this, t.title(), false, msg);
                    update(msg);
                    Notify.show(RunService.this, Notify.ID_WARN, "火花助手 · 无障碍未连接", msg);
                    stopSoon(8000);
                    return;
                }
                if (delegated) {
                    // 请求已经挂起，交给重连上来的无障碍服务执行，避免重复跑一遍
                    Store.addLog(RunService.this, t.title(), true,
                            "无障碍服务已重连，挂起的任务交给它执行");
                    update("无障碍已重连，任务继续执行");
                    stopSoon(4000);
                    return;
                }
                update("开始：" + t.title());
                svc.submit(new SparkService.Request(t, reason, dry, RunService.this));
            }
        }, "spark-run").start();

        return START_NOT_STICKY;
    }

    private SparkService waitForService(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            SparkService s = SparkService.get();
            if (s != null) return s;
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return SparkService.get();
    }

    // Engine.Progress
    @Override
    public void update(String text) {
        steps++;
        final String line = text == null ? "" : text;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.NotificationManager nm =
                            (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.notify(Notify.ID_RUN, Notify.build(RunService.this,
                                "火花助手", line, true));
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 流程跑完，由 SparkService 回调。 */
    public void finish(String summary) {
        finished = true;
        final String s = summary == null ? "完成" : summary;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.NotificationManager nm =
                            (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.notify(Notify.ID_RUN, Notify.build(RunService.this, "火花助手", s, false));
                    }
                } catch (Throwable ignored) {
                }
                stopSoon(5000);
            }
        });
    }

    private void stopSoon(long delayMs) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
                    else stopForeground(false);
                } catch (Throwable ignored) {
                }
                stopSelf();
            }
        }, delayMs);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
