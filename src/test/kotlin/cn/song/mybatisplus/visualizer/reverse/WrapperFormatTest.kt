package cn.song.mybatisplus.visualizer.reverse

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wrapper 形态分发纯函数单测（T-M36.3）：配置 + 是否有实体 → 最终生成形态。
 */
class WrapperFormatTest {

    @Test
    fun `AUTO_有实体Lambda无实体字符串`() {
        assertEquals(GeneratedWrapper.LAMBDA, resolveWrapperFormat(ReverseWrapperFormat.AUTO, hasEntity = true))
        assertEquals(GeneratedWrapper.QUERY, resolveWrapperFormat(ReverseWrapperFormat.AUTO, hasEntity = false))
    }

    @Test
    fun `QUERY_恒字符串`() {
        assertEquals(GeneratedWrapper.QUERY, resolveWrapperFormat(ReverseWrapperFormat.QUERY, hasEntity = true))
        assertEquals(GeneratedWrapper.QUERY, resolveWrapperFormat(ReverseWrapperFormat.QUERY, hasEntity = false))
    }

    @Test
    fun `LAMBDA_无实体回落字符串`() {
        assertEquals(GeneratedWrapper.LAMBDA, resolveWrapperFormat(ReverseWrapperFormat.LAMBDA, hasEntity = true))
        assertEquals(GeneratedWrapper.QUERY, resolveWrapperFormat(ReverseWrapperFormat.LAMBDA, hasEntity = false))
    }

    @Test
    fun `MPJ_LAMBDA_有实体MPJ无实体回落字符串`() {
        assertEquals(GeneratedWrapper.MPJ_LAMBDA, resolveWrapperFormat(ReverseWrapperFormat.MPJ_LAMBDA, hasEntity = true))
        assertEquals(GeneratedWrapper.QUERY, resolveWrapperFormat(ReverseWrapperFormat.MPJ_LAMBDA, hasEntity = false))
    }

    @Test
    fun `WRAPPERS_有实体lambdaQuery工厂无实体query工厂`() {
        assertEquals(GeneratedWrapper.WRAPPERS_LAMBDA, resolveWrapperFormat(ReverseWrapperFormat.WRAPPERS, hasEntity = true))
        assertEquals(GeneratedWrapper.WRAPPERS_QUERY, resolveWrapperFormat(ReverseWrapperFormat.WRAPPERS, hasEntity = false))
    }

    @Test
    fun `持久化值解析_未知值回落AUTO`() {
        assertEquals(ReverseWrapperFormat.AUTO, ReverseWrapperFormat.fromPersisted(null))
        assertEquals(ReverseWrapperFormat.AUTO, ReverseWrapperFormat.fromPersisted("UNKNOWN"))
        assertEquals(ReverseWrapperFormat.MPJ_LAMBDA, ReverseWrapperFormat.fromPersisted("MPJ_LAMBDA"))
        assertEquals(ReverseWrapperFormat.WRAPPERS, ReverseWrapperFormat.fromPersisted("WRAPPERS"))
    }
}
