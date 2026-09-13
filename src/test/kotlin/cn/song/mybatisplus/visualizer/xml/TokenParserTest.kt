package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TokenParser 纯逻辑单测（T-TestGuard-07 零覆盖补测：xml/TokenParser 复刻 MyBatis
 * GenericTokenParser，此前无任何测试）。
 *
 * 覆盖转义形态、未闭合 token 容忍、嵌套替换与 handler 回填。
 */
class TokenParserTest {

    private fun parse(text: String, handler: (String) -> String = { "[$it]" }) =
        TokenParser.parse(text, "#{", "}", handler)

    @Test
    fun `普通token替换`() {
        assertEquals("SELECT [name] FROM t", parse("SELECT #{name} FROM t"))
    }

    @Test
    fun `多token全部替换`() {
        assertEquals("[a] + [b] = [c]", parse("#{a} + #{b} = #{c}"))
    }

    @Test
    fun `反斜杠转义openToken_去反斜杠原样输出`() {
        // 输入含转义 \#{ → 反斜杠被去掉、openToken 原样保留不替换
        assertEquals("#{name} 不替换", parse("\\#{name} 不替换"))
    }

    @Test
    fun `转义closeToken_去反斜杠原样输出`() {
        // "#{a\}b}" 中的 \} 是转义：a 后原样输出 }，随后真正闭合替换 "b"
        assertEquals("a}[b]", parse("#{a\\}b}"))
    }

    @Test
    fun `有open无close_剩余原样输出`() {
        assertEquals("前缀 #{unclosed", parse("前缀 #{unclosed"))
    }

    @Test
    fun `空输入原样返回`() {
        assertEquals("", parse(""))
    }

    @Test
    fun `无token文本原样返回`() {
        assertEquals("plain text", parse("plain text", { "X$it" }))
    }

    @Test
    fun `handler直通等价去除token`() {
        assertEquals("v = name", parse("v = #{name}") { it })
    }

    @Test
    fun `不同token对_动态标签形态`() {
        // foreach/choose 场景用不同 open/close
        val out = TokenParser.parse("<if test='a'>[content]</if>", "<if", "</if>") { it }
        assertEquals(" test='a'>[content]", out)
    }
}
