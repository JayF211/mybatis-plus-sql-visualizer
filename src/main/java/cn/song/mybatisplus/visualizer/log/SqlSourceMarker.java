package cn.song.mybatisplus.visualizer.log;

import java.util.regex.Pattern;

/**
 * 实时日志源码标记（F-44-6 可靠跳转）：由运行期 SqlSourceInterceptor（MyBatis-Plus InnerInterceptor）
 * 在 SQL 执行线程捕获真实调用位置后，向控制台打印一行标记；SqlLogConsoleBridge 解析后写入
 * {@link LogSqlEntry#sourceFile()} / {@link LogSqlEntry#sourceLine()}，使实时日志获得与历史面板一致的精确 file:line 跳转。
 *
 * 标记行格式（单行，以 {@code |} 分隔，前缀固定；M56 T-M56-00 起宽松化支持 3~5 段，缺段为 null）：
 * <pre>
 * SQLVIS-SOURCE|&lt;相对路径&gt;|&lt;行号&gt;                                          （3 段，旧版拦截器）
 * SQLVIS-SOURCE|&lt;相对路径&gt;|&lt;行号&gt;|&lt;mapper 语句 id&gt;                          （4 段）
 * SQLVIS-SOURCE|&lt;相对路径&gt;|&lt;行号&gt;|&lt;mapper 语句 id&gt;|&lt;请求标签&gt;                （5 段）
 * </pre>
 * 3 段为既有格式（存量注入拦截器幂等不升级）；第 4 段 mapper 语句 id、第 5 段请求级聚合标签
 * 由新版拦截器模板选择性附加（第 5 段仅在 4 段在场时可辨，缺失段一律 null 而非空串）。
 * 该格式亦在 {@code docs/instrumentation/SqlSourceInterceptor.kt} 的拦截器示例中被打印，桥与此示例须保持一致。
 */
public final class SqlSourceMarker {

    public static final SqlSourceMarker INSTANCE = new SqlSourceMarker();

    private static final String PREFIX = "SQLVIS-SOURCE|";

    /** 用户可见「3~5 段」含前缀 token；去掉前缀后的字段段为 2~4。 */
    private static final int MIN_SEGMENTS = 2;
    private static final int MAX_SEGMENTS = 4;

    private SqlSourceMarker() {
    }

    /** 生成标记行（3 段形态，供拦截器打印到 stdout）。 */
    public static String format(String relativePath, int line) {
        return PREFIX + relativePath + "|" + line;
    }

    /**
     * 生成标记行（3~5 段形态）：mapperStatement / requestTag 为 null 时对应段省略；
     * requestTag 仅在 mapperStatement 非 null 时输出（段位固定，缺失段不可跳位）。
     */
    public static String format(String relativePath, int line, String mapperStatement, String requestTag) {
        StringBuilder sb = new StringBuilder(format(relativePath, line));
        if (mapperStatement != null) {
            sb.append('|').append(mapperStatement);
            if (requestTag != null) {
                sb.append('|').append(requestTag);
            }
        }
        return sb.toString();
    }

    /**
     * 解析标记行；非标记行返回 null（桥据此区分 SQL 行与标记行，互不干扰）。
     * 支持 3~5 段（SourceMarkerData.mapperStatement / requestTag 缺段 null）。
     * 段位固定不可跳位：第 5 段仅在第 4 段为有效内容时读取；空白段视同缺段。
     * 容错：路径为空 / 行号非数字 / 前缀不符 / 段数不足 3 或超过 5 均返回 null。
     */
    public static SourceMarkerData parse(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith(PREFIX)) {
            return null;
        }
        String[] segs = trimmed.substring(PREFIX.length()).split("\\|", -1);
        if (segs.length < MIN_SEGMENTS || segs.length > MAX_SEGMENTS) {
            return null;
        }
        String path = segs[0];
        if (path.isBlank()) {
            return null;
        }
        Integer ln = toIntOrNull(segs[1]);
        if (ln == null) {
            return null;
        }
        String mapperStatement = segs.length > 2 && !segs[2].isBlank() ? segs[2] : null;
        String requestTag = mapperStatement != null && segs.length > 3 && !segs[3].isBlank() ? segs[3] : null;
        return new SourceMarkerData(path, ln, mapperStatement, requestTag);
    }

    /**
     * T-IntrUpg-01：标记路径是否指向拦截器模板文件本身（自位污染形态）。
     *
     * 旧模板 isSelf 用生成期包名常量，用户拷贝/改包后常量与运行类分叉 → 自跳过失效，
     * 拦截器把 locateSource 自身帧上报为调用点。该形态路径文件名恒为模板注入文件名
     * {@link SourceInterceptorTemplates#INTERCEPTOR_FILE_NAME}（包式/项目根式/裸文件名均可识别，
     * 与 {@link SourceMarkers#MP_PAGINATION_COUNT} 特殊标记互不干扰——后者不含 .java）。
     * 误伤面 = 用户业务类恰名同名文件（模板文件名常量决定，可忽略级；误伤也只丢位置不丢条目）。
     */
    public static boolean isInterceptorSelfPath(String relativePath) {
        String normalized = EntrySourceMatcher.normalize(relativePath);
        String fileName = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;
        return fileName.equals(SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME);
    }

    private static Integer toIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
