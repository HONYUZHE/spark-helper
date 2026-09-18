package com.dsha.spark;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Random;

/** 一条定时任务：什么时候、给谁、发什么。 */
public class Task {

    public String id = "";
    public String label = "";        // 备注，可空
    public String contact = "";      // 抖音里显示的名字（昵称/备注名）
    public String message = "";      // 消息内容；用 | 分隔多条则每次随机选一条
    public int hour = 9;
    public int minute = 0;
    public boolean enabled = true;
    public boolean exactMatch = false;   // 名字是否要求完全一致

    public String lastDay = "";      // 最近一次成功的日期 yyyy-MM-dd
    public String lastResult = "";   // 最近一次的结果文字
    public long lastRunAt = 0L;

    private static final Random RND = new Random();

    public Task() {
        id = Long.toHexString(System.currentTimeMillis()) + Integer.toHexString(RND.nextInt(0xFFFF));
    }

    public String timeText() {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    public String title() {
        String n = contact == null || contact.isEmpty() ? "(未设置好友)" : contact;
        String l = label == null || label.isEmpty() ? "" : label + " · ";
        return timeText() + "  " + l + n;
    }

    /** 支持「早上好|在吗|打卡」这种消息池，每次随机挑一条，降低被判定为机器人的概率。 */
    public String pickMessage() {
        String m = message == null ? "" : message.trim();
        if (m.isEmpty()) return "";
        if (m.indexOf('|') < 0) return m;
        String[] parts = m.split("\\|");
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) pool.add(s);
        }
        if (pool.isEmpty()) return m;
        return pool.get(RND.nextInt(pool.size()));
    }

    public String[] messagePool() {
        String m = message == null ? "" : message;
        if (m.indexOf('|') < 0) return new String[]{m};
        return m.split("\\|");
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("label", label);
            o.put("contact", contact);
            o.put("message", message);
            o.put("hour", hour);
            o.put("minute", minute);
            o.put("enabled", enabled);
            o.put("exact", exactMatch);
            o.put("lastDay", lastDay);
            o.put("lastResult", lastResult);
            o.put("lastRunAt", lastRunAt);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Task fromJson(JSONObject o) {
        Task t = new Task();
        t.id = o.optString("id", t.id);
        t.label = o.optString("label", "");
        t.contact = o.optString("contact", "");
        t.message = o.optString("message", "");
        t.hour = o.optInt("hour", 9);
        t.minute = o.optInt("minute", 0);
        t.enabled = o.optBoolean("enabled", true);
        t.exactMatch = o.optBoolean("exact", false);
        t.lastDay = o.optString("lastDay", "");
        t.lastResult = o.optString("lastResult", "");
        t.lastRunAt = o.optLong("lastRunAt", 0L);
        return t;
    }

    public Task copy() {
        return fromJson(toJson());
    }
}
