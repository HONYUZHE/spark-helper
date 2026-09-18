package com.dsha.spark;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 一次「打开抖音 → 进消息 → 找好友 → 输入 → 发送」的完整流程。
 *
 * 每一步都会写进日志（含失败时的界面快照），所以即使某个版本的抖音改了控件，
 * 用户把日志发回来就能定位，而不用靠猜。
 */
final class Engine {

    interface Progress {
        void update(String text);
    }

    static class Fail extends RuntimeException {
        Fail(String m) {
            super(m);
        }
    }

    private final SparkService svc;
    private final Task task;
    private final boolean dryRun;
    private final String reason;
    private final Progress ui;
    private final StringBuilder log = new StringBuilder();

    private PowerManager.WakeLock wake;
    private String pkg = "";
    private Intent launchIntent;
    private String lastLine = "";

    Engine(SparkService svc, Task task, boolean dryRun, String reason, Progress ui) {
        this.svc = svc;
        this.task = task;
        this.dryRun = dryRun;
        this.reason = reason == null ? "" : reason;
        this.ui = ui;
    }

    String logText() {
        return log.toString();
    }

    /**
     * 跑一次。
     *
     * @return "OK" 已确认发送成功；"UNCONFIRMED" 点了发送但没在界面上确认到；null 失败。
     */
    String run() {
        try {
            pkg = pickPackage();
            line("开始（" + reason + "）" + (dryRun ? "【试运行，不会真的发送】" : ""));
            line("目标好友：" + task.contact + (task.exactMatch ? "（名字需完全一致）" : "（名字包含即可）")
                    + "，抖音包名 " + pkg);
            prepare();
            launch();
            dismissDialogs();
            openMessages();
            openChat();
            String text = typeMessage();
            if (dryRun) {
                line("试运行结束：已找到输入框并写好内容「" + text + "」，**没有**点发送。");
                line(Ui.dump(svc, 60));
                return "OK";
            }
            send();
            boolean confirmed = verify(text);
            if (confirmed) {
                line("已在聊天记录里确认到这条消息。");
                return "OK";
            }
            line("点了发送，但没能在界面上确认到（也可能已经发出去了）。");
            return "UNCONFIRMED";
        } catch (Fail f) {
            line("失败：" + f.getMessage());
            dumpIfUseful();
            return null;
        } catch (Throwable t) {
            line("异常：" + t);
            dumpIfUseful();
            return null;
        } finally {
            cleanup();
        }
    }

    /** 出问题时留一份界面快照；但如果界面还在自己 App 里，快照没有意义。 */
    private void dumpIfUseful() {
        String cur = Ui.norm(Ui.currentPkg(svc));
        if (cur.contains("com.dsha.spark")) {
            line("（界面还停在火花助手里，没有可分析的快照）");
            return;
        }
        line(Ui.dump(svc, 80));
    }

    // ------------------------------------------------------------ 各步骤

    private String pickPackage() {
        PackageManager pm = svc.getPackageManager();
        String[] cands = {"com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"};
        StringBuilder diag = new StringBuilder();
        for (String p : cands) {
            boolean inst;
            try {
                pm.getPackageInfo(p, 0);
                inst = true;
            } catch (Throwable t) {
                inst = false;
            }
            if (!inst) {
                diag.append("· ").append(p).append("：未安装\n");
                continue;
            }
            // 只把 getPackageInfo 当「装没装」的判据；getLaunchIntentForPackage 在部分
            // 系统/ROM 上即使装了也返回 null（实测 Android 16 就是这样），不能用来判断。
            Intent li = null;
            try {
                li = pm.getLaunchIntentForPackage(p);
            } catch (Throwable ignored) {
            }
            java.util.List<ResolveInfo> ri = new java.util.ArrayList<>();
            try {
                ri = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(p), 0);
            } catch (Throwable ignored) {
            }
            diag.append("· ").append(p).append("：已安装")
                    .append("；launchIntent=").append(li == null ? "null" : "有")
                    .append("；launcher 活动数=").append(ri.size());
            if (!ri.isEmpty()) diag.append("（").append(ri.get(0).activityInfo.name).append("）");
            diag.append('\n');

            if (li != null) {
                launchIntent = li;
            } else if (!ri.isEmpty()) {
                // 显式组件启动：完全不受包可见性影响，最稳
                launchIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(new ComponentName(p, ri.get(0).activityInfo.name));
            } else {
                launchIntent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(p);
            }
            line("抖音信息：\n" + diag.toString().trim());
            return p;
        }
        throw new Fail("没找到抖音（com.ss.android.ugc.aweme / .lite），请先安装并登录。\n"
                + "【诊断】\n" + diag.toString().trim());
    }

    private void prepare() {
        PowerManager pm = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            try {
                wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "spark:run");
                wake.setReferenceCounted(false);
                wake.acquire(10 * 60 * 1000L);
                line("已亮屏并持有唤醒锁");
            } catch (Throwable ignored) {
            }
        }
        Ui.sleep(400);
        KeyguardManager km = (KeyguardManager) svc.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            line("检测到锁屏，尝试上滑解锁…");
            Ui.swipeUp(svc);
            Ui.sleep(1200);
            if (km.isKeyguardLocked()) {
                Ui.swipeUp(svc);
                Ui.sleep(1200);
            }
            if (km.isKeyguardLocked()) {
                if (km.isKeyguardSecure()) {
                    throw new Fail("手机锁屏且设置了密码/指纹，程序没法自己解锁。"
                            + "建议把任务时间设在你通常正在用手机的时候，或临时关掉锁屏密码。");
                }
                line("锁屏好像还没解开，继续尝试（可能是没有密码的锁屏）");
            }
        }
    }

    private void launch() {
        Intent i = launchIntent;
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        String entry = i.getComponent() != null ? i.getComponent().flattenToShortString() : (pkg + "（隐式）");
        // 实测：同一个版本里包的可见性会飘（一次 launchIntent=有，一次 null），
        // 所以启动失败就换一次策略重试，而不是干等 30 秒。
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                svc.startActivity(i);
                line("已启动抖音（第 " + (attempt + 1) + " 次，入口 " + entry + "）");
            } catch (Throwable t) {
                line("启动抖音抛异常：" + t);
            }
            boolean up = waitFor("抖音界面出现", attempt == 0 ? 20000 : 15000, new Check() {
                @Override
                public boolean ok() {
                    return Ui.norm(Ui.currentPkg(svc)).equals(pkg) && Ui.root(svc) != null;
                }
            }, true);
            if (up) {
                Ui.sleep(1500);
                return;
            }
            line("没等到抖音界面，当前前台=" + Ui.currentPkg(svc) + "，换方式重试");
            Ui.home(svc);
            Ui.sleep(1200);
        }
        throw new Fail("启动抖音后一直没等到它的界面，当前前台=" + Ui.currentPkg(svc));
    }

    /** 开屏的更新提示、青少年模式、活动弹窗之类，能关就关掉。 */
    private void dismissDialogs() {
        String[] words = {"关闭", "跳过", "以后再说", "稍后再说", "我知道了", "暂不更新", "下次再说", "不再提醒", "暂不开启"};
        for (int round = 0; round < 3; round++) {
            boolean acted = false;
            for (String w : words) {
                AccessibilityNodeInfo n = Ui.find(svc, w, true);
                if (n == null) continue;
                if (Ui.click(svc, n)) {
                    line("关闭弹窗：「" + w + "」");
                    Ui.sleep(800);
                    acted = true;
                    break;
                }
            }
            if (!acted) break;
        }
    }

    private void openMessages() {
        if (!ensureMainPage()) {
            line("返回键退不回主界面，按 HOME 回桌面后重新启动抖音");
            Ui.home(svc);
            Ui.sleep(1500);
            launch();
            ensureMainPage();
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            AccessibilityNodeInfo tab = Ui.findBottomTab(svc, "消息");
            if (tab != null && Ui.click(svc, tab)) {
                line("点击底部「消息」");
                Ui.sleep(1500);
                if (waitFor("消息列表", 8000, new Check() {
                    @Override
                    public boolean ok() {
                        return looksLikeMessageList();
                    }
                }, true)) return;
                line("消息页特征不明显，继续往下走");
                return;
            }
            line("底部没有「消息」标签，按返回键退回主界面（第 " + (attempt + 1) + " 次）");
            Ui.back(svc);
            Ui.sleep(900);
        }
        throw new Fail("没能进入消息页：底部找不到「消息」标签。"
                + "当前界面可能不是抖音主界面（比如停在了个人主页/视频详情），试运行一下再看日志。");
    }

    /** 抖音会恢复上次停留的页面，先退回到有底部导航的主界面再往下走。 */
    private boolean ensureMainPage() {
        for (int i = 0; i < 5; i++) {
            if (Ui.hasBottomNav(svc)) return true;
            line("当前不在抖音主界面，按返回键退回（第 " + (i + 1) + " 次）");
            Ui.back(svc);
            Ui.sleep(900);
        }
        return Ui.hasBottomNav(svc);
    }

    /** 真机实测到的消息页特征：顶部「在线」好友头像条 + 「新关注我的 / 互动消息」两行。 */
    private boolean looksLikeMessageList() {
        if (Ui.find(svc, "新关注我的", false) != null) return true;
        if (Ui.find(svc, "互动消息", false) != null) return true;
        if (Ui.find(svc, "新朋友", false) != null) return true;
        if (Ui.find(svc, "互关朋友", false) != null) return true;
        if (Ui.inputField(svc) != null) return true;
        if (Ui.find(svc, "搜索", false) != null && Ui.findBottomTab(svc, "消息") != null) return true;
        return false;
    }

    /** 聊天页特征：有输入框，或者头部有「音视频通话 / 发送」。 */
    private boolean looksLikeChat() {
        if (Ui.inputField(svc) != null) return true;
        if (Ui.find(svc, "音视频通话", false) != null) return true;
        if (Ui.findSendButton(svc) != null) return true;
        return false;
    }

    private void openChat() {
        boolean opened = false;
        for (int attempt = 0; attempt < 7 && !opened; attempt++) {
            AccessibilityNodeInfo row = Ui.findContactRow(svc, task.contact, task.exactMatch);
            if (row != null) {
                line("匹配到会话节点：「" + Ui.textOf(row) + "」");
                if (Ui.click(svc, row)) {
                    line("点击会话：「" + task.contact + "」");
                    opened = true;
                    break;
                }
                line("会话行点不动，换个位置再试");
            }
            if (attempt == 2) searchContact();
            if (attempt >= 1 && !Ui.scrollList(svc)) {
                line("列表已到底，不再滚动");
                break;
            }
            Ui.sleep(600);
        }
        if (!opened) {
            throw new Fail("消息列表里没找到「" + task.contact + "」。"
                    + "请确认：① 名字和抖音里显示的完全一样；② 对方是你最近聊过的人（列表太靠下就翻不到了）。");
        }
        boolean inChat = waitFor("聊天页（输入框或音视频通话）", 12000, new Check() {
            @Override
            public boolean ok() {
                return looksLikeChat();
            }
        }, false);
        if (!inChat) throw new Fail("点开了会话，但没找到输入框（可能进的是搜索结果页或别人的主页）");
    }

    /** 消息列表顶部的搜索框兜底：搜名字再点第一条。 */
    private void searchContact() {
        AccessibilityNodeInfo box = Ui.inputField(svc);
        if (box == null) {
            AccessibilityNodeInfo s = Ui.find(svc, "搜索", false);
            if (s != null && Ui.click(svc, s)) {
                Ui.sleep(900);
                box = Ui.inputField(svc);
            }
        }
        if (box == null) {
            line("消息页没找到搜索入口，跳过搜索兜底");
            return;
        }
        Ui.click(svc, box);
        Ui.sleep(500);
        if (!Ui.setText(svc, Ui.inputField(svc) != null ? Ui.inputField(svc) : box, task.contact)) {
            line("搜索框写不进字，跳过搜索兜底");
            return;
        }
        line("用搜索找「" + task.contact + "」");
        Ui.sleep(1800);
        AccessibilityNodeInfo r = Ui.findContactRow(svc, task.contact, false);
        if (r != null && Ui.click(svc, r)) {
            line("点开搜索结果");
            Ui.sleep(1600);
        } else {
            line("搜索结果里也没看到这个名字");
        }
    }

    private String typeMessage() {
        String text = task.pickMessage();
        if (text == null || text.trim().isEmpty()) {
            throw new Fail("消息内容是空的，请先在 App 里填一条消息");
        }
        AccessibilityNodeInfo f = Ui.focusInput(svc);
        if (f == null) throw new Fail("找不到聊天输入框");
        if (!Ui.setText(svc, f, text)) {
            throw new Fail("输入框写不进字（这个版本的输入框不支持无障碍写入）。"
                    + "可以换个抖音版本，或者先用「试运行」把日志发回来看看。");
        }
        Ui.sleep(700);
        String got = Ui.textOf(Ui.inputField(svc));
        if (Ui.norm(got).contains(Ui.norm(text))) line("已输入消息：" + text);
        else line("输入框回读为「" + got + "」，继续尝试发送");
        return text;
    }

    private void send() {
        AccessibilityNodeInfo b = Ui.findSendButton(svc);
        if (b == null) b = Ui.findSendNearInput(svc);
        if (b == null) throw new Fail("找不到「发送」按钮");
        String how = Ui.textOf(b);
        if (Ui.click(svc, b)) line("点击发送（控件：" + (how.isEmpty() ? Ui.clsOf(b) : how) + "）");
        else throw new Fail("发送按钮点不动");
        Ui.sleep(2200);
    }

    private boolean verify(final String text) {
        return waitFor("消息出现在聊天记录里", 8000, new Check() {
            @Override
            public boolean ok() {
                List<AccessibilityNodeInfo> hits = Ui.findAll(svc, text, true);
                for (AccessibilityNodeInfo n : hits) {
                    if (n.isEditable()) continue;
                    android.graphics.Rect r = Ui.rect(n);
                    if (r.width() > 0 && r.height() > 0) return true;
                }
                AccessibilityNodeInfo f = Ui.inputField(svc);
                return f != null && !Ui.norm(Ui.textOf(f)).contains(Ui.norm(text));
            }
        }, true);
    }

    private void cleanup() {
        try {
            Ui.home(svc);
        } catch (Throwable ignored) {
        }
        if (wake != null) {
            try {
                if (wake.isHeld()) wake.release();
            } catch (Throwable ignored) {
            }
            wake = null;
        }
    }

    // ------------------------------------------------------------ 工具

    private interface Check {
        boolean ok();
    }

    private boolean waitFor(String what, long timeoutMs, Check c, boolean soft) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            try {
                if (c.ok()) return true;
            } catch (Throwable ignored) {
            }
            Ui.sleep(200);
        }
        if (!soft) throw new Fail("等待「" + what + "」超时（" + timeoutMs + " 毫秒）");
        return false;
    }

    private void line(String s) {
        String stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        lastLine = stamp + " " + s;
        log.append(lastLine).append('\n');
        android.util.Log.i("spark", lastLine);
        if (ui != null) {
            try {
                ui.update(s);
            } catch (Throwable ignored) {
            }
        }
    }
}
