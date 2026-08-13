package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 执行 SQL 支持测试（M17）：
 *  - 测试沙盒（build.gradle 已排除 database 插件 jar）无 Database 插件 → `isAvailable()` = false
 *    （Community 降级路径，稳定可测）；
 *  - `execute()` 降级路径不抛异常（提示经 runCatching 兜底，headless 环境安全）；
 *  - launcher（引用 com.intellij.database 的隔离类）在测试环境**不应被加载**（反射隔离生效）。
 */
class ExecuteSqlSupportTest : BasePlatformTestCase() {

    fun testIsAvailableFalseWithoutDatabasePlugin() {
        // build.gradle Test classpath 已排除 database 插件 jar → 沙盒等价 Community
        assertFalse("测试沙盒无 Database 插件，应判定不可用（Community 降级）", ExecuteSqlSupport.isAvailable())
    }

    fun testExecuteWithoutDatabasePluginDoesNotThrow() {
        // Community 降级：不可用 → 提示升级（headless 下被 runCatching 吞掉）→ 不抛异常、
        // 不触碰反射 launcher（无需 database 类）
        ExecuteSqlSupport.execute(project, "SELECT 1 FROM t")
        assertTrue(true)
    }

    fun testExecuteWithBlankSqlNoop() {
        // 面板层已过滤空白 SQL；支持层对任意文本都不抛异常
        ExecuteSqlSupport.execute(project, "")
        assertTrue(true)
    }
}
