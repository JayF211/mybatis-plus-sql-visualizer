package perf.sqlinsight;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Arrays;
import javax.servlet.http.*;

/** Separate-process, fixed real JDBC workload. No agent APIs on the application classpath. */
public final class SqlInsightWorkload {
    private static final int SQL_PER_REQUEST = 20;
    private static final int WARM_REQUESTS = 200;
    private static final int MEASURE_REQUESTS = 500;
    private static final long[] SQL_NANOS = new long[MEASURE_REQUESTS * SQL_PER_REQUEST];
    private static int samples;
    private static boolean measured;
    private static long checksum;
    private static final java.util.concurrent.atomic.AtomicLong BUSINESS_SQL = new java.util.concurrent.atomic.AtomicLong();
    private static PreparedStatement select;
    private static final class WorkServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
            try {
                for (int i = 0; i < SQL_PER_REQUEST; i++) {
                    select.setInt(1, (i * 37) % 1000);
                    long start = System.nanoTime();
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) checksum += rows.getLong(1);
                    }
                    if (measured) {
                        BUSINESS_SQL.incrementAndGet();
                        SQL_NANOS[samples++] = System.nanoTime() - start;
                    }
                }
            } catch (SQLException failure) { throw new java.io.IOException(failure); }
        }
    }
    public static void main(String[] args) throws Exception {
        BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        System.out.println("BOOT_READY"); System.out.flush();
        if (!"SETUP".equals(commands.readLine())) throw new IllegalStateException("SETUP required");
        Class.forName("org.h2.Driver");
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:insight_perf")) {
            try (Statement setup = connection.createStatement()) {
                setup.execute("CREATE TABLE perf_rows(id INT PRIMARY KEY, amount BIGINT)");
                setup.execute("INSERT INTO perf_rows SELECT X, X*3 FROM SYSTEM_RANGE(0,999)");
            }
            select = connection.prepareStatement("SELECT SUM(amount) FROM perf_rows WHERE id >= ?");
            HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(SqlInsightWorkload.class.getClassLoader(),
                    new Class[]{HttpServletRequest.class}, (p,m,a) -> {
                        if (m.getName().equals("getMethod")) return "GET";
                        if (m.getName().equals("getProtocol")) return "HTTP/1.1";
                        if (m.getReturnType() == boolean.class) return false;
                        if (m.getReturnType() == int.class) return 0;
                        if (m.getReturnType() == long.class) return -1L;
                        return null;
                    });
            HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(SqlInsightWorkload.class.getClassLoader(),
                    new Class[]{HttpServletResponse.class}, (p,m,a) -> {
                        if (m.getReturnType() == boolean.class) return false;
                        if (m.getReturnType() == int.class) return 0;
                        return null;
                    });
            WorkServlet servlet = new WorkServlet();
            long[] warmBlocks = new long[4];
            for (int block = 0; block < 4; block++) {
                long start = System.nanoTime();
                for (int i = 0; i < WARM_REQUESTS / 4; i++) servlet.service(request, response);
                warmBlocks[block] = System.nanoTime() - start;
            }
            System.out.println("WARM_DATA " + Arrays.toString(warmBlocks));
            System.out.println("WARM_READY"); System.out.flush();
            if (!"MEASURE".equals(commands.readLine())) throw new IllegalStateException("MEASURE required");
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
            checksum = 0; measured = true;
            long[] requests = new long[MEASURE_REQUESTS];
            com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long cpu0 = os.getProcessCpuTime(), wall0 = System.nanoTime();
            for (int i = 0; i < MEASURE_REQUESTS; i++) {
                long start = System.nanoTime(); servlet.service(request, response);
                requests[i] = System.nanoTime() - start;
            }
            long wall = System.nanoTime() - wall0, cpu = os.getProcessCpuTime() - cpu0;
            long expected = 0;
            for (int i = 0; i < SQL_PER_REQUEST; i++) for (int row = (i * 37) % 1000; row < 1000; row++) expected += row * 3L;
            expected *= MEASURE_REQUESTS;
            if (BUSINESS_SQL.get() != 10_000 || checksum != expected) throw new AssertionError("business result mismatch");
            long peak = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
                if (pool.getType() == java.lang.management.MemoryType.HEAP) peak += pool.getPeakUsage().getUsed();
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            Arrays.sort(SQL_NANOS); Arrays.sort(requests);
            System.out.println("RESULT {\"requests\":" + MEASURE_REQUESTS + ",\"sqlCount\":" + BUSINESS_SQL.get()
                    + ",\"warmupSql\":" + (WARM_REQUESTS * SQL_PER_REQUEST) + ",\"checksum\":" + checksum
                    + ",\"wallNanos\":" + wall + ",\"cpuNanos\":" + cpu
                    + ",\"sqlP95Nanos\":" + SQL_NANOS[(samples * 95 + 99) / 100 - 1]
                    + ",\"requestP95Nanos\":" + requests[(requests.length * 95 + 99) / 100 - 1]
                    + ",\"heapUsedBytes\":" + used + ",\"heapPoolPeakSumBytes\":" + peak + "}");
            System.out.flush();
            if (!"EXIT".equals(commands.readLine())) throw new IllegalStateException("EXIT required");
            select.close();
        }
    }
}
