package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HqlConverter SELECT 子句展开专项测试（T-HQL-SELECT-01）。
 *
 * 背景（2026-08-31 用户实测）：HQL `SELECT u FROM UserEntity u` 生成的 SQL 原样保留
 * `SELECT u`，在 MySQL 执行报 `[1054] Unknown column 'u' in 'field list'`——
 * HQL 语义中 `SELECT u` 表示「取整个实体」，必须展开为该实体映射的全部列。
 *
 * 本测试覆盖展开规则及其全部「不展开」白名单边界。
 */
class HqlConverterSelectExpandTest {

    private val userTable = TableInfo("UserEntity", "user_entity", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("username", "username"),
            ColumnInfo("status", "status"),
            ColumnInfo("age", "age"),
            ColumnInfo("departmentId", "department_id"),
            ColumnInfo("createTime", "create_time"),
        ))

    /** `SELECT u` / 无 SELECT 子句时的期望展开结果（顺序与 columns 声明顺序一致）。 */
    private val expanded = "u.id, u.username, u.status, u.age, u.department_id, u.create_time"

    // ------------------------------------------------------------------
    // 1. 核心场景：SELECT <别名> 展开
    // ------------------------------------------------------------------

    @Test
    fun `selectAlias_expandsToColumnList`() {
        val hql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT $expanded FROM user_entity u WHERE u.username = ?", result.sql)
    }

    /** 回归护栏：用户实测报错的直接判据——SELECT 后不得残留裸别名。 */
    @Test
    fun `selectAlias_neverLeavesBareAliasInFieldList`() {
        val hql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertFalse("SELECT 子句不应残留裸别名 u", result.sql.contains("SELECT u "))
        assertTrue("WHERE 子句中的别名前缀应保留", result.sql.contains("u.username"))
    }

    // ------------------------------------------------------------------
    // 2. 无 SELECT 子句（HQL 隐式查询）
    // ------------------------------------------------------------------

    @Test
    fun `noSelectClause_insertsColumnList`() {
        val hql = "FROM UserEntity u WHERE u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT $expanded FROM user_entity u WHERE u.username = ?", result.sql)
    }

    @Test
    fun `lowercaseHql_preservesOriginalKeywordCase`() {
        val hql = "from UserEntity u where u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT $expanded from user_entity u where u.username = ?", result.sql)
    }

    // ------------------------------------------------------------------
    // 3. DISTINCT
    // ------------------------------------------------------------------

    @Test
    fun `distinctAlias_expandsAfterDistinctKeyword`() {
        val hql = "SELECT DISTINCT u FROM UserEntity u"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT DISTINCT $expanded FROM user_entity u", result.sql)
    }

    // ------------------------------------------------------------------
    // 4. 白名单：不展开
    // ------------------------------------------------------------------

    @Test
    fun `explicitColumns_notExpanded`() {
        val hql = "SELECT u.id, u.username FROM UserEntity u"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT u.id, u.username FROM user_entity u", result.sql)
    }

    @Test
    fun `countFunction_notExpanded`() {
        val hql = "SELECT count(u) FROM UserEntity u"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT count(u) FROM user_entity u", result.sql)
    }

    @Test
    fun `starSelector_notExpanded`() {
        val hql = "SELECT * FROM UserEntity u WHERE u.id = :id"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT * FROM user_entity u WHERE u.id = ?", result.sql)
    }

    @Test
    fun `unknownIdentifier_notExpanded`() {
        // x 不是 FROM/JOIN 中登记的别名，不得展开（保守优先）
        val hql = "SELECT x FROM UserEntity u WHERE u.id = :id"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT x FROM user_entity u WHERE u.id = ?", result.sql)
    }

    @Test
    fun `deleteStatement_notExpanded`() {
        val hql = "DELETE FROM UserEntity u WHERE u.id = :id"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("DELETE FROM user_entity u WHERE u.id = ?", result.sql)
    }

    // ------------------------------------------------------------------
    // 5. JOIN / 子查询
    // ------------------------------------------------------------------

    @Test
    fun `joinFetch_multipleAliases_joinedEntityDegradesToStar`() {
        val hql = "SELECT u, d FROM UserEntity u JOIN FETCH u.department d"
        val result = HqlConverter.convert(hql, userTable)
        // 主表有列元数据 → 展开；JOIN 副表无列元数据 → 降级 d.*
        assertEquals(
            "SELECT $expanded, d.* /* 列信息未解析，降级为 d.* */ FROM user_entity u " +
                "LEFT JOIN department d ON d.id = u.department_id /* 推断的ON条件，请验证 */",
            result.sql,
        )
    }

    @Test
    fun `joinFetch_singleAlias_joinedTableAliasNotLeaked`() {
        val hql = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :name"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("主表应展开", result.sql.startsWith("SELECT $expanded FROM "))
        assertFalse("SELECT 子句不应含副表别名 d", result.sql.substringBefore(" FROM ").contains(" d"))
    }

    @Test
    fun `existsSubquery_onlyOuterSelectExpanded`() {
        val hql = "SELECT u FROM UserEntity u WHERE EXISTS (SELECT o FROM OrderEntity o WHERE o.customerId = u.id)"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals(
            "SELECT $expanded FROM user_entity u WHERE EXISTS (SELECT o FROM OrderEntity o WHERE o.customerId = u.id)",
            result.sql,
        )
    }

    // ------------------------------------------------------------------
    // 6. IN 子句
    // ------------------------------------------------------------------

    @Test
    fun `inClause_expandsAndKeepsPlaceholder`() {
        val hql = "SELECT u FROM UserEntity u WHERE u.id IN :ids"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT $expanded FROM user_entity u WHERE u.id IN (?)", result.sql)
        assertTrue("IN 参数应被标记", result.inClauseParams.contains("ids"))
    }

    // ------------------------------------------------------------------
    // 7. 降级：列元数据不可得
    // ------------------------------------------------------------------

    @Test
    fun `emptyColumns_degradesToStarWithComment`() {
        val emptyTable = TableInfo("EmptyEntity", "empty_table", emptyList())
        val hql = "SELECT u FROM EmptyEntity u WHERE u.id = :id"
        val result = HqlConverter.convert(hql, emptyTable)
        assertEquals(
            "SELECT u.* /* 列信息未解析，降级为 u.* */ FROM empty_table u WHERE u.id = ?",
            result.sql,
        )
    }

    @Test
    fun `nullTableInfo_degradesToStar`() {
        val hql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = HqlConverter.convert(hql, null)
        assertTrue("应降级为 u.*", result.sql.startsWith("SELECT u.* /* 列信息未解析，降级为 u.* */ FROM unknown_table u"))
    }

    // ------------------------------------------------------------------
    // 8. 裸表名（无别名）——T-BareFromSelect
    // ------------------------------------------------------------------

    /** 裸表名形态下列限定用表名本身（SQL 允许 表名.列）。 */
    private val qualifiedExpanded =
        "user_entity.id, user_entity.username, user_entity.status, user_entity.age, user_entity.department_id, user_entity.create_time"

    @Test
    fun `bareFrom_noAlias_insertsQualifiedColumnList`() {
        // 用户实测：getHibernateTemplate().find("from WmsAsn") 此前只显示 FROM wms_asn
        val hql = "from UserEntity"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT $qualifiedExpanded from user_entity", result.sql)
    }

    @Test
    fun `bareFrom_noAlias_withWhere_keepsTail`() {
        val hql = "from UserEntity where status = :status"
        val result = HqlConverter.convert(hql, userTable)
        assertEquals("SELECT $qualifiedExpanded from user_entity where status = ?", result.sql)
    }

    @Test
    fun `bareFrom_withJoinKeywords_joinKeywordNotRegisteredAsAlias`() {
        // `from t left join ...` 无主表别名时，LEFT 不得被登记为别名（否则产出 SELECT left.id 语法错）
        val hql = "from UserEntity left join OrderEntity o on o.id = UserEntity.departmentId where UserEntity.status = :status"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("应以表名限定列清单开头: ${result.sql}", result.sql.startsWith("SELECT $qualifiedExpanded "))
        assertFalse("不得把 LEFT 当别名展开: ${result.sql}", result.sql.contains("left.id"))
        assertTrue("FROM 子句应原样保留: ${result.sql}", result.sql.contains("from user_entity left join OrderEntity o"))
    }

    @Test
    fun `bareFrom_nullTable_degradesToStar`() {
        val hql = "from UserEntity"
        val result = HqlConverter.convert(hql, null)
        assertEquals(
            "SELECT unknown_table.* /* 列信息未解析，降级为 unknown_table.* */ from unknown_table",
            result.sql,
        )
    }
}
