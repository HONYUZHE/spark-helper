package com.dsha.spark;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.List;

/** 全部界面都在代码里搭，不依赖任何第三方库。 */
public class MainActivity extends Activity {

    private LinearLayout root;
    private LinearLayout taskBox;
    private TextView statusBox;
    private TextView logBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Scheduler.rescheduleAll(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------ 界面

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(28));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("火花助手");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("每天在你设定的时间，自动打开抖音给指定好友发一条消息，维持聊天火花。\n"
                + "不联网、不需要 root、不上传任何数据。");
        sub.setTextSize(13);
        sub.setTextColor(Color.parseColor("#666666"));
        sub.setPadding(0, dp(4), 0, dp(10));
        root.addView(sub);

        statusBox = new TextView(this);
        statusBox.setTextSize(13);
        statusBox.setPadding(dp(10), dp(10), dp(10), dp(10));
        statusBox.setBackgroundColor(Color.parseColor("#F2F4F7"));
        root.addView(statusBox, matchWrap());

        final CheckBox alive = new CheckBox(this);
        alive.setText("常驻通知保活（防止 App 被系统回收后「无障碍连不上」，建议开启）");
        alive.setTextSize(12);
        alive.setPadding(0, dp(6), 0, dp(2));
        alive.setChecked(Store.keepAlive(this));
        alive.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Store.setKeepAlive(MainActivity.this, on);
                if (on) KeepAliveService.start(MainActivity.this);
                else KeepAliveService.stop(MainActivity.this);
                toast(on ? "已开启保活（通知栏会出现一条静默通知）" : "已关闭保活");
                refresh();
            }
        });
        root.addView(alive);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, dp(8));
        btns.addView(button("＋ 添加任务", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editDialog(null);
            }
        }), weightWrap());
        btns.addView(button("全部立即执行", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAll();
            }
        }), weightWrap());
        root.addView(btns);

        root.addView(sectionTitle("任务"));
        taskBox = new LinearLayout(this);
        taskBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(taskBox);

        root.addView(sectionTitle("运行日志"));
        LinearLayout logBtns = new LinearLayout(this);
        logBtns.setOrientation(LinearLayout.HORIZONTAL);
        logBtns.addView(button("复制日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLogs();
            }
        }), weightWrap());
        logBtns.addView(button("导出到 Download", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLog();
            }
        }), weightWrap());
        logBtns.addView(button("清空", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.clearLogs(MainActivity.this);
                refresh();
            }
        }), weightWrap());
        root.addView(logBtns);

        logBox = new TextView(this);
        logBox.setTextSize(11);
        logBox.setTypeface(Typeface.MONOSPACE);
        logBox.setPadding(dp(8), dp(8), dp(8), dp(8));
        logBox.setBackgroundColor(Color.parseColor("#FAFAFA"));
        logBox.setTextIsSelectable(true);
        root.addView(logBox, matchWrap());

        TextView logHint = new TextView(this);
        logHint.setTextSize(11);
        logHint.setTextColor(Color.parseColor("#888888"));
        logHint.setPadding(0, dp(4), 0, 0);
        logHint.setText("上面每条只显示第一行。完整详情（含失败时的抖音界面快照）用「导出到 Download」或「复制日志」。");
        root.addView(logHint);

        TextView tips = new TextView(this);
        tips.setTextSize(12);
        tips.setTextColor(Color.parseColor("#666666"));
        tips.setPadding(0, dp(14), 0, 0);
        tips.setText("使用步骤：\n"
                + "1. 点上面第一行的「开启」，打开无障碍里的「火花助手」。\n"
                + "2. 点「关掉电池优化」，再在系统的应用设置里允许「自启动 / 后台运行 / 后台弹出界面」。\n"
                + "3. 添加任务：填对方在抖音里的名字（和消息列表显示的一致）、消息内容、时间。\n"
                + "4. 先点一次「试运行」确认能自己走完流程，再等定时执行。\n"
                + "5. 到点前手机要开着机、连着网，最好别锁屏（有密码的锁屏程序解不开）。\n\n"
                + "提醒：抖音的用户协议不允许自动化操作，本工具只在你自己手机上、给你自己的好友发消息，"
                + "请保持低频，并自行承担账号风险。");
        root.addView(tips);

        setContentView(sv);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        return p;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(6));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        return t;
    }

    // ------------------------------------------------------------ 刷新

    private void refresh() {
        // 状态
        StringBuilder sb = new StringBuilder();
        boolean accOn = accEnabled();
        boolean accUp = accConnected();
        if (accOn && accUp) {
            sb.append("✅ 无障碍服务：已开启并已连接\n");
        } else if (accOn) {
            sb.append("⚠️ 无障碍服务：设置里是开着的，但当前**没连上**（多半是 App 进程刚被系统回收，"
                    + "几秒到几十秒会自己重连；期间点执行会自动挂起、连上就补跑）\n");
        } else {
            sb.append("❌ 无障碍服务：未开启（必须开启才能自动操作）\n");
        }
        sb.append(batteryOk() ? "✅ 电池优化：已忽略（后台不容易被杀）\n" : "⚠️ 电池优化：未忽略（可能导致定时被系统推迟）\n");
        sb.append(Scheduler.canExact(this) ? "✅ 精确闹钟：可用\n" : "⚠️ 精确闹钟：不可用（会退化成不精确闹钟 + 补跑）\n");
        sb.append(douyinInstalled() ? "✅ 抖音：已安装\n" : "❌ 抖音：没检测到\n");
        sb.append(Store.keepAlive(this) ? "✅ 常驻保活：已开启\n" : "⚠️ 常驻保活：已关闭（进程容易被回收，建议开启）\n");
        sb.append("⏰ 下一次执行：").append(Scheduler.describeNext(this));
        statusBox.setText(sb.toString());

        statusBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fixStatusDialog();
            }
        });

        // 任务
        taskBox.removeAllViews();
        List<Task> tasks = Store.tasks(this);
        if (tasks.isEmpty()) {
            taskBox.addView(body("还没有任务。点上面的「＋ 添加任务」建一个。"));
        }
        for (final Task t : tasks) {
            taskBox.addView(taskCard(t));
        }

        // 日志
        List<Store.Entry> logs = Store.logs(this);
        if (logs.isEmpty()) {
            logBox.setText("（暂无日志）");
        } else {
            StringBuilder lb = new StringBuilder();
            int n = 0;
            for (Store.Entry e : logs) {
                if (n++ >= 12) break;
                String first = e.msg;
                int nl = first.indexOf('\n');
                if (nl > 0) first = first.substring(0, nl);
                lb.append(Store.stamp(e.at)).append(e.ok ? " ✅ " : " ❌ ")
                        .append(e.task).append(' ').append(first).append('\n');
            }
            logBox.setText(lb.toString());
        }
    }

    private View taskCard(final Task t) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackgroundColor(t.enabled ? Color.parseColor("#FFFFFF") : Color.parseColor("#EFEFEF"));

        TextView head = new TextView(this);
        head.setText(t.title());
        head.setTextSize(15);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(head);

        TextView info = new TextView(this);
        String msg = t.message == null ? "" : t.message;
        String[] pool = t.messagePool();
        if (pool.length > 1) msg = pool[0] + " …（共 " + pool.length + " 条随机）";
        String recent = t.lastResult == null || t.lastResult.isEmpty() ? "还没跑过" : t.lastResult;
        info.setText("消息：" + msg + "\n最近：" + recent);
        info.setTextSize(12);
        info.setTextColor(Color.parseColor("#555555"));
        card.addView(info);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        CheckBox cb = new CheckBox(this);
        cb.setText("启用");
        cb.setTextSize(12);
        cb.setChecked(t.enabled);
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Task fresh = Store.byId(MainActivity.this, t.id);
                if (fresh == null) return;
                fresh.enabled = checked;
                Store.upsert(MainActivity.this, fresh);
                if (checked) Scheduler.schedule(MainActivity.this, fresh);
                else Scheduler.cancel(MainActivity.this, fresh.id);
                refresh();
            }
        });
        row.addView(cb, weightWrap());
        row.addView(button("试运行", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RunService.start(MainActivity.this, t.id, "试运行", true);
                toast("试运行开始：会打开抖音但不点发送，完成后回来看日志");
            }
        }), weightWrap());
        row.addView(button("立即发送", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReal(t);
            }
        }), weightWrap());
        card.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("编辑", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editDialog(t);
            }
        }), weightWrap());
        row2.addView(button("删除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除任务")
                        .setMessage("确定删除「" + t.title() + "」？")
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                Store.remove(MainActivity.this, t.id);
                                refresh();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        }), weightWrap());
        card.addView(row2);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(4), 0, dp(4));
        wrap.addView(card, matchWrap());
        return wrap;
    }

    private void confirmReal(final Task t) {
        new AlertDialog.Builder(this)
                .setTitle("立即发送一条")
                .setMessage("现在就用无障碍打开抖音，给「" + t.contact + "」发一条：\n\n"
                        + t.pickMessage() + "\n\n会真的发出去。")
                .setPositiveButton("发送", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        RunService.start(MainActivity.this, t.id, "手动执行", false);
                        toast("开始执行，看通知栏进度");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runAll() {
        List<Task> tasks = Store.tasks(this);
        int n = 0;
        for (Task t : tasks) {
            if (!t.enabled) continue;
            RunService.start(this, t.id, "手动执行全部", false);
            n++;
        }
        toast(n == 0 ? "没有启用的任务" : "已排队 " + n + " 个任务");
    }

    // ------------------------------------------------------------ 编辑任务

    private void editDialog(final Task existing) {
        final Task t = existing == null ? new Task() : existing.copy();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        final EditText label = new EditText(this);
        label.setHint("备注（可空，比如「和妈妈的火花」）");
        label.setText(t.label);
        box.addView(label);

        final EditText contact = new EditText(this);
        contact.setHint("对方在抖音里的名字（必须和消息列表里一致）");
        contact.setText(t.contact);
        box.addView(contact);

        final EditText message = new EditText(this);
        message.setHint("消息内容。多条用 | 隔开，每次随机发一条，例如：在吗|打卡|早");
        message.setText(t.message);
        message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setMinLines(1);
        box.addView(message);

        TextView tl = new TextView(this);
        tl.setText("发送时间");
        tl.setPadding(0, dp(10), 0, 0);
        box.addView(tl);

        final TimePicker tp = new TimePicker(this);
        tp.setIs24HourView(Boolean.TRUE);
        try {
            tp.setHour(t.hour);
            tp.setMinute(t.minute);
        } catch (Throwable ignored) {
        }
        box.addView(tp);

        final CheckBox exact = new CheckBox(this);
        exact.setText("名字必须完全一致（默认只要包含就算）");
        exact.setChecked(t.exactMatch);
        box.addView(exact);

        final CheckBox enabled = new CheckBox(this);
        enabled.setText("启用这个任务");
        enabled.setChecked(t.enabled);
        box.addView(enabled);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加任务" : "编辑任务")
                .setView(sc)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        t.label = label.getText().toString().trim();
                        t.contact = contact.getText().toString().trim();
                        t.message = message.getText().toString().trim();
                        t.exactMatch = exact.isChecked();
                        t.enabled = enabled.isChecked();
                        if (Build.VERSION.SDK_INT >= 23) {
                            t.hour = tp.getHour();
                            t.minute = tp.getMinute();
                        } else {
                            t.hour = tp.getCurrentHour();
                            t.minute = tp.getCurrentMinute();
                        }
                        if (t.contact.isEmpty()) {
                            toast("没填对方名字，没保存");
                            return;
                        }
                        if (t.message.isEmpty()) {
                            toast("没填消息内容，没保存");
                            return;
                        }
                        Store.upsert(MainActivity.this, t);
                        if (t.enabled) Scheduler.schedule(MainActivity.this, t);
                        else Scheduler.cancel(MainActivity.this, t.id);
                        Scheduler.heartbeat(MainActivity.this);
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 状态修复

    private void fixStatusDialog() {
        String[] items = {
                (accEnabled() ? "① 无障碍服务：已开启" : "① 去开启无障碍服务（必做）"),
                (batteryOk() ? "② 电池优化：已忽略" : "② 关掉电池优化（建议）"),
                (Scheduler.canExact(this) ? "③ 精确闹钟：已允许" : "③ 允许精确闹钟（可选）"),
                "④ 打开发抖音的权限设置（自启动/后台）",
                "⑤ 打开系统「电池 → 后台限制」设置"
        };
        new AlertDialog.Builder(this)
                .setTitle("点击逐项处理")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                openAccessibility();
                                break;
                            case 1:
                                requestBattery();
                                break;
                            case 2:
                                requestExactAlarm();
                                break;
                            default:
                                openAppDetails();
                                break;
                        }
                    }
                })
                .show();
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast("在列表里找到「火花助手」并打开");
        } catch (Throwable t) {
            toast("打不开无障碍设置：" + t);
        }
    }

    private void requestBattery() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            toast("打不开电池优化设置");
        }
    }

    private void requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
                        .setData(Uri.parse("package:" + getPackageName())));
                return;
            } catch (Throwable ignored) {
            }
        }
        openAppDetails();
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            toast("打不开应用设置");
        }
    }

    // ------------------------------------------------------------ 状态查询

    /** 无障碍服务在系统设置里是否处于「已开启」（不代表当前已连上，见下一行提示）。 */
    private boolean accEnabled() {
        return SparkService.enabledInSettings(this);
    }

    private boolean accConnected() {
        return SparkService.ready();
    }

    private boolean batteryOk() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean douyinInstalled() {
        try {
            PackageManager pm = getPackageManager();
            pm.getPackageInfo("com.ss.android.ugc.aweme", 0);
            return true;
        } catch (Throwable t) {
            try {
                getPackageManager().getPackageInfo("com.ss.android.ugc.aweme.lite", 0);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------ 杂项

    private String logText() {
        StringBuilder sb = new StringBuilder();
        sb.append("火花助手日志 导出时间 ").append(Store.stamp(System.currentTimeMillis())).append('\n');
        sb.append("Android=").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
                .append(" 无障碍=").append(accEnabled())
                .append(" 电池优化=").append(batteryOk())
                .append(" 精确闹钟=").append(Scheduler.canExact(this))
                .append(" 抖音=").append(douyinInstalled()).append('\n');
        for (Store.Entry e : Store.logs(this)) {
            sb.append("---- ").append(Store.stamp(e.at)).append(e.ok ? " [OK] " : " [FAIL] ")
                    .append(e.task).append('\n').append(e.msg).append('\n');
        }
        return sb.toString();
    }

    private void copyLogs() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("火花助手日志", logText()));
                toast("完整日志已复制到剪贴板");
                return;
            }
        } catch (Throwable ignored) {
        }
        toast("复制失败，改用「导出到 Download」");
    }

    /**
     * 把完整日志写成 Download/火花助手日志.txt。
     * API 29+ 走 MediaStore（不需要存储权限）；更老的系统退回到应用自己的外部目录。
     */
    private void exportLog() {
        String text = logText();
        String name = "火花助手日志.txt";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                android.net.Uri uri = getContentResolver()
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("MediaStore 拒绝写入");
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) throw new IllegalStateException("打不开输出流");
                os.write(text.getBytes("UTF-8"));
                os.close();
                toast("已导出到 Download/" + name);
                return;
            }
            java.io.File f = new java.io.File(getExternalFilesDir(null), name);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(text.getBytes("UTF-8"));
            fo.close();
            toast("已导出到 " + f.getAbsolutePath());
        } catch (Throwable t) {
            toast("导出失败：" + t + "（可以改用「复制日志」）");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
