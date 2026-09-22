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
        // T-SSP-01：Oracle 匿名块形态进池（三委托方执行链/分类链与单实现同口径）
        "DECLARE\n  PROCEDURE p IS\n  BEGIN\n    NULL;\n  END;\nBEGIN\n  p;\nEND;",
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

    // ======================== Oracle PL/SQL 匿名块（T-SqlSplitterPlsql / O-47） ========================

    /**
     * DECLARE 声明段吞并（T-SSP-01）：局部例程体、前向声明与块主体同属**一条**语句。
     *
     * 改前缺陷形态（实测 2 段）：`beginModeEnabled` 只挡「前视武装」，而 `flushWord()` 在
     * armed 后对 BEGIN/END 无条件计数——局部例程的 `END;` 把 blockDepth 归 0，紧随的 `;`
     * 即切段（外层块因 armed 后无条件计数仍是完整一段，故碎成 2 段而非 3 段）。
     */
    @Test
    fun `PLSQL_匿名块含局部例程整段一条`() {
        val sql = "DECLARE\n  PROCEDURE p IS\n  BEGIN\n    NULL;\n  END;\nBEGIN\n  p;\nEND;"
        val parts = split(sql)
        assertEquals("声明段与块主体同属一条语句", 1, parts.size)
        assertEquals(
            "块内 `;` 原样保留、语句界符丢弃",
            "DECLARE\n  PROCEDURE p IS\n  BEGIN\n    NULL;\n  END;\nBEGIN\n  p;\nEND",
            parts[0],
        )
    }

    /** 前向声明 `PROCEDURE p IS;`（无 BEGIN 体）——声明段内顶层 `;` 不切分（T-SSP-01）。 */
    @Test
    fun `PLSQL_前向声明不切碎`() {
        val sql = "DECLARE\n  PROCEDURE p IS;\nBEGIN\n  p;\nEND;"
        assertEquals("前向声明的 `;` 是声明分隔符而非语句界符", 1, split(sql).size)
    }

    /** 声明段吞并边界＝深度归零处（主体 `END`），不是「末个 END」——后接独立语句不得误并。 */
    @Test
    fun `PLSQL_匿名块后独立语句不误并`() {
        val parts = split("DECLARE\n  x NUMBER;\nBEGIN\n  NULL;\nEND;\nSELECT 1;")
        assertEquals(listOf("DECLARE\n  x NUMBER;\nBEGIN\n  NULL;\nEND", "SELECT 1"), parts)
    }

    /** 匿名块后紧接第二个匿名块（DECLARE 起头 + 裸 BEGIN 起头）——两条，不得并成一条。 */
    @Test
    fun `PLSQL_声明段后接BEGIN块不误并`() {
        val parts = split("DECLARE\n  x NUMBER;\nBEGIN\n  NULL;\nEND;\nBEGIN\n  SELECT 2;\nEND;")
        assertEquals("两个匿名块各自成条", 2, parts.size)
        assertTrue("第二块保留自身 BEGIN", parts[1].startsWith("BEGIN"))
    }

    /** 裸 BEGIN 块在前、DECLARE 匿名块在后——块感知已武装仍须认领声明段（两条）。 */
    @Test
    fun `PLSQL_BEGIN块后接声明段块不误并`() {
        val parts = split("BEGIN\n  NULL;\nEND;\nDECLARE\n  x NUMBER;\nBEGIN\n  NULL;\nEND;")
        assertEquals("两条各自成条", 2, parts.size)
        assertTrue("第二条保留 DECLARE 起头", parts[1].startsWith("DECLARE"))
    }

    /** 多个匿名块串接（裸 BEGIN 起头）——两条；守卫用例（改前即绿，钉死「不误并不误碎」）。 */
    @Test
    fun `PLSQL_多个匿名块串接不误并不误碎`() {
        val parts = split("BEGIN SELECT 1; END;\nBEGIN SELECT 2; END;")
        assertEquals("两个匿名块两条", 2, parts.size)
        assertEquals("BEGIN SELECT 1; END", parts[0])
        assertEquals("BEGIN SELECT 2; END", parts[1])
    }

    /** 无配对 `END` 的 `DECLARE`（MySQL 声明语句形态）——不武装声明段，按顶层切分原样（保守）。 */
    @Test
    fun `PLSQL_无配对END的DECLARE按顶层切分原样_保守`() {
        assertEquals(listOf("DECLARE\n  x NUMBER", "SELECT 1"), split("DECLARE\n  x NUMBER;\nSELECT 1;"))
    }

    /**
     * 声明段武装须以「后方确有块主体 BEGIN」为前提（T-SSP-01 订正，独立复核回归）。
     *
     * 改前缺陷：武装只要求「语句首 DECLARE + 后方存在 END 词」。T-SQL `DECLARE @x INT;` 与
     * PG `DECLARE c CURSOR FOR SELECT … CASE … END …` 都是**合法顶层语句**且后方本就有 END 词
     * （CASE 的 END），于是被误当 PL/SQL 声明段武装 → 顶层 `;` 直到文本尾都不再切分，
     * 后续语句被整体吞并（实测 3 段 → 1 段）。修法：武装前再要求后方存在 BEGIN 词。
     */
    @Test
    fun `TSQL_DECLARE语句不误开声明段`() {
        val parts = split("DECLARE @a INT;\nSELECT 1;\nSELECT CASE WHEN 1=1 THEN 1 ELSE 0 END FROM t;")
        assertEquals("T-SQL DECLARE 后方无 BEGIN，不得武装为声明段", 3, parts.size)
        assertEquals("SELECT 1", parts[1])
    }

    /** 单条 T-SQL `DECLARE` + 后接一条含 CASE 的查询：不得并成一条。 */
    @Test
    fun `TSQL_DECLARE后接CASE查询不误并`() {
        val parts = split("DECLARE @x INT;\nSELECT CASE WHEN 1=1 THEN 1 ELSE 0 END AS y;")
        assertEquals(2, parts.size)
        assertEquals("SELECT CASE WHEN 1=1 THEN 1 ELSE 0 END AS y", parts[1])
    }

    /** PG `DECLARE … CURSOR FOR …` 是合法顶层语法，同样不得武装（后方只有 CASE 的 END）。 */
    @Test
    fun `PG_DECLARE_CURSOR不误开声明段`() {
        val parts = split("DECLARE c CURSOR FOR SELECT CASE WHEN a THEN 1 ELSE 2 END FROM t;\nFETCH ALL IN c;")
        assertEquals(2, parts.size)
        assertEquals("FETCH ALL IN c", parts[1])
    }

    /** 正对照：Oracle 匿名块声明段之后确有块主体 BEGIN，仍须整段一条（防「修回归修过头」）。 */
    @Test
    fun `PLSQL_声明段有BEGIN主体仍整段一条_正对照`() {
        val parts = split("DECLARE\n  v NUMBER;\nBEGIN\n  v := 1;\n  SELECT CASE WHEN v=1 THEN 1 ELSE 0 END INTO v FROM dual;\nEND;")
        assertEquals("声明段 + BEGIN 主体 + 块内 CASE 仍为一条", 1, parts.size)
    }

    /**
     * 块内 `CASE … END`（裸 END 收尾，T-SQL / PL/SQL CASE 表达式）——CASE 层隔离（T-SSP-02）。
     *
     * 改前缺陷：CASE 的 `END` 被当块尾递减 → 块内首个 CASE 后的 `;` 即切段（实测 3 段）。
     */
    @Test
    fun `PLSQL_块内CASE表达式不误判块尾`() {
        val sql = "BEGIN\n  SELECT CASE WHEN a THEN 1 ELSE 2 END;\n  SELECT 2;\nEND;"
        assertEquals("CASE 的 END 不递减块深度", 1, split(sql).size)
    }

    /**
     * 含 `IF` / `LOOP` 的 PL/SQL 块整段一条（T-SSP-01 订正，独立复核 major③）。
     *
     * 改前缺陷：`END IF` / `END LOOP` 仍**无条件递减块深度**（只有 CASE 层做了隔离）→ 含 IF/LOOP 的
     * 匿名块与具名过程依旧被切碎，而碎句仍会被逐条提交执行——正是本任务要消除的危害。
     * 复核实测（改前）：`BEGIN IF 1=1 THEN NULL; END IF; END;` 基线 2 段 → 本订正前仍 2 段。
     *
     * 与 CASE 层的差别：CASE 有**裸 END** 收尾形态故须 caseDepth 计数；IF/LOOP/WHILE/REPEAT
     * 只有双词终结形态，跳过递减即可（它们也不自增层计数，无需配对状态）。
     */
    @Test
    fun `PLSQL_块内IF_END_IF不误判块尾`() {
        assertEquals(1, split("BEGIN\n  IF 1=1 THEN\n    NULL;\n  END IF;\nEND;").size)
    }

    @Test
    fun `PLSQL_块内LOOP_END_LOOP不误判块尾`() {
        assertEquals(1, split("BEGIN\n  IF 1=1 THEN NULL; END IF;\n  LOOP NULL; END LOOP;\nEND;").size)
    }

    /** 具名过程体含 IF：`CREATE OR REPLACE PROCEDURE p IS BEGIN … END IF; END;` 整段一条。 */
    @Test
    fun `PLSQL_具名过程含IF不误碎`() {
        assertEquals(1, split("CREATE OR REPLACE PROCEDURE p IS BEGIN IF 1=1 THEN NULL; END IF; END;").size)
    }

    /** 声明段 + 块主体含 IF：整段一条（复核改前读数 3 段）。 */
    @Test
    fun `PLSQL_声明段含IF整段一条`() {
        assertEquals(1, split("DECLARE v NUMBER; BEGIN IF v=1 THEN NULL; END IF; END;").size)
    }

    /** 正对照：`END IF` 块后接独立语句仍须切开（层隔离不得反噬成吞并）。 */
    @Test
    fun `PLSQL_IF块后独立语句不误并`() {
        val parts = split("BEGIN\n  IF 1=1 THEN NULL; END IF;\nEND;\nSELECT 1;")
        assertEquals("IF 块一条 + 独立语句一条", 2, parts.size)
        assertEquals("SELECT 1", parts[1])
    }

    /**
     * T-SQL 批内 `CASE … END` 不参与 `beginEndBalance` 配对（T-SSP-02 同步改造）：
     * 截断批（BEGIN 无配对 END）继续吞后续段，维持「宁整勿碎」。
     */
    @Test
    fun `TSQL_批内CASE不误判配对失衡`() {
        val parts = split("IF @x = 1 BEGIN SELECT CASE WHEN a THEN 1 ELSE 2 END; DELETE FROM t2")
        assertEquals("CASE 的 END 不得充当批内块尾配对", 1, parts.size)
        assertTrue(parts[0].contains("DELETE FROM t2"))
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
    fun `DELIMITER_过程体内DECLARE不误开声明段`() {
        // T-SSP-01 反向边界：过程体 BEGIN 内的 DECLARE（MySQL 局部变量声明）在 blockDepth>0，
        // 不触发声明段吞并——过程体仍一条
        val parts = split(
            "DELIMITER $$\nCREATE PROCEDURE p()\nBEGIN\n  DECLARE x INT;\n  SELECT 1;\nEND$$\nDELIMITER ;\nSELECT 2;",
        )
        assertEquals("过程体一条 + 复位后普通语句一条", 2, parts.size)
        assertTrue(parts[0], parts[0].contains("DECLARE x INT;"))
    }

    @Test
    fun `DELIMITER_自定义界符下的匿名块整段一条`() {
        // T-SSP-01：声明段在自定义界符生效期同样成立（`$$` 才是语句终结符，段内 `;` 不切分）
        val parts = split("DELIMITER $$\nDECLARE\n  x NUMBER;\nBEGIN\n  NULL;\nEND$$\nDELIMITER ;\nSELECT 1;")
        assertEquals("匿名块一条 + 复位后普通语句一条", 2, parts.size)
        assertTrue(parts[0], parts[0].startsWith("DECLARE"))
    }

    @Test
    fun `DELIMITER_splitStripped同口径`() {
        val statements = stripped(procedureScript())
        assertEquals(2, statements.size)
        assertEquals("CREATE", SqlTypeClassifier.getFirstKeyword(statements[0]))
    }
}
