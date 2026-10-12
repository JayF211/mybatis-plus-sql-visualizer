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

/**
 * SqlPreviewService 的 insert/upsert/多租户/remove-by-map/select-by-map 预览测试（自 SqlPreviewServiceBatchTest 拆分）。
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class SqlPreviewServiceInsertMapTest : BasePlatformTestCase() {

    fun testInsertBatchSomeColumnExcludesUpdateFill() {
        // X09（T-M15-08）：insertBatchSomeColumn 排除 FieldFill.UPDATE 填充列（插入不参与）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void batch() {
                    java.util.List<DeviceInfoEntity> list = new java.util.ArrayList<DeviceInfoEntity>();
                    deviceInfoMapper.insertBatchSomeColumn(list);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.UPDATE)
                    private String updateTime;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("insertBatchSomeColumn 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M20-08：BaseMapper 批量族（insert / insertOrUpdate，MP 3.5.7+）
    // ------------------------------------------------------------------

    fun testServiceInsertRendersInsert() {
        // T-M20-08：service.insert(entity) 单实体插入——与 save 同 INSERT 全列模板（isLikelyEntity 步骤 2 解析）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void insert() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoService.insert(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class DeviceInfoService {
                    public void insert(DeviceInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("insert(entity) 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name, dic_status)
            VALUES (?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMapperInsertEntityRendersInsert() {
        // T-M20-08：mapper.insert(entity)——mapper 变量（DeviceInfoMapper extends BaseMapper<Entity>）；
        // 实体参数直接解析（superTypes 泛型路径在 Map 实参等非实体场景兜底，见 selectByMap 用例）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void insert() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoMapper.insert(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                static class BaseMapper<T> {
                    public int insert(T entity) { return 0; }
                }

                static class DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("mapper.insert(entity) 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testInsertTenantColumnAppendedWhenBothSwitchesOn() {
        // D2（T-PortLossBatch1）：INSERT 租户列追加 = 主开关 AND 子开关——两者都开时追加 tenant_id
        val settings = PluginSettings.getInstance()
        try {
            settings.state.injectTenantLine = true
            settings.state.injectTenantOnInsert = true
            myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    private DeviceInfoMapper deviceInfoMapper;

                    public void insert() {
                        DeviceInfoEntity entity = new DeviceInfoEntity();
                        deviceInfoMapper.insert(entity);
                    }

                    static class DeviceInfoEntity {
                        @com.baomidou.mybatisplus.annotation.TableId
                        private Long id;
                        private String name;
                    }

                    static class BaseMapper<T> {
                        public int insert(T entity) { return 0; }
                    }

                    static class DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())
            assertNotNull("insert(entity) 应产出预览结果", result)
            assertEquals(
                "主开关+子开关均开时 INSERT 应追加租户列",
                """
                INSERT INTO device_info_entity (id, name, tenant_id)
                VALUES (?, ?, ?)
                """.trimIndent(),
                result!!.sql,
            )
        } finally {
            settings.state.injectTenantLine = false
            settings.state.injectTenantOnInsert = true
        }
    }

    fun testInsertTenantColumnSkippedWhenSubSwitchOff() {
        // D2（T-PortLossBatch1）：injectTenantOnInsert=false → INSERT 不追加租户列（主开关仍开也不加）
        val settings = PluginSettings.getInstance()
        try {
            settings.state.injectTenantLine = true
            settings.state.injectTenantOnInsert = false
            myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    private DeviceInfoMapper deviceInfoMapper;

                    public void insert() {
                        DeviceInfoEntity entity = new DeviceInfoEntity();
                        deviceInfoMapper.insert(entity);
                    }

                    static class DeviceInfoEntity {
                        @com.baomidou.mybatisplus.annotation.TableId
                        private Long id;
                        private String name;
                    }

                    static class BaseMapper<T> {
                        public int insert(T entity) { return 0; }
                    }

                    static class DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())
            assertNotNull("insert(entity) 应产出预览结果", result)
            assertEquals(
                "子开关关闭时 INSERT 不应追加租户列",
                """
                INSERT INTO device_info_entity (id, name)
                VALUES (?, ?)
                """.trimIndent(),
                result!!.sql,
            )
        } finally {
            settings.state.injectTenantLine = false
            settings.state.injectTenantOnInsert = true
        }
    }

    fun testInsertOrUpdateRendersMysqlUpsert() {
        // T-M20-08：BaseMapper.insertOrUpdate(entity)（MP 3.5.7+）→ 与 saveOrUpdate 同 MySQL 方言 upsert
        // （T-M43：冲突子句按方言渲染）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void insertOrUpdate() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoMapper.insertOrUpdate(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseMapper<T> {
                    public int insertOrUpdate(T entity) { return 0; }
                }

                static class DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("insertOrUpdate 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name, dic_status)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE name = VALUES(name), dic_status = VALUES(dic_status)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testNonEntityInsertCallReturnsNull() {
        // T-M20-08 防误伤：`insert` 是通用 Java 方法名——字符串实参 + 非 service qualifier：
        // isLikelyEntity 挡掉 java.lang.String（步骤 1/2 跳过），qualifier 非 service 无实体泛型
        // （步骤 3 返回 null）→ convert 返回 null → 预览 null
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void ops() {
                    MyList list = new MyList();
                    list.insert("x");
                }

                static class MyList {
                    public void insert(String s) {}
                }
            }
            """.trimIndent(),
        )
        assertNull("非实体 insert 调用不应产出预览", newService().preview(anchor()))
    }

    // ------------------------------------------------------------------
    // T-M20-07：Map 条件消费（removeByMap / deleteByMap / selectByMap / listByMap，MP/MPJ）
    // ------------------------------------------------------------------

    fun testServiceRemoveByMapRendersDelete() {
        // T-M20-07：service.removeByMap(map) 删除——WHERE Map 等值条件占位（键值运行时解析，静态不可得）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void remove() {
                    java.util.Map<String, Object> mapVar = new java.util.HashMap<String, Object>();
                    deviceInfoService.removeByMap(mapVar);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class BaseService<T> {
                    public boolean removeByMap(java.util.Map<String, Object> map) { return false; }
                }

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeByMap 应产出预览结果", result)
        assertEquals(
            """
            DELETE FROM device_info_entity
            WHERE <Map 等值条件: col = ? AND col = ? ...>
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testMapperSelectByMapRendersSelect() {
        // T-M20-07：mapper.selectByMap(map)（BaseMapper）——Map 实参非实体，实体经 mapper
        // superTypes 泛型解析（DeviceInfoMapper extends BaseMapper<DeviceInfoEntity>，步骤 3 兜底）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void select() {
                    java.util.Map<String, Object> mapVar = new java.util.HashMap<String, Object>();
                    deviceInfoMapper.selectByMap(mapVar);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class BaseMapper<T> {
                    public java.util.List<T> selectByMap(java.util.Map<String, Object> map) { return null; }
                }

                static class DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("mapper.selectByMap 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM device_info_entity
            WHERE <Map 等值条件: col = ? AND col = ? ...>
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testServiceListByMapRendersSelect() {
        // T-M20-07：service.listByMap(map) 查询——SELECT 全列 + Map 等值条件占位
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void list() {
                    java.util.Map<String, Object> mapVar = new java.util.HashMap<String, Object>();
                    deviceInfoService.listByMap(mapVar);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class BaseService<T> {
                    public java.util.List<T> listByMap(java.util.Map<String, Object> map) { return null; }
                }

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("listByMap 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM device_info_entity
            WHERE <Map 等值条件: col = ? AND col = ? ...>
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M20-10：实体+Wrapper 形态（getOptById / saveOrUpdate(entity, wrapper) / 批量便捷重载核对）
    // ------------------------------------------------------------------

    fun testGetOptByIdRendersSelect() {
        // T-M20-10：service.getOptById(id)（IService 3.5.15）→ 与 getById 同主键等值 SELECT 模板
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void query() {
                    deviceInfoService.getOptById(1001L);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getOptById 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM device_info_entity
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testSaveOrUpdateEntityWrapperRendersUpdate() {
        // T-M20-10：service.saveOrUpdate(entity, wrapper) 双参——实体字段展开 SET（主键排除进 WHERE），
        // 与 update(entity, wrapper) 同消费语义（U07-② 分支）；依赖 WrapperLocator.UPDATE_CONSUMER_METHODS
        // 合入 "saveOrUpdate"（并行开发者，未合入时本测试失败属预期）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void saveOrUpdate() {
                    boolean saved = deviceInfoService.saveOrUpdate(
                            entity,
                            Wrappers.<DeviceInfoEntity>lambdaUpdate()
                                .eq(DeviceInfoEntity::getId, 1001L))
                        || deviceInfoService.save(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("saveOrUpdate(entity, wrapper) 应产出预览结果", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET name = ?, dic_status = ?
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testIserviceBatchConvenienceOverloadsPreview() {
        // T-M20-10 核对：IService 3.5.15 便捷重载 updateBatchById(Collection) / removeBatchByIds(Collection)
        // （removeBatchByIds 核对时补注册）→ 批量模板可预览
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void batch() {
                    deviceInfoService.updateBatchById(list);
                    deviceInfoService.removeBatchByIds(list);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val expected = mapOf(
            "updateBatchById" to """
                UPDATE device_info_entity
                SET name = ?
                WHERE id = ?
                """.trimIndent(),
            "removeBatchByIds" to """
                DELETE FROM device_info_entity
                WHERE id IN (?, ?, ...)
                """.trimIndent(),
        )
        for ((name, sql) in expected) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            val result = newService().preview(call)
            assertNotNull("$name 应产出预览结果", result)
            assertEquals("$name SQL 断言", sql, result!!.sql)
        }
    }


    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
    }

    private fun newService() = SqlPreviewService(project)

    /** T-TenantLine：每个测试结束后重置多租户配置为默认，防止状态泄漏。 */
    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.injectTenantOnInsert = true
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
}
