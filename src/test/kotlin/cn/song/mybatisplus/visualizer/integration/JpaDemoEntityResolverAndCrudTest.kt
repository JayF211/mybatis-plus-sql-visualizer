package cn.song.mybatisplus.visualizer.integration

import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.DerivedQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.Operation
import cn.song.mybatisplus.visualizer.converter.jpa.PartTreeParser
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.psi.BatchOperationConverter
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * JPA 实体解析（TableInfo）与 TJ1/TJ2/TJ5/TJ6 CRUD 模板用例（自 JpaDemoIntegrationTest 拆出）。
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class JpaDemoEntityResolverAndCrudTest: BasePlatformTestCase() {

    // ==================================================================
    // JpaEntityResolver 集成验证（实体解析 → 表信息）
    // ==================================================================

    fun testJpaEntityResolver解析UserEntity生成正确TableInfo() {
        JpaEntityResolver.clearCache()
        myFixture.configureByText(
            "UserEntity.java",
            """
            @jakarta.persistence.Entity
            @jakarta.persistence.Table(name = "user")
            public class UserEntity {
                @jakarta.persistence.Id
                @jakarta.persistence.Column(name = "id")
                private Long id;

                @jakarta.persistence.Column(name = "username")
                private String username;

                @jakarta.persistence.Column(name = "email")
                private String email;

                @jakarta.persistence.Column(name = "first_name")
                private String firstName;

                @jakarta.persistence.Column(name = "last_name")
                private String lastName;

                @jakarta.persistence.Column(name = "age")
                private Integer age;

                @jakarta.persistence.Column(name = "status")
                private Integer status;

                @jakarta.persistence.Column(name = "department_id")
                private Long departmentId;

                @jakarta.persistence.Column(name = "create_time")
                private java.time.LocalDateTime createTime;
            }
            """.trimIndent(),
        )
        val clazz = findClass(myFixture.file, "UserEntity")
        val table = JpaEntityResolver().resolve(clazz)
        assertEquals("user", table.tableName)
        assertEquals("UserEntity", table.entityClassName)
        assertEquals(9, table.columns.size)
        assertEquals("id", table.columnByField("id")?.columnName)
        assertEquals("username", table.columnByField("username")?.columnName)
        assertEquals("first_name", table.columnByField("firstName")?.columnName)
        assertEquals("department_id", table.columnByField("departmentId")?.columnName)
    }

    // ==================================================================
    // T-J1：JPA CRUD 写路径（demo UserWriteRepository / UserWriteServiceImpl 语料）
    // ==================================================================

    fun testTJ1Save单实体生成INSERT模板() {
        val impl = """
            @com.baomidou.mybatisplus.annotation.TableName("user")
            public class UserEntity {
                private Long id;
                private String username;
                private String email;
                private String firstName;
                private String lastName;
                private Integer age;
                private Integer status;
                private Long departmentId;
                private java.time.LocalDateTime createTime;
            }

            public class UserWriteService {
                private UserRepository userRepository;

                public void saveUser(UserEntity user) {
                    user.setUsername("song");
                    userRepository.save(user);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val saveCall = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "save" }

        // 当前真实能力：JPA save 走 BatchOperationConverter 的 INSERT 模板（M19.5 save 注册）。
        // 表名解析走 MP EntityResolver：@TableName 显式表名优先（JPA @Table 接线为后续里程碑）。
        val preview = BatchOperationConverter.convert(
            saveCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertNotNull("save 应识别为 INSERT 模板", preview)
        assertEquals("user", preview!!.tableName)
        assertTrue("SQL 应以 INSERT INTO 开头: ${preview.sql}", preview.sql.trimStart().startsWith("INSERT INTO"))
        // 宽松断言防脆：VALUES ? 个数 ≥ 列数（9 字段）的一半，主键列是否勾选不影响
        val placeholders = preview.sql.count { it == '?' }
        assertTrue("INSERT 占位符应≥4（9 列的一半）: ${preview.sql}", placeholders >= 4)
    }

    /**
     * O-52 订正（独立复核 major⑭）：**PSI 批量链**的 INSERT 也必须带自增列标记。
     *
     * 缺陷：`BatchOperationConverter.batchColumns` 此前不设 `BatchColumn.autoGenerated`（全仓只有
     * `CrudSqlGenerator` 一条链接线了），于是用户在预览面板**勾回自增列**后，`BatchModel.renderSql`
     * 的 INSERT 分支判 `active.none { it.autoGenerated }` 为真 → SQL Server 上产出**无包装**的 INSERT、
     * 依旧报 544。本链是 MP `saveBatch`/`insertBatch` 等批量操作的实际渲染点，现有测试全绿看不出
     * （CRUD / 回滚 / 面板用例都不走这条链）。
     *
     * 本用例钉两件事：① 实体主键声明 `@TableId(type = IdType.AUTO)` 时本链产出的模型必须带该标记；
     * ② 按 SQL Server 方言渲染时必须出现 `SET IDENTITY_INSERT … ON/OFF` 成对包装（用户可见行为）。
     */
    fun testO52订正_PSI批量链INSERT带自增列标记与SQLServer包装() {
        val impl = """
            @com.baomidou.mybatisplus.annotation.TableName("user")
            public class UserEntity {
                @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
                private Long id;
                private String username;
            }

            public class UserWriteService {
                private UserRepository userRepository;

                public void saveUser(UserEntity user) {
                    userRepository.save(user);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val saveCall = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "save" }

        val preview = BatchOperationConverter.convert(
            saveCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertNotNull("save 应识别为 INSERT 模板", preview)
        val model = preview!!.batchModel
        assertNotNull("批量预览应带 BatchModel（本用例的断言载体）", model)
        val idColumn = model!!.toggleColumns.firstOrNull { it.columnName == "id" }
        assertNotNull("自增主键列应在勾选列清单内: ${model.toggleColumns.map { it.columnName }}", idColumn)
        assertTrue(
            "自增主键列必须带 autoGenerated 标记——缺它则用户在面板勾回自增列后，SQL Server 上仍产出无包装 INSERT",
            idColumn!!.autoGenerated,
        )
        val rendered = model.copy(dialect = cn.song.mybatisplus.visualizer.sql.SqlServerDialect)
            .renderSql(setOf("id", "username"))
        assertTrue(
            "SQL Server 方言下含自增列的 INSERT 必须成对包装 SET IDENTITY_INSERT: $rendered",
            rendered.contains("SET IDENTITY_INSERT") && rendered.contains(" ON;") && rendered.contains(" OFF;"),
        )
    }

    fun testTJ1FindById主键等值SELECT识别并转换() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                java.util.Optional<UserEntity> findById(Long id);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "findById")
        assertTrue("findById 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(method))

        // findById → Subject find + 谓词 Id（SIMPLE_PROPERTY 兜底）→ SELECT + WHERE id = ?
        val partTree = PartTreeParser.parse("findById")
        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 SELECT: $sql", sql.startsWith("SELECT"))
        assertTrue("SQL 应含主键等值条件: $sql", sql.contains("id = ?"))
    }

    fun testTJ1ExistsById存在性SELECT识别并转换() {
        // existsById → EXISTS 语义（SELECT 1 ... WHERE id = ?），主键等值条件是核心验证点
        val partTree = PartTreeParser.parse("existsById")
        assertTrue("existsById 应解析为 EXISTS", partTree.subject.operation == Operation.EXISTS)

        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 SELECT 形态: $sql", sql.startsWith("SELECT"))
        assertTrue("SQL 应含主键等值条件: $sql", sql.contains("id = ?"))
    }

    fun testTJ1DeleteById主键删除DELETE识别并转换() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                void deleteById(Long id);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "deleteById")
        assertTrue("deleteById 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(method))

        val partTree = PartTreeParser.parse("deleteById")
        assertTrue("deleteById 应解析为 DELETE", partTree.subject.operation == Operation.DELETE)

        val stmt = DerivedQueryConverter.convert(partTree, userTable) as DeleteStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 DELETE: $sql", sql.startsWith("DELETE"))
        assertTrue("SQL 应含主键等值条件: $sql", sql.contains("id = ?"))
    }

    fun testTJ1DeleteAllInBatch全表DELETE无WHERE() {
        // T-J1：deleteAllInBatch 为 JpaRepository 自带方法——无 By 且非 *All 词干，不在
        // T-J3 无 By 放行清单内，DerivedMethodLocator 不识别；转换层经 PartTree 宽松解析
        // （Subject delete + 空谓词）仍产出全表 DELETE、无 WHERE（UI 层红色警示语义）。
        val partTree = PartTreeParser.parse("deleteAllInBatch")
        assertTrue("deleteAllInBatch 应解析为 DELETE", partTree.subject.operation == Operation.DELETE)
        assertTrue("deleteAllInBatch 无谓词", partTree.predicates.isEmpty())

        val stmt = DerivedQueryConverter.convert(partTree, userTable) as DeleteStatement
        assertTrue("全表删除应无条件", stmt.conditions.isEmpty())
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 DELETE FROM: $sql", sql.startsWith("DELETE FROM"))
        assertTrue("全表删除不应含 WHERE: $sql", !sql.contains("WHERE"))
    }

    fun testTJ1FindAll与Count无By形态识别并转换() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findAll();

                long count();
            }
            """.trimIndent(),
        )
        // T-J3：findAll / 裸 count 为放行的无 By 形态（宿主须为 Repository 接口，
        // 接口名 Repository 后缀启发在 LightPlatform 下生效）
        val findAll = findMethod(myFixture.file, "findAll")
        assertTrue("findAll 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(findAll))
        val count = findMethod(myFixture.file, "count")
        assertTrue("count 应识别为派生查询", DerivedMethodLocator.isDerivedQuery(count))

        val findAllSql = SqlGenerator.generate(DerivedQueryConverter.convert(PartTreeParser.parse("findAll"), userTable))
        assertTrue("SQL 应为全量 SELECT: $findAllSql", findAllSql.startsWith("SELECT"))
        assertTrue("findAll 无谓词不应含 WHERE: $findAllSql", !findAllSql.contains("WHERE"))

        val countSql = SqlGenerator.generate(DerivedQueryConverter.convert(PartTreeParser.parse("count"), userTable))
        assertTrue("SQL 应含 COUNT(*): $countSql", countSql.contains("COUNT(*)"))
    }

    fun testTJ1FindAllByOrderByCreateTimeDesc仅排序无谓词识别并转换() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                java.util.List<UserEntity> findAllByOrderByCreateTimeDesc();
            }
            """.trimIndent(),
        )
        // 无 By/Order 混合形态：含 By（findAll**By**OrderBy...），Subject findAll + 仅 OrderBy、无谓词
        val method = findMethod(myFixture.file, "findAllByOrderByCreateTimeDesc")
        assertTrue("应识别为派生查询", DerivedMethodLocator.isDerivedQuery(method))

        val partTree = PartTreeParser.parse("findAllByOrderByCreateTimeDesc")
        assertTrue("无谓词（仅排序）", partTree.predicates.isEmpty())

        val stmt = DerivedQueryConverter.convert(partTree, userTable) as SelectStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应含 ORDER BY: $sql", sql.contains("ORDER BY"))
        assertTrue("SQL 应含 create_time DESC: $sql", sql.contains("create_time DESC"))
    }

    // ==================================================================
    // T-J2：@Modifying UPDATE JPQL（demo UserRepository#updateStatusByJpql 形态）
    // ==================================================================

    fun testTJ2ModifyingUpdateJpql定位并生成UpdateStatement() {
        myFixture.configureByText(
            "UserWriteRepository.java",
            """
            public interface UserWriteRepository extends JpaRepository<UserEntity, Long> {
                @org.springframework.transaction.annotation.Transactional
                @org.springframework.data.jpa.repository.Modifying
                @org.springframework.data.jpa.repository.Query("UPDATE UserEntity u SET u.status = :newStatus WHERE u.status = :oldStatus")
                int updateStatusByJpql(Integer oldStatus, Integer newStatus);
            }
            """.trimIndent(),
        )
        val method = findMethod(myFixture.file, "updateStatusByJpql")
        assertTrue("@Modifying @Query 方法应识别为 JPQL", JpqlQueryLocator.hasQueryAnnotation(method))

        val info = JpqlQueryLocator.locate(myFixture.file, method.textOffset + 10)
        assertNotNull("UPDATE JPQL 应被定位", info)
        assertTrue("JPQL 应为 UPDATE: ${info!!.jpql}", info.jpql.startsWith("UPDATE"))

        // J2：UPDATE JPQL → JpaUpdateParser → UpdateStatement（SET/WHERE 均被结构化解析）
        val result = JpqlConverter.convert(info.jpql, userTable)
        val stmt = result.statement
        assertTrue("应生成 UpdateStatement（T-J2）", stmt is UpdateStatement)
        assertTrue("SQL 应以 UPDATE 开头: ${result.sql}", result.sql.startsWith("UPDATE"))
        assertTrue("SQL 应含 SET: ${result.sql}", result.sql.contains("SET"))
        assertTrue("SQL 应含表名 user: ${result.sql}", result.sql.contains("user"))
        assertTrue("SET 赋值应被解析", (stmt as UpdateStatement).sets.isNotEmpty())
    }

    // ==================================================================
    // T-J5 / T-J20：Specification / Query By Example（入口未接线 → 降级占位断言）
    // ==================================================================

    fun testTJ5J20SpecificationLambda入口当前降级不抛异常() {
        // 仿照 demo JpaQueryServiceImpl#specificationQuery 的 lambda 谓词拼装
        val impl = """
            public class UserWriteService {
                private UserRepository userRepository;

                public java.util.List<UserEntity> specificationQuery(String username, Integer minAge, Integer status, Boolean emailNull) {
                    org.springframework.data.jpa.domain.Specification<UserEntity> spec =
                            (root, query, cb) -> {
                                java.util.List<javax.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
                                if (username != null && !username.isEmpty()) {
                                    predicates.add(cb.like(root.get("username"), "%" + username + "%"));
                                }
                                if (minAge != null) {
                                    predicates.add(cb.greaterThanOrEqualTo(root.get("age"), minAge));
                                }
                                if (status != null) {
                                    predicates.add(cb.equal(root.get("status"), status));
                                }
                                if (Boolean.TRUE.equals(emailNull)) {
                                    predicates.add(cb.isNull(root.get("email")));
                                }
                                return cb.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
                            };
                    return userRepository.findAll(spec);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val findAllCall = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "findAll" }

        // @Todo T-J5/T-J20：Specification → WHERE 的派发链路为后续里程碑接线。
        // 当前真实能力：无 Specification 定位器命中（CriteriaQueryLocator 只认 Criteria 调用链），
        // 降级为「不识别且不抛异常」。接线后本用例应升级为宽松 contains 的 WHERE 条件断言
        //（username LIKE / age >= / status = / email IS NULL）。
        val model = CriteriaQueryLocator.locate(findAllCall)
        assertTrue("Specification lambda 当前应降级为无定位命中（不抛异常）", model == null)
    }

    fun testTJ20ExampleOf探针查询当前降级不抛异常() {
        val impl = """
            public class UserWriteService {
                private UserRepository userRepository;

                public UserEntity exampleQuery(UserEntity probe) {
                    org.springframework.data.domain.Example<UserEntity> example =
                            org.springframework.data.domain.Example.of(probe);
                    return userRepository.findOne(example).orElse(null);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val findOneCall = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "findOne" }

        // @Todo T-J20：Query By Example（Example.of → 非空属性等值 WHERE）为后续里程碑接线。
        // 当前真实能力：定位器不命中、批量转换器不识别 findOne——双入口均返回 null 且不抛异常
        //（降级占位）。接线后本用例应升级为等值 WHERE 断言。
        val model = CriteriaQueryLocator.locate(findOneCall)
        assertTrue("Example.of 当前应无 Criteria 定位命中", model == null)
        val batch = BatchOperationConverter.convert(
            findOneCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertTrue("findOne 当前不应被批量转换器误识别", batch == null)
    }

    // ==================================================================
    // T-J6：EntityManager 写 API 与 CriteriaUpdate / CriteriaDelete
    // ==================================================================

    fun testTJ6Persist与Find当前降级不抛异常() {
        val impl = """
            public class UserWriteService {
                private javax.persistence.EntityManager entityManager;

                public void persistUser(UserEntity user) {
                    entityManager.persist(user);
                }

                public UserEntity findUser(Long id) {
                    return entityManager.find(UserEntity.class, id);
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
        val persistCall = calls.first { it.methodExpression.referenceName == "persist" }
        val findCall = calls.first { it.methodExpression.referenceName == "find" }

        // @Todo T-J6：em.persist → INSERT 模板、em.find → SELECT 主键等值为写路径接线点。
        // 当前真实能力：BatchOperationConverter 未注册 persist/find，返回 null（降级占位、不抛
        // 异常）。接线后本用例应升级为 INSERT INTO / WHERE id = ? 断言。
        val persistPreview = BatchOperationConverter.convert(
            persistCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertTrue("persist 当前应无 INSERT 模板能力（降级占位）", persistPreview == null)

        val findPreview = BatchOperationConverter.convert(
            findCall,
            injectLogicDelete = false,
            resolveVariables = false,
            injectTenantLine = false,
        )
        assertTrue("find 当前应无 SELECT 模板能力（降级占位）", findPreview == null)

        // CriteriaQueryLocator 亦不应误命中（无 Criteria 调用链）
        assertTrue("find 不应误命中 Criteria 定位器", CriteriaQueryLocator.locate(findCall) == null)
    }

    fun testTJ6CriteriaUpdate单列SET主键WHERE生成UPDATE文本() {
        val impl = """
            public class UserWriteService {
                @javax.persistence.PersistenceContext
                private javax.persistence.EntityManager entityManager;

                public int criteriaUpdateStatus(Long id, Integer newStatus) {
                    javax.persistence.criteria.CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                    javax.persistence.criteria.CriteriaUpdate<UserEntity> update = cb.createCriteriaUpdate(UserEntity.class);
                    javax.persistence.criteria.Root<UserEntity> root = update.from(UserEntity.class);

                    update.set(root.get("status"), newStatus);
                    update.where(cb.equal(root.get("id"), id));
                    return entityManager.createQuery(update).executeUpdate();
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createCriteriaUpdate" }

        val model = CriteriaQueryLocator.locate(anchor)
        assertNotNull("criteriaUpdateStatus 应识别为 CriteriaUpdate", model)
        assertEquals("操作类型应为 UPDATE", CriteriaOperation.UPDATE, model!!.operation)
        assertTrue("SET 子句应被提取", model.setClauses.isNotEmpty())
        assertEquals("status", model.setClauses[0].fieldName)

        val stmt = CriteriaQueryConverter.convert(model, userTable) as UpdateStatement
        assertTrue("SET 赋值应转换", stmt.sets.isNotEmpty())
        assertTrue("WHERE 条件应转换", stmt.conditions.isNotEmpty())
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应以 UPDATE 开头: $sql", sql.startsWith("UPDATE"))
        assertTrue("SQL 应含 SET: $sql", sql.contains("SET"))
        assertTrue("SQL 应含 status 列: $sql", sql.contains("status"))
    }

    fun testTJ6CriteriaDelete条件删除生成DELETE文本() {
        val impl = """
            public class UserWriteService {
                @javax.persistence.PersistenceContext
                private javax.persistence.EntityManager entityManager;

                public int criteriaDeleteByStatus(Integer status) {
                    javax.persistence.criteria.CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                    javax.persistence.criteria.CriteriaDelete<UserEntity> delete = cb.createCriteriaDelete(UserEntity.class);
                    javax.persistence.criteria.Root<UserEntity> root = delete.from(UserEntity.class);

                    delete.where(cb.equal(root.get("status"), status));
                    return entityManager.createQuery(delete).executeUpdate();
                }
            }
        """.trimIndent()

        myFixture.configureByText("UserWriteService.java", impl)
        val anchor = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "createCriteriaDelete" }

        val model = CriteriaQueryLocator.locate(anchor)
        assertNotNull("criteriaDeleteByStatus 应识别为 CriteriaDelete", model)
        assertEquals("操作类型应为 DELETE", CriteriaOperation.DELETE, model!!.operation)

        val stmt = CriteriaQueryConverter.convert(model, userTable) as DeleteStatement
        val sql = SqlGenerator.generate(stmt)
        assertTrue("SQL 应为 DELETE FROM: $sql", sql.startsWith("DELETE FROM"))
        assertTrue("SQL 应含 status 条件: $sql", sql.contains("status"))
    }

    // ------------------------------------------------------------------
    // 表结构 fixture（对齐 demo 项目 @Table / @Column 映射）
    // ------------------------------------------------------------------

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
            ColumnInfo("email", "email"),
            ColumnInfo("firstName", "first_name"),
            ColumnInfo("lastName", "last_name"),
            ColumnInfo("age", "age"),
            ColumnInfo("status", "status"),
            ColumnInfo("departmentId", "department_id"),
            ColumnInfo("createTime", "create_time"),
        ),
    )

    private val orderTable = TableInfo(
        entityClassName = "OrderEntity",
        tableName = "t_order",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("orderNo", "order_no"),
            ColumnInfo("customerId", "customer_id"),
            ColumnInfo("totalAmount", "total_amount"),
            ColumnInfo("createTime", "create_time"),
        ),
    )

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private fun findMethod(file: com.intellij.psi.PsiFile, methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(file, PsiMethod::class.java)
        return methods.firstOrNull { it.name == methodName }
            ?: error("文件内未找到方法: $methodName")
    }

    private fun findClass(file: com.intellij.psi.PsiFile, className: String): PsiClass {
        val classes = PsiTreeUtil.collectElementsOfType(file, PsiClass::class.java)
        return classes.firstOrNull { it.name == className }
            ?: error("文件内未找到类: $className")
    }
}
