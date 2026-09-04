package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter;
import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

import java.util.List;

public class HbmXmlEntityResolverTest {

    @Test
    public void classMappingDataModelIsCorrectlyStructured() throws Exception {
        HbmXmlMapping.ClassMapping mapping = new HbmXmlMapping.ClassMapping(
                "RuleTableItem",
                "com.vtradex.rule.server.model.rule.RuleTableItem",
                "THORN_RULE_TABLE_ITEM",
                List.of(
                        new HbmXmlMapping.PropertyMapping("id", "ID", true),
                        new HbmXmlMapping.PropertyMapping("gradeType", "GRADE_TYPE", false),
                        new HbmXmlMapping.PropertyMapping("type", "TYPE", false),
                        new HbmXmlMapping.PropertyMapping("lineNO", "LINE_NO", false)
                ),
                List.of(
                        new HbmXmlMapping.AssociationMapping(
                                "ruleTable",
                                "com.vtradex.rule.server.model.rule.ThornRuleTable",
                                "RULE_TABLE_ID")
                )
        );

        assertEquals("RuleTableItem", mapping.getEntityClassName());
        assertEquals("THORN_RULE_TABLE_ITEM", mapping.getTableName());
        assertEquals(4, mapping.getProperties().size());
        assertEquals(1, mapping.getAssociations().size());
        assertEquals("RULE_TABLE_ID", mapping.getAssociations().get(0).getJoinColumn());
    }

    @Test
    public void propertyMappingCorrectlyIdentifiesId() throws Exception {
        HbmXmlMapping.PropertyMapping idProp = new HbmXmlMapping.PropertyMapping("id", "ID", true);
        HbmXmlMapping.PropertyMapping regularProp = new HbmXmlMapping.PropertyMapping("lineNO", "LINE_NO", false);

        assertEquals(true, idProp.isId());
        assertEquals(false, regularProp.isId());
    }

    @Test
    public void associationMappingStoresJoinColumn() throws Exception {
        HbmXmlMapping.AssociationMapping assoc = new HbmXmlMapping.AssociationMapping(
                "ruleTable",
                "com.vtradex.rule.server.model.rule.ThornRuleTable",
                "RULE_TABLE_ID");

        assertEquals("ruleTable", assoc.getFieldName());
        assertEquals("RULE_TABLE_ID", assoc.getJoinColumn());
        assertEquals("com.vtradex.rule.server.model.rule.ThornRuleTable", assoc.getTargetClassName());
    }

    @Test
    public void hqlConversionWithHbmMappingTableInfo() throws Exception {
        TableInfo tableInfo = new TableInfo(
                "RuleTableItem",
                "THORN_RULE_TABLE_ITEM",
                List.of(
                        new ColumnInfo("id", "ID", true, false, null, null, null, ColumnRole.BUSINESS),
                        new ColumnInfo("gradeType", "GRADE_TYPE", false, false, null, null, null, ColumnRole.BUSINESS),
                        new ColumnInfo("type", "TYPE", false, false, null, null, null, ColumnRole.BUSINESS),
                        new ColumnInfo("lineNO", "LINE_NO", false, false, null, null, null, ColumnRole.BUSINESS)
                ),
                null, null, null,
                List.of(
                        new AssociationInfo("ruleTable", "RULE_TABLE_ID", "THORN_RULE_TABLE", "thornRuleTable")
                )
        );

        String hql = "select rti.gradeType from RuleTableItem rti WHERE rti.ruleTable.id = :ruleTableId AND rti.type = :type ORDER BY rti.lineNO";
        HqlConverter.Result result = HqlConverter.convert(hql, tableInfo);

        assertEquals(
                "select rti.GRADE_TYPE from THORN_RULE_TABLE_ITEM rti WHERE rti.RULE_TABLE_ID = ? AND rti.TYPE = ? ORDER BY rti.LINE_NO",
                result.getSql());
    }
}
