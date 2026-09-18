package com.dsha.spark;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 常驻的无障碍服务：自动化流程都在这里跑。
 *
 * 同一时刻只跑一个任务，其余排队 —— 界面上同时开两个流程必然互相踩。
 */
public class SparkService extends AccessibilityService {

    public static class Request {
        public Task task;
        public String reason = "";
        public boolean dryRun;
        public Engine.Progress progress;

        public Request(Task t, String reason, boolean dryRun, Engine.Progress p) {
            this.task = t;
            this.reason = reason;
            this.dryRun = dryRun;
            this.progress = p;
        }
    }

    private static volatile SparkService inst;

    private final Deque<Request> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private ExecutorService worker;
    private Handler main;
    private volatile boolean busy;

    public static SparkService get() {
        return inst;
    }

    public static boolean ready() {
        return inst != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        main = new Handler(Looper.getMainLooper());
        worker = Executors.newSingleThreadExecutor();
        Store.addLog(this, "", true, "无障碍服务已连接，定时任务可以运行了");
        pickupPending();
        pump();
    }

    /**
     * 领取「无障碍断开期间挂起的那次请求」。
     * 场景：进程被系统回收 → 用户/闹钟此刻要求执行 → 找不到服务 → 挂起 →
     * 系统把无障碍服务重新绑上来 → 这里补跑，用户看到的结果和没被回收时一样。
     */
    private void pickupPending() {
        Store.PendingRun p = Store.getPendingRun(this);
        if (p == null) return;
        long age = System.currentTimeMillis() - p.at;
        Store.clearPendingRun(this);
        if (age > 5 * 60 * 1000L) {
            Store.addLog(this, "", true, "有一条挂起的任务但已经过了 " + (age / 1000) + " 秒，放弃执行");
            return;
        }
        Task t = Store.byId(this, p.taskId);
        if (t == null) {
            Store.addLog(this, "", false, "挂起的任务已经不存在了（可能被删除）");
            return;
        }
        Store.addLog(this, t.title(), true, "无障碍服务重连成功，补跑挂起的任务（" + p.reason + "）");
        submit(new Request(t, (p.reason == null ? "" : p.reason) + "·重连补跑", p.dryRun, null));
    }

    /** 无障碍服务在系统设置里是否处于「已开启」（不代表当前已连上）。 */
    public static boolean enabledInSettings(Context c) {
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> list =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                for (AccessibilityServiceInfo i : list) {
                    if (i.getResolveInfo() != null && i.getResolveInfo().serviceInfo != null
                            && c.getPackageName().equals(i.getResolveInfo().serviceInfo.packageName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String s = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 流程用的是主动轮询而不是事件驱动：抖音的控件树在动画期间会反复失效，
        // 基于事件的状态机会被噪音打断。这里只做保活。
    }

    @Override
    public void onInterrupt() {
        Store.addLog(this, "", true, "无障碍服务被系统中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        inst = null;
        Store.addLogSync(this, "", false, "无障碍服务被系统解绑（可能是 App 进程被回收，或用户在设置里关掉了它）");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        inst = null;
        Store.addLogSync(this, "", false, "无障碍服务被销毁");
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }

    /** 提交一次运行请求（会自动排队）。 */
    public void submit(Request r) {
        if (r == null || r.task == null) return;
        synchronized (lock) {
            queue.addLast(r);
        }
        pump();
    }

    private void pump() {
        synchronized (lock) {
            if (busy || queue.isEmpty() || worker == null) return;
            busy = true;
        }
        final Request r;
        synchronized (lock) {
            r = queue.pollFirst();
        }
        if (r == null) {
            busy = false;
            return;
        }
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    String status;
                    Engine e = new Engine(SparkService.this, r.task, r.dryRun, r.reason, r.progress);
                    try {
                        status = e.run();
                    } catch (Throwable t) {
                        status = null;
                        Store.addLog(SparkService.this, r.task.title(), false, "执行异常：" + t);
                    }
                    finishOne(r, status, e);
                    synchronized (lock) {
                        busy = false;
                    }
                    pump();
                }
            });
        } catch (Throwable t) {
            synchronized (lock) {
                busy = false;
            }
            Store.addLog(this, r.task.title(), false, "排队失败：" + t);
        }
    }

    private void finishOne(Request r, String status, Engine e) {
        boolean ok = "OK".equals(status);
        boolean unconfirmed = "UNCONFIRMED".equals(status);
        String label = r.task.title();

        if (ok || unconfirmed) {
            // 只有真正发出去（或试运行）才记日期；试运行不改日期
            if (!r.dryRun) {
                Task fresh = Store.byId(this, r.task.id);
                if (fresh != null) {
                    fresh.lastDay = Store.today();
                    fresh.lastRunAt = System.currentTimeMillis();
                    fresh.lastResult = ok ? "成功 " + Store.stamp(fresh.lastRunAt) : "已发送未确认 " + Store.stamp(fresh.lastRunAt);
                    Store.upsert(this, fresh);
                }
            }
        } else {
            Task fresh = Store.byId(this, r.task.id);
            if (fresh != null) {
                fresh.lastResult = "失败 " + Store.stamp(System.currentTimeMillis());
                Store.upsert(this, fresh);
            }
        }

        String head;
        if (r.dryRun) head = ok ? "试运行完成" : "试运行失败";
        else if (ok) head = "发送成功";
        else if (unconfirmed) head = "已发送（未确认）";
        else head = "发送失败";

        StringBuilder sb = new StringBuilder();
        sb.append(head).append("：").append(label);
        if (!ok && !unconfirmed) {
            String el = e.logText();
            sb.append('\n').append(el.length() > 1500 ? el.substring(0, 1500) : el);
        }
        Store.addLog(this, label, ok || unconfirmed, sb.toString());

        if (r.dryRun) Store.setDryRunDone(this, true);

        // 任务执行完，重新排下一天的闹钟
        Task fresh = Store.byId(this, r.task.id);
        if (fresh != null && fresh.enabled && !r.dryRun) Scheduler.schedule(this, fresh);

        if (r.progress instanceof RunService) {
            ((RunService) r.progress).finish(head + "：" + label);
        } else if (!r.dryRun) {
            Notify.show(this, Notify.ID_RESULT, "火花助手 · " + head, label);
        }
    }
}
