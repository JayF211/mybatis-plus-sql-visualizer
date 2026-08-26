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

class SqlPreviewServiceBatchTest : BasePlatformTestCase() {

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

    fun testBatchSaveRendersInsertWithParamSources() {
        // T-M23-02（用户实测：save 没有填参数的地方）：save(entity) 每个 `?` 登记 `实体.列名` 参数行，
        // 回填引号提示按列类型（数值列无引号、String 列引号）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StoreCouponService storeCouponService;

                public void save(StoreCouponEntity entity) {
                    storeCouponService.save(entity);
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class StoreCouponService extends BaseService<StoreCouponEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO store_coupon_entity (id, name, dic_status)
            VALUES (?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(
            "save 应登记实体字段参数行",
            listOf("entity.id", "entity.name", "entity.dic_status"),
            result.unresolvedParams,
        )
        // 数值列（Long/Integer）无引号、String 列引号
        assertEquals(listOf(false, true, false), result.paramHints.map { it.quote })
    }

    fun testBatchSaveKeywordColumnQuoted() {
        // T-M23.4（用户示例：biz_banner 的 table 字段为 MySQL 保留字）：批量 INSERT 列清单
        // 关键字列名反引号包裹（方言符号），参数行/回填提示不受影响（模型层列名不包裹）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BizBannerService bizBannerService;

                public void save(BizBannerEntity entity) {
                    bizBannerService.save(entity);
                }

                static class BizBannerEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String title;
                    private String name;
                    private String table;
                    private String imageUrl;
                    private Integer dicStatus;
                    private Integer sort;
                }

                static class BaseService<T> {}

                static class BizBannerService extends BaseService<BizBannerEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO biz_banner_entity (id, title, name, `table`, image_url, dic_status, sort)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
        // 参数行来源/引号提示与未引用时一致
        assertEquals(
            listOf("entity.id", "entity.title", "entity.name", "entity.table", "entity.image_url", "entity.dic_status", "entity.sort"),
            result.unresolvedParams,
        )
        assertEquals(listOf(false, true, true, true, true, false, false), result.paramHints.map { it.quote })
    }

    fun testBatchGetByIdKeywordColumnsQuoted() {
        // T-M23.5（用户实测：查询未处理关键字）：批量 SELECT（getById 形态）——
        // 全列清单/表名/主键条件按方言引用（`table` 列反引号包裹）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BizBannerService bizBannerService;

                public void query(Long id) {
                    bizBannerService.getById(id);
                }

                static class BizBannerEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String title;
                    private String name;
                    private String table;
                    private String imageUrl;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class BizBannerService extends BaseService<BizBannerEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getById 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, title, name, `table`, image_url, dic_status
            FROM biz_banner_entity
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testBatchRemoveByIdLogicDeleteKeywordColumnsQuoted() {
        // T-M23.5：批量软删除模板（@TableLogic）——表名/SET 逻辑列/WHERE 主键与逻辑条件按方言引用
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BizBannerService bizBannerService;

                public void remove(Long id) {
                    bizBannerService.removeById(id);
                }

                static class BizBannerEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String table;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class BaseService<T> {}

                static class BizBannerService extends BaseService<BizBannerEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE biz_banner_entity
            SET deleted = 1
            WHERE id = ? AND deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testBatchSaveAutoIncrementIdDefaultUnchecked() {
        // T-M23-04（用户建议：添加时 id 可能不需要）：@TableId(type = IdType.AUTO) 主键默认不勾选
        // → 初始 INSERT 不含 id 列（对齐 MP：自增主键不进 INSERT 列清单）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StoreCouponService storeCouponService;

                public void save(StoreCouponEntity entity) {
                    storeCouponService.save(entity);
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class StoreCouponService extends BaseService<StoreCouponEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO store_coupon_entity (name, dic_status)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals("AUTO 主键不进参数行", listOf("entity.name", "entity.dic_status"), result.unresolvedParams)
        val model = result.batchModel!!
        assertEquals("主键应默认不勾选", false, model.toggleColumns.first { it.columnName == "id" }.checked)
    }

    fun testBatchUpdateByIdExposesToggleColumns() {
        // T-M23-04（用户建议：修改时有些字段可能不需要修改）：updateById 的 SET 列为可勾选（默认全勾选），
        // WHERE 主键为固定列（无勾选）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void update(DeviceInfoEntity entity) {
                    deviceInfoService.updateById(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET name = ?, dic_status = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
        val model = result.batchModel!!
        assertEquals("SET 列应为可勾选", listOf("name", "dic_status"), model.toggleColumns.map { it.columnName })
        assertEquals("SET 列默认全勾选", listOf(true, true), model.toggleColumns.map { it.checked })
        assertEquals("WHERE 主键应为固定列", listOf("id"), model.fixedColumns.map { it.columnName })
    }

    fun testBatchSaveOrUpdateIdSelectable() {
        // T-M23.2（用户实测：taskRecordService.saveOrUpdate(task)——id 应为可选）：
        // saveOrUpdate 主键纳入 toggleColumns（默认勾选渲染完整 upsert；取消 → 纯 INSERT 新记录路径），
        // 不再是固定必填列
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private TaskRecordService taskRecordService;

                public void saveOrUpdate(TaskRecordEntity task) {
                    taskRecordService.saveOrUpdate(task);
                }

                static class TaskRecordEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String taskName;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class TaskRecordService extends BaseService<TaskRecordEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("saveOrUpdate 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO task_record_entity (id, task_name, dic_status)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE task_name = VALUES(task_name), dic_status = VALUES(dic_status)
            """.trimIndent(),
            result!!.sql,
        )
        val model = result.batchModel!!
        assertEquals("主键应在 toggleColumns（可选）", listOf("id", "task_name", "dic_status"), model.toggleColumns.map { it.columnName })
        assertEquals("冲突键应为主键", "id", model.conflictColumn)
        assertTrue("fixedColumns 应为空（无必填列）", model.fixedColumns.isEmpty())
    }

    fun testBatchRemoveByIdUnresolvableArgRegistersParamRow() {
        // T-M23-02（用户实测：removeById 没有填参数的地方）：removeById(1001L) 字面量内联（无参数行，
        // 见 testRemoveByIdRendersDelete）；removeById(id) 运行时值 → `?` + `#id` 参数行
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void remove(Long id) {
                    deviceInfoService.removeById(id);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeById 应产出预览结果", result)
        assertEquals(
            """
            DELETE FROM device_info_entity
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals("removeById 应登记 id 参数行", listOf("id"), result.unresolvedParams)
    }

    fun testSaveEntityRendersInsert() {
        // M19.5（T-M19.5-03，用户实测用例 4 原样）：service.save(new Entity()) 单实体插入——
        // 全列 INSERT（类注释声称支持但注册表缺失，注释与实现不一致，M19.5 补注册）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private CarDeviceInfoService carDeviceInfoService;

                public void save() {
                    carDeviceInfoService.save(new CarDeviceInfoEntity());
                }

                static class CarDeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String deviceCode;
                    private Integer dicStatus;
                }

                static class CarDeviceInfoService {
                    public void save(CarDeviceInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO car_device_info_entity (id, device_code, dic_status)
            VALUES (?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testEntityCrudMethodNamesAreAnchors() {
        // M19.5（T-M19.5-04）：save/getById/listByIds 注册后经 batchOperationMethodNames 集合，
        // WrapperAnchorDetector 场景 D 锚点自动生效（gutter 图标）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void ops() {
                    deviceInfoService.save(entity);
                    deviceInfoService.getById(1001L);
                    deviceInfoService.listByIds(ids);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }

                static class DeviceInfoService {
                    public void save(DeviceInfoEntity entity) {}
                    public void getById(Long id) {}
                    public void listByIds(java.util.List<Long> ids) {}
                }
            }
            """.trimIndent(),
        )
        for (name in listOf("save", "getById", "listByIds")) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            assertSame(
                "$name 方法名应为 gutter 锚点",
                call.methodExpression.referenceNameElement,
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
    }

    fun testUpdateByIdRendersUpdate() {
        // U12（T-M15-08）：service.updateById(entity) 单实体更新（update-strategy: always 全列语义）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void update() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoService.updateById(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class DeviceInfoService {
                    public void updateById(DeviceInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET name = ?, dic_status = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testSaveOrUpdateRendersMysqlUpsert() {
        // U12（T-M15-08）：service.saveOrUpdate(entity) → 默认（MySQL）方言 ON DUPLICATE KEY UPDATE upsert
        // （T-M43：PG 系方言才渲染 ON CONFLICT；Oracle/SQL Server/DM 降级纯 INSERT，见 upsertFollowsDialect）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StationOccupyInfoService stationOccupyInfoService;

                public void saveOrUpdate() {
                    StationOccupyInfoEntity entity = new StationOccupyInfoEntity();
                    stationOccupyInfoService.saveOrUpdate(entity);
                }

                static class StationOccupyInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private Long stationId;
                    private Integer dicStatus;
                }

                static class StationOccupyInfoService {
                    public void saveOrUpdate(StationOccupyInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("saveOrUpdate 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO station_occupy_info_entity (id, station_id, dic_status)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE station_id = VALUES(station_id), dic_status = VALUES(dic_status)
            """.trimIndent(),
            result!!.sql,
        )
    }

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

}
