package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.log.LogToSqlPanel
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一语句切分器测试（T-SemicolonGuard / O-13，T-SG-01 差分样例 + T-SG-03 行为锁死）：
 *
 *  - DBX 修复样例回放（`sql_editability.rs` 7cbd141 同源）：字符串内分号、注释内分号、
 *    转义引号、空语句段；
 *  - 业务样例：日志还原真实形态（时间串带分号后缀 / 含分号 JSON / `-- SQL #N` 注释头）；
 *  - 新增语义：括号深度（`(1;2)` 不切）、BEGIN/END 块感知（块内不切、字符串内 BEGIN 不算、
 *    无配对 END 按顶层切分原样）；
 *  - 迁移一致性：三处委托方（LogToSqlPanel / SqlTypeClassifier / SqlToJavaConverter）
 *    与单实现输出逐段一致；既有形态与迁移前 A 输出逐段一致（除 T-SG-01 判定的修复项）。
 */
class SqlStatementSplitterTest {

    private fun split(sql: String) = SqlStatementSplitter.split(sql)
    private fun stripped(sql: String) = SqlStatementSplitter.splitStripped(sql)

    // ======================== DBX 样例回放（引号内分号） ========================

    @Test
    fun `DBX样例_业务串内分号不切分`() {
        assertEquals(listOf("DELETE FROM t WHERE order_no = '00390360;081111'"), split("DELETE FROM t WHERE order_no = '00390360;081111';"))
    }

    @Test
    fun `DBX样例_双引号内分号不切分`() {
        assertEquals(listOf("""SELECT "a;b" AS c FROM t"""), split("""SELECT "a;b" AS c FROM t;"""))
    }

    @Test
    fun `DBX样例_反斜杠转义引号后仍受保护`() {
        assertEquals(listOf("""SELECT 'a\'b;c' FROM t"""), split("""SELECT 'a\'b;c' FROM t;"""))
    }

    @Test
    fun `DBX样例_SQL标准加倍引号形态`() {
        // 'a''b;c' —— 第二个引号开新串，分号仍在串内
        assertEquals(listOf("SELECT 'a''b;c' FROM t"), split("SELECT 'a''b;c' FROM t;"))
    }

    // ======================== 注释内分号（注释保留契约） ========================

    @Test
    fun `注释内分号不切分_行注释`() {
        assertEquals(
            listOf("-- 备注; 含分号\nSELECT 1", "SELECT 2"),
            split("-- 备注; 含分号\nSELECT 1;\nSELECT 2;"),
        )
    }

    @Test
    fun `注释内分号不切分_块注释`() {
        assertEquals(
            listOf("/* 块注释; 含分号 */\nSELECT 1", "SELECT 2"),
            split("/* 块注释; 含分号 */\nSELECT 1;\nSELECT 2;"),
        )
    }

    @Test
    fun `注释随所属语句保留_SQL编号头形态`() {
        assertEquals(
            listOf("-- SQL #1\nSELECT 1", "-- SQL #2\nUPDATE t SET a = 1 WHERE id = 1"),
            split("-- SQL #1\nSELECT 1;\n-- SQL #2\nUPDATE t SET a = 1 WHERE id = 1;"),
        )
    }

    // ======================== 空段与容错 ========================

    @Test
    fun `空输入与纯分号`() {
        assertEquals(emptyList<String>(), split(""))
        assertEquals(emptyList<String>(), split("   ;  ; "))
    }

    @Test
    fun `连续分号空段跳过`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), split("SELECT 1;; SELECT 2;"))
    }

    @Test
    fun `无分号尾段容错`() {
        assertEquals(listOf("SELECT 1"), split("SELECT 1"))
        assertEquals(listOf("SELECT 1", "SELECT 2"), split("SELECT 1; SELECT 2"))
    }

    @Test
    fun `段trim与界符丢弃`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), split("  SELECT 1 ;\n\n  SELECT 2 ;  "))
    }

    // ======================== 业务样例（日志还原真实形态） ========================

    @Test
    fun `业务样例_时间串带分号后缀`() {
        val text = "UPDATE t SET mark = '2026-09-15 08:00:00;flag=1' WHERE id = 1;\nSELECT * FROM t;"
        assertEquals(
            listOf("UPDATE t SET mark = '2026-09-15 08:00:00;flag=1' WHERE id = 1", "SELECT * FROM t"),
            split(text),
        )
    }

    @Test
    fun `业务样例_含分号JSON值`() {
        assertEquals(
            listOf("""UPDATE t SET cfg = '{"a":1;"b":2}' WHERE id = 1"""),
            split("""UPDATE t SET cfg = '{"a":1;"b":2}' WHERE id = 1;"""),
        )
    }

    @Test
    fun `业务样例_批量还原产物多语句`() {
        val text = (1..5).joinToString("\n") { "DELETE FROM t WHERE id = $it;" }
        assertEquals(5, split(text).size)
        assertEquals("DELETE FROM t WHERE id = 3", split(text)[2])
    }

    @Test
    fun `业务样例_IN列表后多语句`() {
        val text = "SELECT * FROM t WHERE id IN (1, 2, 3);\nUPDATE t SET a = 1 WHERE id IN (1, 2);\nDELETE FROM t;"
        assertEquals(3, split(text).size)
    }

    // ======================== 新增语义：括号深度 ========================

    @Test
    fun `括号内分号不切分`() {
        assertEquals(listOf("CALL p(1; 2)"), split("CALL p(1; 2);"))
    }

    @Test
    fun `嵌套括号内分号不切分_闭后恢复切分`() {
        assertEquals(
            listOf("SELECT f((1; 2), 3)", "SELECT 4"),
            split("SELECT f((1; 2), 3);\nSELECT 4;"),
        )
    }

    @Test
    fun `字符串内括号不参与计数`() {
        // '(' 在字符串里 → 不加深度；顶层分号照切
        assertEquals(listOf("SELECT '(' AS a", "SELECT 2"), split("SELECT '(' AS a;\nSELECT 2;"))
    }

    // ======================== 新增语义：BEGIN/END 块感知 ========================

    @Test
    fun `BEGIN_END块内分号不切分`() {
        val sql = "BEGIN\n  UPDATE t SET a = 1;\n  UPDATE u SET b = 2;\nEND;"
        val parts = split(sql)
        assertEquals("块内分号不切分——整块一条", 1, parts.size)
        // 界符本身丢弃（顶层切分契约）；块内分号原样保留
        assertEquals("BEGIN\n  UPDATE t SET a = 1;\n  UPDATE u SET b = 2;\nEND", parts[0])
    }

    @Test
    fun `嵌套BEGIN_END逐层配对`() {
        val sql = "BEGIN\n  BEGIN\n    SELECT 1;\n  END;\n  SELECT 2;\nEND;"
        assertEquals(1, split(sql).size)
    }

    @Test
    fun `大小写不敏感`() {
        val sql = "begin\n  SELECT 1;\nend;"
        assertEquals(1, split(sql).size)
    }

    @Test
    fun `字符串内BEGIN不算`() {
        val sql = "SELECT 'BEGIN x; END' AS s;\nSELECT 2;"
        assertEquals(2, split(sql).size)
    }

    @Test
    fun `无配对END按顶层切分原样_保守`() {
        // MySQL 事务语句 BEGIN（无 END 配对）→ 不启用块感知，分号照常切分
        val sql = "BEGIN;\nUPDATE t SET a = 1;\nCOMMIT;"
        assertEquals(listOf("BEGIN", "UPDATE t SET a = 1", "COMMIT"), split(sql))
    }

    @Test
    fun `块结束后恢复顶层切分`() {
        val sql = "BEGIN\n  SELECT 1;\nEND;\nSELECT 2;"
        assertEquals(2, split(sql).size)
        assertEquals("SELECT 2", split(sql)[1])
    }

    // ======================== splitStripped（分类契约） ========================

    @Test
    fun `splitStripped_去注释保语句`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), stripped("-- header\nSELECT 1;\nSELECT 2;"))
    }

    @Test
    fun `splitStripped_字符串内注释符不误剥`() {
        // 迁移前 SqlTypeClassifier.stripComments 会把 'a--b' 的 -- 当注释起点（历史缺陷②）
        assertEquals(listOf("SELECT 'a--b;c' FROM t"), stripped("SELECT 'a--b;c' FROM t;"))
    }

    @Test
    fun `splitStripped_空段跳过`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), stripped("SELECT 1;; SELECT 2"))
    }

    // ======================== T-SG-01 差分修复项锁死（迁移前 B 的错误行为） ========================

    @Test
    fun `差分修复①_B字符串内分号误切已修`() {
        // 迁移前 SqlTypeClassifier.splitStatements 对 'a;b' 切成两段（naive split(";")）
        val statements = SqlTypeClassifier.splitStatements("UPDATE t SET name = 'a;b' WHERE id = 1")
        assertEquals(1, statements.size)
        assertEquals("UPDATE t SET name = 'a;b' WHERE id = 1", statements[0])
    }

    @Test
    fun `差分修复②_B注释内分号误切已修`() {
        // 迁移前 B 先切分后去注释：行注释内的 ; 会切成半条语句
        // （样例注意：语句 1 的界符须在注释之前——行注释吞掉本行全部分号，注释内的界符本就不存在）
        val statements = SqlTypeClassifier.splitStatements("SELECT 1; -- note; here\nSELECT 2;")
        assertEquals(listOf("SELECT 1", "SELECT 2"), statements)
    }

    // ======================== T-SG-03 迁移一致性（三委托方与单实现逐段一致） ========================

    @Test
    fun `委托一致_LogToSqlPanel`() {
        for (sql in consistencySamples()) {
            assertEquals("A 与单实现输出应逐段一致：$sql", SqlStatementSplitter.split(sql), LogToSqlPanel.splitSqlStatements(sql))
        }
    }

    @Test
    fun `委托一致_SqlTypeClassifier`() {
        for (sql in consistencySamples()) {
            assertEquals("B 与单实现（stripped）输出应逐段一致：$sql", SqlStatementSplitter.splitStripped(sql), SqlTypeClassifier.splitStatements(sql))
        }
    }

    @Test
    fun `委托一致_SqlToJavaConverter`() {
        for (sql in consistencySamples()) {
            assertEquals("批量转换切分与单实现输出应逐段一致：$sql", SqlStatementSplitter.split(sql), SqlToJavaConverter.splitStatements(sql))
        }
    }

    @Test
    fun `迁移一致_既有形态与迁移前A输出逐段一致`() {
        // 迁移前 A 的历史断言样例（LogToSqlPanelExecuteTest 既有期望）逐字复刻
        assertEquals(listOf("SELECT 1"), LogToSqlPanel.splitSqlStatements("SELECT 1;"))
        assertEquals(
            listOf("-- SQL #1\nSELECT 1", "-- SQL #2\nUPDATE t SET a = 1 WHERE id = 1"),
            LogToSqlPanel.splitSqlStatements("-- SQL #1\nSELECT 1;\n-- SQL #2\nUPDATE t SET a = 1 WHERE id = 1;"),
        )
        assertEquals(1, LogToSqlPanel.splitSqlStatements("UPDATE t SET name = 'a;b' WHERE id = 1;").size)
    }

    /** 一致性样例池（覆盖三委托方的共用形态——新增语义与历史形态混合）。 */
    private fun consistencySamples(): List<String> = listOf(
        "SELECT 1",
        "SELECT 1; SELECT 2;",
        "-- SQL #1\nSELECT 1;\n-- SQL #2\nUPDATE t SET a = 1;",
        "UPDATE t SET name = 'a;b' WHERE id = 1;",
        "/* c; */ SELECT 1; SELECT 2;",
        "CALL p(1; 2);",
        "BEGIN\n  SELECT 1;\nEND;\nSELECT 2;",
        // T-DA-02：DELIMITER 指令形态进一致性样例池（三委托方与单实现同口径）
        "DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT 1; END$$\nDELIMITER ;\nSELECT 2;",
    )

    // ======================== T-SQL 控制流批不可拆（T-TsqlBatchSplit / O-28） ========================

    @Test
    fun `TSQL_IF_ELSE双BEGIN形态整批不拆`() {
        // DBX 1a7b44f 同款样例：两分支 BEGIN/END 包裹，块感知已保护内层分号
        val sql = "IF EXISTS (SELECT 1 FROM t WHERE id = 1) " +
            "BEGIN UPDATE t SET x = 1; END ELSE BEGIN INSERT INTO t VALUES (1); END;"
        assertEquals(1, split(sql).size)
        assertEquals(1, stripped(sql).size)
    }

    @Test
    fun `TSQL_裸IF_ELSE跨顶层分号合并为整批`() {
        // 两分支各自以顶层 `;` 终结且无 BEGIN/END——逐段切分会产出 ELSE 起头碎句
        val sql = "IF @x = 1 UPDATE t SET a = 1;\nELSE INSERT INTO t VALUES (1);"
        val parts = split(sql)
        assertEquals("裸 IF…ELSE 形态整批不拆", 1, parts.size)
        assertTrue("批首保留 IF", parts[0].startsWith("IF @x = 1"))
        assertTrue("ELSE 并入同批", parts[0].contains("ELSE INSERT INTO t VALUES (1)"))
    }

    @Test
    fun `TSQL_ELSE_IF链逐级并入`() {
        val sql = "IF @a = 1 SELECT 1; ELSE IF @b = 2 SELECT 2; ELSE SELECT 3;"
        assertEquals("ELSE IF 链整批不拆", 1, split(sql).size)
    }

    @Test
    fun `TSQL_嵌套BEGIN_END配对`() {
        val sql = "IF @x = 1 BEGIN IF @y = 2 BEGIN DELETE FROM t; END END"
        assertEquals(1, split(sql).size)
    }

    @Test
    fun `TSQL_批后独立语句不误并`() {
        // IF 批已闭合（无 ELSE 起头后续段）→ 后续独立语句正常切分
        val parts = split("IF @x = 1 SELECT 1; SELECT 2;")
        assertEquals(listOf("IF @x = 1 SELECT 1", "SELECT 2"), parts)
    }

    @Test
    fun `TSQL_配对失衡整段保留不抛异常`() {
        // 截断批（BEGIN 未闭合）：继续吞后续段直到批尾（宁整勿碎）
        val parts = split("IF @x = 1 BEGIN UPDATE t SET x = 1; DELETE FROM t2")
        assertEquals("失衡批整段保留", 1, parts.size)
        assertTrue(parts[0].contains("DELETE FROM t2"))
    }

    @Test
    fun `TSQL_控制流关键字在字符串注释内不误判`() {
        // 字符串字面量内的 IF/ELSE/BEGIN 不触发批首识别（首段仍 SELECT）
        assertEquals(2, split("SELECT 'IF BEGIN ELSE' FROM t; SELECT 2;").size)
        // 前导注释内的 IF 不当批首——首关键字跳过注释取真实词（stripped 口径断言；
        // split 保留注释故用 stripped 验证批首识别未触发）
        assertEquals(listOf("SELECT 1", "SELECT 2"), stripped("-- IF something\nSELECT 1; SELECT 2;"))
    }

    @Test
    fun `TSQL_MySQL函数IF不误吞`() {
        // MySQL `IF()` 是函数不出现在语句首位；SELECT 起头不触发合并
        assertEquals(
            listOf("SELECT IF(a > 1, 'x', 'y') FROM t", "SELECT 2"),
            split("SELECT IF(a > 1, 'x', 'y') FROM t; SELECT 2;"),
        )
    }

    // ======================== MERGE 多行样例（O-42 / T-OQCS-04 守卫） ========================

    /**
     * Oracle 形态多行 MERGE 整段产出为**单条**语句。
     *
     * 定位为**守卫用例**（预期直接绿，非修 bug）：本切分器全文无 MERGE 特判
     * （批首合并常量 `CONTROL_FLOW_BATCH_HEADS = setOf("IF")`，只有 TSQL 控制流批），
     * MERGE 体内**无顶层 `;`** → 分号状态机天然整段产出。此用例把该结论钉死，
     * 防止日后有人给 `WHEN MATCHED THEN UPDATE SET ...;` 之类的形态误加批首合并而切碎 MERGE。
     */
    @Test
    fun `Oracle形态多行MERGE整段一条`() {
        val merge = """
            MERGE INTO "issue_new" t
            USING (SELECT ? AS id, ? AS user_name FROM dual) s
            ON (t."id" = s.id)
            WHEN MATCHED THEN UPDATE SET t."user_name" = s.user_name
            WHEN NOT MATCHED THEN INSERT ("id", "user_name") VALUES (s.id, s.user_name)
        """.trimIndent() + ";"
        assertEquals(listOf(merge.removeSuffix(";")), split(merge))
    }

    @Test
    fun `MERGE后接独立语句不误并`() {
        val script = "MERGE INTO t USING s ON (t.id = s.id)\nWHEN MATCHED THEN UPDATE SET t.a = s.a;\nSELECT 1;"
        assertEquals(2, split(script).size)
        assertTrue(split(script)[0].startsWith("MERGE INTO t"))
    }

    // ======================== DELIMITER 指令感知（T-DA-02 / O-39） ========================

    /** 含 `DELIMITER $$` 的存储过程脚本（MySQL 客户端粘贴典型形态）。 */
    private fun procedureScript(): String = """
        DELIMITER $$
        CREATE PROCEDURE p()
        BEGIN
          SELECT 1;
          SELECT 2;
        END$$
        DELIMITER ;
        SELECT 3;
    """.trimIndent()

    @Test
    fun `DELIMITER_存储过程整段一条_指令行不产出_界符不残留`() {
        val parts = split(procedureScript())
        assertEquals("过程体一条 + 复位后的普通语句一条", 2, parts.size)
        assertEquals(
            "过程体保留内部 `;`、去掉首尾指令与 `$$`",
            "CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\n  SELECT 2;\nEND",
            parts[0],
        )
        assertEquals("SELECT 3", parts[1])
        assertTrue("`$$` 不残留", parts.none { it.contains("$$") })
        assertTrue("DELIMITER 指令行不产出", parts.none { it.trimStart().uppercase().startsWith("DELIMITER") })
    }

    @Test
    fun `DELIMITER_自定义界符生效期内分号不切分`() {
        // 过程体无 BEGIN/END 包裹（单语句体）：`;` 仍属语句内部，只有 `$$` 终结
        val parts = split("DELIMITER $$\nCREATE PROCEDURE p() SELECT 1;\nSELECT 2;$$")
        assertEquals("界符生效期内顶层 `;` 不切分", 1, parts.size)
        assertEquals("CREATE PROCEDURE p() SELECT 1;\nSELECT 2;", parts[0])
    }

    @Test
    fun `DELIMITER_复位后分号语义恢复`() {
        val parts = split("DELIMITER $$\nSELECT 1$$\nDELIMITER ;\nSELECT 2;\nSELECT 3;")
        assertEquals(listOf("SELECT 1", "SELECT 2", "SELECT 3"), parts)
    }

    @Test
    fun `DELIMITER_斜杠界符形态_块注释不误判`() {
        // `//` 界符与块注释起始 `/*` 不同字符——注释分支优先，注释随语句保留
        val parts = split(
            "DELIMITER //\nCREATE PROCEDURE p()\nBEGIN\n  /* 注释; */ SELECT 1;\nEND//\nSELECT 2;",
        )
        assertEquals(2, parts.size)
        assertTrue(parts[0], parts[0].contains("/* 注释; */ SELECT 1;"))
        assertTrue("`//` 不残留", parts.none { it.contains("//") })
    }

    @Test
    fun `DELIMITER_空参数复位为分号`() {
        // MySQL 客户端 `DELIMITER` 无参数 = 复位为 `;`（对齐客户端语义）
        assertEquals(listOf("SELECT 1", "SELECT 2"), split("DELIMITER $$\nSELECT 1$$\nDELIMITER\nSELECT 2;"))
    }

    @Test
    fun `DELIMITER_字符串与注释内不识别`() {
        assertEquals(
            listOf("SELECT 'DELIMITER $$' AS s", "SELECT 2"),
            split("SELECT 'DELIMITER $$' AS s;\nSELECT 2;"),
        )
        assertEquals(
            listOf("-- DELIMITER $$\nSELECT 1", "SELECT 2"),
            split("-- DELIMITER $$\nSELECT 1;\nSELECT 2;"),
        )
    }

    @Test
    fun `DELIMITER_非行首或待切段非空时不识别`() {
        // 行首限定：语句中间的 DELIMITER 词不当指令（无界符切换，分号照切）
        assertEquals(
            listOf("SELECT DELIMITER FROM t", "SELECT 2"),
            split("SELECT DELIMITER FROM t;\nSELECT 2;"),
        )
        // 待切段非空（指令未在语句边界）→ 不识别，整段按普通文本处理（保守：不吞不拆）
        val parts = split("SELECT 1\nDELIMITER $$")
        assertEquals(1, parts.size)
        assertTrue(parts[0], parts[0].contains("DELIMITER"))
    }

    @Test
    fun `DELIMITER_splitStripped同口径`() {
        val statements = stripped(procedureScript())
        assertEquals(2, statements.size)
        assertEquals("CREATE", SqlTypeClassifier.getFirstKeyword(statements[0]))
    }
}
