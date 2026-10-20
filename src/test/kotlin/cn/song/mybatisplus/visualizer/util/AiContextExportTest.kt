package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R37（T-LaneB D95/D97）：安全 AI 上下文交递输出块测试（V51.4 定案格式）。
 *
 * 出口＝预览面板「复制为 AI 上下文」动作 → [AiContextExport.build] 纯函数：
 * 注释头（来源+方言+打码计数）+ 敏感字面量参数化 SQL（[DisplayMasker.parameterize]）+
 * 脱敏参数表（值恒 ***，原值不出本方法）。零敏感时块即方言+原文。
 */
class AiContextExportTest {

    @Test
    fun test块含方言行与来源头() {
        val block = AiContextExport.build("SELECT 1 FROM t", "MySQL").block
        assertTrue(block, block.contains("dialect: MySQL"))
        assertTrue(block, block.contains("SELECT 1 FROM t"))
    }

    @Test
    fun test敏感字面量参数化_参数表脱敏_原文不出现() {
        val sql = "SELECT id FROM user WHERE phone = '13812345678' AND password = 'p@ss' AND name = 'tom'"
        val result = AiContextExport.build(sql, "MySQL")
        val block = result.block
        assertTrue(block, block.contains("phone = ?"))
        assertTrue(block, block.contains("password = ?"))
        assertTrue("非敏感字面量保留", block.contains("name = 'tom'"))
        assertFalse("原手机号不得出现", block.contains("13812345678"))
        assertFalse("原密码不得出现", block.contains("p@ss"))
        assertTrue("参数表打码形态", block.contains("***"))
        assertEquals("打码计数=2", 2, result.maskedCount)
    }

    @Test
    fun test零敏感_无参数表行() {
        val result = AiContextExport.build("SELECT id FROM user WHERE name = 'tom'", "MySQL")
        assertEquals(0, result.maskedCount)
        assertFalse("零敏感不应有参数表行", result.block.contains("params"))
    }

    @Test
    fun test方言缺失_头行省略方言() {
        val block = AiContextExport.build("SELECT 1", null).block
        assertFalse("方言缺失时省略方言行", block.contains("dialect:"))
    }

    @Test
    fun test块自描述_含打码声明行() {
        val block = AiContextExport.build("SELECT * FROM t WHERE phone = '13812345678'", "MySQL").block
        assertTrue("头应声明敏感字面量已打码", block.contains("masked"))
        assertTrue("应声明来源便于 AI 溯源", block.contains("SQL Visualizer"))
    }
}
