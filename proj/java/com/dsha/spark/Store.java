package com.dsha.spark;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 全部持久化数据：任务列表 + 运行日志，都放 SharedPreferences，不联网。 */
public final class Store {

    private static final String PREF = "spark";
    private static final String K_TASKS = "tasks";
    private static final String K_LOGS = "logs";
    private static final String K_DRYRUN_DONE = "dryrun_done";
    private static final int MAX_LOGS = 120;

    private Store() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 任务

    public static List<Task> tasks(Context c) {
        List<Task> out = new ArrayList<>();
        String raw = sp(c).getString(K_TASKS, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Task.fromJson(o));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveTasks(Context c, List<Task> list) {
        JSONArray arr = new JSONArray();
        for (Task t : list) arr.put(t.toJson());
        sp(c).edit().putString(K_TASKS, arr.toString()).apply();
    }

    public static void upsert(Context c, Task t) {
        List<Task> list = tasks(c);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(t.id)) {
                list.set(i, t);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(t);
        saveTasks(c, list);
    }

    public static void remove(Context c, String id) {
        List<Task> list = tasks(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        saveTasks(c, list);
        Scheduler.cancel(c, id);
    }

    public static Task byId(Context c, String id) {
        for (Task t : tasks(c)) if (t.id.equals(id)) return t;
        return null;
    }

    // ---------------------------------------------------------------- 日志

    public static class Entry {
        public long at;
        public String task = "";
        public boolean ok;
        public String msg = "";
    }

    public static void addLog(Context c, String task, boolean ok, String msg) {
        List<Entry> list = logs(c);
        Entry e = new Entry();
        e.at = System.currentTimeMillis();
        e.task = task == null ? "" : task;
        e.ok = ok;
        e.msg = msg == null ? "" : msg;
        list.add(0, e);
        while (list.size() > MAX_LOGS) list.remove(list.size() - 1);
        JSONArray arr = new JSONArray();
        for (Entry x : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("at", x.at);
                o.put("task", x.task);
                o.put("ok", x.ok);
                o.put("msg", x.msg);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        sp(c).edit().putString(K_LOGS, arr.toString()).apply();
    }

    public static List<Entry> logs(Context c) {
        List<Entry> out = new ArrayList<>();
        String raw = sp(c).getString(K_LOGS, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.at = o.optLong("at", 0L);
                e.task = o.optString("task", "");
                e.ok = o.optBoolean("ok", false);
                e.msg = o.optString("msg", "");
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void clearLogs(Context c) {
        sp(c).edit().remove(K_LOGS).apply();
    }

    // ---------------------------------------------------------------- 杂项

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static String stamp(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    // ------------------------------------------------------------ 挂起请求

    /**
     * 一次「想跑但当时无障碍服务不在」的请求。等无障碍服务重连上来由它补跑，
     * 用来对付「进程被系统回收 → 无障碍短暂断开」这个空窗期。
     */
    public static class PendingRun {
        public String taskId = "";
        public boolean dryRun;
        public String reason = "";
        public long at;
    }

    private static final String K_PENDING = "pending";

    public static void setPendingRun(Context c, String taskId, boolean dryRun, String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("taskId", taskId);
            o.put("dry", dryRun);
            o.put("reason", reason == null ? "" : reason);
            o.put("at", System.currentTimeMillis());
            sp(c).edit().putString(K_PENDING, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static PendingRun getPendingRun(Context c) {
        String raw = sp(c).getString(K_PENDING, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            PendingRun p = new PendingRun();
            p.taskId = o.optString("taskId", "");
            p.dryRun = o.optBoolean("dry", false);
            p.reason = o.optString("reason", "");
            p.at = o.optLong("at", 0L);
            if (p.taskId.isEmpty()) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearPendingRun(Context c) {
        sp(c).edit().remove(K_PENDING).apply();
    }

    // ------------------------------------------------------------ 保活开关

    private static final String K_ALIVE = "keep_alive";

    public static boolean keepAlive(Context c) {
        return sp(c).getBoolean(K_ALIVE, true);
    }

    public static void setKeepAlive(Context c, boolean v) {
        sp(c).edit().putBoolean(K_ALIVE, v).apply();
    }

    /** 崩溃路径上用：apply() 可能来不及落盘，这里用 commit 同步写。 */
    public static void addLogSync(Context c, String task, boolean ok, String msg) {
        addLog(c, task, ok, msg);
        // addLog 内部用 apply；下面这一次用 commit 保证崩溃前已经写进磁盘
        try {
            SharedPreferences p = sp(c);
            p.edit().putString(K_LOGS, p.getString(K_LOGS, "")).commit();
        } catch (Throwable ignored) {
        }
    }

    /** 试运行只提示一次，别每次开 App 都骚扰。 */
    public static boolean dryRunDone(Context c) {
        return sp(c).getBoolean(K_DRYRUN_DONE, false);
    }

    public static void setDryRunDone(Context c, boolean v) {
        sp(c).edit().putBoolean(K_DRYRUN_DONE, v).apply();
    }
}
