import com.dsha.spark.Task;
import com.dsha.spark.Scheduler;

import java.util.Calendar;

/** 在 JVM 上跑纯逻辑：JSON 往返、消息池随机、定时时刻推算。 */
public class JvmTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  -> " + detail);
        }
    }

    public static void main(String[] a) throws Exception {
        System.out.println("== Task JSON ==");
        Task t = new Task();
        t.label = "和妈妈";
        t.contact = "妈妈";
        t.message = "在吗|打卡|早";
        t.hour = 7;
        t.minute = 5;
        t.exactMatch = true;
        t.enabled = false;
        t.lastDay = "2025-09-17";
        t.lastResult = "成功";
        t.lastRunAt = 1234567L;

        Task back = Task.fromJson(t.toJson());
        check("id 保持", back.id.equals(t.id), back.id);
        check("联系人保持", "妈妈".equals(back.contact), back.contact);
        check("时间保持", back.hour == 7 && back.minute == 5, back.hour + ":" + back.minute);
        check("开关保持", !back.enabled && back.exactMatch, back.enabled + "/" + back.exactMatch);
        check("lastDay 保持", "2025-09-17".equals(back.lastDay), back.lastDay);
        check("lastRunAt 保持", back.lastRunAt == 1234567L, "" + back.lastRunAt);
        check("时间文本", "07:05".equals(back.timeText()), back.timeText());
        check("标题含名字", back.title().contains("妈妈") && back.title().startsWith("07:05"), back.title());

        System.out.println("== 消息池 ==");
        check("池大小", t.messagePool().length == 3, t.messagePool().length + "");
        boolean allInPool = true;
        for (int i = 0; i < 300; i++) {
            String m = t.pickMessage();
            if (!"在吗".equals(m) && !"打卡".equals(m) && !"早".equals(m)) allInPool = false;
        }
        check("随机结果都在池里", allInPool, "有越界结果");
        Task single = new Task();
        single.message = "只有一条";
        check("单条消息原样返回", "只有一条".equals(single.pickMessage()), single.pickMessage());
        Task blank = new Task();
        blank.message = "";
        check("空消息返回空", blank.pickMessage().isEmpty(), blank.pickMessage());
        Task spaces = new Task();
        spaces.message = " a |  | b ";
        check("池里忽略空片段", spaces.messagePool() != null && spaces.messagePool().length >= 2, "");

        System.out.println("== 定时推算 ==");
        long now = System.currentTimeMillis();
        long next = Scheduler.nextTrigger(9, 0);
        check("下一次触发在未来", next > now, next - now + "ms");
        check("下一次触发不超过 25 小时", next - now <= 25L * 3600 * 1000, (next - now) / 3600000.0 + "h");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(next);
        check("触发时刻是 09:00", c.get(Calendar.HOUR_OF_DAY) == 9 && c.get(Calendar.MINUTE) == 0,
                c.get(Calendar.HOUR_OF_DAY) + ":" + c.get(Calendar.MINUTE));
        check("秒/毫秒归零", c.get(Calendar.SECOND) == 0 && c.get(Calendar.MILLISECOND) == 0, "");

        long today = Scheduler.todayTrigger(t);
        Calendar c2 = Calendar.getInstance();
        c2.setTimeInMillis(today);
        check("todayTrigger 是 07:05", c2.get(Calendar.HOUR_OF_DAY) == 7 && c2.get(Calendar.MINUTE) == 5,
                c2.get(Calendar.HOUR_OF_DAY) + ":" + c2.get(Calendar.MINUTE));
        check("两小时后一定算「已过点」", now - Scheduler.todayTrigger(fixedTask(now, -2)) > 0, "");

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 造一个「现在是几点就设成几小时前」的任务，用来验证补跑窗口判定。 */
    static Task fixedTask(long now, int hourOffset) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.HOUR_OF_DAY, hourOffset);
        Task t = new Task();
        t.hour = c.get(Calendar.HOUR_OF_DAY);
        t.minute = c.get(Calendar.MINUTE);
        return t;
    }
}
