package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.testgen.TestAssetGenerator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-TestAssertReal：Community/无数据源降级路径测试。
 *
 * light 环境没有配置任何数据源（Database 插件数据源服务缺席或为空），[DatabaseSchemaResolver.tableMeta]
 * 必须走既有降级模式（反射隔离 + 空结果回落）返回空 TableMeta——NOT NULL 列清单为空，
 * 测试资产生成器随之落 A 档（TODO 占位），不产出错误内容（计划 §2.4 新增测试行）。
 *
 * 注：有数据源场景的 isNotNull 反射链依赖 DatabaseTools 运行时，单测环境不具备，
 * 按 T-Embed 系列先例走 RunIde 沙盒真机验证（计划 §2.4 测试设计说明）。
 */
class DatabaseSchemaResolverFallbackTest : BasePlatformTestCase() {

    fun testTableMetaReturnsEmptyMetaWithoutDatabaseEnvironment() {
        val meta = DatabaseSchemaResolver.tableMeta(project, "users")
        assertEquals("无数据源环境表注释必须为 null（调用方回落实体注释）", null, meta.comment)
        assertTrue("无数据源环境列元数据必须为空", meta.columns.isEmpty())
    }

    fun testBlankTableNameShortCircuitsToEmptyMeta() {
        val meta = DatabaseSchemaResolver.tableMeta(project, "  ")
        assertEquals(null, meta.comment)
        assertTrue(meta.columns.isEmpty())
    }

    fun testEmptyMetaDrivesAGradeTestAsset() {
        val meta = DatabaseSchemaResolver.tableMeta(project, "users")
        val notNullColumns = meta.columns.filterValues { it.notNull == true }.keys.toList()
        val asset = TestAssetGenerator.sqlScript(
            TestAssetGenerator.Input(
                sql = "SELECT * FROM users WHERE id > 1",
                dialectName = "MySQL",
                tableName = "users",
                notNullColumns = notNullColumns,
            ),
            "20260906-000000",
        )
        assertTrue("降级路径必须落 A 档断言占位", asset.content.contains("-- TODO: 补充前置数据后执行断言\nSELECT COUNT(*) AS matched FROM users;"))
        assertFalse("A 档不得出现 B 档清单注释", asset.content.contains("前置数据须覆盖 NOT NULL 列"))
        assertFalse("A 档不得出现 count 前后对比占位", asset.content.contains("total_before"))
    }
}
