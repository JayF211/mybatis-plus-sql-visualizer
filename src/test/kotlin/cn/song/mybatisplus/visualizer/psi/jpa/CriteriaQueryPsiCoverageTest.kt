package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * CriteriaQueryLocator 全量 PSI 测试（T-J4 / T-J6 / T-J7 / T-J17，矩阵 §4 逐行对照）。
 *
 * 与 [CriteriaQueryLocatorTest] 互补：共享一份完整 CriteriaBuilder/CriteriaQuery/
 * CriteriaUpdate/CriteriaDelete 内联桩（LightPlatform 无 JPA classpath），逐项验证：
 *  - T-J4：distinct 识别、notIn/getRestriction 死条目清理
 *  - T-J17：gt/ge/lt/le、isTrue/isFalse、conjunction/disjunction、in 链式收集 bug 修复、
 *    escapeChar、表达式函数（lower/cast/trim/CASE/coalesce/sum 双参）、List 重载解引用
 *  - T-J7：join 默认 INNER、joinCollection 家族、fetch、Join.on 显式 ON
 *  - T-J6：CriteriaUpdate / CriteriaDelete 的 set/from/where
 *  - J13：exists / in 子查询（correlate 降级）
 */
class CriteriaQueryPsiCoverageTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 从 query 方法定位 CriteriaQueryModel。 */
    private fun locateFromMethod(methodName: String = "query"): CriteriaQueryModel? {
        val method = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
            .firstOrNull { it.name == methodName } ?: return null
        return CriteriaQueryLocator.locate(method)
    }

    /** 组装测试文件：query 方法体 + 内联 Criteria 桩。 */
    private fun fixture(vararg bodyLines: String) {
        val body = bodyLines.joinToString("\n                ")
        myFixture.configureByText(
            "CriteriaDemo.java",
            """
            public class CriteriaDemo {
                public void query(EntityManager em) {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    $body
                }
            }

            $STUB
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------------
    // T-J4：distinct / 死条目清理
    // ------------------------------------------------------------------

    fun testTJ4_distinct识别() {
        fixture("cq.distinct(true);")
        val model = locateFromMethod()
        assertNotNull(model)
        assertTrue("distinct(true) 应置位", model!!.isDistinct())
    }

    fun testTJ4_notIn死条目不再识别() {
        // jakarta 3.1 无 cb.notIn（javap 证实）：名单清理后不再误报条件
        fixture("cq.where(cb.notIn(root.get(\"status\"), 0));")
        val model = locateFromMethod()
        assertNotNull("实体仍在，模型不应为 null", model)
        assertTrue("notIn 死条目不应产出条件", model!!.predicates.isEmpty())
    }

    // ------------------------------------------------------------------
    // T-J17：比较与布尔谓词
    // ------------------------------------------------------------------

    fun testTJ17_gt_ge_lt_le数字变体() {
        fixture(
            "cq.where(",
            "        cb.gt(root.get(\"status\"), 1),",
            "        cb.ge(root.get(\"status\"), 1),",
            "        cb.lt(root.get(\"status\"), 9),",
            "        cb.le(root.get(\"status\"), 9));",
        )
        val model = locateFromMethod()
        assertNotNull(model)
        val preds = model!!.predicates
        assertEquals("应解析 4 个条件", 4, preds.size)
        val ops = preds.map { (it as PredicateModel.Simple).op }
        assertEquals(listOf(">", ">=", "<", "<="), ops)
    }

    fun testTJ17_isTrue_isFalse() {
        fixture(
            "cq.where(cb.isTrue(root.get(\"active\")), cb.isFalse(root.get(\"active\")));",
        )
        val model = locateFromMethod()
        val preds = model!!.predicates
        assertEquals(2, preds.size)
        assertEquals("TRUE", (preds[0] as PredicateModel.Simple).valueExpression)
        assertEquals("FALSE", (preds[1] as PredicateModel.Simple).valueExpression)
    }

    fun testTJ17_conjunction_disjunction() {
        fixture("cq.where(cb.conjunction(), cb.disjunction());")
        val model = locateFromMethod()
        val preds = model!!.predicates
        assertEquals(2, preds.size)
        assertTrue(preds[0] is PredicateModel.BooleanConstant)
        assertTrue((preds[0] as PredicateModel.BooleanConstant).isValue())
        assertTrue((preds[1] as PredicateModel.BooleanConstant).isValue().not())
    }

    // ------------------------------------------------------------------
    // T-J17：IN 链式收集 bug 修复
    // ------------------------------------------------------------------

    fun testTJ17_in链式value多层全收集() {
        fixture("cq.where(cb.in(root.get(\"status\")).value(1).value(2));")
        val model = locateFromMethod()
        val preds = model!!.predicates
        assertEquals("只应有一条 IN 条件", 1, preds.size)
        val inPred = preds[0] as PredicateModel.In
        assertEquals(listOf("1", "2"), inPred.valueExpressions)
    }

    fun testTJ17_in链尾not_包裹NOT组() {
        fixture("cq.where(cb.in(root.get(\"status\")).value(1).not());")
        val model = locateFromMethod()
        val preds = model!!.predicates
        assertEquals("只应有一条 NOT 组", 1, preds.size)
        val group = preds[0] as PredicateModel.Group
        assertTrue("应取反", group.isNegated())
        assertEquals(1, group.children.size)
        assertTrue(group.children[0] is PredicateModel.In)
    }

    // ------------------------------------------------------------------
    // T-J17：LIKE escapeChar / 表达式函数
    // ------------------------------------------------------------------

    fun testTJ17_like带escapeChar() {
        fixture("cq.where(cb.like(root.get(\"username\"), \"a!%b\", '!'));")
        val model = locateFromMethod()
        val like = model!!.predicates[0] as PredicateModel.Like
        assertEquals("'!'", like.escapeChar)
        assertEquals("\"a!%b\"", like.patternExpression)
    }

    fun testTJ17_函数表达式左值() {
        fixture("cq.where(cb.equal(cb.lower(root.get(\"username\")), \"admin\"));")
        val model = locateFromMethod()
        val pred = model!!.predicates[0]
        assertTrue("应为表达式比较", pred is PredicateModel.ExpressionComparison)
        val cmp = pred as PredicateModel.ExpressionComparison
        val left = cmp.left as ExpressionModel.Function
        assertEquals("LOWER", left.function)
        assertEquals("username", (left.args[0] as ExpressionModel.Column).fieldName)
        assertEquals("=", cmp.op)
    }

    fun testTJ17_selectCase搜索式() {
        fixture(
            "cq.multiselect(root.get(\"status\"),",
            "        cb.selectCase()",
            "            .when(cb.equal(root.get(\"status\"), 1), \"active\")",
            "            .otherwise(\"inactive\"));",
        )
        val model = locateFromMethod()
        assertEquals(2, model!!.selectColumns.size)
        val case = model.selectColumns[1].expression
        assertTrue("第二列应为 CASE 表达式", case is ExpressionModel.Case)
        val c = case as ExpressionModel.Case
        assertNull("搜索式 CASE 无基准表达式", c.simpleExpr)
        assertEquals(1, c.whens.size)
        assertNotNull("WHEN 条件应为谓词", c.whens[0].predicate)
        assertNotNull(c.otherwise)
    }

    fun testTJ17_selectCase简单式() {
        fixture(
            "cq.multiselect(cb.selectCase(root.get(\"status\")).when(1, \"one\").otherwise(\"other\"));",
        )
        val model = locateFromMethod()
        val c = model!!.selectColumns[0].expression as ExpressionModel.Case
        assertEquals("status", (c.simpleExpr as ExpressionModel.Column).fieldName)
        assertEquals("1", (c.whens[0].condition as ExpressionModel.Value).expressionText)
    }

    fun testTJ17_coalesce链式value() {
        fixture(
            "cq.multiselect(cb.coalesce(root.get(\"nickname\")).value(root.get(\"username\")).value(\"anon\"));",
        )
        val model = locateFromMethod()
        val fn = model!!.selectColumns[0].expression as ExpressionModel.Function
        assertEquals("COALESCE", fn.function)
        assertEquals("链式 value 实参应全部收集", 3, fn.args.size)
    }

    fun testTJ17_count_root为COUNT星() {
        fixture("cq.multiselect(cb.count(root));")
        val model = locateFromMethod()
        val agg = model!!.selectColumns[0].aggregate
        assertNotNull(agg)
        assertEquals("COUNT", agg!!.function)
        assertNull("count(root) 应为 COUNT(*)", agg.fieldName)
    }

    fun testTJ17_sum双参为算术加法() {
        fixture("cq.multiselect(cb.sum(root.get(\"status\"), root.get(\"id\")));")
        val model = locateFromMethod()
        val expr = model!!.selectColumns[0].expression
        assertTrue("双参 sum 应为算术节点", expr is ExpressionModel.Function)
        assertEquals("ADD", (expr as ExpressionModel.Function).function)
        assertEquals(2, expr.args.size)
    }

    fun testTJ17_toLong为CAST() {
        fixture("cq.where(cb.gt(cb.toLong(root.get(\"status\")), 17));")
        val model = locateFromMethod()
        val cmp = model!!.predicates[0] as PredicateModel.ExpressionComparison
        val cast = cmp.left as ExpressionModel.Function
        assertEquals("CAST", cast.function)
        assertEquals("BIGINT", cast.modifier)
        assertEquals(">", cmp.op)
    }

    fun testTJ17_trim带Trimspec() {
        fixture("cq.where(cb.equal(cb.trim(Trimspec.LEADING, root.get(\"username\")), \"x\"));")
        val model = locateFromMethod()
        val cmp = model!!.predicates[0] as PredicateModel.ExpressionComparison
        val trim = cmp.left as ExpressionModel.Function
        assertEquals("TRIM", trim.function)
        assertEquals("LEADING", trim.modifier)
    }

    fun testTJ17_function原样拼接() {
        fixture(
            "cq.where(cb.equal(cb.function(\"DATE\", java.util.Date.class, root.get(\"username\")), \"2024\"));",
        )
        val model = locateFromMethod()
        val cmp = model!!.predicates[0] as PredicateModel.ExpressionComparison
        val fn = cmp.left as ExpressionModel.Function
        assertEquals("DATE", fn.function)
        assertEquals("返回类型实参应跳过", 1, fn.args.size)
    }

    fun testTJ17_literal与parameter实参展开() {
        fixture(
            "cq.where(cb.equal(root.get(\"username\"), cb.literal(\"admin\")),",
            "        cb.equal(root.get(\"status\"), cb.parameter(\"statusParam\")));",
        )
        val model = locateFromMethod()
        val preds = model!!.predicates
        assertEquals(2, preds.size)
        // literal("admin") → 引号文本，converter 求值为字面量
        assertEquals("\"admin\"", (preds[0] as PredicateModel.Simple).valueExpression)
        // parameter("statusParam") → 参数名文本，converter 求值为占位符
        assertEquals("statusParam", (preds[1] as PredicateModel.Simple).valueExpression)
    }

    fun testTJ17_metamodel字段引用() {
        fixture("cq.where(cb.equal(root.get(UserEntity_.username), \"admin\"));")
        val model = locateFromMethod()
        val simple = model!!.predicates[0] as PredicateModel.Simple
        assertEquals("username", simple.fieldName)
    }

    fun testTJ17_multiselect列表实参解引用() {
        fixture(
            "java.util.List cols = new java.util.ArrayList();",
            "cols.add(root.get(\"id\"));",
            "cols.add(root.get(\"username\"));",
            "cq.multiselect(cols);",
        )
        val model = locateFromMethod()
        val columns = model!!.selectColumns
        assertEquals("List 实参应展开为 2 列", 2, columns.size)
        assertEquals("id", columns[0].fieldName)
        assertEquals("username", columns[1].fieldName)
    }

    fun testTJ17_orderBy列表实参解引用() {
        fixture(
            "java.util.List orders = new java.util.ArrayList();",
            "orders.add(cb.desc(root.get(\"id\")));",
            "orders.add(cb.asc(root.get(\"username\")));",
            "cq.orderBy(orders);",
        )
        val model = locateFromMethod()
        assertEquals(2, model!!.orderBys.size)
        assertEquals("id", model.orderBys[0].fieldName)
        assertEquals(false, model.orderBys[0].isAsc())
        assertEquals("username", model.orderBys[1].fieldName)
        assertEquals(true, model.orderBys[1].isAsc())
    }

    fun testTJ17_groupBy列表实参解引用() {
        fixture(
            "java.util.List groups = new java.util.ArrayList();",
            "groups.add(root.get(\"status\"));",
            "cq.groupBy(groups);",
        )
        val model = locateFromMethod()
        assertEquals(1, model!!.groupBys.size)
        assertEquals("status", model.groupBys[0].fieldName)
    }

    fun testTJ17_集合谓词注释降级() {
        fixture("cq.where(cb.isEmpty(root.get(\"nickname\")));")
        val model = locateFromMethod()
        val raw = model!!.predicates[0] as PredicateModel.Raw
        assertTrue("应降级为注释占位: ${raw.expressionText}", raw.expressionText.contains("集合谓词"))
        assertTrue(raw.expressionText.contains("IS EMPTY"))
    }

    fun testTJ17_where直挂不重复提取() {
        fixture("cq.where(cb.equal(root.get(\"status\"), 1));")
        val model = locateFromMethod()
        assertEquals("where 直挂条件只应提取一次", 1, model!!.predicates.size)
    }

    fun testTJ17_链式not取反() {
        fixture("cq.where(cb.equal(root.get(\"status\"), 0).not());")
        val model = locateFromMethod()
        assertEquals(1, model!!.predicates.size)
        val group = model.predicates[0] as PredicateModel.Group
        assertTrue(group.isNegated())
        assertEquals(1, group.children.size)
    }

    // ------------------------------------------------------------------
    // T-J7：JOIN 深化
    // ------------------------------------------------------------------

    fun testTJ7_join无JoinType默认INNER() {
        fixture("Join dept = root.join(\"department\");")
        val model = locateFromMethod()
        assertEquals(1, model!!.joins.size)
        assertEquals(cn.song.mybatisplus.visualizer.model.JoinType.INNER, model.joins[0].joinType)
    }

    fun testTJ7_joinCollection家族识别() {
        fixture(
            "Join a = root.joinCollection(\"tags\");",
            "Join b = root.joinSet(\"tags\");",
            "Join c = root.joinList(\"tags\");",
            "Join d = root.joinMap(\"tags\");",
        )
        val model = locateFromMethod()
        assertEquals("四个集合 join 变体应全部识别", 4, model!!.joins.size)
        assertTrue(model.joins.all { it.joinType == cn.song.mybatisplus.visualizer.model.JoinType.INNER })
    }

    fun testTJ7_fetch识别() {
        fixture("root.fetch(\"department\", JoinType.LEFT);")
        val model = locateFromMethod()
        assertEquals(1, model!!.joins.size)
        val join = model.joins[0]
        assertTrue("fetch 应标记", join.isFetch())
        assertEquals(cn.song.mybatisplus.visualizer.model.JoinType.LEFT, join.joinType)
    }

    fun testTJ7_显式ON优先且不入WHERE() {
        fixture(
            "Join dept = root.join(\"department\", JoinType.LEFT);",
            "dept.on(cb.equal(dept.get(\"code\"), root.get(\"deptCode\")));",
            "cq.where(cb.equal(root.get(\"status\"), 1));",
        )
        val model = locateFromMethod()
        assertEquals(1, model!!.joins.size)
        assertEquals("显式 ON 应挂在 join 上", 1, model.joins[0].onPredicates.size)
        val on = model.joins[0].onPredicates[0] as PredicateModel.ColumnEquals
        assertEquals("code", on.leftFieldName)
        assertEquals("deptCode", on.rightFieldName)
        assertEquals("dept", on.leftVariable)
        assertEquals("root", on.rightVariable)
        assertEquals("ON 谓词不应漏进 WHERE", 1, model.predicates.size)
    }

    // ------------------------------------------------------------------
    // T-J6：CriteriaUpdate / CriteriaDelete
    // ------------------------------------------------------------------

    fun testTJ6_criteriaUpdate识别set与where() {
        fixture(
            "CriteriaUpdate<UserEntity> update = cb.createCriteriaUpdate(UserEntity.class);",
            "Root<UserEntity> uroot = update.from(UserEntity.class);",
            "update.set(uroot.get(\"status\"), 2);",
            "update.set(\"username\", \"admin\");",
            "update.where(cb.equal(uroot.get(\"id\"), 1L));",
        )
        val model = locateFromMethod()
        assertNotNull(model)
        assertEquals(CriteriaOperation.UPDATE, model!!.operation)
        assertEquals("UserEntity", model.entityClassName)
        assertEquals("两个 SET 子句", 2, model.setClauses.size)
        assertEquals("status", model.setClauses[0].fieldName)
        assertEquals("2", model.setClauses[0].valueExpression)
        assertEquals("username", model.setClauses[1].fieldName)
        assertEquals("\"admin\"", model.setClauses[1].valueExpression)
        assertEquals("WHERE 应有一条", 1, model.predicates.size)
    }

    fun testTJ6_criteriaDelete识别() {
        fixture(
            "CriteriaDelete<UserEntity> del = cb.createCriteriaDelete(UserEntity.class);",
            "Root<UserEntity> droot = del.from(UserEntity.class);",
            "del.where(cb.equal(droot.get(\"status\"), 0));",
        )
        val model = locateFromMethod()
        assertNotNull(model)
        assertEquals(CriteriaOperation.DELETE, model!!.operation)
        assertEquals(1, model.predicates.size)
        assertTrue(model.setClauses.isEmpty())
    }

    // ------------------------------------------------------------------
    // T-J17/J13：子查询
    // ------------------------------------------------------------------

    fun testTJ13_exists子查询识别() {
        fixture(
            "Subquery sq = cq.subquery(Long.class);",
            "Root ord = sq.from(OrderEntity.class);",
            "sq.select(ord.get(\"id\"));",
            "sq.where(cb.equal(ord.get(\"userId\"), 1));",
            "cq.where(cb.exists(sq));",
        )
        val model = locateFromMethod()
        val exists = model!!.predicates[0] as PredicateModel.Exists
        assertNull("单层子查询不应降级", exists.subQuery.degradedReason)
        assertEquals("id", exists.subQuery.selectFieldName)
        assertEquals("OrderEntity", exists.subQuery.entityClassName)
        assertEquals("子查询 WHERE 应有一条", 1, exists.subQuery.predicates.size)
    }

    fun testTJ13_pathIn子查询识别() {
        fixture(
            "Subquery sq = cq.subquery(Long.class);",
            "Root ord = sq.from(OrderEntity.class);",
            "sq.select(ord.get(\"userId\"));",
            "cq.where(root.get(\"id\").in(sq));",
        )
        val model = locateFromMethod()
        val inSub = model!!.predicates[0] as PredicateModel.InSubQuery
        assertEquals("id", inSub.fieldName)
        assertEquals("userId", inSub.subQuery.selectFieldName)
        assertNull(inSub.subQuery.degradedReason)
    }

    fun testTJ13_correlate降级() {
        fixture(
            "Subquery sq = cq.subquery(Long.class);",
            "Root ord = sq.from(OrderEntity.class);",
            "Join dept = root.join(\"department\", JoinType.INNER);",
            "sq.correlate(dept);",
            "sq.select(ord.get(\"id\"));",
            "cq.where(cb.exists(sq));",
        )
        val model = locateFromMethod()
        val exists = model!!.predicates[0] as PredicateModel.Exists
        assertNotNull("correlate 子查询应降级", exists.subQuery.degradedReason)
        assertTrue(exists.subQuery.degradedReason!!.contains("correlate"))
    }

    // ------------------------------------------------------------------
    // 端到端（locator → converter → SQL）
    // ------------------------------------------------------------------

    fun test端到端_lower比较生成SQL() {
        fixture("cq.where(cb.equal(cb.lower(root.get(\"username\")), \"admin\"));")
        val model = locateFromMethod()
        val stmt = CriteriaQueryConverter.convert(model!!)
        val sql = cn.song.mybatisplus.visualizer.sql.SqlGenerator.generate(stmt)
        assertTrue("SQL 应包含函数比较: $sql", sql.contains("LOWER(username) = 'admin'"))
    }

    companion object {
        /** 内联 Criteria 桩：LightPlatform 无 JPA classpath，全部本地声明。 */
        private val STUB = """
            class EntityManager { public CriteriaBuilder getCriteriaBuilder() { return null; } }
            class CriteriaBuilder {
                public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                public <T> CriteriaUpdate<T> createCriteriaUpdate(Class<T> cls) { return null; }
                public <T> CriteriaDelete<T> createCriteriaDelete(Class<T> cls) { return null; }
                public Predicate equal(Expression a, Object b) { return null; }
                public Predicate notEqual(Expression a, Object b) { return null; }
                public Predicate greaterThan(Expression a, Number b) { return null; }
                public Predicate greaterThanOrEqualTo(Expression a, Number b) { return null; }
                public Predicate lessThan(Expression a, Number b) { return null; }
                public Predicate lessThanOrEqualTo(Expression a, Number b) { return null; }
                public Predicate gt(Expression a, Number b) { return null; }
                public Predicate ge(Expression a, Number b) { return null; }
                public Predicate lt(Expression a, Number b) { return null; }
                public Predicate le(Expression a, Number b) { return null; }
                public Predicate between(Expression a, Object lo, Object hi) { return null; }
                public Predicate isNull(Expression a) { return null; }
                public Predicate isNotNull(Expression a) { return null; }
                public Predicate isTrue(Expression a) { return null; }
                public Predicate isFalse(Expression a) { return null; }
                public Predicate like(Expression a, String pattern) { return null; }
                public Predicate like(Expression a, String pattern, char escape) { return null; }
                public Predicate notLike(Expression a, String pattern) { return null; }
                public In in(Expression a) { return null; }
                public Predicate conjunction() { return null; }
                public Predicate disjunction() { return null; }
                public Predicate and(Predicate... p) { return null; }
                public Predicate or(Predicate... p) { return null; }
                public Predicate not(Expression a) { return null; }
                public Predicate exists(Subquery sq) { return null; }
                public Predicate isEmpty(Expression c) { return null; }
                public Predicate isNotEmpty(Expression c) { return null; }
                public Predicate isMember(Expression e, Expression c) { return null; }
                public Predicate isNotMember(Expression e, Expression c) { return null; }
                public Expression lower(Expression x) { return null; }
                public Expression upper(Expression x) { return null; }
                public Expression concat(Expression a, Expression b) { return null; }
                public Expression substring(Expression x, int from, int len) { return null; }
                public Expression length(Expression x) { return null; }
                public Expression locate(Expression x, String pattern) { return null; }
                public Expression trim(Expression x) { return null; }
                public Expression trim(Trimspec spec, Expression x) { return null; }
                public Expression abs(Expression x) { return null; }
                public Expression ceiling(Expression x) { return null; }
                public Expression floor(Expression x) { return null; }
                public Expression round(Expression x, int n) { return null; }
                public Expression exp(Expression x) { return null; }
                public Expression ln(Expression x) { return null; }
                public Expression power(Expression a, Expression b) { return null; }
                public Expression sqrt(Expression x) { return null; }
                public Expression sign(Expression x) { return null; }
                public Expression prod(Expression a, Expression b) { return null; }
                public Expression diff(Expression a, Expression b) { return null; }
                public Expression quot(Expression a, Expression b) { return null; }
                public Expression mod(Expression a, Expression b) { return null; }
                public Expression neg(Expression x) { return null; }
                public Expression toLong(Expression x) { return null; }
                public Expression toInteger(Expression x) { return null; }
                public Expression toBigDecimal(Expression x) { return null; }
                public Expression toString(Expression x) { return null; }
                public Expression coalesce(Expression a, Expression b) { return null; }
                public Expression nullif(Expression a, Expression b) { return null; }
                public Expression literal(Object v) { return null; }
                public Expression nullLiteral() { return null; }
                public Expression parameter(String name) { return null; }
                public Expression function(String name, Class type, Expression... args) { return null; }
                public Expression count(Expression x) { return null; }
                public Expression countDistinct(Expression x) { return null; }
                public Expression sum(Expression x) { return null; }
                public Expression sum(Expression a, Expression b) { return null; }
                public Expression avg(Expression x) { return null; }
                public Expression max(Expression x) { return null; }
                public Expression min(Expression x) { return null; }
                public Expression sumAsLong(Expression x) { return null; }
                public Expression sumAsDouble(Expression x) { return null; }
                public Expression greatest(Expression x) { return null; }
                public Expression least(Expression x) { return null; }
                public Case selectCase() { return null; }
                public SimpleCase selectCase(Expression expr) { return null; }
                public Order asc(Expression e) { return null; }
                public Order desc(Expression e) { return null; }
            }
            class CriteriaQuery<T> {
                public Root<T> from(Class<T> cls) { return null; }
                public CriteriaQuery<T> select(Selection s) { return null; }
                public CriteriaQuery<T> multiselect(Selection... s) { return null; }
                public CriteriaQuery<T> where(Predicate... p) { return null; }
                public CriteriaQuery<T> groupBy(Expression... e) { return null; }
                public CriteriaQuery<T> having(Predicate... p) { return null; }
                public CriteriaQuery<T> orderBy(Order... o) { return null; }
                public CriteriaQuery<T> distinct(boolean b) { return null; }
                public Subquery subquery(Class type) { return null; }
            }
            class Subquery {
                public Root from(Class cls) { return null; }
                public Subquery select(Expression e) { return null; }
                public Subquery where(Predicate... p) { return null; }
                public Subquery correlate(Join j) { return null; }
            }
            class Root<T> {
                public Path get(String field) { return null; }
                public Join join(String field) { return null; }
                public Join join(String field, JoinType t) { return null; }
                public Join joinCollection(String field) { return null; }
                public Join joinSet(String field) { return null; }
                public Join joinList(String field) { return null; }
                public Join joinMap(String field) { return null; }
                public Root fetch(String field) { return null; }
                public Root fetch(String field, JoinType t) { return null; }
            }
            class Join {
                public Path get(String field) { return null; }
                public Join on(Predicate... p) { return null; }
            }
            class In {
                public In value(Object v) { return null; }
                public Predicate not() { return null; }
            }
            class Case {
                public Case when(Predicate c, Object r) { return null; }
                public Case otherwise(Object r) { return null; }
            }
            class SimpleCase {
                public SimpleCase when(Object v, Object r) { return null; }
                public SimpleCase otherwise(Object r) { return null; }
            }
            class CriteriaUpdate<T> {
                public Root<T> from(Class<T> cls) { return null; }
                public CriteriaUpdate<T> set(Path p, Object v) { return null; }
                public CriteriaUpdate<T> set(String attr, Object v) { return null; }
                public CriteriaUpdate<T> where(Predicate... p) { return null; }
            }
            class CriteriaDelete<T> {
                public Root<T> from(Class<T> cls) { return null; }
                public CriteriaDelete<T> where(Predicate... p) { return null; }
            }
            class Predicate {}
            class Expression {}
            class Selection {}
            class Order {}
            class Path {}
            enum JoinType { LEFT, INNER, RIGHT }
            enum Trimspec { LEADING, TRAILING, BOTH }
            class UserEntity {
                private Long id; private String username; private Integer status;
                private Boolean active; private String nickname;
            }
            class OrderEntity { private Long id; private Long userId; private Integer status; }
            class UserEntity_ { public static String username; public static String status; }
        """.trimIndent()
    }
}
