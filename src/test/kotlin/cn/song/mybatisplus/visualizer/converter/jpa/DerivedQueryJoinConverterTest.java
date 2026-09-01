package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * DerivedQueryConverter JOIN 构建测试（JPA 嵌套对象 JOIN 查询支持）。
 *
 * 覆盖：
 * - findByAddressCity 生成 LEFT JOIN
 * - findByAddressCityAndName 混合条件
 * - findByDepartmentName 单嵌套条件
 * - findByAddressCityAndAddressStreet 同表多字段
 * - findByAddressCityAndAddressCountry 同关联去重
 * - findByAddressCityAndDepartmentName 多表 JOIN
 * - findByCity 非嵌套属性不生成 JOIN
 * - findByUnknownField 关联未找到 → 降级
 */
public class DerivedQueryJoinConverterTest {

    /** 含 address 关联的 TableInfo。 */
    private static final TableInfo TABLE_WITH_ASSOCIATIONS = new TableInfo(
            "UserEntity",
            "user",
            List.of(
                    new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("firstName", "first_name", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("lastName", "last_name", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("name", "name", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("age", "age", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("status", "status", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("addressId", "address_id", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("departmentId", "department_id", false, false, null, null, null, ColumnRole.BUSINESS)),
            null, null, null,
            List.of(
                    new AssociationInfo("address", "address_id", "address", "address"),
                    new AssociationInfo("department", "department_id", "department", "department")));

    // ------------------------------------------------------------------
    // JOIN 构建测试
    // ------------------------------------------------------------------

    @Test
    public void findByAddressCity_生成_LEFT_JOIN() {
        // 使用 PartTreeParser 解析
        PartTree partTree = PartTreeParser.parse("findByAddressCity");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE_WITH_ASSOCIATIONS);

        // 验证 JOIN 生成
        assertEquals("应生成 1 个 JOIN", 1, statement.getJoins().size());
        JoinClause join = statement.getJoins().get(0);
        assertEquals("address", join.getTableName());
        assertEquals("address", join.getAlias());
        assertEquals(JoinType.LEFT, join.getType());

        // 验证 ON 条件
        assertEquals(1, join.getOnConditions().size());
        Condition.ColumnComparison onCondition = (Condition.ColumnComparison) join.getOnConditions().get(0);
        assertEquals("address", onCondition.getLeft().getTableAlias());
        assertEquals("id", onCondition.getLeft().getColumnName());
        assertEquals("address_id", onCondition.getRight().getColumnName());

        // 验证 SQL 渲染
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 LEFT JOIN", sql.contains("LEFT JOIN"));
        assertTrue("应包含 address 表", sql.contains("address"));
        assertTrue("应包含 ON 条件", sql.contains("address.id = address_id"));
        assertTrue("应包含 WHERE 条件", sql.contains("address.city = ?"));
    }

    @Test
    public void findByAddressCityAndName_混合条件() {
        PartTree partTree = PartTreeParser.parse("findByAddressCityAndName");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE_WITH_ASSOCIATIONS);

        // 验证只生成一个 JOIN（address）
        assertEquals("应生成 1 个 JOIN", 1, statement.getJoins().size());
        assertEquals("address", statement.getJoins().get(0).getTableName());

        // 验证 SQL
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 LEFT JOIN", sql.contains("LEFT JOIN"));
        assertTrue("应包含 address.city 条件", sql.contains("address.city = ?"));
        assertTrue("应包含 name 条件", sql.contains("name = ?"));
        assertTrue("应使用 AND 连接", sql.contains("AND"));
    }

    @Test
    public void findByDepartmentName_单嵌套条件() {
        PartTree partTree = PartTreeParser.parse("findByDepartmentName");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE_WITH_ASSOCIATIONS);

        // 验证 JOIN
        assertEquals("应生成 1 个 JOIN", 1, statement.getJoins().size());
        assertEquals("department", statement.getJoins().get(0).getTableName());
        assertEquals("department", statement.getJoins().get(0).getAlias());

        // 验证 SQL
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 LEFT JOIN department", sql.contains("LEFT JOIN department"));
        assertTrue("应包含 ON 条件", sql.contains("department.id = department_id"));
        assertTrue("应包含 WHERE 条件", sql.contains("department.name = ?"));
    }

    @Test
    public void findByAddressCityAndAddressStreet_同表多字段() {
        PartTree partTree = PartTreeParser.parse("findByAddressCityAndAddressStreet");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE_WITH_ASSOCIATIONS);

        // 验证只生成一个 JOIN（去重）
        assertEquals("同表多字段应只生成 1 个 JOIN", 1, statement.getJoins().size());
        assertEquals("address", statement.getJoins().get(0).getTableName());

        // 验证 SQL
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 address.city", sql.contains("address.city = ?"));
        assertTrue("应包含 address.street", sql.contains("address.street = ?"));
    }

    @Test
    public void findByAddressCityAndAddressCountry_同关联去重() {
        PartTree partTree = PartTreeParser.parse("findByAddressCityAndAddressCountry");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE_WITH_ASSOCIATIONS);

        // 验证只生成一个 JOIN（去重）
        assertEquals("同关联多次使用应只生成 1 个 JOIN", 1, statement.getJoins().size());
        assertEquals("address", statement.getJoins().get(0).getTableName());

        // 验证 SQL
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 address.city", sql.contains("address.city = ?"));
        assertTrue("应包含 address.country", sql.contains("address.country = ?"));
    }

    @Test
    public void findByAddressCityAndDepartmentName_多表_JOIN() {
        PartTree partTree = PartTreeParser.parse("findByAddressCityAndDepartmentName");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE_WITH_ASSOCIATIONS);

        // 验证生成两个 JOIN
        assertEquals("应生成 2 个 JOIN", 2, statement.getJoins().size());

        List<String> tableNames = statement.getJoins().stream().map(JoinClause::getTableName).toList();
        assertTrue("应包含 address 表", tableNames.contains("address"));
        assertTrue("应包含 department 表", tableNames.contains("department"));

        // 验证 SQL
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 address 的 JOIN", sql.contains("LEFT JOIN address"));
        assertTrue("应包含 department 的 JOIN", sql.contains("LEFT JOIN department"));
    }

    @Test
    public void findByCity_非嵌套属性不生成_JOIN() {
        PartTree partTree = PartTreeParser.parse("findByCity");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, TABLE_WITH_ASSOCIATIONS);

        // 验证不生成 JOIN
        assertTrue("非嵌套属性不应生成 JOIN", statement.getJoins().isEmpty());

        // 验证 SQL
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertTrue("应包含 city 条件", sql.contains("city = ?"));
        assertTrue("不应包含 JOIN", !sql.contains("JOIN"));
    }

    @Test
    public void findByUnknownField_关联未找到_降级() {
        // 创建一个 TableInfo，没有 address 关联
        TableInfo tableNoAssoc = new TableInfo(
                "UserEntity",
                "user",
                List.of(
                        new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
                        new ColumnInfo("name", "name", false, false, null, null, null, ColumnRole.BUSINESS)),
                null, null, null,
                List.of());

        PartTree partTree = PartTreeParser.parse("findByAddressCity");
        SelectStatement statement = (SelectStatement) DerivedQueryConverter.convert(partTree, tableNoAssoc);

        // 验证不生成 JOIN
        assertTrue("无关联时应生成空 JOIN 列表", statement.getJoins().isEmpty());

        // 验证降级为普通列解析（不生成 Raw，避免误判驼峰属性名如 lastName 为嵌套关联）
        Condition condition = statement.getConditions().get(0);
        assertTrue("关联未找到时应降级为普通列解析", !(condition instanceof Condition.Raw));
    }

    // ------------------------------------------------------------------
    // 辅助测试：splitNestedPath 验证
    // ------------------------------------------------------------------

    @Test
    public void splitNestedPath_AddressCity() {
        NestedPropertyPath result = PartTreeParser.splitNestedPath("AddressCity");
        assertEquals("address", result.getAssociationPath());
        assertEquals("City", result.getRemainingPath());
        assertTrue(result.isNested());
    }

    @Test
    public void splitNestedPath_Name() {
        NestedPropertyPath result = PartTreeParser.splitNestedPath("Name");
        assertNull(result.getAssociationPath());
        assertEquals("Name", result.getRemainingPath());
        assertTrue(!result.isNested());
    }

    @Test
    public void splitNestedPath_DepartmentName() {
        NestedPropertyPath result = PartTreeParser.splitNestedPath("DepartmentName");
        assertEquals("department", result.getAssociationPath());
        assertEquals("Name", result.getRemainingPath());
        assertTrue(result.isNested());
    }

    @Test
    public void splitNestedPath_空字符串() {
        NestedPropertyPath result = PartTreeParser.splitNestedPath("");
        assertNull(result.getAssociationPath());
        assertEquals("", result.getRemainingPath());
        assertTrue(!result.isNested());
    }

    @Test
    public void splitNestedPath_单字符() {
        NestedPropertyPath result = PartTreeParser.splitNestedPath("A");
        assertNull(result.getAssociationPath());
        assertEquals("A", result.getRemainingPath());
        assertTrue(!result.isNested());
    }
}
