package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class SqlPreviewServiceTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    /** T-TenantLine：每个测试结束后重置多租户配置为默认，防止状态泄漏。 */
    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.tenantColumnName = PluginSettings.DEFAULT_TENANT_COLUMN_NAME
        super.tearDown()
    }

    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        // 空调用链场景（如无条件的 LambdaDeleteWrapper）：锚定变量声明本身
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    fun testPreviewChainStyle() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .isNotNull(DeviceInfoEntity::getStationId);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long shareSchemeId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)

        // 多行 SQL 主展示
        assertEquals(
            """
            SELECT id, share_scheme_id
            FROM device_info_entity
            WHERE id = 1001
                AND station_id IS NOT NULL
            """.trimIndent(),
            result!!.sql,
        )
        // 单行 SQL
        assertEquals(
            "SELECT id, share_scheme_id FROM device_info_entity WHERE id = 1001 AND station_id IS NOT NULL",
            result.compactSql,
        )
        // 元信息
        assertEquals("device_info_entity", result.tableName)
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY, result.wrapperType)
        // 无未解析参数（均为字面量/命名策略）
        assertEquals(emptyList<String>(), result.unresolvedParams)
        assertNull(result.paramTable)
    }

    fun testQueryWrapperConsumedByRemoveRendersDelete() {
        // MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造，service.remove(wrapper) 消费 → 应转 DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void remove() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.remove(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertTrue("删除消费的查询 wrapper 应转 DELETE", result!!.sql.startsWith("DELETE FROM"))
        assertEquals("DELETE FROM device_info_entity WHERE id = 1001", result.compactSql.trim())
    }

    fun testQueryWrapperConsumedByListStaysQuery() {
        // 查询消费（service.list(w)）→ 保持 SELECT
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void list() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.list(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertTrue("查询消费应保持 SELECT", result!!.sql.startsWith("SELECT"))
    }

    fun testBaseTypeWrapperVariableRendersQuery() {
        // 基类型声明 `Wrapper<X> w = new LambdaQueryWrapper<X>()...`：定位 + 转换应为 SELECT
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.Wrapper;
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    Wrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("基类型声明的 wrapper 应能定位并转换", result)
        assertTrue("基类型声明应转 SELECT", result!!.sql.startsWith("SELECT"))
        assertEquals("SELECT id FROM device_info_entity WHERE id = 1001", result.compactSql.trim())
    }

    fun testInlineRemoveNewWrapperRendersDelete() {
        // 内联删除：service.remove(new LambdaQueryWrapper<X>().eq(...)) → 应转 DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void remove() {
                    service.remove(new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L));
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联删除应产出预览结果", result)
        assertTrue("内联删除应转 DELETE", result!!.sql.startsWith("DELETE FROM"))
        assertEquals("DELETE FROM device_info_entity WHERE id = 1001", result.compactSql.trim())
    }

    fun testMethodParameterWrapperRemoveRendersDelete() {
        // 方法参数 wrapper 被 remove 消费：`void del(LambdaQueryWrapper<X> wrapper) { ...; service.remove(wrapper); }` → DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void del(LambdaQueryWrapper<DeviceInfoEntity> wrapper) {
                    wrapper.eq(DeviceInfoEntity::getId, 1001L);
                    service.remove(wrapper);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("方法参数 wrapper 应能定位并转换", result)
        assertTrue("方法参数 wrapper + remove 应转 DELETE", result!!.sql.startsWith("DELETE FROM"))
        assertEquals("DELETE FROM device_info_entity WHERE id = 1001", result.compactSql.trim())
    }

    fun testBatchOperationPreview() {
        // 批量操作调用锚点点击 → 批量 SQL 模板（UPDATE）
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;

            public class Demo {
                public void update(List<DeviceInfoEntity> list) {
                    service.updateBatchById(list);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("批量操作应产出预览结果", result)
        assertTrue("批量更新应转 UPDATE 模板", result!!.sql.startsWith("UPDATE"))
    }

    // ---------------------------------------------------------------
    // T-Mxx：隐式 this 与链式调用修复
    // ---------------------------------------------------------------

    fun testImplicitThisGetById() {
        // 在 ServiceImpl 内部 this.getById(id) —— 显式 this，qualifierExpression 为 PsiThisExpression
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                static class User {
                    private Long id;
                    private String name;
                }
                static class UserServiceImpl extends BaseService<User> {
                    public User getOne(Long id) {
                        return this.getById(id);
                    }
                }
                static class BaseService<T> {
                    public T getById(Long id) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("this.getById(id) 应产出预览结果", result)
        assertTrue("显式 this.getById 应转 SELECT", result!!.sql.startsWith("SELECT"))
        assertTrue("应包含 WHERE id = ?", result.sql.contains("WHERE id = ?"))
    }

    fun testNoThisGetById() {
        // 在 ServiceImpl 内部直接 getById(id) —— 隐式 this，qualifierExpression 为 null
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                static class User {
                    private Long id;
                    private String name;
                }
                static class UserServiceImpl extends BaseService<User> {
                    public User getOne(Long id) {
                        return getById(id);
                    }
                }
                static class BaseService<T> {
                    public T getById(Long id) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getById(id)（无 this）应产出预览结果", result)
        assertTrue("隐式 this.getById 应转 SELECT", result!!.sql.startsWith("SELECT"))
        assertTrue("应包含 WHERE id = ?", result.sql.contains("WHERE id = ?"))
    }

    fun testGetBaseMapperSelectById() {
        // getBaseMapper().selectById(id) —— qualifier 是方法调用，返回 BaseMapper<User>
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                static class User {
                    private Long id;
                    private String name;
                }
                static class BaseMapper<T> {
                    public T selectById(Long id) { return null; }
                }
                static class UserMapper extends BaseMapper<User> {}
                static class UserServiceImpl {
                    public UserMapper getBaseMapper() { return null; }
                    public User findOne(Long id) {
                        return getBaseMapper().selectById(id);
                    }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getBaseMapper().selectById(id) 应产出预览结果", result)
        assertTrue("selectById 应转 SELECT", result!!.sql.startsWith("SELECT"))
        assertTrue("应包含 WHERE id = ?", result.sql.contains("WHERE id = ?"))
    }

    fun testGetBaseMapperSelectList() {
        // getBaseMapper().selectList(wrapper) —— 链式调用 + 全列查询
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                static class User {
                    private Long id;
                    private String name;
                }
                static class BaseMapper<T> {
                    public java.util.List<T> selectList(Object wrapper) { return null; }
                }
                static class UserMapper extends BaseMapper<User> {}
                static class UserServiceImpl {
                    public UserMapper getBaseMapper() { return null; }
                    public java.util.List<User> findAll() {
                        return getBaseMapper().selectList(null);
                    }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getBaseMapper().selectList() 应产出预览结果", result)
        assertTrue("selectList 应转 SELECT", result!!.sql.startsWith("SELECT"))
        // user 是 SQL 关键字，方言自动加反引号 → 断言匹配 `user`（含反引号）
        assertTrue("应包含 FROM `user`, actual: ${result.sql}", result.sql.contains("FROM `user`"))
    }

    fun testBatchGetByIdWithGetterParam() {
        // getById(user.getId()) —— 参数 source 应为 id（getter → 纯字段名）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                static class User {
                    private Long id;
                    private String name;
                }
                static class UserServiceImpl extends BaseService<User> {
                    public User findOne(User user) {
                        return getById(user.getId());
                    }
                }
                static class BaseService<T> {
                    public T getById(Long id) { return null; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getById(user.getId()) 应产出预览结果", result)
        assertTrue(
            "参数 source 应为 id, actual: ${result!!.unresolvedParams}",
            result.unresolvedParams.any { it == "id" },
        )
    }

    fun testImplicitThisSave() {
        // this.save(entity) —— 隐式 this 插入
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                static class User {
                    private Long id;
                    private String name;
                }
                static class UserServiceImpl extends BaseService<User> {
                    public boolean create(User user) {
                        return this.save(user);
                    }
                }
                static class BaseService<T> {
                    public boolean save(T entity) { return true; }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("this.save(entity) 应产出预览结果", result)
        assertTrue("save 应转 INSERT", result!!.sql.startsWith("INSERT"))
    }

    fun testInlineRemoveWrappersFactoryRendersDelete() {
        // 用户场景：service.remove(Wrappers.<X>lambdaQuery().eq(...)) 内联工厂 → 应转 DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void remove(Long id) {
                    service.remove(Wrappers.<DeviceInfoEntity>lambdaQuery()
                        .eq(DeviceInfoEntity::getStationId, id));
                }

                static class DeviceInfoEntity { private Long stationId; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联工厂 remove 应产出预览结果", result)
        assertTrue("内联工厂 remove 应转 DELETE", result!!.sql.startsWith("DELETE FROM"))
    }

    fun testInlineUpdateNewWrapperRendersUpdate() {
        // 用户场景：service.update(new LambdaUpdateWrapper<X>().eq(...).set(...)) → 应转 UPDATE
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

            public class Demo {
                public void update(Long productId, Integer dicProtocolType) {
                    service.update(new LambdaUpdateWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getProductId, productId)
                        .set(DeviceInfoEntity::getDicProtocolType, dicProtocolType));
                }

                static class DeviceInfoEntity {
                    private Long productId;
                    private Integer dicProtocolType;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联 update 应产出预览结果", result)
        assertTrue("内联 update 应转 UPDATE", result!!.sql.startsWith("UPDATE"))
    }

    fun testMultipleWrappersInOneFileDoNotCrossContaminate() {
        // 回归：同一文件多个 wrapper（查询 + 删除），各锚点必须返回各自结果，不得串缓存
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query(Long id) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, id);
                    service.list(w);
                }

                public void remove(Long id) {
                    service.remove(Wrappers.<DeviceInfoEntity>lambdaQuery()
                        .eq(DeviceInfoEntity::getId, id));
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // 先转换查询 wrapper（会写入文件级缓存）
        val queryVar = PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)!!
        val queryResult = newService().preview(queryVar)
        assertNotNull(queryResult)
        assertTrue("查询应转 SELECT", queryResult!!.sql.startsWith("SELECT"))

        // 再转换同一文件里的删除 wrapper —— 必须 DELETE，不能命中查询缓存
        val removeCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "remove" }
        val removeResult = newService().preview(removeCall)
        assertNotNull("删除 wrapper 应产出预览结果", removeResult)
        assertTrue("同一文件多个 wrapper 不得串缓存：应转 DELETE", removeResult!!.sql.startsWith("DELETE FROM"))
    }

    fun testCountConsumedRendersCountStar() {
        // 用户场景：service.count(wrapper)（变量声明）→ SELECT COUNT(*) FROM ...，而非多列
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void count(Long id) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, id);
                    service.count(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("count 消费应产出预览结果", result)
        assertEquals("SELECT COUNT(*) FROM device_info_entity WHERE id = ?", result!!.compactSql.trim())
    }

    fun testInlineCountNewWrapperRendersCountStar() {
        // 内联：service.count(new LambdaQueryWrapper<X>().eq(...)) → COUNT(*)
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void count(Long id) {
                    service.count(new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, id));
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联 count 应产出预览结果", result)
        assertTrue("内联 count 应转 COUNT(*)", result!!.compactSql.contains("COUNT(*)"))
    }

    fun testUserCodeShapeAndGroupAndPageLimit() {
        // T-M29-01/03 用户实测全形态：and(condition, w -> ...) 布尔组 + service.page(Page, wrapper) 分页
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query(long pageNum, long pageSize, String keyword,
                                  java.util.List<Long> stationIds, Integer dicStatus,
                                  java.time.LocalDateTime startTime, java.time.LocalDateTime endTime) {
                    LambdaQueryWrapper<BikeOrderRecordEntity> wrapper = Wrappers.<BikeOrderRecordEntity>lambdaQuery()
                        .in(CollUtil.isNotEmpty(stationIds), BikeOrderRecordEntity::getStationId, stationIds)
                        .eq(ObjUtil.isNotNull(dicStatus), BikeOrderRecordEntity::getDicStatus, dicStatus)
                        .in(BikeOrderRecordEntity::getDicPayStatus, 1, 2)
                        .and(StrUtil.isNotBlank(keyword), w ->
                                w.likeRight(BikeOrderRecordEntity::getCode, keyword)
                                        .or().likeRight(BikeOrderRecordEntity::getUserPhone, keyword))
                        .between(BikeOrderRecordEntity::getCreateTime, startTime, endTime)
                        .orderByDesc(BikeOrderRecordEntity::getCreateTime);
                    Page<BikeOrderRecordEntity> page = bikeOrderRecordService.page(new Page<>(pageNum, pageSize), wrapper);
                }

                static class BikeOrderRecordEntity {
                    private Long id;
                    private String code;
                    private Long stationId;
                    private Integer dicStatus;
                    private Integer dicPayStatus;
                    private String userPhone;
                    private java.time.LocalDateTime createTime;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        val sql = result!!.sql
        // 修复前：and 布尔组被静默丢弃；修复后渲染 (code LIKE ? OR user_phone LIKE ?)
        assertTrue("and 组应渲染：$sql", sql.contains("AND (code LIKE ? OR user_phone LIKE ?)"))
        // 分页消费：LIMIT ?, ?（运行时 pageNum/pageSize）
        assertTrue("分页 LIMIT 应渲染：$sql", sql.contains("LIMIT ?, ?"))
        assertTrue("逻辑删除注入：$sql", sql.contains("AND deleted = 0"))
        // 分页占位符参数进参数区（offset 来源标注计算公式）
        assertTrue("分页 offset 参数缺失：${result.unresolvedParams}", result.unresolvedParams.contains("(pageNum - 1) * pageSize"))
        assertTrue("分页 size 参数缺失：${result.unresolvedParams}", result.unresolvedParams.contains("pageSize"))
        // T-M29.1：动态条件面板——and 组一行承载（组开关 + 组内子条件值来源），子条件不再单独成行
        val views = result.previewModel?.conditionViews.orEmpty()
        val groupView = views.firstOrNull { it.label.contains("code LIKE") && it.label.contains("user_phone LIKE") }
        assertNotNull("面板应有 and 组开关行：${views.map { it.label }}", groupView)
        assertTrue(
            "组行值来源应含 keyword：${groupView!!.valueSources}",
            groupView.valueSources.any { it.contains("keyword") },
        )
        assertTrue("组内子条件不应单独成行：${views.map { it.label }}", views.none { it.label == "code LIKE ?" })
        assertEquals(
            "条件行数 = 5（station_id / dic_status / dic_pay_status / and 组 / between）：${views.map { it.label }}",
            5,
            views.size,
        )
    }

    fun testAndGroupWithoutFlagChildrenStillFlattened() {
        // T-M29.1 回归：无布尔标志 and(w -> ...) 组无开关 → 组内子条件照旧单独成行（M19.6 存量行为）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query(String keyword) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .and(x -> x.likeRight(DeviceInfoEntity::getCode, keyword)
                                     .or().likeRight(DeviceInfoEntity::getUserPhone, keyword));
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String code;
                    private String userPhone;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        val views = result!!.previewModel?.conditionViews.orEmpty()
        // 无组开关行（组无 toggle）；code LIKE / user_phone LIKE 两个子条件行照旧（M19.6 全条件挂开关）
        assertTrue("无组开关行：${views.map { it.label }}", views.none { it.label.contains("code LIKE") && it.label.contains("user_phone LIKE") })
        assertEquals("行数 = 3（id = 1001 / code LIKE / user_phone LIKE）：${views.map { it.label }}", 3, views.size)
    }

    fun testPageConsumedRendersLiteralLimit() {
        // T-M29-03：new Page<>(2, 10) 字面量 → 直接计算 LIMIT 10, 10（offset=(2-1)*10）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.page(new Page<>(2, 10), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("page 消费应产出预览结果", result)
        assertEquals(
            """
            SELECT id
            FROM device_info_entity
            WHERE id = 1001
            LIMIT 10, 10
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewWithUnresolvedParam() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query(Long deviceId) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, deviceId);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        // 方法参数无法静态求值 → 收集为未解析参数
        assertEquals(listOf("deviceId"), result!!.unresolvedParams)
        assertEquals("-- 参数对照: [deviceId=?]", result.paramTable)
    }

    fun testPreviewVariableTraced() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    Long stationId = 101L;
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getStationId, stationId);
                }

                static class DeviceInfoEntity {
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        // 变量赋值追踪内联：stationId=101L → 101
        assertEquals("station_id = 101", result!!.sql.substringAfter("WHERE "))
        assertEquals(emptyList<String>(), result.unresolvedParams)
    }

    fun testPreviewNonWrapperReturnsNull() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    String s = "hello";
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNull("非 Wrapper 代码应返回 null", result)
    }

    fun testPreviewUpdate() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

            public class Demo {
                public void update() {
                    LambdaUpdateWrapper<DeviceInfoEntity> w = new LambdaUpdateWrapper<DeviceInfoEntity>()
                        .set(DeviceInfoEntity::getStationId, 101L)
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 UPDATE 预览", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET station_id = 101
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
        // 有 WHERE → 无警示
        assertNull(result.warning)
    }

    fun testPreviewDeleteWithoutWhereWarning() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.delete.LambdaDeleteWrapper;

            public class Demo {
                public void delete() {
                    LambdaDeleteWrapper<DeviceInfoEntity> w = new LambdaDeleteWrapper<DeviceInfoEntity>();
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 DELETE 预览", result)
        assertEquals("DELETE FROM device_info_entity", result!!.sql)
        // 无 WHERE → 红色警示（F-10 全表删除风险）
        assertNotNull("应提示全表删除风险", result.warning)
    }

    fun testPreviewMpjJoin() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<DeviceInfoEntity> w = new MPJLambdaWrapper<DeviceInfoEntity>()
                        .selectAs(DeviceInfoEntity::getId, "device_id")
                        .leftJoin(StationSchemeMerchantInfo.class, on(DeviceInfoEntity::getStationId, StationSchemeMerchantInfo::getSchemeId))
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long shareSchemeId;
                }

                static class StationSchemeMerchantInfo {
                    private Long schemeId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 MPJ 联表预览", result)
        assertEquals(
            """
            SELECT t.id AS device_id
            FROM device_info_entity t
            LEFT JOIN station_scheme_merchant_info stationSchemeMerchantInfo ON t.station_id = stationSchemeMerchantInfo.scheme_id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjJoinSelectAsMethodRef() {
        // P01 形态：selectAs(Entity::getX, VO::getY) 方法引用别名（第一参为主表字段；关联表列归属在 T-M7-05 覆盖）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<DeviceInfoEntity> w = new MPJLambdaWrapper<DeviceInfoEntity>()
                        .selectAs(DeviceInfoEntity::getId, DeviceDetailVO::getDeviceId)
                        .selectAs(DeviceInfoEntity::getStationId, DeviceDetailVO::getStationId)
                        .leftJoin(StationInfoEntity.class, on(DeviceInfoEntity::getStationId, StationInfoEntity::getId))
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                }

                static class StationInfoEntity {
                    private Long id;
                }

                static class DeviceDetailVO {
                    private Long deviceId;
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 MPJ 联表预览", result)
        assertEquals(
            """
            SELECT t.id AS deviceId, t.station_id AS stationId
            FROM device_info_entity t
            LEFT JOIN station_info_entity stationInfoEntity ON t.station_id = stationInfoEntity.id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjLambdaAliasConstructor() {
        // C4/P11 全链路：new MPJLambdaWrapper<>(Class, "apply_info") + 四参带别名 join + 关联实体注解解析
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<InvoiceApplyEntity> w = new MPJLambdaWrapper<>(InvoiceApplyEntity.class, "apply_info")
                        .innerJoin(InvoiceApplyOrderRelEntity.class, "rel",
                            InvoiceApplyOrderRelEntity::getApplyId, InvoiceApplyEntity::getId)
                        .eq(InvoiceApplyEntity::getId, 1001L);
                }

                static class InvoiceApplyEntity {
                    private Long id;
                }

                static class InvoiceApplyOrderRelEntity {
                    private Long applyId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出带表别名的 MPJ 预览", result)
        assertEquals(
            """
            SELECT apply_info.id
            FROM invoice_apply_entity apply_info
            INNER JOIN invoice_apply_order_rel_entity rel ON rel.apply_id = apply_info.id
            WHERE apply_info.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewLastLimitConcat() {
        // P12：last("LIMIT " + N) 字符串拼接表达式 → LIMIT 100
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    int batchSize = 100;
                    LambdaQueryWrapper<OrderMonitorRecordEntity> w = new LambdaQueryWrapper<OrderMonitorRecordEntity>()
                        .eq(OrderMonitorRecordEntity::getOrderId, 1L)
                        .last("LIMIT " + batchSize);
                }

                static class OrderMonitorRecordEntity {
                    private Long orderId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出带 LIMIT 的预览", result)
        assertEquals(
            """
            SELECT order_id
            FROM order_monitor_record_entity
            WHERE order_id = 1
            LIMIT 100
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjFactoryChain() {
        // P03：baseJoinWrapper() 工厂方法返回 wrapper，调用方继续 .eq() 续链 —— 需跨方法追踪
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<CouponEntity> wrapper = baseJoinWrapper()
                        .eq(CouponEntity::getCouponCode, "CODE1");
                }

                private MPJLambdaWrapper<CouponEntity> baseJoinWrapper() {
                    return new MPJLambdaWrapper<CouponEntity>()
                        .selectAll(CouponEntity.class)
                        .leftJoin(MerchantEntity.class, MerchantEntity::getId, CouponEntity::getMerchantId);
                }

                static class CouponEntity {
                    private String couponCode;
                    private Long merchantId;
                }

                static class MerchantEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出跨方法工厂链的预览", result)
        assertEquals(
            """
            SELECT t.coupon_code, t.merchant_id
            FROM coupon_entity t
            LEFT JOIN merchant_entity merchantEntity ON merchantEntity.id = t.merchant_id
            WHERE t.coupon_code = 'CODE1'
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjLambdaQueryWrapper() {
        // P17：IService.list(new MPJLambdaQueryWrapper<...>().select(...).eq(...)) 单表查询
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.query.MPJLambdaQueryWrapper;

            public class Demo {
                public void query() {
                    subAccountStationService.list(new MPJLambdaQueryWrapper<SubAccountStationEntity>()
                        .select(SubAccountStationEntity::getStationId)
                        .eq(SubAccountStationEntity::getAccountId, 100L));
                }

                static class SubAccountStationEntity {
                    private Long stationId;
                    private Long accountId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 MPJLambdaQueryWrapper 预览", result)
        assertEquals(
            """
            SELECT station_id
            FROM sub_account_station_entity
            WHERE account_id = 100
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewStaticFactoryGetOne() {
        // 用户反馈片段：merchantService.getOne(Wrappers.<T>lambdaQuery().select(9列).eq(列, dto.getPhone()))
        // 静态工厂内联链（无 new、无 Wrapper 变量）应完整转换
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    MerchantEntity merchant = merchantService.getOne(Wrappers.<MerchantEntity>lambdaQuery()
                            .select(
                                    MerchantEntity::getId,
                                    MerchantEntity::getMerchantCode,
                                    MerchantEntity::getName,
                                    MerchantEntity::getPhone,
                                    MerchantEntity::getDicMerchantType,
                                    MerchantEntity::getPassword,
                                    MerchantEntity::getDicStatus,
                                    MerchantEntity::getParentId,
                                    MerchantEntity::getSubAccountName)
                            .eq(MerchantEntity::getPhone, dto.getPhone()));
                }

                static class MerchantEntity {
                    private Long id;
                    private String merchantCode;
                    private String name;
                    private String phone;
                    private Integer dicMerchantType;
                    private String password;
                    private Integer dicStatus;
                    private Long parentId;
                    private String subAccountName;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 Wrappers 静态工厂链预览", result)
        assertEquals(
            """
            SELECT id, merchant_code, name, phone, dic_merchant_type, password, dic_status, parent_id, sub_account_name
            FROM merchant_entity
            WHERE phone = ?
            """.trimIndent(),
            result!!.sql,
        )
        // dto.getPhone() 经 getter→字段引用转换后解析为 phone（master 分支 getter 方法字段名修复）
        assertEquals(listOf("phone"), result.unresolvedParams)
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY, result.wrapperType)
    }

    fun testPreviewPastedSnippetWithTableNameAnnotation() {
        // 粘贴片段含实体 + @TableName 注解：注解类不可解析，走短名兜底（M9 工具窗口反馈）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            @com.baomidou.mybatisplus.annotation.TableName("t_user")
            public class UserEntity {
                private Long id;
            }

            public class Demo {
                public void query() {
                    merchantService.getOne(Wrappers.<UserEntity>lambdaQuery()
                            .eq(UserEntity::getId, 1L));
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(myFixture.file)
        assertNotNull("应识别 @TableName 注解（短名兜底）", result)
        assertEquals(
            """
            SELECT id
            FROM t_user
            WHERE id = 1
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewPastedSnippetWithoutEntityClass() {
        // 模拟工具窗口粘贴：片段无实体类定义，按类型参数类名命名策略兜底表名（M9 增强）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    merchantService.getOne(Wrappers.<MerchantEntity>lambdaQuery()
                            .select(MerchantEntity::getId)
                            .eq(MerchantEntity::getPhone, "138"));
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(myFixture.file)
        assertNotNull("无实体类定义也应产出预览（命名策略兜底）", result)
        assertEquals(
            """
            SELECT id
            FROM merchant_entity
            WHERE phone = '138'
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewBatchUpdate() {
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;

            public class Demo {
                public void batch(List<DeviceInfoEntity> list) {
                    DeviceInfoService service = new DeviceInfoService();
                    service.updateBatchById(list);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                    private Long shareSchemeId;
                }

                static class DeviceInfoService {
                    public void updateBatchById(List<DeviceInfoEntity> list) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应识别批量更新调用", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET station_id = ?, share_scheme_id = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
        // 参数组说明（F-15）
        assertNotNull(result.paramTable)
    }

    fun testPreviewBatchInsert() {
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;

            public class Demo {
                public void batch(List<DeviceInfoEntity> list) {
                    DeviceInfoService service = new DeviceInfoService();
                    service.saveBatch(list);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class DeviceInfoService {
                    public void saveBatch(List<DeviceInfoEntity> list) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应识别批量插入调用", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceLambdaQueryChainRendersSelect() {
        // Q17：service.lambdaQuery()...one() 服务链（真实项目 35 文件约 177 处）→ SELECT；one() 终结点剔除
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    orderService.lambdaQuery()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .orderByDesc(DeviceInfoEntity::getCreateTime)
                        .last("limit 1")
                        .one();
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long createTime;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("服务链应产出预览结果", result)
        assertEquals(
            """
            SELECT id, create_time
            FROM device_info_entity
            WHERE id = 1001
            ORDER BY create_time DESC
            LIMIT 1
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceLambdaQueryChainCountRendersCountStar() {
        // Q17 终结点 count：service.lambdaQuery()...count() → SELECT COUNT(*)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void count() {
                    long total = orderService.lambdaQuery()
                        .eq(DeviceInfoEntity::getDicStatus, 1)
                        .count();
                }

                static class DeviceInfoEntity {
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("服务链 count 应产出预览结果", result)
        assertEquals(
            "SELECT COUNT(*) FROM device_info_entity WHERE dic_status = 1",
            result!!.compactSql.trim(),
        )
    }

    fun testServiceLambdaQueryChainExistsRendersExists() {
        // Q14-③：service.lambdaQuery()...exists() 链式终结点 → SELECT EXISTS (子查询)（MP 3.5.15 BaseMapper.exists 语义）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void check() {
                    boolean exists = giftFundDataService.lambdaQuery()
                        .eq(GiftFundEntity::getId, 1001L)
                        .exists();
                }

                static class GiftFundEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("服务链 exists 应产出预览结果", result)
        assertEquals(
            "SELECT EXISTS (SELECT id FROM gift_fund_entity WHERE id = 1001)",
            result!!.compactSql.trim(),
        )
    }

    fun testServiceLambdaUpdateChainRendersUpdate() {
        // U13：service.lambdaUpdate()...update() 链（真实项目 20 文件约 87 处）→ UPDATE；update() 终结点剔除
        // （set(boolean, ...) 条件 set 的布尔剥离在 T-M15-04 覆盖）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    merchantWalletWithdrawService.lambdaUpdate()
                        .set(MerchantWalletWithdrawEntity::getDicWithdrawStatus, 2)
                        .set(MerchantWalletWithdrawEntity::getTransactionId, transactionId)
                        .eq(MerchantWalletWithdrawEntity::getId, withdraw.getId())
                        .update();
                }

                static class MerchantWalletWithdrawEntity {
                    private Integer dicWithdrawStatus;
                    private String transactionId;
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("lambdaUpdate 链应产出 UPDATE 预览", result)
        assertEquals(
            """
            UPDATE merchant_wallet_withdraw_entity
            SET dic_withdraw_status = 2, transaction_id = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceLambdaUpdateChainRemoveRendersDelete() {
        // U11-⑤：service.lambdaUpdate()...remove() 链式删除（merchant/SubAccountAppService#doConfigStation 真实用例）→ DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void remove() {
                    merchantSubAccountStationService.lambdaUpdate()
                        .eq(MerchantSubAccountStationEntity::getMerchantId, parentId)
                        .eq(MerchantSubAccountStationEntity::getSubMerchantId, subAccountId)
                        .remove();
                }

                static class MerchantSubAccountStationEntity {
                    private Long merchantId;
                    private Long subMerchantId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("lambdaUpdate remove 链应产出 DELETE 预览", result)
        assertEquals(
            "DELETE FROM merchant_sub_account_station_entity WHERE merchant_id = ? AND sub_merchant_id = ?",
            result!!.compactSql.trim(),
        )
    }

    fun testLambdaQueryChainWrapperExplicitTypeDeclaration() {
        // Q17 显式 ChainWrapper 声明（modules/CarDevicePortInfoServiceImpl 真实用例）：
        // LambdaQueryChainWrapper<X> w = lambdaQuery()...; 后接 count(w) 查重 + getOne(w)
        // 从变量声明锚点预览：count(w) 消费识别生效 → SELECT COUNT(*)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryChainWrapper<CarDevicePortInfoEntity> wrapper = lambdaQuery()
                        .eq(CarDevicePortInfoEntity::getDeviceCode, deviceCode)
                        .eq(CarDevicePortInfoEntity::getNumber, number);
                    if (count(wrapper) > 1) { throw new RuntimeException(); }
                    return Optional.ofNullable(getOne(wrapper));
                }

                static class CarDevicePortInfoEntity {
                    private String deviceCode;
                    private String number;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("显式 ChainWrapper 声明应产出预览结果", result)
        assertEquals(
            """
            SELECT COUNT(*)
            FROM car_device_port_info_entity
            WHERE device_code = ?
                AND number = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testQueryWrapperStringColumnsAggregation() {
        // Q03/Q09/Q18（F-17）：字符串版 QueryWrapper 聚合链（merchant/AccountManagementAppService#queryDayAmountSummary 真实用例）：
        // select(表达式) + eq(字符串列) + between(字符串列) + in(boolean, 字符串列) + groupBy(字符串)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    QueryWrapper<MerchantWalletFlowEntity> sumQw = Wrappers.<MerchantWalletFlowEntity>query()
                        .select("DATE(create_time)::timestamp as create_time, amount_direction, SUM(change_available_amount) as change_available_amount")
                        .eq("merchant_id", merchantId)
                        .between("create_time", startTime, endTime)
                        .in(CollUtil.isNotEmpty(flowTypes), "dic_flow_type", flowTypes)
                        .groupBy("DATE(create_time), amount_direction");
                    merchantWalletFlowService.list(sumQw);
                }

                static class MerchantWalletFlowEntity {
                    private Long merchantId;
                    private String createTime;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("字符串版 QueryWrapper 应产出预览结果", result)
        assertEquals(
            """
            SELECT DATE(create_time)::timestamp as create_time, amount_direction, SUM(change_available_amount) as change_available_amount
            FROM merchant_wallet_flow_entity
            WHERE merchant_id = ?
                AND create_time BETWEEN ? AND ?
                AND dic_flow_type IN (?)
            GROUP BY DATE(create_time), amount_direction
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testStringUpdateWrapperSetSqlAndStringColumn() {
        // U09-①②（F-17）：字符串版 UpdateWrapper——new UpdateWrapper<>() + 字符串列 eq + setSql（原子自增）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    UpdateWrapper<StoreCouponEntity> wrapper = new UpdateWrapper<StoreCouponEntity>()
                        .eq("id", 1001L)
                        .setSql("use_total = use_total + 1");
                    storeCouponService.update(wrapper);
                }

                static class StoreCouponEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("字符串版 UpdateWrapper 应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET use_total = use_total + 1
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testStringUpdateWrapperApplyInline() {
        // U09-②：内联 update(new UpdateWrapper<X>().eq("id", v).apply(...).setSql(...))（防超发上限保护）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    storeCouponService.update(new UpdateWrapper<StoreCouponEntity>()
                        .eq("id", couponId)
                        .apply("fission_reward_claim_count < fission_reward_total")
                        .setSql("fission_reward_claim_count = fission_reward_claim_count + 1"));
                }

                static class StoreCouponEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("内联字符串 UpdateWrapper 应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET fission_reward_claim_count = fission_reward_claim_count + 1
            WHERE id = ?
                AND fission_reward_claim_count < fission_reward_total
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testStringUpdateWrapperLambdaConversion() {
        // U09-④：wrapper.lambda() 动态转换为 lambda 形态（admin/CouponAppService#updateStatus 真实用例）——
        // lambda() 返回的续链（eq/set）应并入原链
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    UpdateWrapper<StoreCouponEntity> wrapper = new UpdateWrapper<>();
                    wrapper.lambda().eq(StoreCouponEntity::getId, id)
                        .set(StoreCouponEntity::getDicStatus, status);
                    storeCouponService.update(wrapper);
                }

                static class StoreCouponEntity {
                    private Long id;
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("wrapper.lambda() 转换应产出预览结果", result)
        assertEquals(
            """
            UPDATE store_coupon_entity
            SET dic_status = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testExistsSqlSubqueryCondition() {
        // Q14-①：链内 exists(boolean, "SELECT ... {0}", v) SQL 子查询条件（admin/CarStationPriceTaskRecordAppService#page 真实用例）：
        // 布尔首参剥离为动态条件 + {0} 参数替换 + EXISTS (...) 包裹
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void page() {
                    PriceTaskRecordService.lambdaQuery()
                        .eq(PriceTaskRecordEntity::getId, 1001L)
                        .exists(dto.getStationId() != null,
                            "SELECT 1 FROM rel WHERE rel.task_id = main.id AND rel.station_id = {0}",
                            dto.getStationId())
                        .page(new Page<>(1, 10));
                }

                static class PriceTaskRecordEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("exists SQL 子查询条件应产出预览结果", result)
        // T-M29-03：链式终结点 page(new Page<>(1, 10)) 分页消费 → LIMIT 0, 10（offset=(1-1)*10）
        assertEquals(
            """
            SELECT id
            FROM price_task_record_entity
            WHERE id = 1001
                AND EXISTS (SELECT 1 FROM rel WHERE rel.task_id = main.id AND rel.station_id = ?)
            LIMIT 0, 10
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceExistsWrapperConsumesRendersExists() {
        // Q14-②：service.exists(wrapper) 消费（admin/MerchantAppService 真实用例）→ SELECT EXISTS (子查询)
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void check() {
                    boolean exists = merchantService.exists(new LambdaQueryWrapper<MerchantEntity>()
                        .eq(MerchantEntity::getCode, code));
                }

                static class MerchantEntity {
                    private String code;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("service.exists 消费应产出预览结果", result)
        assertEquals(
            "SELECT EXISTS (SELECT code FROM merchant_entity WHERE code = ?)",
            result!!.compactSql.trim(),
        )
    }

}
