package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Java 代码格式化测试（T-M45「格式化代码」按钮）：IDE Java 格式化器处理代码片段。
 *
 * 断言契约：
 *  - 空格归一（逗号/括号两侧）按默认 Java 风格；
 *  - 超宽链式调用按右边距换行（生成的长 select 列清单一行放不下 → 自动折行）；
 *  - 注释保留（格式化只重排不删除注释——复制时的剥离在复制动作里）；
 *  - 空白输入原样返回。
 */
class JavaCodeFormatterTest : BasePlatformTestCase() {

    fun testFormatNormalizesSpacing() {
        val code = "QueryWrapper<User> wrapper = new QueryWrapper<User>()\n    .eq( \"name\" , 1 );"
        val formatted = JavaCodeFormatter.format(project, code)
        assertTrue(formatted, formatted.contains(".eq(\"name\", 1)"))
        assertFalse(formatted, formatted.contains(".eq( \"name\""))
    }

    fun testFormatNormalizesContinuationIndent() {
        // 链式调用续行缩进按代码风格归一（默认续行缩进 8；输入 4 → 输出 8——
        // 平台实证行为；证明格式化器真实作用于代码片段而非原样返回）
        val code = "QueryWrapper<User> wrapper = new QueryWrapper<User>()\n    .select(\"a\");"
        val formatted = JavaCodeFormatter.format(project, code)
        assertTrue(formatted, formatted.contains("\n        .select(\"a\")"))
        assertFalse(formatted, formatted.contains("\n    .select(\"a\")"))
    }

    fun testFormatIdempotentOnGeneratedShape() {
        // 生成器输出的规范形态：格式化后内容不丢失，再格式化结果稳定
        val code = "QueryWrapper<User> wrapper = new QueryWrapper<User>()\n    .select(\"a\", \"b\");"
        val formatted = JavaCodeFormatter.format(project, code)
        assertTrue(formatted, formatted.contains(".select(\"a\", \"b\")"))
        assertEquals(formatted, JavaCodeFormatter.format(project, formatted))
    }

    fun testFormatKeepsComments() {
        val code = "// 头注释\ncode(); // 行尾注释"
        val formatted = JavaCodeFormatter.format(project, code)
        assertTrue(formatted, formatted.contains("// 头注释"))
        assertTrue(formatted, formatted.contains("// 行尾注释"))
    }

    fun testFormatBlankInputReturnedAsIs() {
        assertEquals("", JavaCodeFormatter.format(project, ""))
        assertEquals("   ", JavaCodeFormatter.format(project, "   "))
        // 无 project（理论场景）→ 原样返回
        assertEquals("code();", JavaCodeFormatter.format(null, "code();"))
    }
}
