package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.JoinColumnRef
import cn.song.mybatisplus.visualizer.model.ColumnRole
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 列名数据源校正单测（O-58，注入式取数缝，无需 Project / 数据源）。
 *
 * 覆盖四层验收口径：
 *  1. 能校正：`order_i_d` → `order_id`（归一化唯一命中）、大小写按库回写；
 *  2. 可用性前提：只有取到真实列集合才校正（取数缝返回 null 时零改动）；
 *  3. 不可用自动降级：取数缝 null / 开关关闭 → 返回**入参实例本身**，`TableInfo.equals` 逐字段相等；
 *  4. 零性能回归：生产取数只引 `columnNamesCached`（零遍历的全列名缓存读）/ `prefetchTables`
 *     （源码扫描守卫），预热按表名去重（同一表名连续两次调用只投递一次）。
 *
 * 另覆盖：只改 `columnName`（其余字段逐个断言）、`columns` 顺序不变、表级派生列名字段同步改写、
 * 歧义（归一化同形多列）与 0 候选一律不动。
 *
 * 第四轮（独立复核后）补齐：
 *  - ① 关联**本表侧**外键列（`associations[].joinColumn` / `joinColumns[].name`）一并校正，
 *    且 `referencedColumnName`（目标表列）不得被碰；
 *  - ② 算法收窄——第 2 步**歧义即停**，不再换 `fieldName` 键重判（复核反例：real = {home_city,
 *    homecity, city}、fieldName = city、columnName = home-city 会被错改成 city）；
 *  - ③ 同一旧列名映射到两个新名时**永久**退出派生字段回写（复核反例：三列同名 `x` 会把
 *    `logicDeleteColumn = "x"` 改成歧义列名）；
 *  - ④ 预热登记溢出**淘汰最旧一条**（不再整体清空、抹掉全部冷却）+ 全局在途预热上限。
 */
class ColumnNameCalibratorTest {

    @Before
    fun setUp() {
        // 逐用例显式设定取数缝与开关：生产默认（null）走数据源链，纯单测环境不可达
        ColumnNameCalibrator.enabledForTest = true
        ColumnNameCalibrator.resetPrefetchStateForTest()
    }

    @After
    fun tearDown() {
        // 静态缝必须还原：同 JVM 内的其他测试类会走 EntityResolver/JpaEntityResolver → 本校正器
        ColumnNameCalibrator.enabledForTest = null
        ColumnNameCalibrator.columnSourceForTest = null
        ColumnNameCalibrator.prefetchSinkForTest = null
        ColumnNameCalibrator.prefetchCooldownMs = ColumnNameCalibrator.PREFETCH_COOLDOWN_MS
        ColumnNameCalibrator.inFlightForTest = null
        ColumnNameCalibrator.resetPrefetchStateForTest()
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun table(vararg columns: ColumnInfo, logicDelete: String? = null, version: String? = null): TableInfo =
        // k2j 适配：TableInfo 为 Java 类，具名实参改位置实参（6 参重载）
        TableInfo("OrderEntity", "t_order", columns.toList(), logicDelete, version, "订单表")

    private fun column(field: String, col: String) = ColumnInfo(field, col)

    /** 同名夹具，表名可指定（预热限流用例按表名区分去重键）。 */
    private fun tableNamed(name: String) = TableInfo("NamedEntity", name, listOf(column("orderID", "order_i_d")))

    /** 除 `columnName` 外逐字段相等（消费方按 fieldName 索引，其余字段被改会连锁）。 */
    private fun assertOnlyColumnNameChanged(before: ColumnInfo, after: ColumnInfo) {
        assertEquals("fieldName 不得改", before.fieldName, after.fieldName)
        assertEquals("isId 不得改", before.isId, after.isId)
        // k2j 适配：getter 为 isIdTypeAuto()，Kotlin 属性名即 isIdTypeAuto
        assertEquals("idTypeAuto 不得改", before.isIdTypeAuto, after.isIdTypeAuto)
        assertEquals("fieldType 不得改", before.fieldType, after.fieldType)
        assertEquals("fill 不得改", before.fill, after.fill)
        assertEquals("comment 不得改", before.comment, after.comment)
        assertEquals("role 不得改", before.role, after.role)
        assertEquals("insertable 不得改", before.isInsertable, after.isInsertable)
        assertEquals("updatable 不得改", before.isUpdatable, after.isUpdatable)
        assertEquals("generatedValueStrategy 不得改", before.generatedValueStrategy, after.generatedValueStrategy)
        assertEquals("embedded 不得改", before.isEmbedded, after.isEmbedded)
        assertEquals("converted 不得改", before.isConverted, after.isConverted)
    }

    // ------------------------------------------------------------------
    // 1. 精确命中：库里的形态与当前值逐字符相同 → 保留
    // ------------------------------------------------------------------

    @Test
    fun `精确命中的列名原样保留`() {
        val input = table(column("id", "id"), column("orderID", "order_id"))
        val output = ColumnNameCalibrator.calibrate(input, setOf("id", "order_id", "created_time"))
        assertEquals("精确命中不得改写", listOf("id", "order_id"), output.allColumnNames)
        assertSame("无改动时应返回入参实例本身", input, output)
    }

    // ------------------------------------------------------------------
    // 2. 归一化唯一命中 → 采用真实列名（H1/H2 主命中路径）
    // ------------------------------------------------------------------

    @Test
    fun `连续大写缩写猜测出的order_i_d被校正为order_id`() {
        // 用户报障原形态：字段 orderID，命名策略逐大写插 _ → order_i_d；库真实列 order_id
        val input = table(column("orderID", "order_i_d"))
        val output = ColumnNameCalibrator.calibrate(input, setOf("id", "order_id"))
        assertEquals("应校正为库里的真实列名", listOf("order_id"), output.allColumnNames)
        assertNotEquals("校正必须真实发生（反向验证的判别点）", input.allColumnNames, output.allColumnNames)
    }

    @Test
    fun `注解与库不同步时按归一化唯一命中校正`() {
        // 步 2 的另一种形态：注解写了 order_idx，库里是 order_id（归一化后不同形 → 步 3 以 fieldName 命中）
        val input = table(column("orderID", "order_idx"), column("createTime", "create_time"))
        val output = ColumnNameCalibrator.calibrate(input, setOf("order_id", "created_time"))
        assertEquals("步 3：按 fieldName 归一化唯一命中", "order_id", output.columns[0].columnName)
        assertEquals("create_time 归一化后与 created_time 不同形 → 不猜，保持原样", "create_time", output.columns[1].columnName)
    }

    @Test
    fun `大小写按库实际形态回写`() {
        // Oracle/PG 折叠族：库目录给出大写形态，实体侧猜测是小写 → 回写库里的实际大小写
        val input = table(column("orderID", "order_id"))
        val output = ColumnNameCalibrator.calibrate(input, setOf("ORDER_ID"))
        assertEquals("应按库实际大小写回写", listOf("ORDER_ID"), output.allColumnNames)
    }

    // ------------------------------------------------------------------
    // 3. 歧义 / 0 候选 → 原样保留（宁缺不错）
    // ------------------------------------------------------------------

    @Test
    fun `归一化同形多列歧义时原样保留`() {
        val input = table(column("orderID", "order_i_d"))
        val output = ColumnNameCalibrator.calibrate(input, setOf("order_id", "orderId"))
        assertEquals("两列归一化同形 → 不猜", listOf("order_i_d"), output.allColumnNames)
        assertSame("歧义时返回入参实例本身", input, output)
    }

    @Test
    fun `零候选时原样保留`() {
        val input = table(column("qty", "qty"), column("orderID", "order_i_d"))
        val output = ColumnNameCalibrator.calibrate(input, setOf("id", "quantity"))
        assertEquals("语义改名无解 → 不猜", listOf("qty", "order_i_d"), output.allColumnNames)
        assertSame("0 候选时返回入参实例本身", input, output)
    }

    // ------------------------------------------------------------------
    // 4. 降级等价（本任务 P0 断言）：逐字段等于输入
    // ------------------------------------------------------------------

    @Test
    fun `取数缝返回null时整个TableInfo逐字段等于输入`() {
        val input = table(
            // k2j 适配：ColumnInfo 为 Java 类，具名实参改位置实参（8 参重载，默认值照抄生产链）
            ColumnInfo("id", "id", true, true, "java.lang.Long", null, null, ColumnRole.PRIMARY),
            ColumnInfo("orderID", "order_i_d", false, false, "java.lang.String", null, "订单号", ColumnRole.BUSINESS),
            ColumnInfo("deleted", "deleted", false, false, null, "INSERT", null, ColumnRole.SYSTEM),
            logicDelete = "deleted",
            version = "order_i_d",
        )
        val output = ColumnNameCalibrator.calibrate(input, null)
        assertEquals("查不了 → 逐字段等于输入", input, output)
        assertSame("查不了 → 返回入参实例本身", input, output)
    }

    @Test
    fun `取数缝返回空集合时逐字段等于输入`() {
        val input = table(column("orderID", "order_i_d"))
        assertEquals("空真实列集合 = 无真值可仲裁", input, ColumnNameCalibrator.calibrate(input, emptySet()))
    }

    @Test
    fun `生产入口在取数缝恒返回null时逐字段等于输入`() {
        val input = table(
            ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.PRIMARY),
            ColumnInfo("orderID", "order_i_d", false, false, "java.lang.String", null, null, ColumnRole.BUSINESS),
            logicDelete = "order_i_d",
        )
        // 数据源不可用 / 未连接 / 内省未完成 / 表看不见 —— 生产侧统一表现为「取不到真实列」
        ColumnNameCalibrator.columnSourceForTest = { _, _ -> null }
        val output = ColumnNameCalibrator.calibrate(null, input)
        assertEquals("降级态必须与改动前逐字段一致", input, output)
        assertSame("降级态返回入参实例本身（无 Project 亦不等待）", input, output)
    }

    @Test
    fun `开关关闭时逐字段等于输入`() {
        val input = table(column("orderID", "order_i_d"), logicDelete = "order_i_d")
        // 取数缝给出「能唯一确定」的真值，唯独开关关闭 —— 结果必须与取数缝返回 null 完全一致
        ColumnNameCalibrator.columnSourceForTest = { _, _ -> setOf("order_id") }
        ColumnNameCalibrator.enabledForTest = false
        val output = ColumnNameCalibrator.calibrate(null, input)
        assertEquals("开关关闭 → 逐字段等于输入", input, output)
        assertSame("开关关闭 → 返回入参实例本身", input, output)
    }

    // ------------------------------------------------------------------
    // 5. 只改 columnName：顺序不变、其余字段逐字段不变
    // ------------------------------------------------------------------

    @Test
    fun `只改columnName其余字段与顺序逐项不变`() {
        val input = table(
            ColumnInfo("id", "id", true, true, "java.lang.Long", null, null, ColumnRole.PRIMARY),
            ColumnInfo("orderID", "order_i_d", false, false, "java.lang.String", null, "订单号", ColumnRole.BUSINESS),
            ColumnInfo("createTime", "create_time", false, false, null, "INSERT", "创建时间", ColumnRole.AUDIT),
            logicDelete = "order_i_d",
            version = "create_time",
        )
        val output = ColumnNameCalibrator.calibrate(input, setOf("id", "order_id", "create_time"))
        assertEquals("列数不变", input.columns.size, output.columns.size)
        for (i in input.columns.indices) {
            assertOnlyColumnNameChanged(input.columns[i], output.columns[i])
        }
        assertEquals("顺序不变（fieldName 序列）", input.columns.map { it.fieldName }, output.columns.map { it.fieldName })
        assertEquals("顺序不变（columnName 序列）", listOf("id", "order_id", "create_time"), output.allColumnNames)
        // 表级派生列名字段持有的是同一个列名，必须随之同步，否则同一 TableInfo 里同一列有两个名字
        assertEquals("logicDeleteColumn 应同步改写", "order_id", output.logicDeleteColumn)
        assertEquals("versionColumn 未校正则保持原值", "create_time", output.versionColumn)
        assertEquals("表级其余字段不得改", input.comment, output.comment)
        assertEquals("entityClassName 不得改", input.entityClassName, output.entityClassName)
        assertEquals("tableName 不得改", input.tableName, output.tableName)
    }

    @Test
    fun `复合主键列清单随列名同步改写`() {
        // k2j 适配：compositeIdColumns 无短重载，走全参构造（中段字段照抄 7 参重载的默认值）
        val input = TableInfo(
            "OrderEntity",
            "t_order",
            listOf(column("orderID", "order_i_d")),
            null, null, null, listOf(),
            null, null, null, null, null,
            listOf(), listOf(), listOf(), listOf(), listOf(),
            null, null, null, listOf("order_i_d"), null,
        )
        val output = ColumnNameCalibrator.calibrate(input, setOf("order_id"))
        assertEquals(listOf("order_id"), output.compositeIdColumns)
    }

    // ------------------------------------------------------------------
    // 6. 预热去重（T-03）：同一表名连续两次调用只投递一次
    // ------------------------------------------------------------------

    @Test
    fun `生产入口未命中缓存时同一表名只投递一次预热`() {
        val dispatched = mutableListOf<String>()
        ColumnNameCalibrator.columnSourceForTest = { _, _ -> null }
        ColumnNameCalibrator.prefetchSinkForTest = { _, tableName -> dispatched += tableName }
        val input = table(column("orderID", "order_i_d"))

        ColumnNameCalibrator.calibrate(null, input)
        ColumnNameCalibrator.calibrate(null, input)
        assertEquals("冷却窗口内同一表名只投递一次（防补全热路径投递风暴）", listOf("t_order"), dispatched)

        // 冷却窗口过后允许重投（数据源晚连接时不会把该表永久钉死）
        ColumnNameCalibrator.prefetchCooldownMs = 0L
        ColumnNameCalibrator.calibrate(null, input)
        assertEquals("冷却窗口归零后应允许重投", listOf("t_order", "t_order"), dispatched)
    }

    @Test
    fun `命中缓存时不投递预热`() {
        val dispatched = mutableListOf<String>()
        ColumnNameCalibrator.columnSourceForTest = { _, _ -> setOf("order_id") }
        ColumnNameCalibrator.prefetchSinkForTest = { _, tableName -> dispatched += tableName }
        ColumnNameCalibrator.calibrate(null, table(column("orderID", "order_i_d")))
        assertTrue("命中缓存即无需预热", dispatched.isEmpty())
    }

    // ------------------------------------------------------------------
    // 7. 零性能回归守卫（T-03）：生产取数只引缓存读与预热投递
    // ------------------------------------------------------------------

    /**
     * 源码扫描守卫：本校正器的生产取数只允许出现「零遍历的**全列名**缓存读」与「后台预热」两个
     * DatabaseSchemaResolver 成员。两个方向都要钉：
     *  - 会触发数据源遍历的同步入口（tableMeta 系）被引入 → 补全/预览热路径多出一次 schema 全扫，
     *    这种回归在功能测试里完全不可见；
     *  - 退回只含元数据子集的 `columnsCached` → 唯一性判定在不完整候选集上失真，会**选错列**
     *    （见 `DatabaseSchemaResolverSchemaScopeTest` 的 O-58 用例），且同样不可见。
     */
    @Test
    fun `生产取数只引用零遍历的缓存读与后台预热`() {
        val source = stripComments(calibratorSource())
        val referenced = Regex("""DatabaseSchemaResolver\s*\.\s*(\w+)""").findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "生产取数只允许引用 columnNamesCached（零遍历的全列名缓存读）与 prefetchTables（后台预热）：$referenced",
            setOf("columnNamesCached", "prefetchTables"),
            referenced,
        )
        assertTrue(
            "会触发数据源遍历的同步取数入口不得出现在本文件（含注释外的任何位置）",
            !Regex("""(?<![\w$])tableMeta(?![\w$])""").containsMatchIn(source),
        )
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            // k2j 适配：生产树是 src/main/java（master 侧为 src/main/kotlin）
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun calibratorSource(): String = repoRoot()
        .resolve("src/main/java/cn/song/mybatisplus/visualizer/psi/ColumnNameCalibrator.java")
        .readText()


    // ------------------------------------------------------------------
    // 8. ① 关联外键列（本表侧）一并校正
    // ------------------------------------------------------------------

    /**
     * 无 `@JoinColumn` 时 `AssociationInfo.joinColumn` 是 `{fieldName}_id` 的**猜测值**
     * （[JpaMetadataReader.readToOneJoinColumns] 的默认形态），而 JOIN ON 直接消费它——只校列清单
     * 不改它，JOIN 预览照样报 unknown column。这里字段名 `orderId` → 猜测 `order_id_id`，
     * 库真实列 `order_id`（算法第 3 步以 fieldName 命中）。
     */
    @Test
    fun `关联外键列按同一算法校正`() {
        // k2j 适配：AssociationInfo / JoinColumnRef 为 Java 类，具名实参改位置实参
        val association = AssociationInfo(
            "orderId",
            "order_id_id",
            "t_order",
            "order",
            null,
            listOf(JoinColumnRef("order_id_id", "id")),
            null,
        )
        val input = TableInfo(
            "OrderItemEntity",
            "t_order_item",
            listOf(column("id", "id")),
            null, null, null, listOf(association),
        )
        val output = ColumnNameCalibrator.calibrate(input, setOf("id", "order_id"))
        assertEquals("外键列应校正为真实列名", "order_id", output.associations[0].joinColumn)
        assertEquals("joinColumns[].name 同步校正", "order_id", output.associations[0].joinColumns[0].name)
        assertEquals(
            "referencedColumnName 指向目标表列，本表真值集合无仲裁权 → 不得碰",
            "id",
            output.associations[0].joinColumns[0].referencedColumnName,
        )
        assertEquals("关联其余字段不得改", "orderId", output.associations[0].fieldName)
        assertEquals("目标表/别名不得改", "t_order", output.associations[0].targetTable)
    }

    @Test
    fun `外键列精确命中时原样保留`() {
        val association = AssociationInfo(
            "order",
            "order_id",
            "t_order",
            "order",
            null,
            listOf(JoinColumnRef("order_id")),
            null,
        )
        val input = TableInfo("OrderItemEntity", "t_order_item", listOf(column("id", "id")), null, null, null, listOf(association))
        val output = ColumnNameCalibrator.calibrate(input, setOf("id", "order_id"))
        assertEquals("精确命中 → 关联与外键列都保持原样", input, output)
        assertSame("无改动时返回入参实例本身", input, output)
    }

    // ------------------------------------------------------------------
    // 9. ② 算法收窄：第 2 步歧义即停，不再换 fieldName 键重判
    // ------------------------------------------------------------------

    /**
     * 复核反例：real = {home_city, homecity, city}、fieldName = `city`、columnName = `home-city`。
     * 归一化后 `home-city` → `homecity` 在真实集合里有 **home_city / homecity 两候选**（歧义），
     * 而 `city` 键唯一命中 `city`——旧实现会「换一把尺子」改写错列，现要求歧义即停、保持原样。
     */
    @Test
    fun `第2步歧义时不得再用fieldName键重判`() {
        val input = table(column("city", "home-city"))
        val real = setOf("home_city", "homecity", "city")
        val output = ColumnNameCalibrator.calibrate(input, real)
        assertEquals(
            "步 2 歧义（home_city / homecity 同形）→ 必须保持原样，不得改写成 city",
            listOf("home-city"),
            output.allColumnNames,
        )
        assertSame("歧义时返回入参实例本身", input, output)
    }

    @Test
    fun `第2步无候选时仍可按fieldName唯一命中`() {
        // 与上一条对照：步 2 **无候选**（不是歧义）时步 3 照常生效。
        // 注意猜测值取 `order_idx`（归一化 `orderidx`）而非 `order_i_d`——后者的归一化形式与
        // `order_id` 相同，会在**步 2** 就命中，根本走不到步 3（那样这条用例就名实不符了）。
        val input = table(column("orderID", "order_idx"))
        assertEquals(
            "步 2 无候选 → 步 3 以 fieldName（orderid）唯一命中 order_id",
            listOf("order_id"),
            ColumnNameCalibrator.calibrate(input, setOf("order_id")).allColumnNames,
        )
    }

    // ------------------------------------------------------------------
    // 10. ③ renames 歧义永久禁用（派生字段不得被改成歧义列名）
    // ------------------------------------------------------------------

    /**
     * 复核反例输入：columns = [(alpha,"x"), (beta,"x"), (alpha,"x")]、real = {alpha, beta}。
     * 旧实现：第 3 列的 put 因 `previous == null` 把已判歧义的旧名 `x` **重新登记**为 alpha，
     * 于是 `logicDeleteColumn = "x"` 被改成 alpha（歧义列名）。现要求 `x` 永久退出回写。
     */
    @Test
    fun `同名旧列名歧义后永久退出派生字段回写`() {
        val input = TableInfo(
            "AmbiguousEntity",
            "t_amb",
            listOf(
                column("alpha", "x"),
                column("beta", "x"),
                column("alpha", "x"),
            ),
            "x",
        )
        val output = ColumnNameCalibrator.calibrate(input, setOf("alpha", "beta"))
        assertEquals("三列各自按 fieldName 校正", listOf("alpha", "beta", "alpha"), output.allColumnNames)
        assertEquals(
            "x 映射到两个不同新名（alpha/beta）→ 歧义 → 派生字段保持原样，不得被复活成 alpha",
            "x",
            output.logicDeleteColumn,
        )
    }

    @Test
    fun `唯一映射时派生字段照常回写`() {
        // 与上一条对照：唯一映射不受歧义拉黑影响
        val input = TableInfo(
            "OrderEntity",
            "t_order",
            listOf(column("orderID", "order_i_d")),
            "order_i_d",
        )
        val output = ColumnNameCalibrator.calibrate(input, setOf("order_id"))
        assertEquals("唯一映射 → 派生字段同步改写", "order_id", output.logicDeleteColumn)
    }

    // ------------------------------------------------------------------
    // 11. ④ 预热限流：溢出淘汰最旧 + 全局在途上限
    // ------------------------------------------------------------------

    @Test
    fun `预热登记溢出时淘汰最旧一条而其余冷却仍有效`() {
        val dispatched = mutableListOf<String>()
        ColumnNameCalibrator.columnSourceForTest = { _, _ -> null }
        ColumnNameCalibrator.prefetchSinkForTest = { _, name -> dispatched += name }
        repeat(ColumnNameCalibrator.MAX_PREFETCH_TABLES) { i ->
            ColumnNameCalibrator.calibrate(null, tableNamed("t$i"))
        }
        assertEquals("填满登记表", ColumnNameCalibrator.MAX_PREFETCH_TABLES, dispatched.size)

        ColumnNameCalibrator.calibrate(null, tableNamed("t_overflow"))
        assertEquals("溢出后仍应投递（并淘汰最旧一条）", ColumnNameCalibrator.MAX_PREFETCH_TABLES + 1, dispatched.size)

        // 未被淘汰的表名仍在冷却窗口内 → 不再投递（旧实现整体 clear 会把全部冷却抹掉、这里会重投）
        ColumnNameCalibrator.calibrate(null, tableNamed("t19"))
        ColumnNameCalibrator.calibrate(null, tableNamed("t1"))
        assertEquals(
            "溢出后其余表名的冷却仍有效（整体 clear 会全部重投）",
            ColumnNameCalibrator.MAX_PREFETCH_TABLES + 1,
            dispatched.size,
        )

        // 被淘汰的最旧表名（t0）可重新投递 → 证明是「淘汰最旧」而非「整体清空」
        ColumnNameCalibrator.calibrate(null, tableNamed("t0"))
        assertEquals(listOf("t0"), dispatched.drop(ColumnNameCalibrator.MAX_PREFETCH_TABLES + 1))
    }

    @Test
    fun `在途预热达全局上限时本次不投递`() {
        val dispatched = mutableListOf<String>()
        ColumnNameCalibrator.columnSourceForTest = { _, _ -> null }
        ColumnNameCalibrator.prefetchSinkForTest = { _, name -> dispatched += name }

        ColumnNameCalibrator.inFlightForTest = ColumnNameCalibrator.MAX_IN_FLIGHT_PREFETCH
        ColumnNameCalibrator.calibrate(null, table(column("orderID", "order_i_d")))
        assertTrue("在途已达上限 → 本次不投递（不阻塞不等待，冷却后自然重试）", dispatched.isEmpty())

        ColumnNameCalibrator.inFlightForTest = 0
        ColumnNameCalibrator.prefetchCooldownMs = 0L // 清掉上一次登记留下的冷却，专测在途闸
        ColumnNameCalibrator.calibrate(null, table(column("orderID", "order_i_d")))
        assertEquals("在途放开后应恢复投递", listOf("t_order"), dispatched)
    }

    /** 剥离块注释与行注释：守卫扫的是代码事实，KDoc 里提及某成员不算引用。 */
    private fun stripComments(text: String): String =
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(text) { " ".repeat(it.value.length) }
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
}
