package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * EntityResolver 表名/字段配置测试（T-M19.4-03）：
 *  - 表名推断策略：SNAKE（默认，对齐 MP）驼峰转下划线 / CAMEL 纯保留驼峰（仅首字母小写、
 *    不去 Entity 后缀）；`@TableName` 显式表名不受推断策略影响（M19.4-R1）
 *  - 忽略字段名（`;` 分隔，Java 字段名匹配）：命中字段不进列集合（SELECT 全列展开/参数对照/
 *    补全列集合同源，均不含）；与 @TableField(exist=false) 并存、注解优先（M19.4-R3）
 *  - 统一修饰链（**推断语义**——仅作用于推断表名；T-M19.4 用户反馈收窄，`@TableName` 显式表名
 *    即最终表名、优先级最高、豁免修饰）：移除前缀/后缀首匹配（大小写敏感，M19.4-R2）+ 添加前缀/后缀单值
 */
class EntityResolverTableConfigTest : BasePlatformTestCase() {

    private fun resolveEntity(code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    /**
     * 设置表配置并执行断言，finally 恢复原状态（APP 级服务跨测试方法共享，必须还原防污染）。
     * 用 getState().copy() 快照 + loadState 还原（data class copy 与序列化快照同构）。
     */
    private fun withTableConfig(
        tableNameInference: String = PluginSettings.INFERENCE_SNAKE,
        ignoredFields: String = "",
        tablePrefix: String = "",
        tableSuffix: String = "",
        removeTablePrefix: String = "",
        removeTableSuffix: String = "",
        block: () -> Unit,
    ) {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tableNameInference = tableNameInference
            settings.state.ignoredFields = ignoredFields
            settings.state.tablePrefix = tablePrefix
            settings.state.tableSuffix = tableSuffix
            settings.state.removeTablePrefix = removeTablePrefix
            settings.state.removeTableSuffix = removeTableSuffix
            block()
        } finally {
            settings.loadState(snapshot)
        }
    }

    // ------------------------------------------------------------------
    // T-M19.4-03-①：表名推断策略（无 @TableName 时）
    // ------------------------------------------------------------------

    fun testTableNameInferenceSnakeDefault() {
        // 默认 SNAKE：对齐 MP 现状——toSnakeCase 作用于**完整类名**（Entity 后缀不剥离，
        // BikeDeviceInfoEntity → bike_device_info_entity；计划示例 bike_device_info 为去后缀
        // 理想形式，真实现状含 _entity，SNAKE 保持现状不改默认行为）
        withTableConfig() {
            val clazz = resolveEntity(
                """
                public class BikeDeviceInfoEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("bike_device_info_entity", EntityResolver().resolve(clazz).tableName)
        }
    }

    fun testTableNameInferenceCamel() {
        // T-M19.4-03-①：CAMEL = **纯保留驼峰**——仅首字母小写，**不去 Entity 后缀**
        // （用户反馈：文案「保留驼峰」与行为一致；BikeDeviceInfo → bikeDeviceInfo 贴近用户场景）
        withTableConfig(tableNameInference = PluginSettings.INFERENCE_CAMEL) {
            val clazz = resolveEntity(
                """
                public class BikeDeviceInfoEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("bikeDeviceInfoEntity", EntityResolver().resolve(clazz).tableName)
            // 无 Entity 后缀类名：纯首字母小写（用户场景：实体 BikeDeviceInfo → 表 bikeDeviceInfo）
            val clazzNoSuffix = resolveEntity(
                """
                public class BikeDeviceInfo {
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("bikeDeviceInfo", EntityResolver().resolve(clazzNoSuffix).tableName)
        }
    }

    fun testExplicitTableNameNotAffectedByInference() {
        // M19.4-R1：推断策略仅作用于**无注解推断**——@TableName 显式表名不受影响
        withTableConfig(tableNameInference = PluginSettings.INFERENCE_CAMEL) {
            val clazz = resolveEntity(
                """
                @com.baomidou.mybatisplus.annotation.TableName("bike_device_info")
                public class BikeDeviceInfoEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("bike_device_info", EntityResolver().resolve(clazz).tableName)
        }
    }

    // ------------------------------------------------------------------
    // T-M19.4-03-②：忽略字段名（`;` 分隔，Java 字段名匹配）
    // ------------------------------------------------------------------

    fun testIgnoredFieldsSkipped() {
        // 命中忽略列表的字段不进列集合——SELECT 全列展开/参数对照/补全列集合同源于
        // TableInfo.columns，断言列集合即可覆盖三处消费
        withTableConfig(ignoredFields = "transientField; remark") {
            val clazz = resolveEntity(
                """
                public class DeviceInfoEntity {
                    private Long id;
                    private String name;
                    private String transientField;
                    private String remark;
                }
                """.trimIndent(),
            )
            val table = EntityResolver().resolve(clazz)
            assertEquals(listOf("id", "name"), table.allColumnNames)
            assertNull(table.columnByField("transientField"))
            assertNull(table.columnByField("remark"))
        }
    }

    fun testIgnoredFieldsWhitespaceTolerant() {
        // 配置含空格/空项（`; ` 分隔、连续 `;;`）不影响匹配
        withTableConfig(ignoredFields = "transientField ;  ; remark") {
            val clazz = resolveEntity(
                """
                public class DeviceInfoEntity {
                    private Long id;
                    private String transientField;
                    private String remark;
                }
                """.trimIndent(),
            )
            assertEquals(listOf("id"), EntityResolver().resolve(clazz).allColumnNames)
        }
    }

    fun testIgnoredFieldsAnnotationExistFalseStillWins() {
        // M19.4-R3：@TableField(exist=false) 注解优先、忽略字段仅补充——两者并存不冲突
        withTableConfig(ignoredFields = "name") {
            val clazz = resolveEntity(
                """
                public class DeviceInfoEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
                    private String name;
                }
                """.trimIndent(),
            )
            assertEquals(listOf("id"), EntityResolver().resolve(clazz).allColumnNames)
        }
    }

    // ------------------------------------------------------------------
    // T-M19.4-03-③：统一修饰链（移除前缀/后缀首匹配 + 添加多值拼接，全局语义）
    // ------------------------------------------------------------------

    fun testRemoveTablePrefixSuffix() {
        // t_bike_device_log（SNAKE 推断，无 Entity 后缀类名贴近计划示例）→ 移除前缀 t_ →
        // bike_device_log → 移除后缀 _log → bike_device
        withTableConfig(removeTablePrefix = "t_", removeTableSuffix = "_log") {
            val clazz = resolveEntity(
                """
                public class TBikeDeviceLog {
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("bike_device", EntityResolver().resolve(clazz).tableName)
        }
    }

    fun testRemovePrefixNoMatchKeepsName() {
        // 前缀不匹配（`tmp_` vs `t_bike...`）→ 不修饰
        withTableConfig(removeTablePrefix = "tmp_") {
            val clazz = resolveEntity(
                """
                public class TBikeDeviceInfo {
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("t_bike_device_info", EntityResolver().resolve(clazz).tableName)
        }
    }

    fun testRemovePrefixCaseSensitive() {
        // M19.4-R2：移除大小写敏感——T_ 不匹配 t_ 开头
        withTableConfig(removeTablePrefix = "T_") {
            val clazz = resolveEntity(
                """
                public class TBikeDeviceInfo {
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("t_bike_device_info", EntityResolver().resolve(clazz).tableName)
        }
    }

    fun testAddPrefixSuffixSingleValueRemoveMultiValue() {
        // T-M19.4（用户反馈定稿）：添加表前缀/后缀为**单值**（不再 `;` 分隔拼接——`sys_` 原样添加）；
        // 移除前缀/后缀保持多值**首个匹配**（x_ 不匹配 t_ 开头，命中 t_；z_/_tmp 均不匹配后缀 → 不移除）
        withTableConfig(
            tablePrefix = "sys_",
            tableSuffix = "_log",
            removeTablePrefix = "x_;t_",
            removeTableSuffix = "z_;_tmp",
        ) {
            val clazz = resolveEntity(
                """
                public class TBikeDeviceInfo {
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("sys_bike_device_info_log", EntityResolver().resolve(clazz).tableName)
        }
    }

    fun testExplicitTableNameNotDecorated() {
        // T-M19.4（用户反馈：@TableName 应为最高优先级）：显式 @TableName 即最终表名——
        // **豁免修饰链**——`@TableName("t_device_info")` + 添加前缀 sys_ + 移除前缀 t_ → 仍为 t_device_info
        // （原 T-M16.2-03「全局语义」按用户反馈收窄：修饰链仅作用于推断表名）
        withTableConfig(tablePrefix = "sys_", removeTablePrefix = "t_") {
            val clazz = resolveEntity(
                """
                @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
                public class DeviceInfoEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            assertEquals("t_device_info", EntityResolver().resolve(clazz).tableName)
        }
    }

    // ------------------------------------------------------------------
    // T-TS-07：类名兜底推断的显式 project 取值口径
    // ------------------------------------------------------------------

    /**
     * 项目级 + 全局表配置同时设定并断言，finally 各自还原快照（两个服务都跨用例共享）。
     *
     * 与 [withTableConfig] 的区别：本夹具专测**显式传入 project** 的取值口径——
     * `inferDecoratedTableName(className, project)` 是 WrapperLocator / EntityClassFinder /
     * CriteriaQueryConverter 全部兜底调用方的统一入口，显式 project 决定读哪一份项目级表配置。
     */
    private fun withProjectTableConfig(
        useProjectSettings: Boolean,
        projectPrefix: String,
        globalPrefix: String = "",
        block: () -> Unit,
    ) {
        val global = PluginSettings.getInstance()
        val globalSnapshot = global.getState().copy()
        val proj = ProjectPluginSettings.getInstance(project)
        val projSnapshot = proj.getState().copy()
        try {
            // 推断策略固定 SNAKE（与 EntityResolver.inferDecoratedTableName 的全局默认一致），
            // 消除跨用例状态对表名基底的干扰
            global.state.tableNameInference = PluginSettings.INFERENCE_SNAKE
            global.state.tablePrefix = globalPrefix
            proj.state.useProjectSettings = useProjectSettings
            proj.state.tablePrefix = projectPrefix
            block()
        } finally {
            global.loadState(globalSnapshot)
            proj.loadState(projSnapshot)
        }
    }

    fun testInferDecoratedTableNameExplicitProjectHonorsProjectConfig() {
        // T-TS-07：显式传入 project → 按**该项目**的项目级表配置修饰（不再受「首个打开项目」影响）
        withProjectTableConfig(useProjectSettings = true, projectPrefix = "proj_") {
            assertEquals(
                "显式 project 应读该项目的项目级表配置（表前缀）",
                "proj_bike_device_info",
                EntityResolver.inferDecoratedTableName("BikeDeviceInfo", project),
            )
        }
        // 项目级总开关关闭 → 回退全局（显式 project 亦然，语义与 effectiveXxx 内置回退一致）
        withProjectTableConfig(useProjectSettings = false, projectPrefix = "proj_", globalPrefix = "g_") {
            assertEquals(
                "项目级未启用应回退全局前缀",
                "g_bike_device_info",
                EntityResolver.inferDecoratedTableName("BikeDeviceInfo", project),
            )
        }
    }
}
