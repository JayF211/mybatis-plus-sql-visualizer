package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-ConsoleDbContext：数据源 URL → 默认库名解析的表驱动测试（纯逻辑，默认 test 任务可跑）。
 *
 * 产品定案（用户 20260917）：预览会话默认以「数据源 URL 里的库」为准。本解析是 B 项对齐的判据
 * 输入，错一个字符就会把会话切到不存在的库，故逐形态钉死。
 */
class JdbcUrlDatabaseTest {

    @Test
    fun testNameFromUrlTableDriven() {
        val cases = listOf(
            // 真实事故现场：demo 工程的两个数据源
            "jdbc:mysql://localhost:3306/jpa_demo?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" to "jpa_demo",
            "jdbc:mysql://localhost:3306/mpj_demo" to "mpj_demo",
            "jdbc:postgresql://host:5432/dbx_test" to "dbx_test",
            "jdbc:mysql://127.0.0.1:3306/a/b" to "a",
            "jdbc:sqlserver://host:1433;databaseName=dbx" to "dbx",
            "jdbc:sqlserver://host:1433;encrypt=true;databaseName=dbx" to "dbx",
            // 无库段 / 非 URL 形态 → null（调用方跳过对齐）
            "jdbc:mysql://localhost:3306" to null,
            "jdbc:mysql://localhost:3306/" to null,
            "jdbc:oracle:thin:@localhost:1521:ORCL" to null,
            "" to null,
            "   " to null,
            null to null,
        )
        cases.forEach { (url, expected) ->
            assertEquals("nameFromUrl($url)", expected, JdbcUrlDatabase.nameFromUrl(url))
        }
    }
}
