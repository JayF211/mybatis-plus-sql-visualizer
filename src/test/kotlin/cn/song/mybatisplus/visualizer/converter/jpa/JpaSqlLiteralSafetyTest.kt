package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-JpaDeleteWhereGuard 同构普查批：实体名/关联路径替换端的单引号字面量安全。
 *
 * 缺陷背景：同一管道内属性映射有字面量保护，而实体名→表名替换端是纯文本正则全文命中——
 * `like '%UserEntity%'` 被改成 `'%user%'`、裸 replaceFirst 命中更长标识符前缀
 * （`OwnerInfo` → `OwnerInfoHelper`）、字面量内的 `from OrderEntity` 驱动错表映射。
 * 修复＝[JpaSqlLiteralMasking] 掩码→替换→还原。
 */
class JpaSqlLiteralSafetyTest {

    // k2j 互操作：TableInfo/ColumnInfo 是 Java 构造器，具名实参禁用改位置形态（同包 JpaUpdateConverterTest 先例）
    private val userTable = TableInfo(
        "UserEntity",
        "user",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("username", "username"),
            ColumnInfo("remark", "remark"),
            ColumnInfo("status", "status"),
        ),
    )

    private val ruleTableItemTable = TableInfo(
        "RuleTableItem",
        "THORN_RULE_TABLE_ITEM",
        listOf(
            ColumnInfo("id", "ID", true),
            ColumnInfo("note", "NOTE"),
        ),
        null,
        null,
        null,
        listOf(
            AssociationInfo("ruleTable", "RULE_TABLE_ID", "THORN_RULE_TABLE", "thornRuleTable"),
        ),
    )

    /** mapEntityToTable：实体名替换，LIKE 字面量内的实体名原文保留。 */
    @Test
    fun `hql_entityReplaced_literalEntityNameIntact`() {
        val result = HqlConverter.convert(
            "select u.remark from UserEntity u where u.remark like '%UserEntity%'",
            userTable,
        )
        assertEquals(
            "select u.remark from user u where u.remark like '%UserEntity%'",
            result.sql,
        )
    }

    /** UPDATE SET 字面量含实体名：同样保留（HQL 链）。 */
    @Test
    fun `hql_update_setLiteralEntityNameIntact`() {
        val result = HqlConverter.convert(
            "update UserEntity set remark = 'UserEntity' where id = 1",
            userTable,
        )
        assertEquals(
            "update user set remark = 'UserEntity' where id = 1",
            result.sql,
        )
    }

    /** JPQL 链同口径。 */
    @Test
    fun `jpql_entityReplaced_literalEntityNameIntact`() {
        val result = JpqlConverter.convert(
            "SELECT u.remark FROM UserEntity u WHERE u.remark LIKE '%UserEntity%'",
            userTable,
        )
        assertEquals(
            "SELECT u.remark FROM user u WHERE u.remark LIKE '%UserEntity%'",
            result.sql,
        )
    }

    /** fallback 裸 replaceFirst 缺陷：实体名是更长标识符的前缀时不得误命中。 */
    @Test
    fun `fallback_prefixSubstring_notCorrupted`() {
        val result = HqlConverter.convert(
            "select OwnerInfoHelper.toString(x) from OwnerInfo o",
            null,
        )
        // OwnerInfoHelper 不得被拆坏（此前产物 unknown_tableHelper）；FROM 位实体照常替换
        assertTrue("Helper 标识符应完整保留: ${result.sql}", result.sql.contains("OwnerInfoHelper.toString"))
        assertTrue("FROM 位实体应替换为兜底表名: ${result.sql}", result.sql.contains("from unknown_table o"))
    }

    /** mapAssociationPath：字面量内的点号路径不得被改写或注入注释（三段式关联路径同款口径）。 */
    @Test
    fun `associationPath_literalDotPathIntact`() {
        val result = HqlConverter.convert(
            "update RuleTableItem t set t.note = 'a.ruleTable.id x' where t.ruleTable.id = 5",
            ruleTableItemTable,
        )
        assertEquals(
            "update THORN_RULE_TABLE_ITEM t set t.NOTE = 'a.ruleTable.id x' where t.RULE_TABLE_ID = 5",
            result.sql,
        )
    }

    /** 掩码助手往返：字面量原文在 mask→unmask 后逐字还原（含空字面量与相邻形态）。 */
    @Test
    fun `masking_roundTrip_preservesLiterals`() {
        val sql = "select 'it''s a.%UserEntity%' from t where x = '' and y = 'from OrderEntity'"
        // k2j 互操作：Masked 是 Java record，解构需 component1/2 改访问器方法调用
        val result = JpaSqlLiteralMasking.mask(sql)
        assertTrue("掩码文本不得含引号字面量内容", !result.text().contains("UserEntity"))
        assertEquals(sql, JpaSqlLiteralMasking.unmask(result.text(), result.literals()))
    }
}
