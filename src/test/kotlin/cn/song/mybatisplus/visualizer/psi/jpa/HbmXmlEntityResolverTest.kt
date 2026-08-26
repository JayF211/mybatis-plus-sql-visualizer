package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HbmXmlEntityResolver 单元测试。
 *
 * 注意：此测试验证 XML 解析逻辑的正确性。
 * 由于需要 IntelliJ Platform 环境（VirtualFileManager, PsiManager 等），
 * 实际的文件搜索和解析测试需要在集成测试环境中运行。
 *
 * 此处仅测试数据模型的转换逻辑。
 */
class HbmXmlEntityResolverTest {

    @Test
    fun `class mapping data model is correctly structured`() {
        val mapping = HbmXmlMapping.ClassMapping(
            entityClassName = "RuleTableItem",
            fullClassName = "com.vtradex.rule.server.model.rule.RuleTableItem",
            tableName = "THORN_RULE_TABLE_ITEM",
            properties = listOf(
                HbmXmlMapping.PropertyMapping("id", "ID", isId = true),
                HbmXmlMapping.PropertyMapping("gradeType", "GRADE_TYPE"),
                HbmXmlMapping.PropertyMapping("type", "TYPE"),
                HbmXmlMapping.PropertyMapping("lineNO", "LINE_NO"),
            ),
            associations = listOf(
                HbmXmlMapping.AssociationMapping(
                    fieldName = "ruleTable",
                    targetClassName = "com.vtradex.rule.server.model.rule.ThornRuleTable",
                    joinColumn = "RULE_TABLE_ID",
                ),
            ),
        )

        assertEquals("RuleTableItem", mapping.entityClassName)
        assertEquals("THORN_RULE_TABLE_ITEM", mapping.tableName)
        assertEquals(4, mapping.properties.size)
        assertEquals(1, mapping.associations.size)
        assertEquals("RULE_TABLE_ID", mapping.associations[0].joinColumn)
    }

    @Test
    fun `property mapping correctly identifies id`() {
        val idProp = HbmXmlMapping.PropertyMapping("id", "ID", isId = true)
        val regularProp = HbmXmlMapping.PropertyMapping("lineNO", "LINE_NO", isId = false)

        assertEquals(true, idProp.isId)
        assertEquals(false, regularProp.isId)
    }

    @Test
    fun `association mapping stores join column`() {
        val assoc = HbmXmlMapping.AssociationMapping(
            fieldName = "ruleTable",
            targetClassName = "com.vtradex.rule.server.model.rule.ThornRuleTable",
            joinColumn = "RULE_TABLE_ID",
        )

        assertEquals("ruleTable", assoc.fieldName)
        assertEquals("RULE_TABLE_ID", assoc.joinColumn)
        assertEquals("com.vtradex.rule.server.model.rule.ThornRuleTable", assoc.targetClassName)
    }

    /**
     * 验证 HQL 关联字段路径解析与 HBM 映射配合工作。
     * 模拟从 .hbm.xml 解析出的 TableInfo 传入 HqlConverter 的场景。
     */
    @Test
    fun `hql conversion with hbm mapping table info`() {
        // 模拟从 .hbm.xml 解析出的 TableInfo
        val tableInfo = TableInfo(
            entityClassName = "RuleTableItem",
            tableName = "THORN_RULE_TABLE_ITEM",
            columns = listOf(
                cn.song.mybatisplus.visualizer.model.ColumnInfo("id", "ID", isId = true),
                cn.song.mybatisplus.visualizer.model.ColumnInfo("gradeType", "GRADE_TYPE"),
                cn.song.mybatisplus.visualizer.model.ColumnInfo("type", "TYPE"),
                cn.song.mybatisplus.visualizer.model.ColumnInfo("lineNO", "LINE_NO"),
            ),
            associations = listOf(
                cn.song.mybatisplus.visualizer.model.AssociationInfo(
                    fieldName = "ruleTable",
                    joinColumn = "RULE_TABLE_ID",
                    targetTable = "THORN_RULE_TABLE",
                    targetAlias = "thornRuleTable",
                ),
            ),
        )

        val hql = "select rti.gradeType from RuleTableItem rti WHERE rti.ruleTable.id = :ruleTableId AND rti.type = :type ORDER BY rti.lineNO"
        val result = HqlConverter.convert(hql, tableInfo)

        // 验证表名、列名、关联字段都正确映射
        assertEquals(
            "select rti.GRADE_TYPE from THORN_RULE_TABLE_ITEM rti WHERE rti.RULE_TABLE_ID = ? AND rti.TYPE = ? ORDER BY rti.LINE_NO",
            result.sql,
        )
    }
}
