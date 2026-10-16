package perf.sqlinsight;

import java.io.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.*;

/** Java 8 source/API surface. Real javax service + local JDBC; no agent classes on application CP. */
public final class H09FunctionalWorkload {
    private static Connection connection;
    private static int mode;
    private static final AtomicInteger calls = new AtomicInteger();
    private static final class WorkServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (mode == 2) return; // zero-SQL request must still close
            try {
                if (mode < 2) connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    calls.incrementAndGet();
                    if (mode == 0) statement.executeUpdate("INSERT INTO h09_rows VALUES (1)");
                    else if (mode == 1) statement.executeUpdate("INSERT INTO h09_rows VALUES (2)");
                    else statement.executeQuery("SELECT missing_column FROM h09_missing_table");
                }
                if (mode == 0) connection.commit();
                else if (mode == 1) connection.rollback();
            } catch (SQLException original) { throw new IOException("expected JDBC error", original); }
        }
    }
    public static void main(String[] args) throws Exception {
        BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        System.out.println("BOOT " + System.getProperty("java.version")); System.out.flush();
        expect(commands, "SETUP");
        Class.forName("org.h2.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:h09_functional")) {
            connection = c;
            try (Statement setup = c.createStatement()) { setup.execute("CREATE TABLE h09_rows(id INT PRIMARY KEY)"); }
            HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(H09FunctionalWorkload.class.getClassLoader(),
                new Class[]{HttpServletRequest.class}, (p,m,a) -> {
                    if (m.getName().equals("getMethod")) return "GET";
                    if (m.getName().equals("getProtocol")) return "HTTP/1.1";
                    return defaultValue(m.getReturnType());
                });
            HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(H09FunctionalWorkload.class.getClassLoader(),
                new Class[]{HttpServletResponse.class}, (p,m,a) -> defaultValue(m.getReturnType()));
            System.out.println("SETUP_READY"); System.out.flush(); expect(commands, "RUN");
            WorkServlet servlet = new WorkServlet();
            for (mode = 0; mode < 3; mode++) servlet.service(request, response);
            mode = 3;
            try { servlet.service(request, response); throw new AssertionError("expected exception"); }
            catch (IOException expected) { if (!(expected.getCause() instanceof SQLException)) throw expected; }
            // Same thread reused after exception: a fresh zero-SQL request cannot inherit the failed request.
            mode = 2; servlet.service(request, response);
            int rows;
            try (Statement check = c.createStatement(); ResultSet result = check.executeQuery("SELECT COUNT(*) FROM h09_rows")) {
                result.next(); rows = result.getInt(1);
            }
            if (rows != 1 || calls.get() != 3) throw new AssertionError("independent database/call count mismatch");
            System.out.println("RESULT {\"requests\":5,\"requestSqlCalls\":" + calls.get() + ",\"committedRows\":" + rows + "}");
            System.out.flush(); expect(commands, "EXIT");
        }
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return -1L;
        return null;
    }
    private static void expect(BufferedReader reader, String value) throws IOException {
        if (!value.equals(reader.readLine())) throw new IOException("expected " + value);
    }
}
