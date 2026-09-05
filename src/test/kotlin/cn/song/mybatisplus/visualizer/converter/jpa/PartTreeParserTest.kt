package cn.song.mybatisplus.visualizer.converter.jpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
class PartTreeParserTest {

    // ========================================================================
    // Subject 解析测试
    // ========================================================================

    @Test
    fun `find_基本查询`() {
        val tree = PartTreeParser.parse("findByLastName")
        assertEquals(Operation.FIND, tree.subject.operation)
        assertFalse(tree.subject.distinct)
        assertNull(tree.subject.limit)
    }

    @Test
    fun `count_计数操作`() {
        val tree = PartTreeParser.parse("countByDepartmentId")
        assertEquals(Operation.COUNT, tree.subject.operation)
    }

    @Test
    fun `exists_存在检查`() {
        val tree = PartTreeParser.parse("existsByEmail")
        assertEquals(Operation.EXISTS, tree.subject.operation)
    }

    @Test
    fun `delete_删除操作`() {
        val tree = PartTreeParser.parse("deleteByStatus")
        assertEquals(Operation.DELETE, tree.subject.operation)
    }

    @Test
    fun `remove_删除操作`() {
        val tree = PartTreeParser.parse("removeByExpired")
        assertEquals(Operation.DELETE, tree.subject.operation)
    }

    @Test
    fun `Distinct_去重`() {
        val tree = PartTreeParser.parse("findDistinctByLastName")
        assertTrue(tree.subject.distinct)
    }

    @Test
    fun `Top10_限制10条`() {
        val tree = PartTreeParser.parse("findTop10ByLastName")
        assertEquals(10, tree.subject.limit)
    }

    @Test
    fun `Top1_限制1条`() {
        val tree = PartTreeParser.parse("findTop1ByLastName")
        assertEquals(1, tree.subject.limit)
    }

    @Test
    fun `First_默认1条`() {
        val tree = PartTreeParser.parse("findFirstByLastName")
        assertEquals(1, tree.subject.limit)
    }

    @Test
    fun `First3_限制3条`() {
        val tree = PartTreeParser.parse("findFirst3ByLastName")
        assertEquals(3, tree.subject.limit)
    }

    @Test
    fun `DistinctTop_组合`() {
        val tree = PartTreeParser.parse("findDistinctTop5ByLastName")
        assertTrue(tree.subject.distinct)
        assertEquals(5, tree.subject.limit)
    }

    @Test
    fun `read_查询同义词`() {
        val tree = PartTreeParser.parse("readByEmail")
        assertEquals(Operation.FIND, tree.subject.operation)
    }

    @Test
    fun `get_查询同义词`() {
        val tree = PartTreeParser.parse("getByEmail")
        assertEquals(Operation.FIND, tree.subject.operation)
    }

    @Test
    fun `query_查询同义词`() {
        val tree = PartTreeParser.parse("queryByEmail")
        assertEquals(Operation.FIND, tree.subject.operation)
    }

    @Test
    fun `search_查询同义词`() {
        val tree = PartTreeParser.parse("searchByEmail")
        assertEquals(Operation.FIND, tree.subject.operation)
    }

    @Test
    fun `stream_查询同义词`() {
        val tree = PartTreeParser.parse("streamByEmail")
        assertEquals(Operation.FIND, tree.subject.operation)
    }

    @Test
    fun `无By_空谓词`() {
        val tree = PartTreeParser.parse("findAll")
        assertEquals(Operation.FIND, tree.subject.operation)
        assertTrue(tree.predicates.isEmpty())
        assertTrue(tree.orderBy.isEmpty())
    }

    // ========================================================================
    // Part.Type 条件类型测试（27 种）
    // ========================================================================

    @Test
    fun `Between_范围`() {
        val tree = PartTreeParser.parse("findByAgeBetween")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("age", cond.propertyPath)
        assertEquals(PartType.BETWEEN, cond.type)
    }

    @Test
    fun `IsBetween_范围`() {
        val tree = PartTreeParser.parse("findByAgeIsBetween")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.BETWEEN, cond.type)
    }

    @Test
    fun `NotNull_非空`() {
        val tree = PartTreeParser.parse("findByNameNotNull")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.IS_NOT_NULL, cond.type)
    }

    @Test
    fun `IsNotNull_非空`() {
        val tree = PartTreeParser.parse("findByNameIsNotNull")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.IS_NOT_NULL, cond.type)
    }

    @Test
    fun `Null_为空`() {
        val tree = PartTreeParser.parse("findByNameNull")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.IS_NULL, cond.type)
    }

    @Test
    fun `IsNull_为空`() {
        val tree = PartTreeParser.parse("findByNameIsNull")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.IS_NULL, cond.type)
    }

    @Test
    fun `LessThan_小于`() {
        val tree = PartTreeParser.parse("findByAgeLessThan")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("age", cond.propertyPath)
        assertEquals(PartType.LESS_THAN, cond.type)
    }

    @Test
    fun `IsLessThan_小于`() {
        val tree = PartTreeParser.parse("findByAgeIsLessThan")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.LESS_THAN, cond.type)
    }

    @Test
    fun `LessThanEqual_小于等于`() {
        val tree = PartTreeParser.parse("findByAgeLessThanEqual")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("age", cond.propertyPath)
        assertEquals(PartType.LESS_THAN_EQUAL, cond.type)
    }

    @Test
    fun `IsLessThanEqual_小于等于`() {
        val tree = PartTreeParser.parse("findByAgeIsLessThanEqual")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.LESS_THAN_EQUAL, cond.type)
    }

    @Test
    fun `GreaterThan_大于`() {
        val tree = PartTreeParser.parse("findByAgeGreaterThan")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("age", cond.propertyPath)
        assertEquals(PartType.GREATER_THAN, cond.type)
    }

    @Test
    fun `IsGreaterThan_大于`() {
        val tree = PartTreeParser.parse("findByAgeIsGreaterThan")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.GREATER_THAN, cond.type)
    }

    @Test
    fun `GreaterThanEqual_大于等于`() {
        val tree = PartTreeParser.parse("findByAgeGreaterThanEqual")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("age", cond.propertyPath)
        assertEquals(PartType.GREATER_THAN_EQUAL, cond.type)
    }

    @Test
    fun `IsGreaterThanEqual_大于等于`() {
        val tree = PartTreeParser.parse("findByAgeIsGreaterThanEqual")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.GREATER_THAN_EQUAL, cond.type)
    }

    @Test
    fun `Before_之前`() {
        val tree = PartTreeParser.parse("findByDateBefore")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("date", cond.propertyPath)
        assertEquals(PartType.BEFORE, cond.type)
    }

    @Test
    fun `IsBefore_之前`() {
        val tree = PartTreeParser.parse("findByDateIsBefore")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.BEFORE, cond.type)
    }

    @Test
    fun `After_之后`() {
        val tree = PartTreeParser.parse("findByDateAfter")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("date", cond.propertyPath)
        assertEquals(PartType.AFTER, cond.type)
    }

    @Test
    fun `IsAfter_之后`() {
        val tree = PartTreeParser.parse("findByDateIsAfter")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.AFTER, cond.type)
    }

    @Test
    fun `NotLike_不似`() {
        val tree = PartTreeParser.parse("findByNameNotLike")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.NOT_LIKE, cond.type)
    }

    @Test
    fun `IsNotLike_不似`() {
        val tree = PartTreeParser.parse("findByNameIsNotLike")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.NOT_LIKE, cond.type)
    }

    @Test
    fun `Like_模糊`() {
        val tree = PartTreeParser.parse("findByNameLike")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.LIKE, cond.type)
    }

    @Test
    fun `IsLike_模糊`() {
        val tree = PartTreeParser.parse("findByNameIsLike")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.LIKE, cond.type)
    }

    @Test
    fun `StartingWith_前缀`() {
        val tree = PartTreeParser.parse("findByNameStartingWith")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.STARTING_WITH, cond.type)
    }

    @Test
    fun `IsStartingWith_前缀`() {
        val tree = PartTreeParser.parse("findByNameIsStartingWith")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.STARTING_WITH, cond.type)
    }

    @Test
    fun `StartsWith_前缀`() {
        val tree = PartTreeParser.parse("findByNameStartsWith")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.STARTING_WITH, cond.type)
    }

    @Test
    fun `EndingWith_后缀`() {
        val tree = PartTreeParser.parse("findByNameEndingWith")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.ENDING_WITH, cond.type)
    }

    @Test
    fun `IsEndingWith_后缀`() {
        val tree = PartTreeParser.parse("findByNameIsEndingWith")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.ENDING_WITH, cond.type)
    }

    @Test
    fun `EndsWith_后缀`() {
        val tree = PartTreeParser.parse("findByNameEndsWith")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.ENDING_WITH, cond.type)
    }

    @Test
    fun `Containing_包含`() {
        val tree = PartTreeParser.parse("findByNameContaining")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.CONTAINING, cond.type)
    }

    @Test
    fun `IsContaining_包含`() {
        val tree = PartTreeParser.parse("findByNameIsContaining")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.CONTAINING, cond.type)
    }

    @Test
    fun `Contains_包含`() {
        val tree = PartTreeParser.parse("findByNameContains")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.CONTAINING, cond.type)
    }

    @Test
    fun `NotContaining_不包含`() {
        val tree = PartTreeParser.parse("findByNameNotContaining")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.NOT_CONTAINING, cond.type)
    }

    @Test
    fun `IsNotContaining_不包含`() {
        val tree = PartTreeParser.parse("findByNameIsNotContaining")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.NOT_CONTAINING, cond.type)
    }

    @Test
    fun `NotContains_不包含`() {
        val tree = PartTreeParser.parse("findByNameNotContains")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.NOT_CONTAINING, cond.type)
    }

    @Test
    fun `NotEmpty_非空集合`() {
        val tree = PartTreeParser.parse("findByTagsNotEmpty")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("tags", cond.propertyPath)
        assertEquals(PartType.IS_NOT_EMPTY, cond.type)
    }

    @Test
    fun `IsNotEmpty_非空集合`() {
        val tree = PartTreeParser.parse("findByTagsIsNotEmpty")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.IS_NOT_EMPTY, cond.type)
    }

    @Test
    fun `IsEmpty_空集合`() {
        val tree = PartTreeParser.parse("findByTagsIsEmpty")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("tags", cond.propertyPath)
        assertEquals(PartType.IS_EMPTY, cond.type)
    }

    @Test
    fun `Empty_空集合`() {
        val tree = PartTreeParser.parse("findByTagsEmpty")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.IS_EMPTY, cond.type)
    }

    @Test
    fun `NotIn_不在集合`() {
        val tree = PartTreeParser.parse("findByStatusNotIn")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("status", cond.propertyPath)
        assertEquals(PartType.NOT_IN, cond.type)
    }

    @Test
    fun `IsNotIn_不在集合`() {
        val tree = PartTreeParser.parse("findByStatusIsNotIn")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.NOT_IN, cond.type)
    }

    @Test
    fun `In_在集合中`() {
        val tree = PartTreeParser.parse("findByStatusIn")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("status", cond.propertyPath)
        assertEquals(PartType.IN, cond.type)
    }

    @Test
    fun `IsIn_在集合中`() {
        val tree = PartTreeParser.parse("findByStatusIsIn")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.IN, cond.type)
    }

    @Test
    fun `Near_近邻`() {
        val tree = PartTreeParser.parse("findByLocationNear")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("location", cond.propertyPath)
        assertEquals(PartType.NEAR, cond.type)
    }

    @Test
    fun `IsNear_近邻`() {
        val tree = PartTreeParser.parse("findByLocationIsNear")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.NEAR, cond.type)
    }

    @Test
    fun `Within_范围内`() {
        val tree = PartTreeParser.parse("findByLocationWithin")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("location", cond.propertyPath)
        assertEquals(PartType.WITHIN, cond.type)
    }

    @Test
    fun `IsWithin_范围内`() {
        val tree = PartTreeParser.parse("findByLocationIsWithin")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.WITHIN, cond.type)
    }

    @Test
    fun `Regex_正则`() {
        val tree = PartTreeParser.parse("findByEmailRegex")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("email", cond.propertyPath)
        assertEquals(PartType.REGEX, cond.type)
    }

    @Test
    fun `MatchesRegex_正则`() {
        val tree = PartTreeParser.parse("findByEmailMatchesRegex")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.REGEX, cond.type)
    }

    @Test
    fun `Matches_正则`() {
        val tree = PartTreeParser.parse("findByEmailMatches")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.REGEX, cond.type)
    }

    @Test
    fun `Exists_存在`() {
        val tree = PartTreeParser.parse("findBySubExists")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("sub", cond.propertyPath)
        assertEquals(PartType.EXISTS, cond.type)
    }

    @Test
    fun `True_真`() {
        val tree = PartTreeParser.parse("findByActiveTrue")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("active", cond.propertyPath)
        assertEquals(PartType.TRUE, cond.type)
    }

    @Test
    fun `IsTrue_真`() {
        val tree = PartTreeParser.parse("findByActiveIsTrue")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.TRUE, cond.type)
    }

    @Test
    fun `False_假`() {
        val tree = PartTreeParser.parse("findByActiveFalse")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("active", cond.propertyPath)
        assertEquals(PartType.FALSE, cond.type)
    }

    @Test
    fun `IsFalse_假`() {
        val tree = PartTreeParser.parse("findByActiveIsFalse")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.FALSE, cond.type)
    }

    @Test
    fun `Not_不等于`() {
        val tree = PartTreeParser.parse("findByStatusNot")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("status", cond.propertyPath)
        assertEquals(PartType.NEGATING_SIMPLE_PROPERTY, cond.type)
    }

    @Test
    fun `IsNot_不等于`() {
        val tree = PartTreeParser.parse("findByStatusIsNot")
        val cond = tree.predicates[0].conditions[0]
        assertEquals(PartType.NEGATING_SIMPLE_PROPERTY, cond.type)
    }

    @Test
    fun `Is_等于`() {
        val tree = PartTreeParser.parse("findByStatusIs")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("status", cond.propertyPath)
        assertEquals(PartType.SIMPLE_PROPERTY, cond.type)
    }

    @Test
    fun `SIMPLE_PROPERTY_兜底`() {
        val tree = PartTreeParser.parse("findByLastName")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("lastName", cond.propertyPath)
        assertEquals(PartType.SIMPLE_PROPERTY, cond.type)
    }

    // ========================================================================
    // And/Or 组合测试
    // ========================================================================

    @Test
    fun `And_组合`() {
        val tree = PartTreeParser.parse("findByLastNameAndFirstName")
        assertEquals(1, tree.predicates.size)
        assertEquals(Connector.AND, tree.predicates[0].connector)
        assertEquals(2, tree.predicates[0].conditions.size)
        assertEquals("lastName", tree.predicates[0].conditions[0].propertyPath)
        assertEquals("firstName", tree.predicates[0].conditions[1].propertyPath)
    }

    @Test
    fun `Or_组合`() {
        val tree = PartTreeParser.parse("findByLastNameOrFirstName")
        assertEquals(2, tree.predicates.size)
        assertEquals(Connector.AND, tree.predicates[0].connector)
        assertEquals(Connector.OR, tree.predicates[1].connector)
        assertEquals("lastName", tree.predicates[0].conditions[0].propertyPath)
        assertEquals("firstName", tree.predicates[1].conditions[0].propertyPath)
    }

    @Test
    fun `混合AndOr_组合`() {
        val tree = PartTreeParser.parse("findByLastNameAndFirstNameOrEmail")
        assertEquals(2, tree.predicates.size)
        // 第一个 OrPart：lastName AND firstName
        assertEquals(2, tree.predicates[0].conditions.size)
        assertEquals("lastName", tree.predicates[0].conditions[0].propertyPath)
        assertEquals("firstName", tree.predicates[0].conditions[1].propertyPath)
        // 第二个 OrPart：email
        assertEquals(Connector.OR, tree.predicates[1].connector)
        assertEquals(1, tree.predicates[1].conditions.size)
        assertEquals("email", tree.predicates[1].conditions[0].propertyPath)
    }

    @Test
    fun `多And_三条件`() {
        val tree = PartTreeParser.parse("findByLastNameAndFirstNameAndEmail")
        assertEquals(1, tree.predicates.size)
        assertEquals(3, tree.predicates[0].conditions.size)
    }

    // ========================================================================
    // OrderBy 测试
    // ========================================================================

    @Test
    fun `OrderByAsc_升序`() {
        val tree = PartTreeParser.parse("findByLastNameOrderByFirstNameAsc")
        assertEquals(1, tree.orderBy.size)
        assertEquals("firstName", tree.orderBy[0].propertyPath)
        assertEquals(SortDirection.ASC, tree.orderBy[0].direction)
    }

    @Test
    fun `OrderByDesc_降序`() {
        val tree = PartTreeParser.parse("findByLastNameOrderByFirstNameDesc")
        assertEquals(1, tree.orderBy.size)
        assertEquals("firstName", tree.orderBy[0].propertyPath)
        assertEquals(SortDirection.DESC, tree.orderBy[0].direction)
    }

    @Test
    fun `OrderBy_默认升序`() {
        val tree = PartTreeParser.parse("findByStatusOrderByCreatedAt")
        assertEquals(1, tree.orderBy.size)
        assertEquals("createdAt", tree.orderBy[0].propertyPath)
        assertEquals(SortDirection.ASC, tree.orderBy[0].direction)
    }

    @Test
    fun `OrderBy多字段`() {
        val tree = PartTreeParser.parse("findByStatusOrderByLastNameAscAndFirstNameDesc")
        assertEquals(2, tree.orderBy.size)
        assertEquals("lastName", tree.orderBy[0].propertyPath)
        assertEquals(SortDirection.ASC, tree.orderBy[0].direction)
        assertEquals("firstName", tree.orderBy[1].propertyPath)
        assertEquals(SortDirection.DESC, tree.orderBy[1].direction)
    }

    @Test
    fun `OrderBy在中间_谓词前后`() {
        val tree = PartTreeParser.findByNameAndAgeOrderByCreatedAtDesc
        // 谓词部分：name AND age
        assertEquals(1, tree.predicates.size)
        assertEquals(2, tree.predicates[0].conditions.size)
        // OrderBy
        assertEquals(1, tree.orderBy.size)
        assertEquals("createdAt", tree.orderBy[0].propertyPath)
        assertEquals(SortDirection.DESC, tree.orderBy[0].direction)
    }

    private val PartTreeParser.findByNameAndAgeOrderByCreatedAtDesc
        get() = parse("findByNameAndAgeOrderByCreatedAtDesc")

    // ========================================================================
    // IgnoreCase 测试
    // ========================================================================

    @Test
    fun `IgnoreCase_忽略大小写`() {
        val tree = PartTreeParser.parse("findByLastNameIgnoreCase")
        val cond = tree.predicates[0].conditions[0]
        assertTrue(cond.ignoreCase)
    }

    @Test
    fun `AllIgnoreCase_全忽略大小写`() {
        val tree = PartTreeParser.parse("findByLastNameAndFirstNameAllIgnoreCase")
        assertTrue(tree.predicates[0].conditions[0].ignoreCase)
        assertTrue(tree.predicates[0].conditions[1].ignoreCase)
    }

    @Test
    fun `IgnoreCase_不影响OrderBy`() {
        val tree = PartTreeParser.parse("findByLastNameOrderByFirstNameAscIgnoreCase")
        val cond = tree.predicates[0].conditions[0]
        assertTrue(cond.ignoreCase)
        assertEquals(1, tree.orderBy.size)
    }

    // ------------------------------------------------------------------------
    // T-J4：IgnoreCase / AllIgnoreCase 任意位置剥离 + 逐段 ignoreCase 标记
    // ------------------------------------------------------------------------

    @Test
    fun `TJ4_IgnoreCase_中间位置逐段剥离不并入属性名`() {
        // 旧实现：中间位置的 IgnoreCase 会并入属性名（"nameIgnoreCase"）
        val tree = PartTreeParser.parse("findByNameIgnoreCaseAndEmail")
        val conditions = tree.predicates[0].conditions
        assertEquals(2, conditions.size)
        assertEquals("name", conditions[0].propertyPath)
        assertTrue("IgnoreCase 紧跟的段应记录 ignoreCase 标记", conditions[0].ignoreCase)
        assertEquals("email", conditions[1].propertyPath)
        assertFalse("其余段不应误标 ignoreCase", conditions[1].ignoreCase)
    }

    @Test
    fun `TJ4_IgnoreCase_条件关键字后仍生效`() {
        val tree = PartTreeParser.parse("findByNameStartingWithIgnoreCaseAndEmail")
        val conditions = tree.predicates[0].conditions
        assertEquals(2, conditions.size)
        assertEquals(PartType.STARTING_WITH, conditions[0].type)
        assertEquals("name", conditions[0].propertyPath)
        assertTrue(conditions[0].ignoreCase)
        assertEquals("email", conditions[1].propertyPath)
        assertFalse(conditions[1].ignoreCase)
    }

    @Test
    fun `TJ4_AllIgnoreCase_中间位置全局生效`() {
        val tree = PartTreeParser.parse("findByNameAllIgnoreCaseAndEmail")
        val conditions = tree.predicates[0].conditions
        assertEquals(2, conditions.size)
        assertEquals("name", conditions[0].propertyPath)
        assertEquals("email", conditions[1].propertyPath)
        assertTrue("AllIgnoreCase 应全局生效", conditions[0].ignoreCase)
        assertTrue(conditions[1].ignoreCase)
    }

    @Test
    fun `TJ4_跨Or段_逐段标记`() {
        val tree = PartTreeParser.parse("findByNameIgnoreCaseOrEmail")
        assertEquals(2, tree.predicates.size)
        assertTrue(tree.predicates[0].conditions[0].ignoreCase)
        assertEquals("name", tree.predicates[0].conditions[0].propertyPath)
        assertFalse(tree.predicates[1].conditions[0].ignoreCase)
        assertEquals("email", tree.predicates[1].conditions[0].propertyPath)
    }

    // ========================================================================
    // 嵌套属性路径测试
    // ========================================================================

    @Test
    fun `嵌套属性_AddressCity`() {
        val tree = PartTreeParser.parse("findByAddressCity")
        val cond = tree.predicates[0].conditions[0]
        // 当前实现仅首字母小写标准化
        assertEquals("addressCity", cond.propertyPath)
    }

    @Test
    fun `嵌套属性_AddressCityAndAddressStreet`() {
        val tree = PartTreeParser.parse("findByAddressCityAndAddressStreet")
        assertEquals(2, tree.predicates[0].conditions.size)
        assertEquals("addressCity", tree.predicates[0].conditions[0].propertyPath)
        assertEquals("addressStreet", tree.predicates[0].conditions[1].propertyPath)
    }

    // ========================================================================
    // 边界情况测试
    // ========================================================================

    @Test
    fun `单条件_无AndOr`() {
        val tree = PartTreeParser.parse("findByEmail")
        assertEquals(1, tree.predicates.size)
        assertEquals(Connector.AND, tree.predicates[0].connector)
        assertEquals(1, tree.predicates[0].conditions.size)
    }

    @Test
    fun `无谓词_仅Subject`() {
        val tree = PartTreeParser.parse("findAll")
        assertTrue(tree.predicates.isEmpty())
        assertTrue(tree.orderBy.isEmpty())
    }

    @Test
    fun `count_无谓词`() {
        val tree = PartTreeParser.parse("countAll")
        assertEquals(Operation.COUNT, tree.subject.operation)
        assertTrue(tree.predicates.isEmpty())
    }

    @Test
    fun `条件后紧跟OrderBy_无歧义`() {
        val tree = PartTreeParser.parse("findByNameNotNullOrderByNameAsc")
        // 条件：name IS NOT NULL
        assertEquals(1, tree.predicates.size)
        assertEquals(PartType.IS_NOT_NULL, tree.predicates[0].conditions[0].type)
        // OrderBy
        assertEquals(1, tree.orderBy.size)
        assertEquals("name", tree.orderBy[0].propertyPath)
        assertEquals(SortDirection.ASC, tree.orderBy[0].direction)
    }

    @Test
    fun `IsNull优先级高于SimpleProperty`() {
        // NameIsNull 应解析为 name IS NULL，而不是 nameIs = null
        val tree = PartTreeParser.parse("findByNameIsNull")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.IS_NULL, cond.type)
    }

    @Test
    fun `NotNull优先级高于SimpleProperty`() {
        val tree = PartTreeParser.parse("findByNameNotNull")
        val cond = tree.predicates[0].conditions[0]
        assertEquals("name", cond.propertyPath)
        assertEquals(PartType.IS_NOT_NULL, cond.type)
    }

    @Test
    fun `复杂组合_findDistinctTop10`() {
        val tree = PartTreeParser.parse("findDistinctTop10ByLastName")
        assertEquals(Operation.FIND, tree.subject.operation)
        assertTrue(tree.subject.distinct)
        assertEquals(10, tree.subject.limit)
    }

    @Test
    fun `countDistinct_组合`() {
        val tree = PartTreeParser.parse("countDistinctByStatus")
        assertEquals(Operation.COUNT, tree.subject.operation)
        assertTrue(tree.subject.distinct)
    }

    @Test
    fun `Top100_大数字限制`() {
        val tree = PartTreeParser.parse("findTop100ByCreatedAtGreaterThan")
        assertEquals(100, tree.subject.limit)
        assertEquals(PartType.GREATER_THAN, tree.predicates[0].conditions[0].type)
    }

    @Test
    fun `First_无数字默认1`() {
        val tree = PartTreeParser.parse("findFirstByLastName")
        assertEquals(1, tree.subject.limit)
    }

    @Test
    fun `Top_无数不限制`() {
        // findTopByLastName → Top 后面无数字，不应解析为 limit
        val tree = PartTreeParser.parse("findTopByLastName")
        assertNull(tree.subject.limit)
    }

    // ========================================================================
    // splitNestedPath 测试（JPA 嵌套对象 JOIN 查询支持）
    // ========================================================================

    @Test
    fun `splitNestedPath_AddressCity → address + City`() {
        val result = PartTreeParser.splitNestedPath("AddressCity")
        assertEquals("address", result.associationPath)
        assertEquals("City", result.remainingPath)
        assertTrue(result.isNested)
    }

    @Test
    fun `splitNestedPath_Name → 非嵌套`() {
        val result = PartTreeParser.splitNestedPath("Name")
        assertNull(result.associationPath)
        assertEquals("Name", result.remainingPath)
        assertFalse(result.isNested)
    }

    @Test
    fun `splitNestedPath_DepartmentName → department + Name`() {
        val result = PartTreeParser.splitNestedPath("DepartmentName")
        assertEquals("department", result.associationPath)
        assertEquals("Name", result.remainingPath)
        assertTrue(result.isNested)
    }

    @Test
    fun `splitNestedPath_空字符串 → 非嵌套`() {
        val result = PartTreeParser.splitNestedPath("")
        assertNull(result.associationPath)
        assertEquals("", result.remainingPath)
        assertFalse(result.isNested)
    }

    @Test
    fun `splitNestedPath_单字符 → 非嵌套`() {
        val result = PartTreeParser.splitNestedPath("A")
        assertNull(result.associationPath)
        assertEquals("A", result.remainingPath)
        assertFalse(result.isNested)
    }

    @Test
    fun `splitNestedPath_三token → 首token为关联`() {
        val result = PartTreeParser.splitNestedPath("AddressCityStreet")
        assertEquals("address", result.associationPath)
        assertEquals("CityStreet", result.remainingPath)
        assertTrue(result.isNested)
    }

    // ------------------------------------------------------------------
    // T-J3/M39：By 与 OrderBy 相邻形态（isOrderByAt 修复回归）
    // ------------------------------------------------------------------

    @Test
    fun `parse_AllByOrderBy_无谓词仅排序`() {
        val result = PartTreeParser.parse("findAllByOrderByCreateTimeDesc")
        assertTrue("Subject 的 By 不应被误跳过，OrderBy 应被剥离为排序", result.predicates.isEmpty())
        assertEquals(1, result.orderBy.size)
        assertEquals("createTime", result.orderBy[0].propertyPath)
    }

    @Test
    fun `parse_Top10ByOrderBy_谓词为空且排序保留`() {
        val result = PartTreeParser.parse("findTop10ByOrderByCreateTimeDesc")
        assertTrue(result.predicates.isEmpty())
        assertEquals(1, result.orderBy.size)
        assertEquals("DESC", result.orderBy[0].direction.name)
    }

    @Test
    fun `parse_XxxOrderByYyy_常规OrderBy仍正确`() {
        val result = PartTreeParser.parse("findByStatusOrderByIdDesc")
        assertEquals(1, result.predicates.size)
        assertEquals(1, result.orderBy.size)
        assertEquals("id", result.orderBy[0].propertyPath)
    }
}