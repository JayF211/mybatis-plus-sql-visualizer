package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * O-57 HQL/JPQL 别名作用域属性→列名映射测试。
 *
 * 缺陷（用户报障）：`join WmsPickTicketDetail detail on detail.pickTicketId = pickTicket.id`
 * 里副表别名的属性**从不映射**（改前只遍历根实体列清单、且替换正则不带别名限定），
 * 预览 SQL 复制出去执行报 `Unknown column 'detail.pickTicketId'`。
 *
 * 验证点：
 *  1. 用户 HQL 同形复现——副表别名属性映射为列名（改前必红）
 *  2. `pickTicket.updateInfo.createdTime` 的**嵌入路径展平**（`@Embedded` 展平，计划 §六 D2 已实施）：
 *     嵌入段 `updateInfo.` 去掉，产物 `pickTicket.created_time`；去掉嵌入段是必须的——保留它会产出
 *     无效 SQL（SQL 语义下 `别名.嵌入对象` 被当成「库.表」）。整串黄金比对同时钉住这一点
 *  3. 跨别名同名字段各按其实体列清单映射（T-02 判据，改前必红）
 *  4. 隐式作用域（O-71 契约变更）：无别名限定的裸属性按根实体列清单精确映射为列名
 *  5. 自连接：同一实体两个别名各自绑定，不合并
 *  6. 无 Project / 实体不可解析：原样透传，不猜列名
 *  7. 双链路一致：HqlConverter 与 JpqlConverter 行为逐字相同
 *  8. 非嵌入的中间段**保留不删**（「不靠猜测丢中间段」的反向判据）
 *
 * 夹具用 LightPlatform 的 PsiClass 注入写法（照 [JpaJoinOnResolverTest] 既有形态，免索引依赖），
 * 另有一例走生产入口的 project 形态。BasePlatformTestCase 使用 JUnit 3 约定，
 * 测试方法名以 `test` 前缀开头。
 */
class HqlAliasColumnMappingTest : BasePlatformTestCase() {

    /** 夹具内全部类（按简单名索引）。 */
    private val classes = LinkedHashMap<String, PsiClass>()

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("Demo.java", FIXTURE)
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .forEach { clazz -> clazz.name?.let { classes[it] = clazz } }
    }

    private fun tableOf(name: String): TableInfo {
        val clazz = classes[name]
        assertNotNull("夹具内未找到类: $name", clazz)
        return JpaEntityResolver().resolve(clazz!!)
    }

    /** 实体解析函数注入形态：按短名从夹具 PsiClass 解析（免索引）。 */
    private fun resolveEntity(name: String): TableInfo? =
        classes[name]?.let { JpaEntityResolver().resolve(it) }

    private fun convert(hql: String, rootEntity: String): HqlConverter.Result =
        HqlConverter.convertWithEntityResolver(hql, tableOf(rootEntity), ::resolveEntity)

    // ------------------------------------------------------------------
    // 1 + 2：用户报障形态（副表别名属性 + 嵌入路径）
    // ------------------------------------------------------------------

    /** O-57：用户报障 HQL 同形复现——ON 里的副表属性必须映射为列名（改前必红）。 */
    fun test用户HQL_副表别名属性映射为列名() {
        val result = convert(USER_HQL, "WmsPickTicket")
        assertTrue("detail.pickTicketId 应映射为列名: ${result.sql}", result.sql.contains("detail.pick_ticket_id"))
        assertTrue("detail.itemId 应映射为列名: ${result.sql}", result.sql.contains("detail.item_id"))
        assertFalse("不应残留副表属性名: ${result.sql}", result.sql.contains("pickTicketId"))
        assertFalse("不应残留副表属性名: ${result.sql}", result.sql.contains("detail.itemId"))
        // 根实体属性映射不回归
        assertTrue("根实体属性应映射: ${result.sql}", result.sql.contains("pickTicket.warehouse_id"))
        // 副表实体名→表名（别名作用域解析产物透传给 JpaFromEntityMapper，免二次 PSI）
        assertTrue("副表实体名应映射为表名: ${result.sql}", result.sql.contains("wms_pick_ticket_detail detail"))
        assertTrue("副表实体名应映射为表名: ${result.sql}", result.sql.contains("wms_item item"))
    }

    /**
     * O-57 §七 验收：用户报障 HQL 的**整串黄金比对**。
     *
     * 一次钉死：副表别名属性（`detail.pick_ticket_id` / `detail.item_id`）、根实体属性
     * （`pickTicket.warehouse_id`）、同名属性不动（`pickTicket.status`）、**嵌入路径展平**
     * （两处 `pickTicket.updateInfo.createdTime` → `pickTicket.created_time`，嵌入段 `updateInfo.` 去掉——
     * 保留它会产出无效 SQL，见 [JpaAliasPropertyMapper.isFlattenedEmbeddedPath]）、
     * 实体名→表名、参数替换、ORDER BY 原样。
     */
    fun test用户HQL_整串黄金比对_嵌入路径展平() {
        val result = convert(USER_HQL, "WmsPickTicket")
        assertEquals("用户报障 HQL 的完整产物", USER_HQL_EXPECTED, result.sql)
        assertFalse("不得残留嵌入段（无效 SQL 形态）: ${result.sql}", result.sql.contains("updateInfo"))
    }

    // ------------------------------------------------------------------
    // 嵌入路径展平（@Embedded，计划 §六 D2 已实施）
    // ------------------------------------------------------------------

    /** 嵌入段的去留由**列自身的 embeddedParent** 决定，而不是「中间段不是本表字段就丢」。 */
    fun test嵌入路径展平_嵌入段去掉而列名用覆写值() {
        // UpdateInfo.createdTime 经 @Column(name="created_time") 覆写，故展平后是 created_time
        val flattened = convert(USER_HQL, "WmsPickTicket")
        assertTrue("嵌入段应去掉: ${flattened.sql}", flattened.sql.contains("pickTicket.created_time"))
        assertFalse("嵌入段不得残留: ${flattened.sql}", flattened.sql.contains("updateInfo."))
        // 嵌入对象内部字段名原样出现在路径里时同样展平（非报障写法，同一机制）
        val alt = convert(
            "select pickTicket.updateInfo.updatedBy from WmsPickTicket pickTicket",
            "WmsPickTicket",
        )
        assertTrue("未覆写的嵌入列按 snake 兜底: ${alt.sql}", alt.sql.contains("pickTicket.updated_by"))
        assertFalse("嵌入段不得残留: ${alt.sql}", alt.sql.contains("updateInfo."))
    }

    /**
     * 非嵌入的中间段**必须原样保留**——这是「不靠猜测丢中间段」的反向判据。
     *
     * `pickTicket.unknownMiddle.createdTime` 里 `unknownMiddle` 不是本表的嵌入对象，
     * 故 `createdTime` 映射为列名但中间段保留（SQL 文本仍不可执行，属关联路径范畴、
     * 由 `mapAssociationPath` 与降级注释承担，本映射器不越权删段）。
     */
    fun test非嵌入中间段_保留不删() {
        val result = convert(
            "select pickTicket.unknownMiddle.createdTime from WmsPickTicket pickTicket",
            "WmsPickTicket",
        )
        assertTrue("属性名仍应映射: ${result.sql}", result.sql.contains("pickTicket.unknownMiddle.created_time"))
    }

    // ------------------------------------------------------------------
    // N1：限定符与点号之间的空白（合法 HQL/JPQL 写法）
    // ------------------------------------------------------------------

    /** N1：点号两侧空白须容忍，且空白原样保留（旧实现会替换，紧邻正则则漏映射＝回归）。 */
    fun test点号两侧空白_仍映射且空白原样保留() {
        val spaced = convert("select z.id from WmsZone z where z .createdTime > :t", "WmsZone")
        assertTrue("点号前空格应容忍: ${spaced.sql}", spaced.sql.contains("z .create_time"))
        val tabbed = convert("select z.id from WmsZone z where z\t.createdTime > :t", "WmsZone")
        assertTrue("点号前制表符应容忍: ${tabbed.sql}", tabbed.sql.contains("z\t.create_time"))
        val both = convert("select z.id from WmsZone z where z . createdTime > :t", "WmsZone")
        assertTrue("点号两侧空白应容忍: ${both.sql}", both.sql.contains("z . create_time"))
    }

    // ------------------------------------------------------------------
    // 3：跨别名同名字段（T-02 判据）
    // ------------------------------------------------------------------

    /** O-57 T-02：两表同名属性映射到不同列时，各按其实体列清单映射（改前必红）。 */
    fun test跨别名同名字段各按其实体映射() {
        val hql = "select count(1) from WmsZone z " +
            "join WmsLocation loc on loc.zoneId = z.id " +
            "join WmsShelf sh on sh.zoneId = z.id " +
            "where loc.createdTime >= :start and sh.createdTime < :end"
        val result = convert(hql, "WmsZone")
        // WmsLocation.createdTime → create_time；WmsShelf.createdTime → gmt_create（同名不同列）
        assertTrue("loc 按 WmsLocation 列清单映射: ${result.sql}", result.sql.contains("loc.create_time"))
        assertTrue("sh 按 WmsShelf 列清单映射: ${result.sql}", result.sql.contains("sh.gmt_create"))
        assertFalse("不得跨别名误伤（sh 用成根/别家的列）: ${result.sql}", result.sql.contains("sh.create_time"))
        // ON 里的副表外键属性同样映射
        assertTrue("ON 中副表属性应映射: ${result.sql}", result.sql.contains("loc.zone_id"))
        assertTrue("ON 中副表属性应映射: ${result.sql}", result.sql.contains("sh.zone_id"))
    }

    // ------------------------------------------------------------------
    // 4：隐式作用域（裸属性）
    // ------------------------------------------------------------------

    /**
     * O-71：无别名限定的裸属性（`where beAutoZone = ...`）按根实体列清单精确映射。
     * 契约变更：O-57 以「只收窄不丢失」为由保守排除裸属性（正则要求带点号），
     * 实测证明该排除使无别名/漏写前缀的 HQL 永不映射、产物必为无效 SQL，故推翻
     * （用户报障形态与机制归因见 docs/开发计划/20260923-1609-O71-T-HqlNoAliasProp）。
     * 混合形态（有别名 z 但个别属性漏写前缀）同样覆盖。
     */
    fun test隐式作用域_裸属性映射为列名() {
        val hql = "select count(1) from WmsZone z where z.createdTime > :t and beAutoZone = true"
        val result = convert(hql, "WmsZone")
        assertTrue("别名限定属性应映射: ${result.sql}", result.sql.contains("z.create_time"))
        assertTrue("裸属性应映射为列名: ${result.sql}", result.sql.contains("be_auto_zone = true"))
        assertFalse("不得残留裸属性名: ${result.sql}", result.sql.contains("beAutoZone"))
    }

    /** O-71：裸属性映射后 JPQL 链与 HQL 链产物逐字一致（同一份替换核心）。 */
    fun testJpql链路_裸属性一致() {
        val hql = "select count(1) from WmsZone z where z.createdTime > :t and beAutoZone = true"
        val table = tableOf("WmsZone")
        val hqlResult = HqlConverter.convert(hql, table, project)
        val jpqlResult = JpqlConverter.convert(hql, table, project)
        assertEquals("两条链产物应逐字一致", hqlResult.sql, jpqlResult.sql)
        assertTrue("裸属性应映射为列名: ${jpqlResult.sql}", jpqlResult.sql.contains("be_auto_zone = true"))
    }

    /** O-71：UPDATE 的 SET 与 WHERE 裸属性同样映射；同名列（id）透传不变形。 */
    fun testUpdate_SET裸属性映射() {
        val result = convert("update WmsZone set beAutoZone = :b where id = :id", "WmsZone")
        assertTrue("SET 裸属性应映射: ${result.sql}", result.sql.contains("set be_auto_zone = ?"))
        assertTrue("同名列透传: ${result.sql}", result.sql.contains("where id = ?"))
        assertFalse("不得残留裸属性名: ${result.sql}", result.sql.contains("beAutoZone"))
    }

    // ------------------------------------------------------------------
    // 5：自连接
    // ------------------------------------------------------------------

    /** O-57：同一实体两个别名各自绑定，不合并、不丢替换。 */
    fun test自连接_同实体两别名各自映射() {
        val hql = "select a.code from WmsItem a join WmsItem b on b.id = a.parentId where b.parentId is null"
        val result = convert(hql, "WmsItem")
        assertEquals(
            "两个别名的 parentId 都应映射: ${result.sql}",
            2,
            Regex("parent_id").findAll(result.sql).count(),
        )
        assertFalse("不应残留属性名: ${result.sql}", result.sql.contains("parentId"))
    }

    // ------------------------------------------------------------------
    // 6：无元数据不猜
    // ------------------------------------------------------------------

    /** O-57：无 Project 时副表实体解析不出 → 原样透传（不猜列名），根实体映射不受影响。 */
    fun test无Project_副表属性原样透传() {
        val result = HqlConverter.convert(USER_HQL, tableOf("WmsPickTicket"))
        assertTrue("副表属性应原样保留（不猜）: ${result.sql}", result.sql.contains("detail.pickTicketId"))
        assertFalse("无元数据不得凭空映射: ${result.sql}", result.sql.contains("pick_ticket_id"))
        assertTrue("根实体属性映射不回归: ${result.sql}", result.sql.contains("pickTicket.warehouse_id"))
        assertTrue("副表实体名无元数据时保留: ${result.sql}", result.sql.contains("WmsPickTicketDetail detail"))
    }

    /** O-57：注入解析函数返回 null（实体不可解析）时同样原样透传。 */
    fun test实体不可解析_原样透传() {
        val result = HqlConverter.convertWithEntityResolver(USER_HQL, tableOf("WmsPickTicket")) { null }
        assertTrue("副表属性应原样保留: ${result.sql}", result.sql.contains("detail.pickTicketId"))
        assertTrue("根实体属性映射不回归: ${result.sql}", result.sql.contains("pickTicket.warehouse_id"))
    }

    // ------------------------------------------------------------------
    // N2/N3：ON 未映射诊断的边界（区域大小写、语句类型）
    // ------------------------------------------------------------------

    /** N2：ON 区域前瞻须大小写不敏感——小写 `where`/`join` 之后的点号字面量不得被当成 ON 段。 */
    fun testON区域边界_小写where与join截断() {
        // 小写 where 之后的点号字符串字面量（`'a.b%'`）不得被当成 ON 里的限定符
        val literal = convert(
            "select count(1) from WmsZone z join WmsLocation loc on loc.zoneId = z.id " +
                "where loc.remark like 'a.b%'",
            "WmsZone",
        )
        assertFalse("WHERE 内的点号字面量不得触发 ON 提示: ${literal.sql}", literal.sql.contains(UNMAPPED_HINT))
        // 小写 join 之后的第二个 ON 之外的未知限定符（WHERE 内）同样不提示
        val where = convert(
            "select count(1) from WmsZone z join WmsLocation loc on loc.zoneId = z.id " +
                "join WmsShelf sh on sh.zoneId = z.id where bad.thing = 1",
            "WmsZone",
        )
        assertFalse("ON 区域不得越过小写 join/where: ${where.sql}", where.sql.contains(UNMAPPED_HINT))
        // 反向确认边界两侧都对：第二个 ON 的未知限定符仍被点名，而 WHERE 里的不算
        val second = convert(
            "select count(1) from WmsZone z join WmsLocation loc on loc.zoneId = z.id " +
                "join WmsShelf sh on sh.zoneId = z.id and bogus2.foo = 1 where z.status = 'A'",
            "WmsZone",
        )
        assertTrue("第二个 ON 的未知限定符应被点名: ${second.sql}", second.sql.contains("ON 条件中的 bogus2"))
        assertFalse("WHERE 里的限定符不得被算进 ON: ${second.sql}", second.sql.contains("ON 条件中的 z"))
    }

    /** T-06：ON 里的限定符从未在 FROM/JOIN 出现过 → 附一次性尾注，且只点名真正未知的限定符。 */
    fun testON未映射限定符_附一次性提示() {
        val result = convert(
            "select count(1) from WmsZone z join WmsLocation loc on loc.zoneId = z.id and bogus.foo = 1",
            "WmsZone",
        )
        assertTrue("应附未映射提示: ${result.sql}", result.sql.contains(UNMAPPED_HINT))
        assertTrue("提示应点名 bogus: ${result.sql}", result.sql.contains("ON 条件中的 bogus"))
        assertFalse("已知别名不得被点名: ${result.sql}", result.sql.contains("ON 条件中的 loc"))
        assertFalse("已知别名不得被点名: ${result.sql}", result.sql.contains("ON 条件中的 z"))
    }

    /** N3：UPDATE/DELETE 不追加 ON 未映射尾注（尾注会让 UPDATE 的 WHERE 降级为 Raw）。 */
    fun test尾注不落在UPDATE与DELETE语句() {
        val update = convert("update WmsZone z set z.status = :s where z.remark like '% on a.b %'", "WmsZone")
        assertFalse("UPDATE 不得追加 ON 尾注: ${update.sql}", update.sql.contains(UNMAPPED_HINT))
        val delete = convert("delete from WmsZone z where z.remark like '% on a.b %'", "WmsZone")
        assertFalse("DELETE 不得追加 ON 尾注: ${delete.sql}", delete.sql.contains(UNMAPPED_HINT))
    }

    // ------------------------------------------------------------------
    // 字面量保护：引号内的 `限定符.属性` 不得被替换
    // ------------------------------------------------------------------

    /** 字面量内的点号路径（含带空白形态）必须逐字未变，字面量里的 `x` 不得被当限定符。 */
    fun test字面量内的点号路径不被替换() {
        val result = convert("select z.id from WmsZone z where z.remark like '% x .createdTime %'", "WmsZone")
        assertTrue("字面量应逐字未变: ${result.sql}", result.sql.contains("'% x .createdTime %'"))
        assertFalse("字面量内属性不得被替换: ${result.sql}", result.sql.contains("'% x .create_time %'"))
        // 字面量里没有别的映射来源，整句不该出现任何列名
        assertFalse("字面量内不得产生列名: ${result.sql}", result.sql.contains("create_time"))
    }

    /** 标准 `''` 转义形态：相邻两段字面量内的路径都被保护。 */
    fun test字面量双单引号转义形态受保护() {
        // 用会被映射的属性名（createdTime→create_time）构造，确保「无字面量保护」时必红
        val escaped = convert("select z.id from WmsZone z where z.remark = 'it''s b.createdTime'", "WmsZone")
        assertTrue("转义字面量应逐字未变: ${escaped.sql}", escaped.sql.contains("'it''s b.createdTime'"))
        assertFalse("转义字面量内不得被替换: ${escaped.sql}", escaped.sql.contains("create_time"))
        // 复核给的原始形态（b.c 本身无映射来源）同样必须逐字未变
        val plain = convert("select z.id from WmsZone z where z.remark = 'it''s b.c'", "WmsZone")
        assertTrue("转义字面量应逐字未变: ${plain.sql}", plain.sql.contains("'it''s b.c'"))
    }

    /** 未闭合单引号（到语句末尾）不得吞掉其后的合法属性映射。 */
    fun test未闭合单引号_其后属性仍映射() {
        val result = convert("select z.id from WmsZone z where z.remark = 'abc and z.createdTime > :t", "WmsZone")
        assertTrue("未闭合字面量之后的属性仍应映射: ${result.sql}", result.sql.contains("z.create_time"))
    }

    // ------------------------------------------------------------------
    // 7：双链路一致（生产入口 project 形态）
    // ------------------------------------------------------------------

    /** O-57：生产入口（project 形态，按短名 PSI 检索）——HqlConverter 链路。 */
    fun testHql链路_生产入口project形态() {
        val result = HqlConverter.convert(USER_HQL, tableOf("WmsPickTicket"), project)
        assertTrue("副表属性应映射为列名: ${result.sql}", result.sql.contains("detail.pick_ticket_id"))
        assertTrue("副表属性应映射为列名: ${result.sql}", result.sql.contains("detail.item_id"))
    }

    /** O-57：JpqlConverter 链路必须与 HqlConverter 一致（同一份替换核心）。 */
    fun testJpql链路_与Hql链路一致() {
        val table = tableOf("WmsPickTicket")
        val hqlResult = HqlConverter.convert(USER_HQL, table, project)
        val jpqlResult = JpqlConverter.convert(USER_HQL, table, project)
        assertEquals("两条链产物应逐字一致", hqlResult.sql, jpqlResult.sql)
        assertTrue("副表属性应映射为列名: ${jpqlResult.sql}", jpqlResult.sql.contains("detail.pick_ticket_id"))
    }

    /** O-57 T-03：替换产物是列名，重复调用结果一致（幂等）。 */
    fun test幂等_重复调用结果一致() {
        val once = convert(USER_HQL, "WmsPickTicket").sql
        val twice = HqlConverter.convertWithEntityResolver(once, tableOf("WmsPickTicket"), ::resolveEntity)
        assertEquals("列名产物不得再次命中别名规则", once, twice.sql)
    }

    private companion object {
        /** ON 未映射尾注的稳定片段（断言用；文案与 JpaAliasPropertyMapper 一致）。 */
        const val UNMAPPED_HINT = "未出现在 FROM/JOIN"

        /** 用户报障 HQL 的完整期望产物：实体名→表名、副表别名属性→列名、嵌入路径展平。 */
        val USER_HQL_EXPECTED = "select distinct item.code" +
            " from wms_pick_ticket pickTicket" +
            " join wms_pick_ticket_detail detail on detail.pick_ticket_id = pickTicket.id" +
            " join wms_item item on item.id = detail.item_id" +
            " where pickTicket.warehouse_id = ?" +
            " and pickTicket.status = 'EN'" +
            " and pickTicket.created_time >= ?" +
            " and pickTicket.created_time < ?" +
            " order by item.code"

        val USER_HQL = "select distinct item.code" +
            " from WmsPickTicket pickTicket" +
            " join WmsPickTicketDetail detail on detail.pickTicketId = pickTicket.id" +
            " join WmsItem item on item.id = detail.itemId" +
            " where pickTicket.warehouseId = :warehouseId" +
            " and pickTicket.status = 'EN'" +
            " and pickTicket.updateInfo.createdTime >= :startTime" +
            " and pickTicket.updateInfo.createdTime < :endTime" +
            " order by item.code"

        val FIXTURE = """
            public class Demo {

                @javax.persistence.Embeddable
                public static class UpdateInfo {
                    @javax.persistence.Column(name = "created_time")
                    private java.util.Date createdTime;

                    private String updatedBy;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "wms_pick_ticket")
                public static class WmsPickTicket {
                    @javax.persistence.Id
                    private Long id;

                    private String status;

                    @javax.persistence.Column(name = "warehouse_id")
                    private Long warehouseId;

                    @javax.persistence.Embedded
                    private UpdateInfo updateInfo;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "wms_pick_ticket_detail")
                public static class WmsPickTicketDetail {
                    @javax.persistence.Id
                    private Long id;

                    @javax.persistence.Column(name = "pick_ticket_id")
                    private Long pickTicketId;

                    @javax.persistence.Column(name = "item_id")
                    private Long itemId;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "wms_item")
                public static class WmsItem {
                    @javax.persistence.Id
                    private Long id;

                    private String code;

                    @javax.persistence.Column(name = "parent_id")
                    private Long parentId;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "wms_zone")
                public static class WmsZone {
                    @javax.persistence.Id
                    private Long id;

                    @javax.persistence.Column(name = "create_time")
                    private java.util.Date createdTime;

                    private Boolean beAutoZone;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "wms_location")
                public static class WmsLocation {
                    @javax.persistence.Id
                    private Long id;

                    @javax.persistence.Column(name = "zone_id")
                    private Long zoneId;

                    @javax.persistence.Column(name = "create_time")
                    private java.util.Date createdTime;
                }

                @javax.persistence.Entity
                @javax.persistence.Table(name = "wms_shelf")
                public static class WmsShelf {
                    @javax.persistence.Id
                    private Long id;

                    @javax.persistence.Column(name = "zone_id")
                    private Long zoneId;

                    @javax.persistence.Column(name = "gmt_create")
                    private java.util.Date createdTime;
                }
            }
        """.trimIndent()
    }
}
