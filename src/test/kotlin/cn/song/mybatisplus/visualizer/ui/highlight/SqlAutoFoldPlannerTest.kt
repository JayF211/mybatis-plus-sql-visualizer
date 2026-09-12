package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SqlAutoFoldPlanner 纯逻辑单测（T-AutoFold F1/F6）。
 *
 * 纯函数零平台依赖——覆盖：阈值边界（=阈值折、-1 不折）；SELECT/INSERT 列/VALUES 组/SET
 * 四类识别；嵌套子查询不双折；单行列表不折；压缩单行文本零输出；占位符列数正确
 * （引号/括号内逗号不误分段）；多条语句互不粘连。
 */
class SqlAutoFoldPlannerTest {

    private fun plan(sql: String, threshold: Int = 3, template: String = "… {0} 列") =
        SqlAutoFoldPlanner.plan(sql, threshold, template)

    private fun fold(sql: String, threshold: Int = 3) = plan(sql, threshold).firstOrNull()

    // ---------- 阈值边界 ----------

    fun test阈值等于元素数时折叠() {
        // 3 列 + 阈值 3 → 折叠，占位符 … 3 列
        val spec = fold("SELECT\n    a,\n    b,\n    c\nFROM t")
        assertTrue("达到阈值应折叠", spec != null)
        assertEquals("… 3 列", spec!!.placeholder)
    }

    fun test阈值减一不折叠() {
        assertTrue("低于阈值不折叠", fold("SELECT\n    a,\n    b\nFROM t", threshold = 3) == null)
    }

    fun test阈值为零防御性空输出() {
        assertTrue(plan("SELECT\n    a,\n    b,\n    c\nFROM t", threshold = 0).isEmpty())
    }

    fun test空文本空输出() {
        assertTrue(plan("", threshold = 3).isEmpty())
    }

    // ---------- 四类列表识别 ----------

    fun testSelect列清单区域边界与跨行判定() {
        val sql = "SELECT\n    id,\n    name,\n    age,\n    email\nFROM user WHERE id = 1"
        val spec = fold(sql)!!
        // 区域从列表首元素（id）起，止于末元素行尾；FROM 行保持可见
        assertEquals("SELECT\n    ".length, spec.startOffset)
        val emailIdx = sql.indexOf("email")
        assertEquals(emailIdx + "email".length, spec.endOffset)
        assertTrue("占位符应含元素数 4", spec.placeholder.contains("4"))
    }

    fun testInsert列清单与Values组各自折叠() {
        val sql = "INSERT INTO t (a1, a2, a3) VALUES\n    (1, 2, 3),\n    (4, 5, 6)"
        val specs = plan(sql)
        // 列清单 1 个区域 + 2 个 VALUES 组 = 3 个（各自独立判定元素数）
        assertEquals("INSERT 列清单 + 每组 VALUES 各一区域", 3, specs.size)
        assertTrue(specs[0].placeholder.contains("3"))
        // 升序且互不重叠
        assertTrue(specs[0].endOffset <= specs[1].startOffset)
        assertTrue(specs[1].endOffset <= specs[2].startOffset)
    }

    fun testUpdateSet清单折叠到Where前() {
        val sql = "UPDATE t SET\n    x = 1,\n    y = 2,\n    z = 3\nWHERE id = 1"
        val spec = fold(sql)!!
        assertTrue("区域不含 WHERE", spec.endOffset <= sql.indexOf("WHERE"))
        assertTrue(spec.placeholder.contains("3"))
    }

    // ---------- 嵌套与互斥 ----------

    fun test嵌套子查询内层不双折() {
        val sql = "SELECT\n    a,\n    (SELECT b, c, d FROM t2) AS sub,\n    e\nFROM t1"
        val specs = plan(sql)
        // 外层 SELECT 列清单 1 个区域；内层 SELECT 是外层元素的一部分不另产区域
        assertEquals(1, specs.size)
        assertTrue("外层区域覆盖内层", specs[0].startOffset < sql.indexOf("SELECT b") && specs[0].endOffset > sql.indexOf("FROM t2"))
    }

    // ---------- 单行 / 压缩态 ----------

    fun test单行列表不折叠() {
        assertTrue("同行无折叠价值", fold("SELECT a, b, c, d FROM t") == null)
    }

    fun test压缩单行Insert零输出() {
        assertTrue(plan("INSERT INTO t (a1, a2, a3) VALUES (1, 2, 3)").isEmpty())
    }

    // ---------- 占位符列数：引号/括号内逗号不误分段 ----------

    fun test字符串与函数括号内逗号不计入元素数() {
        // 字符串内 2 个逗号 + CONCAT 内 2 个逗号——顶层元素实为 3 个
        val sql = "SELECT\n    'a,b,c' AS s,\n    CONCAT(x, y, z) AS c,\n    n\nFROM t"
        val spec = fold(sql)!!
        assertTrue("顶层元素应为 3 而非被内嵌逗号抬高", spec.placeholder.contains("3"))
    }

    // ---------- 状态机健壮性 ----------

    fun test多条语句互不粘连() {
        val sql = "SELECT\n    a,\n    b,\n    c;\nSELECT\n    x,\n    y,\n    z\nFROM t2"
        val specs = plan(sql)
        // 第一条以分号收尾（未到 FROM 即封口，3 元素达标折叠）；第二条独立
        assertEquals(2, specs.size)
        assertTrue(specs[0].endOffset < sql.indexOf("SELECT\n    x"))
    }

    fun testSelect后无From直接结束也封口() {
        // 末尾无 FROM/WHERE（如 REPLACED INTO 展示片段）：EOF 收口仍产出
        val spec = fold("SELECT\n    a,\n    b,\n    c")
        assertTrue(spec != null)
        assertTrue(spec!!.placeholder.contains("3"))
    }

    fun test无列表语句零输出() {
        assertTrue(plan("DELETE FROM t WHERE id = 1", threshold = 1).isEmpty())
    }
}
