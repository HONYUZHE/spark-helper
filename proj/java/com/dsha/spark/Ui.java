package com.dsha.spark;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * 无障碍相关的底层操作。
 *
 * 设计原则：**任何一步都不假定唯一的控件写法**。抖音不同版本里，同一个入口可能是
 * TextView 文字、可能是 contentDescription、也可能是不可点击的文本（要靠祖先或坐标点）。
 * 所以这里每个查找都返回「候选里最像的那个」，并且都留着坐标点击的兜底。
 */
final class Ui {

    private Ui() {
    }

    // ------------------------------------------------------------ 基础

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static AccessibilityNodeInfo root(AccessibilityService s) {
        try {
            return s.getRootInActiveWindow();
        } catch (Throwable t) {
            return null;
        }
    }

    static String norm(String x) {
        if (x == null) return "";
        return x.replaceAll("\\s+", "").trim();
    }

    static String textOf(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().trim();
    }

    static String descOf(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getContentDescription();
        return t == null ? "" : t.toString().trim();
    }

    static String clsOf(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getClassName();
        return t == null ? "" : t.toString();
    }

    static boolean match(String have, String want, boolean exact) {
        String a = norm(have);
        String b = norm(want);
        if (a.isEmpty() || b.isEmpty()) return false;
        return exact ? a.equals(b) : a.contains(b);
    }

    /** 0=完全相同 1=以它开头 2=包含它 3=不匹配。 */
    static int score(String have, String want) {
        String a = norm(have);
        String b = norm(want);
        if (a.isEmpty() || b.isEmpty()) return 3;
        if (a.equals(b)) return 0;
        if (a.startsWith(b)) return 1;
        if (a.contains(b)) return 2;
        return 3;
    }

    static Rect rect(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        if (n != null) n.getBoundsInScreen(r);
        return r;
    }

    static int[] centerOf(AccessibilityNodeInfo n) {
        Rect r = rect(n);
        if (r.width() <= 0 || r.height() <= 0) return null;
        return new int[]{r.centerX(), r.centerY()};
    }

    static int screenW(AccessibilityService s) {
        return s.getResources().getDisplayMetrics().widthPixels;
    }

    static int screenH(AccessibilityService s) {
        return s.getResources().getDisplayMetrics().heightPixels;
    }

    /** 广度优先收集当前所有窗口里的节点，带上限防止极端界面把内存吃满。 */
    static List<AccessibilityNodeInfo> collect(AccessibilityService s, int limit) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        for (AccessibilityNodeInfo r : roots(s)) q.add(r);
        while (!q.isEmpty() && out.size() < limit) {
            AccessibilityNodeInfo n = q.poll();
            out.add(n);
            int cc;
            try {
                cc = n.getChildCount();
            } catch (Throwable t) {
                continue;
            }
            for (int i = 0; i < cc; i++) {
                try {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    static List<AccessibilityNodeInfo> roots(AccessibilityService s) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> ws = s.getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) out.add(r);
                }
            }
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = root(s);
            if (r != null) out.add(r);
        }
        return out;
    }

    static String currentPkg(AccessibilityService s) {
        AccessibilityNodeInfo r = root(s);
        if (r != null) {
            CharSequence p = r.getPackageName();
            if (p != null) return p.toString();
        }
        try {
            List<AccessibilityWindowInfo> ws = s.getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (!w.isActive()) continue;
                    AccessibilityNodeInfo x = w.getRoot();
                    if (x != null && x.getPackageName() != null) return x.getPackageName().toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ------------------------------------------------------------ 查找

    static List<AccessibilityNodeInfo> findAll(AccessibilityService s, String text, boolean exact) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : collect(s, 1500)) {
            String t = textOf(n);
            if (match(t, text, exact)) out.add(n);
        }
        return out;
    }

    static boolean hasText(AccessibilityService s, String text, boolean exact) {
        return !findAll(s, text, exact).isEmpty();
    }

    /** 找可点击度最高的匹配节点：优先本身可点击的，其次选最靠上的（更可能是标题/入口）。 */
    static AccessibilityNodeInfo find(AccessibilityService s, String text, boolean exact) {
        List<AccessibilityNodeInfo> hits = findAll(s, text, exact);
        if (hits.isEmpty()) return null;
        List<AccessibilityNodeInfo> sorted = new ArrayList<>(hits);
        Collections.sort(sorted, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                int sa = clickableAncestor(a, 6) != null ? 0 : 1;
                int sb = clickableAncestor(b, 6) != null ? 0 : 1;
                if (sa != sb) return sa - sb;
                return rect(a).top - rect(b).top;
            }
        });
        return sorted.get(0);
    }

    /**
     * 底部导航栏里的某个标签（例如「消息」）。
     * 只认屏幕最底部那一条 —— 以前有个「退而取整棵树里位置最低的『消息』」的兜底，
     * 在个人主页那种界面上会点到完全不相干的东西，已经去掉。
     */
    static AccessibilityNodeInfo findBottomTab(AccessibilityService s, String label) {
        int h = screenH(s);
        List<AccessibilityNodeInfo> hits = findAll(s, label, true);
        AccessibilityNodeInfo best = null;
        Rect bestR = null;
        for (AccessibilityNodeInfo n : hits) {
            if (!n.isVisibleToUser()) continue;
            Rect r = rect(n);
            if (r.height() <= 0) continue;
            if (r.top < h * 0.80) continue;              // 只要屏幕最底部的导航区
            if (best == null || r.top > bestR.top) {
                best = n;
                bestR = r;
            }
        }
        return best;
    }

    /** 是否处于「有底部导航」的抖音主界面（首页/朋友/消息/我 同时存在）。 */
    static boolean hasBottomNav(AccessibilityService s) {
        return findBottomTab(s, "首页") != null
                && findBottomTab(s, "消息") != null
                && findBottomTab(s, "我") != null;
    }

    static AccessibilityNodeInfo findByDesc(AccessibilityService s, String desc, boolean exact) {
        for (AccessibilityNodeInfo n : collect(s, 1500)) {
            if (match(descOf(n), desc, exact)) return n;
        }
        return null;
    }

    /**
     * 消息列表里的某个联系人。
     *
     * 真机实测（抖音 3200×2000）：一个会话有两套节点 ——
     *   · 可点击的整行：[0,1069,3200,1249]，文字是「名字,预览文字」拼起来的
     *   · 单独的名字 TextView：[40,1089,180,1229]，只有 140px 宽
     * 所以**不能**用宽度去筛选候选（会把精确匹配的那个名字节点筛掉），
     * 正确的做法是：候选按文字匹配度打分，再往上找「整行」用来点击。
     */
    static AccessibilityNodeInfo findContactRow(AccessibilityService s, String name, boolean exact) {
        int sh = screenH(s), sw = screenW(s);
        AccessibilityNodeInfo best = null;
        Rect bestR = null;
        int bestScore = 99;
        int bestLen = Integer.MAX_VALUE;
        int bestRegion = 99;
        String nn = norm(name);
        // 消息页里这些「通知行」的文字可能含有好友名字，点进去是互动页/主页而不是聊天，
        // 真机实测：正是这种「互动消息,<好友名> 在评论中提到了你」的行把流程带去了个人主页。
        String[] notifPrefix = {"互动消息", "新关注我的", "购物消息", "系统消息",
                "陌生人消息", "群通知", "新朋友", "互关朋友"};
        for (AccessibilityNodeInfo n : collect(s, 1500)) {
            if (!n.isVisibleToUser()) continue;
            String t = textOf(n);
            if (t.isEmpty()) continue;
            String nt = norm(t);
            int sc = score(t, name);
            if (sc == 3) continue;

            boolean notif = false;
            for (String p : notifPrefix) {
                if (nt.startsWith(p)) {
                    notif = true;
                    break;
                }
            }
            if (notif) continue;

            // 只认三种：① 整段文字就是名字；② 抖音的会话行格式「名字,预览」；
            // ③ 独立的昵称控件（不含逗号、不太长）。「包含名字」不再单独成立，
            // 否则「互动消息,XXX 在评论中提到了你」这种行会被误当成会话。
            boolean rowLabel = nt.startsWith(nn + ",") || nt.startsWith(nn + "，");
            boolean nameWidget = !nt.contains(",") && !nt.contains("，") && nt.length() <= 30;
            boolean accept = exact ? (sc == 0 || rowLabel) : (sc == 0 || rowLabel || nameWidget);
            if (!accept) continue;

            Rect r = rect(n);
            if (r.width() < 20 || r.height() < 20) continue;       // 太小的徽标/未读点
            if (r.height() > sh * 0.30) continue;                  // 太高，不是列表项
            int cy = r.centerY();
            if (cy < sh * 0.10 || cy > sh * 0.90) continue;        // 排除顶栏与底部导航

            // 往上找「整行」：优先可点击且够宽的祖先，其次够宽的祖先，都没有就用自己
            AccessibilityNodeInfo wide = null;
            AccessibilityNodeInfo cur = n;
            for (int i = 0; i < 6 && cur != null; i++) {
                Rect rr = rect(cur);
                if (rr.width() >= sw * 0.45 && rr.height() <= sh * 0.40) {
                    wide = cur;
                    if (cur.isClickable()) break;
                }
                try {
                    cur = cur.getParent();
                } catch (Throwable e) {
                    break;
                }
            }
            AccessibilityNodeInfo target = wide != null ? wide : n;
            Rect tr = rect(target);

            // 打分：匹配度 > 是否在「会话列表区」（真机实测消息页顶部还有一条
            // 「在线好友头像条」，同名的人会出现两次；优先点会话列表里那个，
            // 头像条点下去有可能进的是个人主页而不是聊天）
            // > 文字更短（名字节点优于「名字+预览」拼接） > 更靠上
            int len = norm(t).length();
            int region = cy > sh * 0.35 ? 0 : 1;
            boolean better = sc < bestScore
                    || (sc == bestScore && region < bestRegion)
                    || (sc == bestScore && region == bestRegion && len < bestLen)
                    || (sc == bestScore && region == bestRegion && len == bestLen
                        && bestR != null && tr.top < bestR.top);
            if (better) {
                bestScore = sc;
                bestRegion = region;
                bestLen = len;
                best = target;
                bestR = tr;
            }
        }
        return best;
    }

    static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n, int maxUp) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < maxUp && cur != null; i++) {
            try {
                if (cur.isClickable() && cur.isEnabled()) return cur;
                cur = cur.getParent();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * 聊天页的输入框：下半屏的可编辑节点，取最靠下的那个。
     *
     * 注意要排除「搜索相关表情包」——表情面板打开时那也是个输入框，
     * 往里写消息会写错地方。
     */
    static AccessibilityNodeInfo inputField(AccessibilityService s) {
        int sh = screenH(s);
        AccessibilityNodeInfo best = null;
        Rect bestR = null;
        for (AccessibilityNodeInfo n : collect(s, 1500)) {
            if (!n.isVisibleToUser()) continue;
            String cls = clsOf(n);
            String id = viewId(n);
            String t = textOf(n);
            boolean editable = n.isEditable()
                    || cls.contains("EditText")
                    || id.toLowerCase().contains("edit")
                    || id.toLowerCase().contains("input");
            if (!editable) continue;
            if (t.contains("搜索") || id.toLowerCase().contains("search")) continue;
            Rect r = rect(n);
            if (r.width() <= 0 || r.height() <= 0) continue;
            if (r.top < sh * 0.30) continue;
            if (best == null || r.top > bestR.top) {
                best = n;
                bestR = r;
            }
        }
        return best;
    }

    static String viewId(AccessibilityNodeInfo n) {
        try {
            String v = n.getViewIdResourceName();
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 表情面板是否打开（真机实测：面板底部有「搜索相关表情包」）。 */
    static boolean emojiPanelOpen(AccessibilityService s) {
        for (AccessibilityNodeInfo n : collect(s, 1500)) {
            String t = textOf(n);
            if (t.contains("搜索相关表情包") || t.contains("搜索表情")) return true;
        }
        return false;
    }

    /** 找到并聚焦输入框；输入框是自定义控件时，先点一下占位气泡再找。 */
    static AccessibilityNodeInfo focusInput(AccessibilityService s) {
        // 表情面板挡着输入框时，先回退一次把它收起来（返回键只收面板，不会退出聊天）
        if (emojiPanelOpen(s)) {
            back(s);
            sleep(700);
        }
        AccessibilityNodeInfo f = inputField(s);
        if (f != null) {
            click(s, f);
            sleep(400);
            AccessibilityNodeInfo again = inputField(s);
            return again != null ? again : f;
        }
        String[] hints = {"发送消息", "说点什么", "发消息", "输入"};
        for (String h : hints) {
            AccessibilityNodeInfo ph = find(s, h, false);
            if (ph != null) {
                click(s, ph);
                sleep(700);
                AccessibilityNodeInfo again = inputField(s);
                if (again != null) return again;
            }
        }
        return null;
    }

    /** 「发送」按钮：下半屏里文字/描述为「发送」的节点，取最靠下的。 */
    static AccessibilityNodeInfo findSendButton(AccessibilityService s) {
        int sh = screenH(s);
        AccessibilityNodeInfo best = null;
        Rect bestR = null;
        for (AccessibilityNodeInfo n : collect(s, 1500)) {
            if (!n.isVisibleToUser()) continue;
            String t = textOf(n);
            String d = descOf(n);
            boolean isSend = norm(t).equals("发送") || d.contains("发送");
            if (!isSend) continue;
            Rect r = rect(n);
            if (r.width() <= 0 || r.height() <= 0) continue;
            if (r.top < sh * 0.45) continue;
            if (best == null || r.top > bestR.top) {
                best = n;
                bestR = r;
            }
        }
        return best;
    }

    /** 最后的兜底：输入框同一行、位于它右侧的可点击控件。 */
    static AccessibilityNodeInfo findSendNearInput(AccessibilityService s) {
        AccessibilityNodeInfo f = inputField(s);
        if (f == null) return null;
        Rect fr = rect(f);
        AccessibilityNodeInfo row = f;
        for (int i = 0; i < 4 && row != null; i++) {
            Rect rr = rect(row);
            if (rr.width() > screenW(s) * 0.6) break;
            try {
                row = row.getParent();
            } catch (Throwable t) {
                row = null;
            }
        }
        if (row == null) return null;
        AccessibilityNodeInfo best = null;
        Rect bestR = null;
        for (AccessibilityNodeInfo n : flatten(row, 400)) {
            if (!n.isVisibleToUser()) continue;
            Rect r = rect(n);
            if (r.width() <= 0 || r.height() <= 0) continue;
            if (r.left < fr.right - 8) continue;
            if (r.centerY() < fr.top - 60 || r.centerY() > fr.bottom + 60) continue;
            if (best == null || r.left > bestR.left) {
                best = n;
                bestR = r;
            }
        }
        return best;
    }

    static List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo n, int limit) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(n);
        while (!q.isEmpty() && out.size() < limit) {
            AccessibilityNodeInfo x = q.poll();
            out.add(x);
            int cc;
            try {
                cc = x.getChildCount();
            } catch (Throwable t) {
                continue;
            }
            for (int i = 0; i < cc; i++) {
                try {
                    AccessibilityNodeInfo c = x.getChild(i);
                    if (c != null) q.add(c);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    static AccessibilityNodeInfo findScrollable(AccessibilityService s) {
        for (AccessibilityNodeInfo n : collect(s, 1500)) {
            if (n.isScrollable() && n.isVisibleToUser()) {
                Rect r = rect(n);
                if (r.width() > screenW(s) * 0.5 && r.height() > screenH(s) * 0.3) return n;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 动作

    static boolean click(AccessibilityService s, AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isClickable() && n.isEnabled()) {
            try {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            } catch (Throwable ignored) {
            }
        }
        AccessibilityNodeInfo p = clickableAncestor(n, 6);
        if (p != null) {
            try {
                if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            } catch (Throwable ignored) {
            }
        }
        int[] c = centerOf(p != null ? p : n);
        return c != null && tap(s, c[0], c[1]);
    }

    static boolean tap(AccessibilityService s, int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription st =
                    new GestureDescription.StrokeDescription(p, 0, 60);
            return s.dispatchGesture(new GestureDescription.Builder().addStroke(st).build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean swipe(AccessibilityService s, int x1, int y1, int x2, int y2, int ms) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription.StrokeDescription st =
                    new GestureDescription.StrokeDescription(p, 0, Math.max(50, ms));
            return s.dispatchGesture(new GestureDescription.Builder().addStroke(st).build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean setText(AccessibilityService s, AccessibilityNodeInfo field, String text) {
        if (trySetText(field, text)) return true;
        // 有时候拿到的节点不是真正的输入框，全局聚焦的那个才是
        try {
            AccessibilityNodeInfo r = root(s);
            if (r != null) {
                AccessibilityNodeInfo focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused != null && focused != field && trySetText(focused, text)) return true;
            }
        } catch (Throwable ignored) {
        }
        // 兜底：写剪贴板 + 粘贴
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    s.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("spark", text));
            sleep(200);
            AccessibilityNodeInfo target = field;
            try {
                AccessibilityNodeInfo r = root(s);
                if (r != null) {
                    AccessibilityNodeInfo focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused != null) target = focused;
                }
            } catch (Throwable ignored) {
            }
            if (target == null) return false;
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            sleep(200);
            return target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean trySetText(AccessibilityNodeInfo n, String text) {
        if (n == null) return false;
        try {
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        } catch (Throwable t) {
            return false;
        }
    }

    static void back(AccessibilityService s) {
        try {
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        } catch (Throwable ignored) {
        }
    }

    static void home(AccessibilityService s) {
        try {
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        } catch (Throwable ignored) {
        }
    }

    /** 上滑解锁（只对没有密码的锁屏有效）。 */
    static void swipeUp(AccessibilityService s) {
        int w = screenW(s), h = screenH(s);
        swipe(s, w / 2, (int) (h * 0.80), w / 2, (int) (h * 0.20), 220);
    }

    static boolean scrollList(AccessibilityService s) {
        AccessibilityNodeInfo sc = findScrollable(s);
        if (sc != null) {
            try {
                if (sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
            } catch (Throwable ignored) {
            }
        }
        return swipe(s, screenW(s) / 2, (int) (screenH(s) * 0.70),
                screenW(s) / 2, (int) (screenH(s) * 0.30), 300);
    }

    // ------------------------------------------------------------ 调试

    /** 把当前界面压缩成可读文本，出问题时能直接看出是哪一步对不上。 */
    static String dump(AccessibilityService s, int maxLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("包名=").append(currentPkg(s)).append('\n');
        int shown = 0;
        for (AccessibilityNodeInfo n : collect(s, 2000)) {
            if (shown >= maxLines) {
                sb.append("...(已截断)\n");
                break;
            }
            if (!n.isVisibleToUser()) continue;
            String t = textOf(n);
            String d = descOf(n);
            if (t.isEmpty() && d.isEmpty()) continue;
            Rect r = rect(n);
            if (r.width() <= 0 || r.height() <= 0) continue;
            String cls = clsOf(n);
            int dot = cls.lastIndexOf('.');
            if (dot >= 0) dot = 0;
            sb.append("  ").append(cls.substring(Math.max(0, cls.lastIndexOf('.') + 1)));
            sb.append(n.isClickable() ? "[可点]" : "");
            if (n.isEditable()) sb.append("[可输入]");
            sb.append(" \"").append(t.isEmpty() ? d : t).append('"');
            sb.append(" @").append(r.left).append(',').append(r.top)
                    .append('-').append(r.right).append(',').append(r.bottom);
            sb.append('\n');
            shown++;
        }
        if (shown == 0) sb.append("  (界面里没有可取文字的节点)\n");
        return sb.toString();
    }

    static long now() {
        return SystemClock.uptimeMillis();
    }
}
