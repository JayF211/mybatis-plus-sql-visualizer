package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;

import java.util.regex.Pattern;

/**
 * 实时日志源码标记（F-44-6 可靠跳转）：由运行期 SqlSourceInterceptor（MyBatis-Plus InnerInterceptor）
 * 在 SQL 执行线程捕获真实调用位置后，向控制台打印一行标记；SqlLogConsoleBridge 解析后写入
 * {@link LogSqlEntry#sourceFile()} / {@link LogSqlEntry#sourceLine()}，使实时日志获得与历史面板一致的精确 file:line 跳转。
 *
 * 标记行格式（单行，以 `|` 分隔，前缀固定）：
 * ```
 * SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|42
 * ```
 * 相对路径基于标准 Maven/Gradle 源码布局（包名 + 源文件名）。该格式亦在
 * `docs/instrumentation/SqlSourceInterceptor.kt` 的拦截器示例中被打印，桥与此示例须保持一致。
 */
public final class SqlSourceMarker {

    public static final SqlSourceMarker INSTANCE = new SqlSourceMarker();

    private static final String PREFIX = "SQLVIS-SOURCE|";

    private SqlSourceMarker() {
    }

    /** 生成标记行（供拦截器打印到 stdout）。 */
    public static String format(String relativePath, int line) {
        return PREFIX + relativePath + "|" + line;
    }

    private static final Pattern RE = Pattern.compile("^SQLVIS-SOURCE\\|(.+?)\\|(\\d+)$");

    /**
     * 解析标记行；非标记行返回 null（桥据此区分 SQL 行与标记行，互不干扰）。
     * 容错：路径为空 / 行号非数字 / 前缀不符均返回 null。
     */
    public static Pair<String, Integer> parse(String line) {
        java.util.regex.Matcher m = RE.matcher(line.trim());
        if (!m.matches()) {
            return null;
        }
        String path = m.group(1);
        if (path.isBlank()) {
            return null;
        }
        Integer ln = toIntOrNull(m.group(2));
        if (ln == null) {
            return null;
        }
        return new Pair<>(path, ln);
    }

    private static Integer toIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
