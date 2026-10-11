package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.ColumnNameCalibrator
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.ColumnMeta
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.DasAccessors
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.ModelTraversal
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.TableMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P7 / P7b：同名表**按库（schema）限定**的匹配语义 + 遍历 0 张表判「查不了」。
 *
 * 背景（真机 2026-09-20）：`scan: table=user, ds=jpa_demo@localhost, tablesScanned=335, hit=true,
 * foundColumns=60`——一次遍历把所有同名表的列合并进同一个 Map（60 = 业务表 9 列 + `mysql.user`
 * 系统表 50+），表注释也被最后一张覆盖。三个成因各自成断言：
 *
 *  1. **匹配**只比表名不看库 → 表祖先链须含本数据源 URL 的库名（[JdbcUrlDatabase] 口径），
 *     且首个匹配即锁定（旧行为=继续合并、后一张覆盖）；
 *  2. **缓存**键只有表名，而命中发生在匹配之前 → 键带库名维度（同名表在两个库各自成条目）；
 *  3. **补全候选**跨全部数据源 putIfAbsent 并集 → 每个表名只认首个持有它的数据源。
 *
 * P7b：模型非 null 但遍历 0 张表（内省未完成）此前被当「确认没有此表」记负缓存——DDL 生效探测
 * 的 500/1500/3000ms 三次退避重试会被首次那一记负缓存全挡掉，误报「DDL 未生效」。
 *
 * O-58（2026-09-21）：同一套替身还用于验「列元数据过滤不得污染列名集合」——[TableMeta.columns] 只收录
 * 「有注释 ∨ 有类型串 ∨ notNull 为 true」的列，而 [TableMeta.allColumnNames] 无条件收录全部列名；
 * 列名校正的唯一性判定必须落在后者上（前者缺列会让歧义凭空消失 → 选错列）。经
 * [DatabaseSchemaResolver.columnNameSources] + [DatabaseSchemaResolver.mergeCachedColumnNames]
 * 走真实的缓存读法（`columnNamesCached` 只差 project 非空与 database 插件可用两道闸，单测环境给不出）。
 *
 * 单测环境无 database 插件（`Class.forName` 全链不可用，见 [DatabaseSchemaResolverObjectKindTest]），
 * 故经 [DatabaseSchemaResolver.traversalForTest] 注入遍历替身 + [DatabaseSchemaResolver
 * .dataSourceDatabaseForTest] 注入库名替身；**匹配/合并/判定逻辑仍是生产代码本体**。
 */
class DatabaseSchemaResolverSchemaScopeTest {

    @Before
    fun resetState() {
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.invalidateAll()
        DatabaseSchemaResolver.negativeTtlMs = DatabaseSchemaResolver.NEGATIVE_TTL_MS
        DatabaseSchemaResolver.traversalForTest = null
        DatabaseSchemaResolver.dataSourceDatabaseForTest = null
    }

    @After
    fun restoreState() {
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.invalidateAll()
        DatabaseSchemaResolver.negativeTtlMs = DatabaseSchemaResolver.NEGATIVE_TTL_MS
        DatabaseSchemaResolver.traversalForTest = null
        DatabaseSchemaResolver.dataSourceDatabaseForTest = null
    }

    // ---------- 假模型（只保留 P7 判定用到的四件事：名字 / 注释 / 祖先链 / 列） ----------

    private class FakeNs(val nsName: String, val up: Any? = null)
    private class FakeTable(
        val tableName: String,
        val columns: List<String>,
        val up: Any? = null,
        val tableComment: String? = null,
    )
    private class FakeColumn(val columnName: String, val columnComment: String?)

    private val accessors = DasAccessors(
        isTable = { it is FakeTable },
        isColumn = { it is FakeColumn },
        name = {
            when (it) {
                is FakeTable -> it.tableName
                is FakeColumn -> it.columnName
                is FakeNs -> it.nsName
                else -> null
            }
        },
        comment = {
            when (it) {
                is FakeTable -> it.tableComment
                is FakeColumn -> it.columnComment
                else -> null
            }
        },
        children = { (it as? FakeTable)?.columns?.map { c -> FakeColumn(c, "注释_$c") } },
        parent = {
            when (it) {
                is FakeTable -> it.up
                is FakeNs -> it.up
                else -> null
            }
        },
        typeSpec = { "int(11)" },
        notNull = { true },
    )

    /** 零 COMMENT 的来源（真机 jpa_demo.user：9 列都有类型串、注释全空）。 */
    private val noCommentAccessors = DasAccessors(
        isTable = { it is FakeTable },
        isColumn = { it is FakeColumn },
        name = {
            when (it) {
                is FakeTable -> it.tableName
                is FakeColumn -> it.columnName
                is FakeNs -> it.nsName
                else -> null
            }
        },
        comment = { null },
        children = { (it as? FakeTable)?.columns?.map { c -> FakeColumn(c, null) } },
        parent = {
            when (it) {
                is FakeTable -> it.up
                is FakeNs -> it.up
                else -> null
            }
        },
        typeSpec = { "int(11)" },
        notNull = { true },
    )

    /** 库节点 → 模式节点 → 表（祖先链含库名，与 DBN 的 Database → Schema → Table 同形）。 */
    private fun tableIn(
        database: String?,
        tableName: String,
        columns: List<String>,
        tableComment: String? = null,
    ): FakeTable = FakeTable(tableName, columns, FakeNs("public", database?.let { FakeNs(it) }), tableComment)

    /** 注入遍历替身并返回调用计数（计数 = 真实扫描次数，负缓存语义落在它上面）。 */
    private fun stubTraversal(vararg elements: Any?): () -> Int {
        var calls = 0
        DatabaseSchemaResolver.traversalForTest = {
            calls++
            ModelTraversal(elements.toList(), accessors)
        }
        return { calls }
    }

    private fun stubDatabase(name: String?) {
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { name }
    }

    // ---------- P7b：遍历 0 张表 = 查不了 ----------

    @Test
    fun `遍历0张表_判查不了且不写负缓存_下次调用重扫`() {
        stubDatabase("mpj_demo")
        val calls = stubTraversal() // model 非 null（替身即已拿到模型），但元素列表为空

        val ds = Any()
        val meta = DatabaseSchemaResolver.resolveFromSources("t_zero", listOf(ds))
        assertTrue("遍历 0 张表 → 走「无法解析」分支（scanBailed）", meta.scanBailed)
        assertNull(meta.comment)
        assertTrue(meta.columns.isEmpty())
        assertEquals("首次进入扫描", 1, calls())

        val again = DatabaseSchemaResolver.resolveFromSources("t_zero", listOf(ds))
        assertTrue("未写负缓存", again.scanBailed)
        assertEquals("未写负缓存 → 下次调用仍重扫（内省完成后即可命中）", 2, calls())
    }

    @Test
    fun `元素非空但无表节点_同样判查不了`() {
        stubDatabase("mpj_demo")
        val calls = stubTraversal(FakeNs("mpj_demo"), FakeNs("public", FakeNs("mpj_demo")))

        val ds = Any()
        val meta = DatabaseSchemaResolver.resolveFromSources("t_zero", listOf(ds))
        assertTrue("遍历有元素但一张 DasTable 都没筛出（模型只建到库/模式）→ 查不了", meta.scanBailed)
        DatabaseSchemaResolver.resolveFromSources("t_zero", listOf(ds))
        assertEquals("同样不写负缓存", 2, calls())
    }

    @Test
    fun `遍历到表但未命中_仍按未找到记负缓存`() {
        stubDatabase("mpj_demo")
        val calls = stubTraversal(tableIn("mpj_demo", "other_table", listOf("id")))

        val ds = Any()
        val meta = DatabaseSchemaResolver.resolveFromSources("t_absent", listOf(ds))
        assertFalse("扫到过表 → 不是「查不了」", meta.scanBailed)
        assertNull(meta.comment)
        DatabaseSchemaResolver.resolveFromSources("t_absent", listOf(ds))
        assertEquals("真·未命中仍按负缓存只扫一次（T-MetaCheapSource 语义不变）", 1, calls())
    }

    @Test
    fun `bail判定_已有原因不被0张表覆盖`() {
        assertEquals(
            "遍历 0 张表 → bail 非空",
            DatabaseSchemaResolver.TRAVERSED_ZERO_TABLES,
            DatabaseSchemaResolver.bailAfterTraversal(hit = false, scannedTables = 0, existingBail = null),
        )
        assertNull(
            "扫到过表且未命中 → 不是查不了",
            DatabaseSchemaResolver.bailAfterTraversal(hit = false, scannedTables = 3, existingBail = null),
        )
        assertNull(
            "命中 → 不是查不了",
            DatabaseSchemaResolver.bailAfterTraversal(hit = true, scannedTables = 1, existingBail = null),
        )
        assertEquals(
            "已有更精确的 bail（模型未加载等）不被覆盖",
            "数据源模型未加载(getModel=null)",
            DatabaseSchemaResolver.bailAfterTraversal(
                hit = false,
                scannedTables = 0,
                existingBail = "数据源模型未加载(getModel=null)",
            ),
        )
    }

    // ---------- P7：同名表按库限定 + 首个匹配锁定 ----------

    @Test
    fun `同名表只取首个匹配_不再合并后续同名表的列`() {
        stubDatabase("mpj_demo")
        stubTraversal(
            tableIn("mpj_demo", "user", listOf("id", "username")),
            tableIn("mpj_demo", "user", listOf("id", "host", "password_last_changed")),
        )

        val meta = DatabaseSchemaResolver.resolveFromSources("user", listOf(Any()))
        assertEquals(
            "首个匹配即锁定：后续同名表的列不得并进来（旧行为=合并成 4 列）",
            setOf("id", "username"),
            meta.columns.keys,
        )
    }

    @Test
    fun `同名系统表不在本库时_不命中_不被系统表列污染`() {
        stubDatabase("mpj_demo")
        stubTraversal(
            // 遍历顺序把系统表放在前面（真机 mysql.user 常在业务库之前/之后不定）
            tableIn("mysql", "user", listOf("host", "user", "authentication_string")),
            tableIn("mpj_demo", "user", listOf("id", "username")),
        )

        val meta = DatabaseSchemaResolver.resolveFromSources("user", listOf(Any()))
        assertEquals(
            "祖先链不含本数据源库名的同名表被跳过，取到业务库那张",
            setOf("id", "username"),
            meta.columns.keys,
        )
    }

    @Test
    fun `库名取不到_退回按名匹配_首个同名表即命中`() {
        stubDatabase(null) // URL 无库段 / Oracle SID 形态
        stubTraversal(
            tableIn("mysql", "user", listOf("host")),
            tableIn("mpj_demo", "user", listOf("id", "username")),
        )

        val meta = DatabaseSchemaResolver.resolveFromSources("user", listOf(Any()))
        assertEquals(
            "取不到库名 → 退回按名匹配（不引入新失败模式），首个同名表即锁定",
            setOf("host"),
            meta.columns.keys,
        )
    }

    // ---------- P7：缓存维度（库名进键） ----------

    @Test
    fun `同名表在两个库的数据源下_各自只拿本库那张表的列`() {
        val dsA = Any()
        val dsB = Any()
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { ds -> if (ds === dsA) "db_a" else "db_b" }
        stubTraversal(
            tableIn("db_a", "user", listOf("a_only")),
            tableIn("db_b", "user", listOf("b_only")),
        )

        val a = DatabaseSchemaResolver.resolveFromSources("user", listOf(dsA))
        val b = DatabaseSchemaResolver.resolveFromSources("user", listOf(dsB))
        assertEquals("A 库数据源只拿 A 库那张表", setOf("a_only"), a.columns.keys)
        assertEquals("B 库数据源只拿 B 库那张表", setOf("b_only"), b.columns.keys)
    }

    @Test
    fun `缓存键带库名_同数据源换库后不吃旧库的缓存条目`() {
        val ds = Any()
        var currentDb = "db_a"
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { currentDb }
        val calls = stubTraversal(tableIn("db_a", "user", listOf("a_only")))

        val a = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        assertEquals(setOf("a_only"), a.columns.keys)
        assertEquals(1, calls())

        // 同一数据源改连另一个库（重建连接 URL）：旧实现键只有表名 → 直接吃 db_a 的缓存条目
        currentDb = "db_b"
        DatabaseSchemaResolver.traversalForTest = {
            ModelTraversal(listOf(tableIn("db_b", "user", listOf("b_only"))), accessors)
        }
        val b = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        assertEquals("库名进键后按 db_b 重新取数，不吃 db_a 的缓存", setOf("b_only"), b.columns.keys)
    }

    @Test
    fun `缓存键_库名取不到时退回纯表名`() {
        val ds = Any()
        stubDatabase(null)
        val calls = stubTraversal(tableIn(null, "user", listOf("id")))

        val first = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        assertEquals(setOf("id"), first.columns.keys)
        DatabaseSchemaResolver.resolveFromSources("USER", listOf(ds))
        assertEquals("库名缺失时键仍是表名小写（大小写归一，正缓存命中不重扫）", 1, calls())
    }

    // ---------- P7：补全候选不跨数据源并集 ----------

    @Test
    fun `补全候选_每个表名只认首个持有它的数据源`() {
        val cols = { names: List<String> -> names.associateWith { ColumnMeta("注释_$it", "int(11)") } }
        val merged = DatabaseSchemaResolver.mergeCachedColumns(
            listOf("user", "role"),
            listOf(
                { name: String -> if (name == "user") cols(listOf("a_only", "shared")) else null },
                { name: String -> if (name == "user") cols(listOf("b_only", "shared")) else cols(listOf("role_id")) },
            ),
        )
        assertEquals(
            "user 只取首个持有它的数据源（旧行为=两源并集成 a_only/shared/b_only）",
            setOf("a_only", "shared"),
            merged.filterKeys { it != "role_id" }.keys,
        )
        assertTrue("后一个数据源里的 role 表照常进候选（首源未持有时不被前源挡住）", merged.containsKey("role_id"))
    }

    @Test
    fun `补全候选_无任何数据源持有时为空`() {
        val merged = DatabaseSchemaResolver.mergeCachedColumns(
            listOf("ghost"),
            listOf({ _: String -> null }, { _: String -> null }),
        )
        assertTrue("未预热/无数据源 → 空 Map（调用方回落实体列，改动前语义）", merged.isEmpty())
    }

    // ---------- 缓存与负缓存的口径回归（P7 改动不得破坏既有语义） ----------

    @Test
    fun `正命中仍零扫描且同实例返回`() {
        stubDatabase("mpj_demo")
        val calls = stubTraversal(tableIn("mpj_demo", "user", listOf("id")))
        val ds = Any()

        val first = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        val second = DatabaseSchemaResolver.resolveFromSources("user", listOf(ds))
        assertEquals("正命中不再进扫描", 1, calls())
        assertEquals(first, second)
        assertTrue("命中表列非空（调用方据此判定已解析）", first.columns.isNotEmpty())
    }

    @Test
    fun `列元数据_注释与类型串按 DBN 口径收录`() {
        stubDatabase("mpj_demo")
        stubTraversal(tableIn("mpj_demo", "user", listOf("id")))
        val meta: TableMeta = DatabaseSchemaResolver.resolveFromSources("user", listOf(Any()))
        assertEquals(ColumnMeta("注释_id", "int(11)", true), meta.columns["id"])
    }

    // ---------- O-58：全列名集合（未过滤）与新入口 ----------

    /**
     * 列元数据按列名区分：只有 [metadataColumn] 带注释 + 类型串（进 [TableMeta.columns]），
     * 其余列三样皆无（无注释、无类型串、notNull 不为 true）→ 被既有过滤剔除，只留在全列名集合里。
     */
    private fun o58Accessors(metadataColumn: String): DasAccessors = DasAccessors(
        isTable = { it is FakeTable },
        isColumn = { it is FakeColumn },
        name = {
            when (it) {
                is FakeTable -> it.tableName
                is FakeColumn -> it.columnName
                is FakeNs -> it.nsName
                else -> null
            }
        },
        comment = { col -> (col as? FakeColumn)?.columnName?.takeIf { it == metadataColumn }?.let { "注释" } },
        children = { (it as? FakeTable)?.columns?.map { c -> FakeColumn(c, null) } },
        parent = {
            when (it) {
                is FakeTable -> it.up
                is FakeNs -> it.up
                else -> null
            }
        },
        typeSpec = { col -> if ((col as? FakeColumn)?.columnName == metadataColumn) "varchar(64)" else null },
        notNull = { false },
    )

    /** 注入 O-58 替身：`t_user` 含 [columns]，仅 [metadataColumn] 带元数据。 */
    private fun stubO58Table(columns: List<String>, metadataColumn: String) {
        stubDatabase("mpj_demo")
        DatabaseSchemaResolver.traversalForTest = {
            ModelTraversal(listOf(tableIn("mpj_demo", "t_user", columns)), o58Accessors(metadataColumn))
        }
    }

    @Test
    fun `O58_新入口未预热返回空_预热后返回该表全列含被过滤列`() {
        stubO58Table(listOf("user_name", "username"), metadataColumn = "username")
        val ds = Any()
        val lookups = DatabaseSchemaResolver.columnNameSources(listOf(ds))

        assertTrue(
            "未预热（缓存无该表条目）→ 空 Map，调用方回落",
            DatabaseSchemaResolver.mergeCachedColumnNames(listOf("t_user"), lookups).isEmpty(),
        )

        DatabaseSchemaResolver.resolveFromSources("t_user", listOf(ds)) // 预热（写缓存）

        assertEquals(
            "预热后 → 该表全列名（含被 columns 过滤掉的 user_name）",
            setOf("user_name", "username"),
            DatabaseSchemaResolver.mergeCachedColumnNames(listOf("t_user"), lookups)["t_user"],
        )
    }

    @Test
    fun `O58_被过滤的列仍进全列名但不进列元数据`() {
        stubO58Table(listOf("user_name", "username"), metadataColumn = "username")
        val meta = DatabaseSchemaResolver.resolveFromSources("t_user", listOf(Any()))

        assertEquals("带元数据的列进 columns", setOf("username"), meta.columns.keys)
        assertEquals(
            "无注释无类型串且 notNull 不为 true 的列也进全列名（本次修复的判据）",
            setOf("user_name", "username"),
            meta.allColumnNames,
        )
    }

    /**
     * O-58 的存在理由（本用例即反向验证的判据）：列名校正的第 2/3 步在候选集上判**唯一**，
     * 候选集缺列会让歧义凭空消失 → 采纳剩下的那一列（可能是错的那一列）。
     *
     * 两段各覆盖一种形态，每段都带「过滤子集会给错答案」的对照断言——把错答案钉进测试，
     * 使「退回只含元数据子集的 columns」这一改动必然变红：
     *  ① 用户报障形态：字段 `userName` → 猜测 `user_name`（猜测值本身是真实列，但无元数据）；
     *  ② 真歧义形态：字段 `orderID` → 猜测 `order_i_d`（不是真实列），库里 `order_id` / `orderid` 同形。
     */
    @Test
    fun `O58_被过滤的列仍参与歧义判定_不得据此选错列`() {
        // ① 猜测值本身被过滤：全列名里精确命中 → 保持原样；过滤子集里唯一命中 → 错改成 username
        stubO58Table(listOf("user_name", "username"), metadataColumn = "username")
        val ds = Any()
        val meta = DatabaseSchemaResolver.resolveFromSources("t_user", listOf(ds))
        val real = DatabaseSchemaResolver.mergeCachedColumnNames(
            listOf("t_user"),
            DatabaseSchemaResolver.columnNameSources(listOf(ds)),
        )["t_user"]
        // 断言顺序有意如此：先断行为（校正结论），再断输入形态——反向验证变异数据形态时，
        // 红灯落在「不得选错」这条行为断言上，而不是先被输入形态断言挡下。
        val userTable = TableInfo("UserEntity", "t_user", listOf(ColumnInfo("userName", "user_name")))
        assertEquals(
            "被过滤的列本身是真实列 → 精确命中 → 保持原样",
            "user_name",
            ColumnNameCalibrator.calibrate(userTable, real).columns[0].columnName,
        )
        assertEquals("校正器读到的是完整列集合", setOf("user_name", "username"), real)
        assertEquals(
            "对照：过滤子集下该列缺席，唯一性失真 → 错改成 username",
            "username",
            ColumnNameCalibrator.calibrate(userTable, meta.columns.keys).columns[0].columnName,
        )

        // ② 真歧义：两列归一化同形（order_id / orderid），猜测值不在真列里 → 歧义 → 保持原样
        stubO58Table(listOf("order_id", "orderid"), metadataColumn = "orderid")
        val meta2 = DatabaseSchemaResolver.resolveFromSources("t_user", listOf(Any()))

        val orderTable = TableInfo("OrderEntity", "t_order", listOf(ColumnInfo("orderID", "order_i_d")))
        assertEquals(
            "两列归一化同形 → 歧义 → 必须保持原样（宁缺不错）",
            "order_i_d",
            ColumnNameCalibrator.calibrate(orderTable, meta2.allColumnNames).columns[0].columnName,
        )
        assertEquals("被过滤的列（order_id）仍在全列名里", setOf("order_id", "orderid"), meta2.allColumnNames)
        assertEquals("只有 orderid 带元数据", setOf("orderid"), meta2.columns.keys)
        assertEquals(
            "对照：过滤子集下歧义消失 → 错选成 orderid",
            "orderid",
            ColumnNameCalibrator.calibrate(orderTable, meta2.columns.keys).columns[0].columnName,
        )
    }

    // ---------- T-Dgm-StmtScope：整图批量取数的「句内来源一致性」 ----------
    //
    // 真机 A10（2026-10-11 沙盒实测）：同一句 `"user"` + user_address + area，本地 MySQL 的
    // jpa_demo 里也有一张 `user`（9 列、**零 COMMENT**，注册序在 mpj_demo 之前）——
    // 逐表单表解析把主表喂成 jpa_demo.user：列注释空白、类型串/库里独有列都来自那张表；
    // 而同一句的 JOIN 表只有 mpj_demo 有、解析正确 → 图上「主表无注释、副表有注释」。
    // 下面前两组用例的判据 = 主表必须与副表落在同一来源（真机缺陷即可反向验证：去掉投票即变红）。

    /** 按数据源分支的遍历替身（跨库同名表要求两个来源的模型不同——单模型替身给不出该形态）。 */
    private fun stubTraversalPerSource(byDs: (Any) -> Pair<List<FakeTable>, DasAccessors>) {
        DatabaseSchemaResolver.traversalForTest = { ds ->
            val (elements, acc) = byDs(ds)
            ModelTraversal(elements, acc)
        }
    }

    /** 真机形态：注册序在前、零 COMMENT 的源（jpa_demo）；另一个 = mpj_demo（表/列注释齐全）。 */
    private fun stubJpaThenMpj(
        jpaTables: Map<String, List<String>>,
        mpjTables: Map<String, List<String>>,
    ): Pair<Any, Any> {
        val jpa = Any()
        val mpj = Any()
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { ds -> if (ds === jpa) "jpa_demo" else "mpj_demo" }
        stubTraversalPerSource { ds ->
            when {
                ds === jpa -> jpaTables.map { (t, cols) -> tableIn("jpa_demo", t, cols) } to noCommentAccessors
                ds === mpj ->
                    mpjTables.map { (t, cols) -> tableIn("mpj_demo", t, cols, "库注释_$t") } to accessors
                else -> emptyList<FakeTable>() to accessors
            }
        }
        return jpa to mpj
    }

    @Test
    fun `主表与副表取同一来源——跨库同名表不再把主表喂成另一张表`() {
        val (jpa, mpj) = stubJpaThenMpj(
            jpaTables = mapOf("user" to listOf("id", "username", "email")),
            mpjTables = mapOf(
                "user" to listOf("id", "name", "age", "sex", "head_img"),
                "user_address" to listOf("id", "user_id", "tel"),
                "area" to listOf("id", "province", "city"),
            ),
        )

        val metas = DatabaseSchemaResolver.resolveBatchFromSources(
            listOf("\"user\"", "user_address", "area"),
            listOf(jpa, mpj),
        )

        assertEquals(
            "键按传入原样返回（含引用符），调用方按模型表名回查",
            setOf("\"user\"", "user_address", "area"),
            metas.keys,
        )
        val main = metas.getValue("\"user\"")
        assertEquals(
            "主表改判给持有本句 3 张表的来源（mpj_demo）：列注释来自 DB（本次缺陷的判据）",
            "注释_name",
            main.columns.getValue("name").comment,
        )
        assertEquals("表头注释同样来自该来源（DB COMMENT 优先）", "库注释_user", main.comment)
        assertFalse(
            "注册序在前、零 COMMENT 的那张同名表（jpa_demo.user）不得再参与主表富化",
            main.columns.containsKey("username"),
        )
        assertEquals("同句副表同口径", "注释_tel", metas.getValue("user_address").columns.getValue("tel").comment)
        assertEquals("同句副表同口径（第二张）", "注释_province", metas.getValue("area").columns.getValue("province").comment)
    }

    @Test
    fun `单表批量与逐表单表解析同口径——注册序首个命中`() {
        val (jpa, mpj) = stubJpaThenMpj(
            jpaTables = mapOf("user" to listOf("id", "username", "email")),
            mpjTables = mapOf("user" to listOf("id", "name", "age", "sex", "head_img")),
        )

        val metas = DatabaseSchemaResolver.resolveBatchFromSources(listOf("user"), listOf(jpa, mpj))

        assertEquals(
            "单表批量无从投票消歧 → 与 tableMeta 逐字一致：注册序首个命中",
            setOf("id", "username", "email"),
            metas.getValue("user").columns.keys,
        )
    }

    @Test
    fun `主来源没有的表保留原来源——跨库联接逐表回落`() {
        val (jpa, mpj) = stubJpaThenMpj(
            jpaTables = mapOf(
                "user" to listOf("id", "username", "email"),
                "jpa_log" to listOf("id", "log_no"),
            ),
            mpjTables = mapOf(
                "user" to listOf("id", "name", "age", "sex", "head_img"),
                "user_address" to listOf("id", "user_id", "tel"),
            ),
        )

        val metas = DatabaseSchemaResolver.resolveBatchFromSources(
            listOf("user", "user_address", "jpa_log"),
            listOf(jpa, mpj),
        )

        assertEquals(
            "主来源（mpj_demo）没有的表保留阶段 1 的来源（跨库联接不被一刀切掉）",
            setOf("id", "log_no"),
            metas.getValue("jpa_log").columns.keys,
        )
        assertEquals(
            "同句多数表仍在主来源",
            "注释_user_id",
            metas.getValue("user_address").columns.getValue("user_id").comment,
        )
    }

    @Test
    fun `未解析到的表也有条目——调用方据此保留实体推断值`() {
        val (jpa, mpj) = stubJpaThenMpj(
            jpaTables = mapOf("user" to listOf("id", "username", "email")),
            mpjTables = mapOf(
                "user" to listOf("id", "name", "age", "sex", "head_img"),
                "area" to listOf("id", "province", "city"),
            ),
        )

        val metas = DatabaseSchemaResolver.resolveBatchFromSources(
            listOf("user", "ghost_table", "area"),
            listOf(jpa, mpj),
        )

        assertTrue("未解析到的键也在（值 = 空 TableMeta）", metas.containsKey("ghost_table"))
        assertNull(metas.getValue("ghost_table").comment)
        assertTrue(metas.getValue("ghost_table").columns.isEmpty())
    }

    @Test
    fun `句内主来源投票_同票取注册序在前者_无持有返回null`() {
        assertEquals(
            "同票（各持 2 张）→ 取注册序在前者",
            0,
            DatabaseSchemaResolver.pickStatementSource(listOf(0, 0, 1, 1)),
        )
        assertEquals(
            "持有数多者胜",
            1,
            DatabaseSchemaResolver.pickStatementSource(listOf(-1, 1, 1, 0)),
        )
        assertNull("无来源持有任何表 → null", DatabaseSchemaResolver.pickStatementSource(listOf(-1, -1)))
        assertNull("空批次 → null", DatabaseSchemaResolver.pickStatementSource(emptyList()))
    }
}
