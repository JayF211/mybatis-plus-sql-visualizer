package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入语言词法单测（纯逻辑，无平台 fixture）。
 *
 * 覆盖 T-XmlGap W2（`#{}`/`${}` 分色）+ W3（表达式细着色）的全部判别位：两类参数定界符、
 * `#{}` 参数规格子状态、`${}` OGNL 子状态（关键字/布尔/字符串/数字/静态引用/运算符/括号点）、
 * 嵌套与未闭合、`\#{` 转义、属性值宿主样例（`test` 表达式）。
 * 守卫纯逻辑面（属性白名单与长度熔断）沿用原有用例；`inMapperFile` 需 PSI，归 fixture 测试。
 */
class SqlMapperHighlightingLexerTest {

    private fun tokens(sql: String): List<Pair<IElementType, String>> {
        val lexer = SqlMapperHighlightingLexer()
        lexer.start(sql)
        val out = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            out += lexer.tokenType!! to lexer.bufferSequence.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            lexer.advance()
        }
        return out
    }

    private fun texts(sql: String, type: IElementType): List<String> =
        tokens(sql).filter { it.first == type }.map { it.second }

    private fun upperTexts(sql: String, type: IElementType): List<String> =
        texts(sql, type).map { it.uppercase() }

    // ------------------------------------------------------------------
    // 基线（既有语义不变）
    // ------------------------------------------------------------------

    @Test
    fun test关键字_大小写不敏感着色() {
        val ks = upperTexts("select * from t where a = 1", SqlMapperTT.KEYWORD)
        assertEquals(listOf("SELECT", "FROM", "WHERE"), ks)
    }

    @Test
    fun test字符串_单引号_内部分号与关键字不分裂() {
        assertEquals(listOf("'a SELECT b'"), texts("SELECT 'a SELECT b' FROM t", SqlMapperTT.STRING))
    }

    @Test
    fun test字符串_未闭合_扫到尾() {
        assertEquals(listOf("'abc"), texts("SELECT 'abc", SqlMapperTT.STRING))
    }

    @Test
    fun test注释_行注释与块注释() {
        assertEquals(listOf("-- c"), texts("-- c\nSELECT 1", SqlMapperTT.COMMENT))
        assertEquals(1, texts("/* block */", SqlMapperTT.COMMENT).size)
    }

    @Test
    fun test守卫_属性白名单() {
        assertTrue(XmlSynHiGate.allowAttributeValue("test", "page != null"))
        assertTrue(XmlSynHiGate.allowAttributeValue("collection", "list"))
        assertFalse(XmlSynHiGate.allowAttributeValue("unknown", "x"))
    }

    @Test
    fun test守卫_长度熔断() {
        assertFalse(XmlSynHiGate.allowAttributeValue("test", "x".repeat(5000)))
    }

    // ------------------------------------------------------------------
    // W2：`#{}` / `${}` 分色（两类 token，不再共用 PARAM）
    // ------------------------------------------------------------------

    @Test
    fun test参数定界符_井号与美元号各成一类token() {
        val sql = "SELECT * FROM t WHERE id = #{id} AND n = \${n}"
        assertEquals(listOf("#{", "}"), texts(sql, SqlMapperTT.PARAM_HASH))
        assertEquals(listOf("\${", "}"), texts(sql, SqlMapperTT.PARAM_DOLLAR))
    }

    @Test
    fun test参数定界符_两类互不混淆() {
        // 同一语句里两个占位符：井号侧的闭合 `}` 必须落 PARAM_HASH、美元号侧的落 PARAM_DOLLAR
        val all = tokens("#{a}")
        assertEquals(listOf(SqlMapperTT.PARAM_HASH, SqlMapperTT.IDENTIFIER, SqlMapperTT.PARAM_HASH), all.map { it.first })
        val allDollar = tokens("\${a}")
        assertEquals(listOf(SqlMapperTT.PARAM_DOLLAR, SqlMapperTT.IDENTIFIER, SqlMapperTT.PARAM_DOLLAR), allDollar.map { it.first })
    }

    @Test
    fun test转义_反斜杠井号_不产参数token() {
        assertTrue("转义形态不应有 #{ 定界符 token", texts("\\#{escaped}", SqlMapperTT.PARAM_HASH).isEmpty())
        assertTrue("转义形态不应有 \${ 定界符 token", texts("\\\${escaped}", SqlMapperTT.PARAM_DOLLAR).isEmpty())
    }

    @Test
    fun test转义_反斜杠井号_后续内容仍按顶层词法() {
        // `\#{abc}` 整体按普通文本：`abc` 是普通标识符，两侧花括号是普通 BRACE（非 PARAM_HASH）
        // 注：样例词不能挑方言保留字（如 `escaped` 是 MySQL 保留字，并集后归关键字）
        assertEquals(listOf("abc"), texts("\\#{abc}", SqlMapperTT.IDENTIFIER))
        assertEquals(listOf("{", "}"), texts("\\#{abc}", SqlMapperTT.BRACE))
    }

    // ------------------------------------------------------------------
    // W3：`#{…}` 参数规格子状态
    // ------------------------------------------------------------------

    @Test
    fun test参数规格_参数名与属性键值分色() {
        val sql = "#{id, jdbcType=VARCHAR}"
        // `#{}` 内不判关键字：参数名/属性名/属性值一律标识符（否则 `#{size}` 等高频参数名会被
        // Oracle/H2 保留字误着关键字色）——W3 #4 的「参数名 identifier」口径
        assertEquals(listOf("id", "jdbcType", "VARCHAR"), texts(sql, SqlMapperTT.IDENTIFIER))
        assertTrue("`#{}` 内不应有关键字色", texts(sql, SqlMapperTT.KEYWORD).isEmpty())
        assertEquals(listOf(","), texts(sql, SqlMapperTT.COMMA))
        assertEquals(listOf("="), texts(sql, SqlMapperTT.OPERATOR))
    }

    @Test
    fun test参数规格_高频参数名不着关键字色() {
        // W3 #4 落地判据：`size`/`key`/`value`/`list` 都是方言保留字（Oracle/H2），但作为参数名必须按标识符
        val sql = "#{size} #{key} #{value} #{list} #{ids[0]}"
        assertEquals(listOf("size", "key", "value", "list", "ids"), texts(sql, SqlMapperTT.IDENTIFIER))
        assertTrue("参数名不应着关键字色", texts(sql, SqlMapperTT.KEYWORD).isEmpty())
        assertEquals(listOf("[", "]"), texts(sql, SqlMapperTT.BRACKET))
        assertEquals(listOf("0"), texts(sql, SqlMapperTT.NUMBER))
    }

    @Test
    fun test参数规格_闭合后回到顶层词法() {
        val sql = "#{id} SELECT 1"
        val types = tokens(sql).map { it.first }
        assertEquals(
            listOf(
                SqlMapperTT.PARAM_HASH, SqlMapperTT.IDENTIFIER, SqlMapperTT.PARAM_HASH,
                SqlMapperTT.WHITE_SPACE, SqlMapperTT.KEYWORD, SqlMapperTT.WHITE_SPACE, SqlMapperTT.NUMBER,
            ),
            types,
        )
    }

    @Test
    fun test未闭合_井号_扫到缓冲末尾且不产error() {
        val sql = "SELECT #{abc"
        assertEquals(listOf("#{"), texts(sql, SqlMapperTT.PARAM_HASH))
        assertEquals(listOf("abc"), texts(sql, SqlMapperTT.IDENTIFIER))
        assertEquals("SELECT", upperTexts(sql, SqlMapperTT.KEYWORD).single())
        // 不产 error token：末 token（`abc`）覆盖到缓冲末尾，token 序列连续无洞
        assertEquals(sql.length, SqlMapperHighlightingLexer().let { lx ->
            lx.start(sql)
            while (lx.tokenType != null) lx.advance()
            lx.tokenEnd
        })
    }

    // ------------------------------------------------------------------
    // W3：`${…}` OGNL 子状态
    // ------------------------------------------------------------------

    @Test
    fun testOGNL_关键字null与and() {
        val sql = "\${page != null and page.size > 0}"
        assertEquals(listOf("null", "and"), texts(sql, SqlMapperTT.KEYWORD))
        assertEquals(listOf("page", "page", "size"), texts(sql, SqlMapperTT.IDENTIFIER))
        assertEquals(listOf("!=", ">"), texts(sql, SqlMapperTT.OPERATOR))
        assertEquals(listOf("."), texts(sql, SqlMapperTT.DOT))
        assertEquals(listOf("0"), texts(sql, SqlMapperTT.NUMBER))
    }

    @Test
    fun testOGNL_not与in与notin() {
        assertEquals(listOf("not", "in"), texts("\${not (a in b)}", SqlMapperTT.KEYWORD))
        assertEquals(listOf("not", "in"), texts("\${a not in b}", SqlMapperTT.KEYWORD))
    }

    @Test
    fun testOGNL_布尔字面量独立token() {
        assertEquals(listOf("true", "false"), texts("\${flag == true or x == false}", SqlMapperTT.BOOLEAN))
        assertEquals(listOf("or"), texts("\${flag == true or x == false}", SqlMapperTT.KEYWORD))
    }

    @Test
    fun testOGNL_null不是布尔() {
        assertTrue(texts("\${a == null}", SqlMapperTT.BOOLEAN).isEmpty())
        assertEquals(listOf("null"), texts("\${a == null}", SqlMapperTT.KEYWORD))
    }

    @Test
    fun testOGNL_字符串两种引号() {
        assertEquals(
            listOf("'x'", "\"y\""),
            texts("\${name != 'x' and t == \"y\"}", SqlMapperTT.STRING),
        )
    }

    @Test
    fun testOGNL_数字含小数() {
        assertEquals(listOf("1.5"), texts("\${a == 1.5}", SqlMapperTT.NUMBER))
    }

    @Test
    fun testOGNL_静态引用整体token() {
        val sql = "\${@com.Foo@BAR != null}"
        assertEquals(listOf("@com.Foo@BAR"), texts(sql, SqlMapperTT.STATIC_REF))
        assertEquals(listOf("null"), texts(sql, SqlMapperTT.KEYWORD))
        assertEquals(listOf("!="), texts(sql, SqlMapperTT.OPERATOR))
    }

    @Test
    fun testOGNL_静态引用_内部类美元号() {
        assertEquals(listOf("@a.b.Foo\$Bar@X"), texts("\${@a.b.Foo\$Bar@X}", SqlMapperTT.STATIC_REF))
    }

    @Test
    fun testOGNL_落单的at符退化为运算符() {
        // 形态不成立（无第二个 @）→ 不吞字，退化为单字符运算符
        val sql = "\${@a}"
        assertEquals(listOf("@"), texts(sql, SqlMapperTT.OPERATOR))
        assertEquals(listOf("a"), texts(sql, SqlMapperTT.IDENTIFIER))
        assertTrue(texts(sql, SqlMapperTT.STATIC_REF).isEmpty())
    }

    @Test
    fun testOGNL_嵌套花括号_深度归零才闭合() {
        val sql = "\${list in {1, 2}}"
        assertEquals(listOf("\${", "}"), texts(sql, SqlMapperTT.PARAM_DOLLAR))
        assertEquals(listOf("{", "}"), texts(sql, SqlMapperTT.BRACE))
        assertEquals(listOf("1", "2"), texts(sql, SqlMapperTT.NUMBER))
        assertEquals(listOf(","), texts(sql, SqlMapperTT.COMMA))
        assertEquals(listOf("in"), texts(sql, SqlMapperTT.KEYWORD))
    }

    @Test
    fun testOGNL_字符串内的花括号不闭合参数() {
        val sql = "\${a == '}'}"
        assertEquals(listOf("\${", "}"), texts(sql, SqlMapperTT.PARAM_DOLLAR))
        assertEquals(listOf("'}'"), texts(sql, SqlMapperTT.STRING))
    }

    @Test
    fun testOGNL_方括号与圆括号() {
        val sql = "\${a[0].size() > 1}"
        assertEquals(listOf("[", "]"), texts(sql, SqlMapperTT.BRACKET))
        assertEquals(listOf("(", ")"), texts(sql, SqlMapperTT.PAREN))
        assertEquals(listOf("."), texts(sql, SqlMapperTT.DOT))
        assertEquals(listOf("0", "1"), texts(sql, SqlMapperTT.NUMBER))
    }

    @Test
    fun test限定名尾段不判关键字_点后按标识符() {
        // `page.size` 的 size 是属性名——Oracle 保留字 `size` 不得在限定名尾段误判（W3 #8 判别位）
        assertEquals(listOf("page", "size"), texts("page.size", SqlMapperTT.IDENTIFIER))
        assertTrue("点后词不应判关键字", texts("page.size", SqlMapperTT.KEYWORD).isEmpty())
        // 限定名同理：t.order / x.value / t.key 全按标识符
        assertEquals(
            listOf("t", "order", "x", "value", "t", "key"),
            texts("t.order x.value t.key", SqlMapperTT.IDENTIFIER),
        )
        // 裸用的 size 仍按方言并集着关键字色（Oracle 保留字）——这是「全方言超集」的既定代价（只多不少）
        assertEquals(listOf("SIZE"), upperTexts("size", SqlMapperTT.KEYWORD))
    }

    @Test
    fun test未闭合_美元号_扫到缓冲末尾且不产error() {
        val sql = "SELECT \${abc"
        assertEquals(listOf("\${"), texts(sql, SqlMapperTT.PARAM_DOLLAR))
        assertEquals(listOf("abc"), texts(sql, SqlMapperTT.IDENTIFIER))
    }

    @Test
    fun testOGNL_词集按Ognl_kt收敛_不认eq与nin() {
        // Ognl.kt 无 eq/neq/lt/gt/lte/gte/nin/mod/new/instanceof 实现 → 按普通标识符着色（不误判关键字）
        assertEquals(listOf("a", "eq", "b"), texts("\${a eq b}", SqlMapperTT.IDENTIFIER))
        assertTrue(texts("\${a eq b}", SqlMapperTT.KEYWORD).isEmpty())
        assertTrue(texts("\${x nin y}", SqlMapperTT.KEYWORD).isEmpty())
        assertEquals(listOf("x", "nin", "y"), texts("\${x nin y}", SqlMapperTT.IDENTIFIER))
    }

    // ------------------------------------------------------------------
    // W3 #6：标点族与运算符语义槽位
    // ------------------------------------------------------------------

    @Test
    fun test标点族_ParenBracketCommaDot() {
        val sql = "SELECT count(a), b[1] FROM t"
        assertEquals(listOf("(", ")"), texts(sql, SqlMapperTT.PAREN))
        assertEquals(listOf("[", "]"), texts(sql, SqlMapperTT.BRACKET))
        assertEquals(listOf(","), texts(sql, SqlMapperTT.COMMA))
        assertTrue(texts(sql, SqlMapperTT.DOT).isEmpty())
    }

    @Test
    fun test运算符_两字符形态不拆散() {
        assertEquals(listOf("<=", ">=", "<>", "!="), texts("a <= b >= c <> d != e", SqlMapperTT.OPERATOR))
    }

    // ------------------------------------------------------------------
    // W3 #7：关键字覆盖面（补全词集与方言词如实着色）
    // ------------------------------------------------------------------

    @Test
    fun test关键字集_补全词集与方言词均着色() {
        val sql = "SELECT COUNT(*) FROM t RETURNING x MERGE INTO y GROUP_CONCAT(z)"
        val ks = upperTexts(sql, SqlMapperTT.KEYWORD)
        assertTrue("COUNT（SqlLexer.KEYWORDS 独有）应着色：$ks", "COUNT" in ks)
        assertTrue("RETURNING（方言保留字）应着色：$ks", "RETURNING" in ks)
        assertTrue("MERGE（SQL Server 保留字）应着色：$ks", "MERGE" in ks)
    }

    @Test
    fun test关键字集_差异词表逐词着色() {
        // 内部调研报告 §0.9 点名的 16 个「补全能提示但编辑器不着色」的词，逐个钉住
        val diffWords = listOf(
            "TABLE", "CREATE", "DROP", "ALTER", "ADD", "COLUMN", "PRIMARY", "KEY",
            "FOREIGN", "CONSTRAINT", "DEFAULT", "INDEX", "FULL", "CROSS", "USING",
        )
        val sql = diffWords.joinToString(" ")
        val ks = upperTexts(sql, SqlMapperTT.KEYWORD)
        assertEquals(diffWords, ks)
    }

    // ------------------------------------------------------------------
    // W3 #8：属性值宿主样例（`test="…"` 的属性值注入同一语言，顶层词法）
    // ------------------------------------------------------------------

    @Test
    fun test属性值样例_test表达式分色() {
        val sql = "page != null and page.size > 0"
        assertEquals(listOf("null", "and"), texts(sql, SqlMapperTT.KEYWORD))
        assertEquals(listOf("page", "page", "size"), texts(sql, SqlMapperTT.IDENTIFIER))
        assertEquals(listOf("!=", ">"), texts(sql, SqlMapperTT.OPERATOR))
        assertEquals(listOf("."), texts(sql, SqlMapperTT.DOT))
        assertEquals(listOf("0"), texts(sql, SqlMapperTT.NUMBER))
    }

    @Test
    fun test属性值样例_布尔字面量() {
        val sql = "flag == true or flag == false"
        assertEquals(listOf("true", "false"), texts(sql, SqlMapperTT.BOOLEAN))
        assertEquals(listOf("or"), texts(sql, SqlMapperTT.KEYWORD))
    }

    @Test
    fun test属性值样例_字符串与空串比较() {
        val sql = "name != '' and status == 'A'"
        assertEquals(listOf("''", "'A'"), texts(sql, SqlMapperTT.STRING))
        assertEquals(listOf("and"), texts(sql, SqlMapperTT.KEYWORD))
        assertEquals(listOf("name", "status"), texts(sql, SqlMapperTT.IDENTIFIER))
    }
}
