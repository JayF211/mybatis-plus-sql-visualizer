package cn.song.mybatisplus.visualizer.reverse

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Java 注释剥离单元测试（T-M45）：复制「纯 Java 代码」时的注释过滤。
 *
 * 关键边界（全部为纯文本扫描，不依赖 PSI）：
 *  - 整行注释行删除、行尾注释保留代码行；
 *  - 字符串/字符字面量内的斜杠注释标记不误伤（含转义引号）；
 *  - 代码空行保留（只删注释行，不压缩代码结构）。
 */
class JavaCommentStripperTest {

    @Test
    fun `整行注释_整行删除`() {
        val code = "// SQL → QueryWrapper 反向生成\nQueryWrapper<User> wrapper = new QueryWrapper<>();"
        assertEquals(
            "QueryWrapper<User> wrapper = new QueryWrapper<>();",
            JavaCommentStripper.strip(code),
        )
    }

    @Test
    fun `行尾注释_保留代码行并去行尾空白`() {
        val code = "Page<User> page = userService.page(new Page<>(1, 1), wrapper);  // 分页 current/size 已占位"
        assertEquals(
            "Page<User> page = userService.page(new Page<>(1, 1), wrapper);",
            JavaCommentStripper.strip(code),
        )
    }

    @Test
    fun `多条注释行_全部删除`() {
        val code = "// 头注释一\n// 头注释二\n// 注意：占位符\ncode();"
        assertEquals("code();", JavaCommentStripper.strip(code))
    }

    @Test
    fun `字符串内斜杠注释标记_不误伤`() {
        val code = "wrapper.apply(\"http://a/b?x=1\"); // 行注释"
        assertEquals("wrapper.apply(\"http://a/b?x=1\");", JavaCommentStripper.strip(code))
    }

    @Test
    fun `字符串内块注释标记_不误伤`() {
        val code = "wrapper.last(\"/* 原始 SQL 注释内容 */\");"
        assertEquals("wrapper.last(\"/* 原始 SQL 注释内容 */\");", JavaCommentStripper.strip(code))
    }

    @Test
    fun `块注释单行_整段删除`() {
        val code = "/* 块注释 */\ncode();"
        assertEquals("code();", JavaCommentStripper.strip(code))
    }

    @Test
    fun `块注释多行_整段删除`() {
        val code = "/* 块注释第一行\n   第二行 */\ncode();"
        assertEquals("code();", JavaCommentStripper.strip(code))
    }

    @Test
    fun `行尾块注释_保留代码行`() {
        val code = "code(); /* 行尾块 */\nnext();"
        assertEquals("code();\nnext();", JavaCommentStripper.strip(code))
    }

    @Test
    fun `字符字面量斜杠_不误伤`() {
        val code = "char slash = '/'; char star = '*'; // 注释"
        assertEquals("char slash = '/'; char star = '*';", JavaCommentStripper.strip(code))
    }

    @Test
    fun `转义引号_字符串不提前结束`() {
        val code = "String s = \"a\\\"//b\"; // 注释"
        assertEquals("String s = \"a\\\"//b\";", JavaCommentStripper.strip(code))
    }

    @Test
    fun `代码空行_原样保留`() {
        val code = "a();\n\n// 注释行\nb();"
        assertEquals("a();\n\nb();", JavaCommentStripper.strip(code))
    }

    @Test
    fun `CRLF换行_注释行删除`() {
        val code = "// 注释\r\ncode();"
        assertEquals("code();", JavaCommentStripper.strip(code))
    }

    @Test
    fun `无注释_原样返回`() {
        val code = "a();\n\nb();"
        assertEquals(code, JavaCommentStripper.strip(code))
    }

    @Test
    fun `全文仅注释_结果为空`() {
        assertEquals("", JavaCommentStripper.strip("// 只有注释"))
    }

    @Test
    fun `空输入_返回空`() {
        assertEquals("", JavaCommentStripper.strip(""))
        assertEquals("", JavaCommentStripper.strip("  "))
    }
}
