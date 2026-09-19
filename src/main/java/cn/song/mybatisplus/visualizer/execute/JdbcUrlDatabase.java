// T-SyncK2JMaster309 自 execute/JdbcUrlDatabase.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.Nullable;

/**
 * T-ConsoleDbContext：从 JDBC URL 解析「数据源默认库」——预览会话的库上下文以此为准。
 *
 * <p>背景（真机 20260917 事故）：预览执行 {@code SELECT ... FROM user u WHERE u.username = …} 报
 * {@code [42S22][1054] Unknown column 'u.username' in 'field list'}，而同一 SQL 在原生控制台正常。
 * 根因=会话当前库与 SQL 所属库不一致——DBN 的「文件→数据源」映射把 exec 文件钉在
 * {@code mpj_demo@localhost} 上（{@code .idea/data_source_mapping.xml}，2026-09-11 写入），而该库的
 * {@code user} 表只有 id/name/age/sex/head_img（无 username），与报错逐字吻合；用户当前操作的
 * 是 {@code jpa_demo} 库。产品定案（用户 20260917）：<b>预览默认以「数据源 URL 里的库」为准</b>。</p>
 *
 * <p>本对象为纯函数（无平台/DBN 依赖），可进默认 test 任务。</p>
 */
public final class JdbcUrlDatabase {

    public static final JdbcUrlDatabase INSTANCE = new JdbcUrlDatabase();
    private JdbcUrlDatabase() {}

    /**
     * 解析 JDBC URL 里的默认库名；无法判定时返回 null（调用方据此跳过对齐，保持原状）。
     *
     * <p>覆盖形态：</p>
     * <ul>
     *  <li>{@code jdbc:mysql://localhost:3306/jpa_demo?useUnicode=true} → {@code jpa_demo}</li>
     *  <li>{@code jdbc:postgresql://host:5432/db} → {@code db}</li>
     *  <li>{@code jdbc:sqlserver://host:1433;databaseName=dbx} → {@code dbx}（分号参数形态）</li>
     *  <li>无库段（{@code jdbc:mysql://host:3306}）/ 非 URL 形态（Oracle thin {@code @host:1521:SID}）→ null</li>
     * </ul>
     */
    @Nullable
    public static String nameFromUrl(@Nullable String url) {
        String raw = url != null ? url.trim() : "";
        if (raw.isEmpty()) return null;

        // 分号参数形态（SQL Server 等）：databaseName=/databasename=
        {
            int semi = raw.indexOf(';');
            if (semi >= 0) {
                for (String kv : raw.substring(semi + 1).split(";", -1)) {
                    int eq = kv.indexOf('=');
                    if (eq > 0 && kv.substring(0, eq).trim().equalsIgnoreCase("databaseName")) {
                        String v = kv.substring(eq + 1).trim();
                        if (!v.isEmpty()) return v;
                    }
                }
            }
        }

        int schemeIdx = raw.indexOf("://");
        if (schemeIdx < 0) return null; // Oracle thin/SID 等形态不猜
        String afterScheme = raw.substring(schemeIdx + 3);
        int queryIdx = afterScheme.indexOf('?');
        String body = queryIdx >= 0 ? afterScheme.substring(0, queryIdx) : afterScheme;
        int slash = body.indexOf('/');
        if (slash < 0) return null; // 只有 host[:port]，未指定库
        String afterSlash = body.substring(slash + 1);
        int nextSlash = afterSlash.indexOf('/');
        String db = (nextSlash >= 0 ? afterSlash.substring(0, nextSlash) : afterSlash).trim();
        return db.isEmpty() ? null : db;
    }
}
