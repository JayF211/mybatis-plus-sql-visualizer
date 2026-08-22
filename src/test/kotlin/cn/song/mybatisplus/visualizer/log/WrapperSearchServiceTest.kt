package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wrapper 搜索服务单元测试（O-1 Phase A）：测试表名匹配纯逻辑。
 *
 * ReferencesSearch 部分需真实项目环境（集成测试覆盖），本测试聚焦纯函数 [WrapperSearchService.matchesTable]。
 */
class WrapperSearchServiceTest {

    @Test
    fun `精确表名匹配返回 true`() {
        val table = TableInfo(entityClassName = "User", tableName = "user", columns = emptyList())
        assertTrue(WrapperSearchService.matchesTable(table, "user"))
    }

    @Test
    fun `大小写不敏感匹配`() {
        val table = TableInfo(entityClassName = "User", tableName = "user_info", columns = emptyList())
        assertTrue(WrapperSearchService.matchesTable(table, "USER_INFO"))
    }

    @Test
    fun `表名不匹配返回 false`() {
        val table = TableInfo(entityClassName = "User", tableName = "user", columns = emptyList())
        assertFalse(WrapperSearchService.matchesTable(table, "order_info"))
    }

    @Test
    fun `null 表信息返回 false`() {
        assertFalse(WrapperSearchService.matchesTable(null, "user"))
    }

    @Test
    fun `空表名返回 false`() {
        val table = TableInfo(entityClassName = "User", tableName = "user", columns = emptyList())
        assertFalse(WrapperSearchService.matchesTable(table, ""))
    }

    @Test
    fun `空目标表名返回 false`() {
        val table = TableInfo(entityClassName = "User", tableName = "user", columns = emptyList())
        assertFalse(WrapperSearchService.matchesTable(table, ""))
    }
}
