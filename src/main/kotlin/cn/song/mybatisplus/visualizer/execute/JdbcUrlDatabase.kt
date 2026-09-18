package cn.song.mybatisplus.visualizer.execute

/**
 * T-ConsoleDbContext：从 JDBC URL 解析「数据源默认库」——预览会话的库上下文以此为准。
 *
 * 背景（真机 20260917 事故）：预览执行 `SELECT ... FROM user u WHERE u.username = …` 报
 * `[42S22][1054] Unknown column 'u.username' in 'field list'`，而同一 SQL 在原生控制台正常。
 * 根因=会话当前库与 SQL 所属库不一致——DBN 的「文件→数据源」映射把 exec 文件钉在
 * `mpj_demo@localhost` 上（`.idea/data_source_mapping.xml`，2026-09-11 写入），而该库的
 * `user` 表只有 id/name/age/sex/head_img（无 username），与报错逐字吻合；用户当前操作的
 * 是 `jpa_demo` 库。产品定案（用户 20260917）：**预览默认以「数据源 URL 里的库」为准**。
 *
 * 本对象为纯函数（无平台/DBN 依赖），可进默认 test 任务。
 */
object JdbcUrlDatabase {

    /**
     * 解析 JDBC URL 里的默认库名；无法判定时返回 null（调用方据此跳过对齐，保持原状）。
     *
     * 覆盖形态：
     *  - `jdbc:mysql://localhost:3306/jpa_demo?useUnicode=true` → `jpa_demo`
     *  - `jdbc:postgresql://host:5432/db` → `db`
     *  - `jdbc:sqlserver://host:1433;databaseName=dbx` → `dbx`（分号参数形态）
     *  - 无库段（`jdbc:mysql://host:3306`）/ 非 URL 形态（Oracle thin `@host:1521:SID`）→ null
     */
    fun nameFromUrl(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null

        // 分号参数形态（SQL Server 等）：databaseName=/databasename=
        run {
            val semi = raw.indexOf(';')
            if (semi >= 0) {
                raw.substring(semi + 1).split(';').forEach { kv ->
                    val eq = kv.indexOf('=')
                    if (eq > 0 && kv.substring(0, eq).trim().equals("databaseName", ignoreCase = true)) {
                        kv.substring(eq + 1).trim().takeIf { it.isNotEmpty() }?.let { return it }
                    }
                }
            }
        }

        val schemeIdx = raw.indexOf("://")
        if (schemeIdx < 0) return null // Oracle thin/SID 等形态不猜
        val afterScheme = raw.substring(schemeIdx + 3)
        val queryIdx = afterScheme.indexOf('?')
        val body = if (queryIdx >= 0) afterScheme.substring(0, queryIdx) else afterScheme
        val slash = body.indexOf('/')
        if (slash < 0) return null // 只有 host[:port]，未指定库
        val db = body.substring(slash + 1).substringBefore('/').trim()
        return db.takeIf { it.isNotEmpty() }
    }
}
