package cn.song.mybatisplus.sqlvis.agent.hook;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * execute 族判定与影响行数提取（§4.2 #5：getUpdateCount 直取）。
 */
final class EventBuilder {

    private static final AtomicReference<Method> UPDATE_COUNT_METHOD = new AtomicReference<Method>();

    static boolean isExecuteFamily(String name) {
        return "execute".equals(name) || "executeQuery".equals(name) || "executeUpdate".equals(name)
                || "executeLargeUpdate".equals(name) || "executeBatch".equals(name)
                || "executeLargeBatch".equals(name);
    }

    /**
     * 影响行数：executeUpdate/executeLargeUpdate 取返回值；execute(boolean) 为 **false** 时
     * 反射 raw.getUpdateCount()（缓存 Method）；batch 求和（SUCCESS_NO_INFO=-2 跳过）；查询 null。
     *
     * <p>JDBC 语义：execute() 返回 true = 首结果为 ResultSet（此时 getUpdateCount() 恒为 -1，
     * 无意义）；返回 false = 首结果为更新计数（此时 getUpdateCount() 才是真实行数）。
     * 旧实现条件写成 TRUE 故 SELECT 恒产出 -1 并显示为「(-1 rows)」——本方法同时兜底负值。
     */
    static Long extractUpdateCount(String op, Object result, Object rawStatement) {
        if ("executeUpdate".equals(op) && result instanceof Integer) {
            return nonNegative(((Integer) result).longValue());
        }
        if ("executeLargeUpdate".equals(op) && result instanceof Long) {
            return nonNegative((Long) result);
        }
        if ("executeBatch".equals(op) && result instanceof int[]) {
            return sumBatch((int[]) result);
        }
        if ("executeLargeBatch".equals(op) && result instanceof long[]) {
            long sum = 0;
            for (long v : (long[]) result) {
                if (v >= 0) {
                    sum += v;
                }
            }
            return sum;
        }
        if ("execute".equals(op) && Boolean.FALSE.equals(result)) {
            try {
                Method m = UPDATE_COUNT_METHOD.get();
                if (m == null) {
                    m = rawStatement.getClass().getMethod("getUpdateCount");
                    UPDATE_COUNT_METHOD.set(m);
                }
                return nonNegative(((Integer) m.invoke(rawStatement)).longValue());
            } catch (Throwable ignore) {
                return null;
            }
        }
        return null;
    }

    /** 负值 = 无有效计数（JDBC getUpdateCount 的 -1 语义）→ null，不上报垃圾值。 */
    private static Long nonNegative(long v) {
        return v >= 0 ? v : null;
    }

    private static Long sumBatch(int[] arr) {
        long sum = 0;
        for (int v : arr) {
            if (v >= 0) {
                sum += v;
            }
        }
        return sum;
    }

    private EventBuilder() {
    }
}
