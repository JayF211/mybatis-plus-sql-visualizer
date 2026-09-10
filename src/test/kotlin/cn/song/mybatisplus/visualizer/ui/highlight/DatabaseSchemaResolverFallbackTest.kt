package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Community/无数据源降级路径测试（T-TestAssertReal 期间落库，保留部分）。
 *
 * light 环境没有配置任何数据源（Database 插件数据源服务缺席或为空），[DatabaseSchemaResolver.tableMeta]
 * 必须走既有降级模式（反射隔离 + 空结果回落）返回空 TableMeta——comment 为 null、列元数据为空，
 * 供 JOIN 富化/补全等调用方回落实体注释，不产出错误内容。
 *
 * 注：有数据源场景的 isNotNull 反射链依赖 DatabaseTools 运行时，单测环境不具备，
 * 按 T-Embed 系列先例走 RunIde 沙盒真机验证。
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
}
