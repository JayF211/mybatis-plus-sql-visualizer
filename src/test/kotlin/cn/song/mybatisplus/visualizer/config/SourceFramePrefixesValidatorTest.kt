package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.config.SourceFramePrefixesValidator.PrefixField.BIZ
import cn.song.mybatisplus.visualizer.config.SourceFramePrefixesValidator.PrefixField.SKIP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PrefixValidate 纯函数单测：两栏前缀登记的格式校验与一键修复（面板只做展示回写，判定全在此覆盖）。
 */
class SourceFramePrefixesValidatorTest {

    // ---------- 合法形态 ----------

    @Test
    fun test合法形态_含尾点与不带尾点与下划线数字() {
        val report = SourceFramePrefixesValidator.validate(
            "com.corp.engine.\ncom.corp2\na.b_c1.\$Inner",
            "com.corp.shared.util.",
        )
        assertFalse(
            "合法前缀不得报任何问题: $report",
            report.hasProblems,
        )
    }

    @Test
    fun test空文本与空行_不算问题() {
        val report = SourceFramePrefixesValidator.validate("", "  \n  \n\t")
        assertFalse("空登记不是问题: $report", report.hasProblems)
    }

    // ---------- 非法行 ----------

    @Test
    fun test非法形态_逐类报出行号与内容() {
        val report = SourceFramePrefixesValidator.validate(
            "com corp.engine.\ncom..corp.\n.com.\ncom.corp.*\ncom.公司.\ncom.corp.engine.,",
            "",
        )
        val invalid = report.invalidLines
        // 行号按原始 1 起始（含空行计数），6 条全部命中
        assertEquals(listOf(1, 2, 3, 4, 5, 6), invalid.map { it.lineNo })
        assertEquals(SKIP, invalid[0].field)
        assertEquals("com corp.engine.", invalid[0].text)
        assertEquals("com..corp.", invalid[1].text)
        assertEquals(".com.", invalid[2].text)
        assertEquals("com.corp.*", invalid[3].text)
        assertEquals("com.公司.", invalid[4].text)
        assertEquals("com.corp.engine.,", invalid[5].text)
    }

    @Test
    fun test行号含空行计数_便于用户回找() {
        val report = SourceFramePrefixesValidator.validate("com.corp.\n\nbad prefix", "")
        assertEquals(3, report.invalidLines.single().lineNo)
    }

    // ---------- 同栏重复 ----------

    @Test
    fun test同栏重复_报重复且合法行才计() {
        val report = SourceFramePrefixesValidator.validate(
            "com.corp.\n com.corp. \ncom.corp.\nbad line",
            "",
        )
        // trim 后三条同值 → 重复一次（组内 >1 记一条）；非法行不重复计
        val dup = report.duplicates.single()
        assertEquals(SKIP, dup.field)
        assertEquals("com.corp.", dup.prefix)
    }

    // ---------- 跨栏冲突 ----------

    @Test
    fun test跨栏冲突_同前缀两栏都登记() {
        val report = SourceFramePrefixesValidator.validate(
            "com.corp.\ncom.corp.shared.",
            "com.corp.shared.\ncom.other.",
        )
        assertEquals(listOf("com.corp.shared."), report.conflicts.map { it.prefix })
    }

    // ---------- 一键修复 ----------

    @Test
    fun test一键修复_规范化与剔除非法与去重() {
        val (skip, biz) = SourceFramePrefixesValidator.autoFix(
            "  com.corp.  \n\nbad line\ncom.corp.\ncom.other.\n",
            " com.biz.\ncom.biz2*\ncom.biz.\ncom.other.",
        )
        assertEquals("trim/丢空行/丢非法/去重（保留首个）", "com.corp.\ncom.other.", skip)
        assertEquals("BIZ 同款规范化", "com.biz.", biz)
    }

    @Test
    fun test一键修复_跨栏冲突按skip优先从BIZ剔除() {
        val (skip, biz) = SourceFramePrefixesValidator.autoFix(
            "com.corp.engine.",
            "com.corp.engine.\ncom.corp.shared.util.",
        )
        assertEquals("com.corp.engine.", skip)
        assertEquals("与 skip 冲突的条目从 BIZ 侧剔除（运行时本就是 skip 赢）", "com.corp.shared.util.", biz)
    }

    @Test
    fun test一键修复_幂等() {
        val rawSkip = "com.corp.\nbad\ncom.corp."
        val rawBiz = "com.corp.\ncom.biz.\ncom.biz."
        val first = SourceFramePrefixesValidator.autoFix(rawSkip, rawBiz)
        val second = SourceFramePrefixesValidator.autoFix(first.first, first.second)
        assertEquals("修复应幂等（再跑不变）", first, second)
        val report = SourceFramePrefixesValidator.validate(first.first, first.second)
        assertFalse("修复后应零问题: $report", report.hasProblems)
    }

    @Test
    fun test一键修复_合法输入原样保留() {
        val skip = "com.corp.engine.\ncom.corp.meta."
        val biz = "com.corp.shared.util."
        val fixed = SourceFramePrefixesValidator.autoFix(skip, biz)
        assertEquals(skip, fixed.first)
        assertEquals(biz, fixed.second)
    }

    // ---------- 语义红线锚点 ----------

    @Test
    fun test合法前缀不含通配符与反斜杠等粘错形态() {
        val report = SourceFramePrefixesValidator.validate(
            "com/corp/engine\ncom.corp.engine.\\\\\ncom\\.corp.\n#com.corp.\ncom.corp. ",
            "",
        )
        // 「com.corp. 」trim 后合法不计；其余 4 条全非法
        assertEquals(4, report.invalidLines.size)
        assertTrue(report.hasProblems)
    }
}
