package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * currentDialect 兜底契约单测（T-TestGuard-07 零覆盖补测：psi/PsiDialect 此前无任何测试）。
 *
 * 纯环境（无平台 Application / settings service 不可用）下必须兜底到默认方言，
 * 不得抛异常——批量模板与 raw 拼接的保留字引用全靠它。
 */
class PsiDialectTest {

    @Test
    fun `设置服务不可用时兜底默认方言`() {
        val fallback = DialectRegistry.byName(null)
        assertEquals(fallback, currentDialect())
    }
}
