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
                if (rawStatement instanceof java.sql.Statement) {
                    return nonNegative(((java.sql.Statement) rawStatement).getUpdateCount());
                }
                Method m = UPDATE_COUNT_METHOD.get();
                if (m == null || !m.getDeclaringClass().isInstance(rawStatement)) {
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

    static void applyResult(cn.song.mybatisplus.sqlvis.agent.model.SqlEvent event,
                            String op, Object result, Throwable failure) {
        event.resultStatus = failure == null ? "SUCCESS" : "ERROR";
        event.errorClass = failure == null ? null : failure.getClass().getName();
        event.sqlState = failure instanceof java.sql.SQLException
                ? ((java.sql.SQLException) failure).getSQLState() : null;
        event.updateCountStatus = event.updateCount == null ? "UNKNOWN" : "KNOWN";
        if (failure != null) {
            // A stale getUpdateCount on a failed call is not evidence for this execution.
            event.updateCount = null;
            event.updateCountStatus = "UNKNOWN";
        }
        Object batch = result;
        if (failure instanceof java.sql.BatchUpdateException) {
            batch = ((java.sql.BatchUpdateException) failure).getLargeUpdateCounts();
        }
        if (batch instanceof int[] || batch instanceof long[]) {
            boolean partial = failure != null;
            int known = 0;
            long sum = 0;
            int length = batch instanceof int[] ? ((int[]) batch).length : ((long[]) batch).length;
            for (int i = 0; i < length; i++) {
                long value = batch instanceof int[] ? ((int[]) batch)[i] : ((long[]) batch)[i];
                if (value < 0) { partial = true; continue; }
                if (Long.MAX_VALUE - sum < value) { partial = true; continue; }
                known++;
                sum += value;
            }
            event.updateCount = known == 0 && partial ? null : sum;
            event.updateCountStatus = event.updateCount == null ? "UNKNOWN" : partial ? "PARTIAL" : "KNOWN";
        }
    }

    private EventBuilder() {
    }
}
