package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateWrapperConverter 手工模型单测（T-TestGuard-07 零覆盖补测：字符串版 UpdateWrapper
 * 转换器此前无直接测试——其 set/setSql 解析委托 LambdaUpdateConverter，此处锁定 UPDATE
 * 类型路由与字符串条件透传契约，QueryWrapperParserTest 同款手工构造模型风格，不依赖 PSI）。
 */
class UpdateWrapperConverterTest {

    private val table = TableInfo("CouponEntity", "coupon", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("useTotal", "use_total"),
        ))

    @Test
    fun `UPDATE类型路由_字符串setSql与eq条件`() {
        val chain = WrapperChain(WrapperType.UPDATE, "CouponEntity", listOf(
                WrapperCall("setSql", listOf(WrapperArg.ValueArg(cn.song.mybatisplus.visualizer.model.SqlValue.Literal("use_total = use_total + 1")))),
                WrapperCall("eq", listOf(
                    WrapperArg.ColumnArg(cn.song.mybatisplus.visualizer.model.ColumnRef("id")),
                    WrapperArg.ValueArg(cn.song.mybatisplus.visualizer.model.SqlValue.Literal("5")),
                )),
            ))
        val statement = UpdateWrapperConverter.INSTANCE.convert(chain, table)
        assertEquals("字符串版 UpdateWrapper 应产出 UPDATE 语句模型", cn.song.mybatisplus.visualizer.model.UpdateStatement::class, statement::class)
        val sql = SqlGenerator.generate(statement)
        assertTrue("应含 SET 原始片段", sql.contains("SET use_total = use_total + 1"))
        assertTrue("应含 WHERE 条件", sql.contains("WHERE id = 5"))
        assertEquals("UPDATE coupon\nSET use_total = use_total + 1\nWHERE id = 5", sql.trim())
    }
}
