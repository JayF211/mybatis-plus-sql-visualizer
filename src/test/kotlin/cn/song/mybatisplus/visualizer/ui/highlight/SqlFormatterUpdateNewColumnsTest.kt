package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PreviewUx-Pack #1：UPDATE 预览 (new) 列单测（吸收 DBX dml_preview_sql 纯函数口径）。
 *
 * 纯逻辑测试（SqlFormatter 无 PSI 依赖，headless 可跑）：
 *  - SET 子句解析：列名/表达式分离（自引用/函数调用/嵌套括号内逗号不切分）
 *  - formatVertical UPDATE 标注：行尾 `-- 列名 (new) = 表达式`（T-VPairAnno 同款范式），
 *    开关关闭不标注、已标注不重复（幂等）、非 UPDATE 不标注
 */
class SqlFormatterUpdateNewColumnsTest {

    // ---------- parseUpdateSetAssignments：SET 子句解析 ----------

    @Test
    fun test解析基本SET子句() {
        val assignments = SqlFormatter.parseUpdateSetAssignments(
            lex("UPDATE user SET name = 'x', age = 20 WHERE id = 1"),
        )
        assertEquals(2, assignments.size)
        assertEquals("name", assignments[0].column)
        assertEquals("'x'", assignments[0].expression)
        assertEquals("age", assignments[1].column)
        assertEquals("20", assignments[1].expression)
    }

    @Test
    fun test自引用表达式原样搬() {
        // DBX 口径：按旧值求值即得新值，自引用 a = a + 1 原样即正确
        val assignments = SqlFormatter.parseUpdateSetAssignments(
            lex("UPDATE t SET use_total = use_total + 1 WHERE id = 1"),
        )
        assertEquals(1, assignments.size)
        assertEquals("use_total", assignments[0].column)
        assertEquals("use_total + 1", assignments[0].expression)
    }

    @Test
    fun test函数调用与嵌套括号() {
        val assignments = SqlFormatter.parseUpdateSetAssignments(
            lex("UPDATE t SET v = CONCAT(a, b), ts = NOW(), w = (a + b) * 2 WHERE id = 1"),
        )
        assertEquals(3, assignments.size)
        // 括号内逗号是函数参数不是 SET 项边界
        assertEquals("v", assignments[0].column)
        assertEquals("CONCAT(a, b)", assignments[0].expression)
        assertEquals("ts", assignments[1].column)
        assertEquals("NOW()", assignments[1].expression)
        assertEquals("w", assignments[2].column)
        assertEquals("(a + b) * 2", assignments[2].expression)
    }

    @Test
    fun test带点列名与终止于ORDER() {
        val assignments = SqlFormatter.parseUpdateSetAssignments(
            lex("UPDATE t SET t.a = 1, t.b = 2 ORDER BY t.a"),
        )
        assertEquals(2, assignments.size)
        assertEquals("t.a", assignments[0].column)
        assertEquals("t.b", assignments[1].column)
    }

    @Test
    fun test无SET或残缺项返回空或跳过() {
        assertEquals(0, SqlFormatter.parseUpdateSetAssignments(lex("SELECT 1")).size)
        assertEquals(0, SqlFormatter.parseUpdateSetAssignments(lex("UPDATE t SET WHERE id = 1")).size)
        // 无 = 的项跳过，合法项保留
        val assignments = SqlFormatter.parseUpdateSetAssignments(
            lex("UPDATE t SET a = 1, broken WHERE id = 1"),
        )
        assertEquals(1, assignments.size)
        assertEquals("a", assignments[0].column)
    }

    // ---------- formatVertical：UPDATE (new) 列标注 ----------

    @Test
    fun test竖排UPDATE追加New列标注() {
        val out = SqlFormatter.formatVertical("update user set name = 'x', age = age + 1 where id = 1")
        assertTrue(out, out.contains("name = 'x',  -- name (new) = 'x'"))
        assertTrue(out, out.contains("age = age + 1  -- age (new) = age + 1"))
        // WHERE 段不标注
        assertTrue(out, out.contains("WHERE id = 1"))
        assertFalse(out, out.contains("id (new)"))
    }

    @Test
    fun test竖排UPDATE开关关闭不标注() {
        val out = SqlFormatter.formatVertical("update user set name = 'x' where id = 1", annotatePairs = false)
        assertFalse(out, out.contains("(new)"))
    }

    @Test
    fun test竖排UPDATE幂等_已标注不重复() {
        val once = SqlFormatter.formatVertical("update user set name = 'x' where id = 1")
        // 重输入：format 把行尾注释拆为独立行（既有注释形态），但 (new) 标注不重复追加
        val twice = SqlFormatter.formatVertical(once)
        assertEquals(1, twice.split("(new)").size - 1)
        // 注释独立行形态收敛：第三次起逐字节稳定（幂等不动点）
        val thrice = SqlFormatter.formatVertical(twice)
        assertEquals(twice, thrice)
    }

    @Test
    fun test非UPDATE语句不标注() {
        val out = SqlFormatter.formatVertical("select a, b from t where a in (1, 2, 3, 4, 5)")
        assertFalse(out, out.contains("(new)"))
    }
}

/** SqlFormatter.lex 是 private——测试侧用同款 SqlLexer 分词（与生产同一分词器，无自建逻辑）。 */
private fun lex(sql: String): List<Pair<IElementType, String>> {
    val lexer = SqlLexer()
    lexer.start(sql, 0, sql.length, 0)
    val tokens = mutableListOf<Pair<IElementType, String>>()
    while (true) {
        val type = lexer.tokenType ?: break
        if (type != TokenType.WHITE_SPACE) tokens += type to lexer.tokenText
        lexer.advance()
    }
    return tokens
}
