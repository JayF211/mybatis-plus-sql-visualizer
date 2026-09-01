package cn.song.mybatisplus.visualizer.converter.jpa;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * PartTreeParser 单元测试。
 *
 * 覆盖：
 * - 全部 27 种 Part.Type 条件类型
 * - Subject 解析（find/count/exists/delete + Distinct + Top N / First N）
 * - OrderBy 解析
 * - IgnoreCase / AllIgnoreCase 检测
 * - 嵌套属性路径
 * - 边界情况（空谓词、单条件、多 And/Or 组合）
 */
public class PartTreeParserTest {

    private static PartTree tree(String name) {
        return PartTreeParser.parse(name);
    }

    private static Condition cond0(PartTree tree) {
        return tree.getPredicates().get(0).getConditions().get(0);
    }

    // ========================================================================
    // Subject 解析测试
    // ========================================================================

    @Test
    public void find_基本查询() {
        PartTree tree = tree("findByLastName");
        assertEquals(Operation.FIND, tree.getSubject().getOperation());
        assertFalse(tree.getSubject().isDistinct());
        assertNull(tree.getSubject().getLimit());
    }

    @Test
    public void count_计数操作() {
        PartTree tree = tree("countByDepartmentId");
        assertEquals(Operation.COUNT, tree.getSubject().getOperation());
    }

    @Test
    public void exists_存在检查() {
        PartTree tree = tree("existsByEmail");
        assertEquals(Operation.EXISTS, tree.getSubject().getOperation());
    }

    @Test
    public void delete_删除操作() {
        PartTree tree = tree("deleteByStatus");
        assertEquals(Operation.DELETE, tree.getSubject().getOperation());
    }

    @Test
    public void remove_删除操作() {
        PartTree tree = tree("removeByExpired");
        assertEquals(Operation.DELETE, tree.getSubject().getOperation());
    }

    @Test
    public void Distinct_去重() {
        PartTree tree = tree("findDistinctByLastName");
        assertTrue(tree.getSubject().isDistinct());
    }

    @Test
    public void Top10_限制10条() {
        PartTree tree = tree("findTop10ByLastName");
        assertEquals(10, tree.getSubject().getLimit());
    }

    @Test
    public void Top1_限制1条() {
        PartTree tree = tree("findTop1ByLastName");
        assertEquals(1, tree.getSubject().getLimit());
    }

    @Test
    public void First_默认1条() {
        PartTree tree = tree("findFirstByLastName");
        assertEquals(1, tree.getSubject().getLimit());
    }

    @Test
    public void First3_限制3条() {
        PartTree tree = tree("findFirst3ByLastName");
        assertEquals(3, tree.getSubject().getLimit());
    }

    @Test
    public void DistinctTop_组合() {
        PartTree tree = tree("findDistinctTop5ByLastName");
        assertTrue(tree.getSubject().isDistinct());
        assertEquals(5, tree.getSubject().getLimit());
    }

    @Test
    public void read_查询同义词() {
        PartTree tree = tree("readByEmail");
        assertEquals(Operation.FIND, tree.getSubject().getOperation());
    }

    @Test
    public void get_查询同义词() {
        PartTree tree = tree("getByEmail");
        assertEquals(Operation.FIND, tree.getSubject().getOperation());
    }

    @Test
    public void query_查询同义词() {
        PartTree tree = tree("queryByEmail");
        assertEquals(Operation.FIND, tree.getSubject().getOperation());
    }

    @Test
    public void search_查询同义词() {
        PartTree tree = tree("searchByEmail");
        assertEquals(Operation.FIND, tree.getSubject().getOperation());
    }

    @Test
    public void stream_查询同义词() {
        PartTree tree = tree("streamByEmail");
        assertEquals(Operation.FIND, tree.getSubject().getOperation());
    }

    @Test
    public void 无By_空谓词() {
        PartTree tree = tree("findAll");
        assertEquals(Operation.FIND, tree.getSubject().getOperation());
        assertTrue(tree.getPredicates().isEmpty());
        assertTrue(tree.getOrderBy().isEmpty());
    }

    // ========================================================================
    // Part.Type 条件类型测试（27 种）
    // ========================================================================

    @Test
    public void Between_范围() {
        PartTree tree = tree("findByAgeBetween");
        Condition cond = cond0(tree);
        assertEquals("age", cond.getPropertyPath());
        assertEquals(PartType.BETWEEN, cond.getType());
    }

    @Test
    public void IsBetween_范围() {
        PartTree tree = tree("findByAgeIsBetween");
        assertEquals(PartType.BETWEEN, cond0(tree).getType());
    }

    @Test
    public void NotNull_非空() {
        PartTree tree = tree("findByNameNotNull");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.IS_NOT_NULL, cond.getType());
    }

    @Test
    public void IsNotNull_非空() {
        PartTree tree = tree("findByNameIsNotNull");
        assertEquals(PartType.IS_NOT_NULL, cond0(tree).getType());
    }

    @Test
    public void Null_为空() {
        PartTree tree = tree("findByNameNull");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.IS_NULL, cond.getType());
    }

    @Test
    public void IsNull_为空() {
        PartTree tree = tree("findByNameIsNull");
        assertEquals(PartType.IS_NULL, cond0(tree).getType());
    }

    @Test
    public void LessThan_小于() {
        PartTree tree = tree("findByAgeLessThan");
        Condition cond = cond0(tree);
        assertEquals("age", cond.getPropertyPath());
        assertEquals(PartType.LESS_THAN, cond.getType());
    }

    @Test
    public void IsLessThan_小于() {
        PartTree tree = tree("findByAgeIsLessThan");
        assertEquals(PartType.LESS_THAN, cond0(tree).getType());
    }

    @Test
    public void LessThanEqual_小于等于() {
        PartTree tree = tree("findByAgeLessThanEqual");
        Condition cond = cond0(tree);
        assertEquals("age", cond.getPropertyPath());
        assertEquals(PartType.LESS_THAN_EQUAL, cond.getType());
    }

    @Test
    public void IsLessThanEqual_小于等于() {
        PartTree tree = tree("findByAgeIsLessThanEqual");
        assertEquals(PartType.LESS_THAN_EQUAL, cond0(tree).getType());
    }

    @Test
    public void GreaterThan_大于() {
        PartTree tree = tree("findByAgeGreaterThan");
        Condition cond = cond0(tree);
        assertEquals("age", cond.getPropertyPath());
        assertEquals(PartType.GREATER_THAN, cond.getType());
    }

    @Test
    public void IsGreaterThan_大于() {
        PartTree tree = tree("findByAgeIsGreaterThan");
        assertEquals(PartType.GREATER_THAN, cond0(tree).getType());
    }

    @Test
    public void GreaterThanEqual_大于等于() {
        PartTree tree = tree("findByAgeGreaterThanEqual");
        Condition cond = cond0(tree);
        assertEquals("age", cond.getPropertyPath());
        assertEquals(PartType.GREATER_THAN_EQUAL, cond.getType());
    }

    @Test
    public void IsGreaterThanEqual_大于等于() {
        PartTree tree = tree("findByAgeIsGreaterThanEqual");
        assertEquals(PartType.GREATER_THAN_EQUAL, cond0(tree).getType());
    }

    @Test
    public void Before_之前() {
        PartTree tree = tree("findByDateBefore");
        Condition cond = cond0(tree);
        assertEquals("date", cond.getPropertyPath());
        assertEquals(PartType.BEFORE, cond.getType());
    }

    @Test
    public void IsBefore_之前() {
        PartTree tree = tree("findByDateIsBefore");
        assertEquals(PartType.BEFORE, cond0(tree).getType());
    }

    @Test
    public void After_之后() {
        PartTree tree = tree("findByDateAfter");
        Condition cond = cond0(tree);
        assertEquals("date", cond.getPropertyPath());
        assertEquals(PartType.AFTER, cond.getType());
    }

    @Test
    public void IsAfter_之后() {
        PartTree tree = tree("findByDateIsAfter");
        assertEquals(PartType.AFTER, cond0(tree).getType());
    }

    @Test
    public void NotLike_不似() {
        PartTree tree = tree("findByNameNotLike");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.NOT_LIKE, cond.getType());
    }

    @Test
    public void IsNotLike_不似() {
        PartTree tree = tree("findByNameIsNotLike");
        assertEquals(PartType.NOT_LIKE, cond0(tree).getType());
    }

    @Test
    public void Like_模糊() {
        PartTree tree = tree("findByNameLike");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.LIKE, cond.getType());
    }

    @Test
    public void IsLike_模糊() {
        PartTree tree = tree("findByNameIsLike");
        assertEquals(PartType.LIKE, cond0(tree).getType());
    }

    @Test
    public void StartingWith_前缀() {
        PartTree tree = tree("findByNameStartingWith");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.STARTING_WITH, cond.getType());
    }

    @Test
    public void IsStartingWith_前缀() {
        PartTree tree = tree("findByNameIsStartingWith");
        assertEquals(PartType.STARTING_WITH, cond0(tree).getType());
    }

    @Test
    public void StartsWith_前缀() {
        PartTree tree = tree("findByNameStartsWith");
        assertEquals(PartType.STARTING_WITH, cond0(tree).getType());
    }

    @Test
    public void EndingWith_后缀() {
        PartTree tree = tree("findByNameEndingWith");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.ENDING_WITH, cond.getType());
    }

    @Test
    public void IsEndingWith_后缀() {
        PartTree tree = tree("findByNameIsEndingWith");
        assertEquals(PartType.ENDING_WITH, cond0(tree).getType());
    }

    @Test
    public void EndsWith_后缀() {
        PartTree tree = tree("findByNameEndsWith");
        assertEquals(PartType.ENDING_WITH, cond0(tree).getType());
    }

    @Test
    public void Containing_包含() {
        PartTree tree = tree("findByNameContaining");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.CONTAINING, cond.getType());
    }

    @Test
    public void IsContaining_包含() {
        PartTree tree = tree("findByNameIsContaining");
        assertEquals(PartType.CONTAINING, cond0(tree).getType());
    }

    @Test
    public void Contains_包含() {
        PartTree tree = tree("findByNameContains");
        assertEquals(PartType.CONTAINING, cond0(tree).getType());
    }

    @Test
    public void NotContaining_不包含() {
        PartTree tree = tree("findByNameNotContaining");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.NOT_CONTAINING, cond.getType());
    }

    @Test
    public void IsNotContaining_不包含() {
        PartTree tree = tree("findByNameIsNotContaining");
        assertEquals(PartType.NOT_CONTAINING, cond0(tree).getType());
    }

    @Test
    public void NotContains_不包含() {
        PartTree tree = tree("findByNameNotContains");
        assertEquals(PartType.NOT_CONTAINING, cond0(tree).getType());
    }

    @Test
    public void NotEmpty_非空集合() {
        PartTree tree = tree("findByTagsNotEmpty");
        Condition cond = cond0(tree);
        assertEquals("tags", cond.getPropertyPath());
        assertEquals(PartType.IS_NOT_EMPTY, cond.getType());
    }

    @Test
    public void IsNotEmpty_非空集合() {
        PartTree tree = tree("findByTagsIsNotEmpty");
        assertEquals(PartType.IS_NOT_EMPTY, cond0(tree).getType());
    }

    @Test
    public void IsEmpty_空集合() {
        PartTree tree = tree("findByTagsIsEmpty");
        Condition cond = cond0(tree);
        assertEquals("tags", cond.getPropertyPath());
        assertEquals(PartType.IS_EMPTY, cond.getType());
    }

    @Test
    public void Empty_空集合() {
        PartTree tree = tree("findByTagsEmpty");
        assertEquals(PartType.IS_EMPTY, cond0(tree).getType());
    }

    @Test
    public void NotIn_不在集合() {
        PartTree tree = tree("findByStatusNotIn");
        Condition cond = cond0(tree);
        assertEquals("status", cond.getPropertyPath());
        assertEquals(PartType.NOT_IN, cond.getType());
    }

    @Test
    public void IsNotIn_不在集合() {
        PartTree tree = tree("findByStatusIsNotIn");
        assertEquals(PartType.NOT_IN, cond0(tree).getType());
    }

    @Test
    public void In_在集合中() {
        PartTree tree = tree("findByStatusIn");
        Condition cond = cond0(tree);
        assertEquals("status", cond.getPropertyPath());
        assertEquals(PartType.IN, cond.getType());
    }

    @Test
    public void IsIn_在集合中() {
        PartTree tree = tree("findByStatusIsIn");
        assertEquals(PartType.IN, cond0(tree).getType());
    }

    @Test
    public void Near_近邻() {
        PartTree tree = tree("findByLocationNear");
        Condition cond = cond0(tree);
        assertEquals("location", cond.getPropertyPath());
        assertEquals(PartType.NEAR, cond.getType());
    }

    @Test
    public void IsNear_近邻() {
        PartTree tree = tree("findByLocationIsNear");
        assertEquals(PartType.NEAR, cond0(tree).getType());
    }

    @Test
    public void Within_范围内() {
        PartTree tree = tree("findByLocationWithin");
        Condition cond = cond0(tree);
        assertEquals("location", cond.getPropertyPath());
        assertEquals(PartType.WITHIN, cond.getType());
    }

    @Test
    public void IsWithin_范围内() {
        PartTree tree = tree("findByLocationIsWithin");
        assertEquals(PartType.WITHIN, cond0(tree).getType());
    }

    @Test
    public void Regex_正则() {
        PartTree tree = tree("findByEmailRegex");
        Condition cond = cond0(tree);
        assertEquals("email", cond.getPropertyPath());
        assertEquals(PartType.REGEX, cond.getType());
    }

    @Test
    public void MatchesRegex_正则() {
        PartTree tree = tree("findByEmailMatchesRegex");
        assertEquals(PartType.REGEX, cond0(tree).getType());
    }

    @Test
    public void Matches_正则() {
        PartTree tree = tree("findByEmailMatches");
        assertEquals(PartType.REGEX, cond0(tree).getType());
    }

    @Test
    public void Exists_存在() {
        PartTree tree = tree("findBySubExists");
        Condition cond = cond0(tree);
        assertEquals("sub", cond.getPropertyPath());
        assertEquals(PartType.EXISTS, cond.getType());
    }

    @Test
    public void True_真() {
        PartTree tree = tree("findByActiveTrue");
        Condition cond = cond0(tree);
        assertEquals("active", cond.getPropertyPath());
        assertEquals(PartType.TRUE, cond.getType());
    }

    @Test
    public void IsTrue_真() {
        PartTree tree = tree("findByActiveIsTrue");
        assertEquals(PartType.TRUE, cond0(tree).getType());
    }

    @Test
    public void False_假() {
        PartTree tree = tree("findByActiveFalse");
        Condition cond = cond0(tree);
        assertEquals("active", cond.getPropertyPath());
        assertEquals(PartType.FALSE, cond.getType());
    }

    @Test
    public void IsFalse_假() {
        PartTree tree = tree("findByActiveIsFalse");
        assertEquals(PartType.FALSE, cond0(tree).getType());
    }

    @Test
    public void Not_不等于() {
        PartTree tree = tree("findByStatusNot");
        Condition cond = cond0(tree);
        assertEquals("status", cond.getPropertyPath());
        assertEquals(PartType.NEGATING_SIMPLE_PROPERTY, cond.getType());
    }

    @Test
    public void IsNot_不等于() {
        PartTree tree = tree("findByStatusIsNot");
        assertEquals(PartType.NEGATING_SIMPLE_PROPERTY, cond0(tree).getType());
    }

    @Test
    public void Is_等于() {
        PartTree tree = tree("findByStatusIs");
        Condition cond = cond0(tree);
        assertEquals("status", cond.getPropertyPath());
        assertEquals(PartType.SIMPLE_PROPERTY, cond.getType());
    }

    @Test
    public void SIMPLE_PROPERTY_兜底() {
        PartTree tree = tree("findByLastName");
        Condition cond = cond0(tree);
        assertEquals("lastName", cond.getPropertyPath());
        assertEquals(PartType.SIMPLE_PROPERTY, cond.getType());
    }

    // ========================================================================
    // And/Or 组合测试
    // ========================================================================

    @Test
    public void And_组合() {
        PartTree tree = tree("findByLastNameAndFirstName");
        assertEquals(1, tree.getPredicates().size());
        assertEquals(Connector.AND, tree.getPredicates().get(0).getConnector());
        assertEquals(2, tree.getPredicates().get(0).getConditions().size());
        assertEquals("lastName", tree.getPredicates().get(0).getConditions().get(0).getPropertyPath());
        assertEquals("firstName", tree.getPredicates().get(0).getConditions().get(1).getPropertyPath());
    }

    @Test
    public void Or_组合() {
        PartTree tree = tree("findByLastNameOrFirstName");
        assertEquals(2, tree.getPredicates().size());
        assertEquals(Connector.AND, tree.getPredicates().get(0).getConnector());
        assertEquals(Connector.OR, tree.getPredicates().get(1).getConnector());
        assertEquals("lastName", tree.getPredicates().get(0).getConditions().get(0).getPropertyPath());
        assertEquals("firstName", tree.getPredicates().get(1).getConditions().get(0).getPropertyPath());
    }

    @Test
    public void 混合AndOr_组合() {
        PartTree tree = tree("findByLastNameAndFirstNameOrEmail");
        assertEquals(2, tree.getPredicates().size());
        // 第一个 OrPart：lastName AND firstName
        assertEquals(2, tree.getPredicates().get(0).getConditions().size());
        assertEquals("lastName", tree.getPredicates().get(0).getConditions().get(0).getPropertyPath());
        assertEquals("firstName", tree.getPredicates().get(0).getConditions().get(1).getPropertyPath());
        // 第二个 OrPart：email
        assertEquals(Connector.OR, tree.getPredicates().get(1).getConnector());
        assertEquals(1, tree.getPredicates().get(1).getConditions().size());
        assertEquals("email", tree.getPredicates().get(1).getConditions().get(0).getPropertyPath());
    }

    @Test
    public void 多And_三条件() {
        PartTree tree = tree("findByLastNameAndFirstNameAndEmail");
        assertEquals(1, tree.getPredicates().size());
        assertEquals(3, tree.getPredicates().get(0).getConditions().size());
    }

    // ========================================================================
    // OrderBy 测试
    // ========================================================================

    @Test
    public void OrderByAsc_升序() {
        PartTree tree = tree("findByLastNameOrderByFirstNameAsc");
        assertEquals(1, tree.getOrderBy().size());
        assertEquals("firstName", tree.getOrderBy().get(0).getPropertyPath());
        assertEquals(SortDirection.ASC, tree.getOrderBy().get(0).getDirection());
    }

    @Test
    public void OrderByDesc_降序() {
        PartTree tree = tree("findByLastNameOrderByFirstNameDesc");
        assertEquals(1, tree.getOrderBy().size());
        assertEquals("firstName", tree.getOrderBy().get(0).getPropertyPath());
        assertEquals(SortDirection.DESC, tree.getOrderBy().get(0).getDirection());
    }

    @Test
    public void OrderBy_默认升序() {
        PartTree tree = tree("findByStatusOrderByCreatedAt");
        assertEquals(1, tree.getOrderBy().size());
        assertEquals("createdAt", tree.getOrderBy().get(0).getPropertyPath());
        assertEquals(SortDirection.ASC, tree.getOrderBy().get(0).getDirection());
    }

    @Test
    public void OrderBy多字段() {
        PartTree tree = tree("findByStatusOrderByLastNameAscAndFirstNameDesc");
        assertEquals(2, tree.getOrderBy().size());
        assertEquals("lastName", tree.getOrderBy().get(0).getPropertyPath());
        assertEquals(SortDirection.ASC, tree.getOrderBy().get(0).getDirection());
        assertEquals("firstName", tree.getOrderBy().get(1).getPropertyPath());
        assertEquals(SortDirection.DESC, tree.getOrderBy().get(1).getDirection());
    }

    @Test
    public void OrderBy在中间_谓词前后() {
        PartTree tree = tree("findByNameAndAgeOrderByCreatedAtDesc");
        // 谓词部分：name AND age
        assertEquals(1, tree.getPredicates().size());
        assertEquals(2, tree.getPredicates().get(0).getConditions().size());
        // OrderBy
        assertEquals(1, tree.getOrderBy().size());
        assertEquals("createdAt", tree.getOrderBy().get(0).getPropertyPath());
        assertEquals(SortDirection.DESC, tree.getOrderBy().get(0).getDirection());
    }

    // ========================================================================
    // IgnoreCase 测试
    // ========================================================================

    @Test
    public void IgnoreCase_忽略大小写() {
        PartTree tree = tree("findByLastNameIgnoreCase");
        Condition cond = cond0(tree);
        assertTrue(cond.isIgnoreCase());
    }

    @Test
    public void AllIgnoreCase_全忽略大小写() {
        PartTree tree = tree("findByLastNameAndFirstNameAllIgnoreCase");
        assertTrue(tree.getPredicates().get(0).getConditions().get(0).isIgnoreCase());
        assertTrue(tree.getPredicates().get(0).getConditions().get(1).isIgnoreCase());
    }

    @Test
    public void IgnoreCase_不影响OrderBy() {
        PartTree tree = tree("findByLastNameOrderByFirstNameAscIgnoreCase");
        Condition cond = cond0(tree);
        assertTrue(cond.isIgnoreCase());
        assertEquals(1, tree.getOrderBy().size());
    }

    // ========================================================================
    // 嵌套属性路径测试
    // ========================================================================

    @Test
    public void 嵌套属性_AddressCity() {
        PartTree tree = tree("findByAddressCity");
        Condition cond = cond0(tree);
        // 当前实现仅首字母小写标准化
        assertEquals("addressCity", cond.getPropertyPath());
    }

    @Test
    public void 嵌套属性_AddressCityAndAddressStreet() {
        PartTree tree = tree("findByAddressCityAndAddressStreet");
        assertEquals(2, tree.getPredicates().get(0).getConditions().size());
        assertEquals("addressCity", tree.getPredicates().get(0).getConditions().get(0).getPropertyPath());
        assertEquals("addressStreet", tree.getPredicates().get(0).getConditions().get(1).getPropertyPath());
    }

    // ========================================================================
    // 边界情况测试
    // ========================================================================

    @Test
    public void 单条件_无AndOr() {
        PartTree tree = tree("findByEmail");
        assertEquals(1, tree.getPredicates().size());
        assertEquals(Connector.AND, tree.getPredicates().get(0).getConnector());
        assertEquals(1, tree.getPredicates().get(0).getConditions().size());
    }

    @Test
    public void 无谓词_仅Subject() {
        PartTree tree = tree("findAll");
        assertTrue(tree.getPredicates().isEmpty());
        assertTrue(tree.getOrderBy().isEmpty());
    }

    @Test
    public void count_无谓词() {
        PartTree tree = tree("countAll");
        assertEquals(Operation.COUNT, tree.getSubject().getOperation());
        assertTrue(tree.getPredicates().isEmpty());
    }

    @Test
    public void 条件后紧跟OrderBy_无歧义() {
        PartTree tree = tree("findByNameNotNullOrderByNameAsc");
        // 条件：name IS NOT NULL
        assertEquals(1, tree.getPredicates().size());
        assertEquals(PartType.IS_NOT_NULL, tree.getPredicates().get(0).getConditions().get(0).getType());
        // OrderBy
        assertEquals(1, tree.getOrderBy().size());
        assertEquals("name", tree.getOrderBy().get(0).getPropertyPath());
        assertEquals(SortDirection.ASC, tree.getOrderBy().get(0).getDirection());
    }

    @Test
    public void IsNull优先级高于SimpleProperty() {
        // NameIsNull 应解析为 name IS NULL，而不是 nameIs = null
        PartTree tree = tree("findByNameIsNull");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.IS_NULL, cond.getType());
    }

    @Test
    public void NotNull优先级高于SimpleProperty() {
        PartTree tree = tree("findByNameNotNull");
        Condition cond = cond0(tree);
        assertEquals("name", cond.getPropertyPath());
        assertEquals(PartType.IS_NOT_NULL, cond.getType());
    }

    @Test
    public void 复杂组合_findDistinctTop10() {
        PartTree tree = tree("findDistinctTop10ByLastName");
        assertEquals(Operation.FIND, tree.getSubject().getOperation());
        assertTrue(tree.getSubject().isDistinct());
        assertEquals(10, tree.getSubject().getLimit());
    }

    @Test
    public void countDistinct_组合() {
        PartTree tree = tree("countDistinctByStatus");
        assertEquals(Operation.COUNT, tree.getSubject().getOperation());
        assertTrue(tree.getSubject().isDistinct());
    }

    @Test
    public void Top100_大数字限制() {
        PartTree tree = tree("findTop100ByCreatedAtGreaterThan");
        assertEquals(100, tree.getSubject().getLimit());
        assertEquals(PartType.GREATER_THAN, tree.getPredicates().get(0).getConditions().get(0).getType());
    }

    @Test
    public void First_无数字默认1() {
        PartTree tree = tree("findFirstByLastName");
        assertEquals(1, tree.getSubject().getLimit());
    }

    @Test
    public void Top_无数不限制() {
        // findTopByLastName → Top 后面无数字，不应解析为 limit
        PartTree tree = tree("findTopByLastName");
        assertNull(tree.getSubject().getLimit());
    }

    // ========================================================================
    // splitNestedPath 测试（JPA 嵌套对象 JOIN 查询支持）
    // ========================================================================

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
        assertFalse(result.isNested());
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
        assertFalse(result.isNested());
    }

    @Test
    public void splitNestedPath_单字符() {
        NestedPropertyPath result = PartTreeParser.splitNestedPath("A");
        assertNull(result.getAssociationPath());
        assertEquals("A", result.getRemainingPath());
        assertFalse(result.isNested());
    }

    @Test
    public void splitNestedPath_三token() {
        NestedPropertyPath result = PartTreeParser.splitNestedPath("AddressCityStreet");
        assertEquals("address", result.getAssociationPath());
        assertEquals("CityStreet", result.getRemainingPath());
        assertTrue(result.isNested());
    }
}
