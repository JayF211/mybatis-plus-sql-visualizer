package cn.song.mybatisplus.visualizer.generator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DbTypeQuoteHint 测试（T-CrudSql-03）：数据库类型名 → 回填引号提示的启发判定。 */
class DbTypeQuoteHintTest {

    @Test
    fun numericFamiliesReturnFalse() {
        assertEquals(false, DbTypeQuoteHint.decide("BIGINT"))
        assertEquals(false, DbTypeQuoteHint.decide("decimal(10,2)"))
        assertEquals(false, DbTypeQuoteHint.decide("NUMBER"))
        assertEquals(false, DbTypeQuoteHint.decide("bit"))
    }

    @Test
    fun textAndTemporalTypesReturnTrue() {
        assertEquals(true, DbTypeQuoteHint.decide("VARCHAR(64)"))
        assertEquals(true, DbTypeQuoteHint.decide("text"))
        assertEquals(true, DbTypeQuoteHint.decide("datetime"))
        assertEquals(true, DbTypeQuoteHint.decide("char"))
    }

    @Test
    fun unknownTypeReturnsNullForPanelHeuristic() {
        assertNull(DbTypeQuoteHint.decide(null))
        assertNull(DbTypeQuoteHint.decide(""))
        assertNull(DbTypeQuoteHint.decide("   "))
    }
}
