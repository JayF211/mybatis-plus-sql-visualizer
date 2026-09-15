package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 元数据失效器测试（T-DdlCacheInvalidate / T-DC-02，表驱动）：
 * DDL 正例/反例、提交关键字、解析失败 fail closed、多语句任一命中、失效编排（登记动作
 * 命中/不命中/异常不吞）。普通 JUnit——识别器仅 JSqlParser + SqlCommentStripper。
 */
class MetadataInvalidatorTest {

    private fun mayChange(sql: String) = MetadataInvalidator.mayChangeMetadata(sql)

    // ---------- DDL 正例 ----------

    @Test
    fun `DDL正例_建删改表索引视图`() {
        assertTrue(mayChange("CREATE TABLE t (id INT)"))
        assertTrue(mayChange("ALTER TABLE t ADD COLUMN c INT"))
        assertTrue(mayChange("DROP TABLE t"))
        assertTrue(mayChange("TRUNCATE TABLE t"))
        assertTrue(mayChange("CREATE INDEX idx ON t(c)"))
        assertTrue(mayChange("DROP VIEW v"))
        assertTrue(mayChange("RENAME TABLE t TO t2"))
        assertTrue(mayChange("CREATE OR REPLACE VIEW v AS SELECT 1"))
    }

    @Test
    fun `小写DDL同样命中`() {
        assertTrue(mayChange("alter table t add column c int"))
        assertTrue(mayChange("drop table if exists t"))
    }

    // ---------- 提交关键字（DBX object_cache.rs:41 对齐） ----------

    @Test
    fun `提交关键字命中`() {
        assertTrue(mayChange("COMMIT"))
        assertTrue(mayChange("commit work"))
        assertTrue(mayChange("ROLLBACK"))
        assertTrue("END（PL/SQL 块尾）解析失败或泛型形态 → 关键字路兜底", mayChange("END"))
        assertTrue(mayChange("ABORT"))
    }

    // ---------- 反例（DML/DQL 不改元数据） ----------

    @Test
    fun `DML与查询不命中`() {
        assertFalse(mayChange("SELECT * FROM t"))
        assertFalse(mayChange("INSERT INTO t (a) VALUES (1)"))
        assertFalse(mayChange("UPDATE t SET a = 1 WHERE id = 1"))
        assertFalse(mayChange("DELETE FROM t WHERE id = 1"))
        assertFalse(mayChange("SELECT 'DROP TABLE' AS s FROM t")) // 字符串内关键字不误伤
    }

    @Test
    fun `注释内DDL不误伤`() {
        assertFalse(mayChange("SELECT 1 -- DROP TABLE t"))
        assertFalse(mayChange("/* ALTER TABLE t */ SELECT 1"))
    }

    // ---------- 解析失败 fail closed ----------

    @Test
    fun `解析失败按可能改变处理`() {
        assertTrue("残缺语句 fail closed", mayChange("CREATE TABLE"))
        assertTrue("方言形态解析不出 fail closed", mayChange("BEGIN UPDATE t SET a=1; END"))
    }

    // ---------- 多语句任一命中 ----------

    @Test
    fun `多语句任一DDL命中即失效`() {
        assertTrue(mayChange("SELECT 1; ALTER TABLE t ADD COLUMN c INT"))
        assertTrue(mayChange("INSERT INTO t (a) VALUES (1); COMMIT"))
        assertFalse(mayChange("SELECT 1; INSERT INTO t (a) VALUES (2); DELETE FROM t WHERE id = 1"))
    }

    @Test
    fun `空与空白不命中`() {
        assertFalse(mayChange(""))
        assertFalse(mayChange("   "))
        assertFalse(mayChange("-- 只有注释"))
    }

    // ---------- 失效编排（登记动作/异常不吞） ----------

    @Test
    fun `失效编排_DDL触发登记动作`() {
        var invoked = false
        MetadataInvalidator.register("test.probe") { invoked = true }
        try {
            MetadataInvalidator.invalidateAfterExecution("ALTER TABLE t ADD COLUMN c INT")
            assertTrue("DDL 执行 → 登记动作被调用", invoked)
            MetadataInvalidator.invalidateAfterExecution("SELECT 1")
            // SELECT 不触发（invoked 已 true 无法区分——再置 false 验证）
            invoked = false
            MetadataInvalidator.invalidateAfterExecution("SELECT * FROM t WHERE a = 1")
            assertFalse("非 DDL 不触发失效动作", invoked)
        } finally {
            MetadataInvalidator.unregister("test.probe")
        }
    }

    @Test
    fun `失效编排_动作异常不外抛`() {
        MetadataInvalidator.register("test.throwing") { throw IllegalStateException("boom") }
        try {
            // 异常动作被吞（只记日志），不影响执行链也不影响后续动作
            var second = false
            MetadataInvalidator.register("test.second") { second = true }
            MetadataInvalidator.invalidateAfterExecution("DROP TABLE t")
            assertTrue("前序动作异常不阻断后续动作", second)
        } finally {
            MetadataInvalidator.unregister("test.throwing")
            MetadataInvalidator.unregister("test.second")
        }
    }

    @Test
    fun `内置登记_DatabaseSchemaResolver失效入口在册`() {
        // 内置登记不抛异常（Database 插件反射环境缺失时 invalidateAll 仍可安全执行）
        MetadataInvalidator.invalidateAfterExecution("CREATE TABLE probe (id INT)")
    }

    @Test
    fun `DatabaseSchemaResolver失效幂等安全`() {
        // invalidateAll 幂等且无副作用（无 database 插件环境=空缓存清理；headless 下
        // columnComments 经 isDatabaseAvailable 探测短路返回空 Map——真机走查覆盖真实链路）
        cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.invalidateAll()
        cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.invalidateAll()
    }
}
