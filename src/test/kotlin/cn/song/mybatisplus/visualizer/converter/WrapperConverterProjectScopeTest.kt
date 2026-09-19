package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.lang.reflect.Proxy

/**
 * T-FS-04：Wrapper 模型穿参——4 处 converter 兜底路径的**显式 project 取值口径**（平台用例）。
 *
 * 背景：`converter/WrapperConverter.kt`（[MpjJoinSupport.parseJoin]）与
 * `converter/MpjLambdaConverter.kt`（parseJoin / parseCollection / renderCollectionSubSelect）
 * 的类名兜底推断原先不传 project → `EntityResolver.effectiveTableSetting` 回退
 * `ProjectManager.openProjects.firstOrNull()`「首个打开项目」，多项目同开且项目级表配置
 * （前缀/后缀/推断策略）不同时**表名修饰取错项目**。T-FS-04 把 project 穿进 SPI
 * `WrapperConverter.convert(chain, table, project = null)` 并透传到这 4 处。
 *
 * 夹具形态：**两个项目 + 项目级配置不同**——
 *  - 真实测试项目（= `openProjects.firstOrNull()`，项目级前缀 `real_`）代表「首个打开项目」；
 *  - 两个接口代理项目（项目级前缀 `pa_` / `pb_`）代表「另一个同开的项目」。
 * 全局前缀 `g_` 三者皆不同，任一取值串味立即可见。
 *
 * 项目桩用 `java.lang.reflect.Proxy` 接口代理（本仓先例 `execute/CachedTempPsiFileTest.kt:35`；
 * 项目未引入 mock 框架），仅 `getService(ProjectPluginSettings::class.java)` 返回自持有的
 * 真实 [ProjectPluginSettings] 实例——本用例消费的正是 `ProjectPluginSettings.getInstance(project)`
 * 这一处平台服务查找。
 *
 * 未传路径（省略 project / 显式 null）逐字节不变：默认参数 null 使
 * `effectiveTableSetting` 的 null 分支（「首个打开项目」回退）原样生效，无新行为。
 */
class WrapperConverterProjectScopeTest : BasePlatformTestCase() {

    private var savedGlobalState: PluginSettings.State? = null
    private var savedProjectState: ProjectPluginSettings.State? = null

    override fun setUp() {
        super.setUp()
        savedGlobalState = PluginSettings.getInstance().getState().copy()
        savedProjectState = ProjectPluginSettings.getInstance(project).getState().copy()
        // 全局 = g_：显式 project 路径若串味到全局，断言即红
        PluginSettings.getInstance().loadState(
            PluginSettings.State().apply {
                tablePrefix = "g_"
                tableNameInference = PluginSettings.INFERENCE_SNAKE
            },
        )
        // 「首个打开项目」（= 本测试项目）= real_：未传 project 的既有语义锚点
        ProjectPluginSettings.getInstance(project).loadState(
            ProjectPluginSettings.State().apply {
                useProjectSettings = true
                tablePrefix = "real_"
                tableNameInference = PluginSettings.INFERENCE_SNAKE
            },
        )
    }

    override fun tearDown() {
        ProjectPluginSettings.getInstance(project).loadState(savedProjectState!!)
        PluginSettings.getInstance().loadState(savedGlobalState!!)
        super.tearDown()
    }

    // ------------------------------------------------------------------
    // 夹具：项目桩
    // ------------------------------------------------------------------

    /** 项目桩持有的项目级设置（前缀生效；推断策略固定 SNAKE 消除跨用例干扰）。 */
    private fun projectWithPrefix(name: String, prefix: String): Project {
        val settings = ProjectPluginSettings().apply {
            loadState(
                ProjectPluginSettings.State().apply {
                    useProjectSettings = true
                    tablePrefix = prefix
                    tableNameInference = PluginSettings.INFERENCE_SNAKE
                },
            )
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(Project::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getService" -> if (args?.getOrNull(0) == ProjectPluginSettings::class.java) settings else null
                "getName" -> name
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ProjectProxy($name)"
                else -> null
            }
        } as Project
    }

    private val projectA: Project by lazy { projectWithPrefix("projectA", "pa_") }
    private val projectB: Project by lazy { projectWithPrefix("projectB", "pb_") }

    private val table = TableInfo(
        "DeviceInfoEntity",
        "car_device_info",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("stationId", "station_id"),
        ),
    )

    // ------------------------------------------------------------------
    // 1. 取值口径基座：inferDecoratedTableName 的显式 project / 未传 project
    // ------------------------------------------------------------------

    fun testInferDecoratedTableNameExplicitProjectHonorsThatProject() {
        // 显式传 project → 按**该项目**的项目级表配置修饰；两个项目配置不同，产出表名分别正确
        assertEquals(
            "显式 projectA 应按 A 的项目级表前缀修饰",
            "pa_device_info_entity",
            EntityResolver.inferDecoratedTableName("DeviceInfoEntity", projectA),
        )
        assertEquals(
            "显式 projectB 应按 B 的项目级表前缀修饰",
            "pb_device_info_entity",
            EntityResolver.inferDecoratedTableName("DeviceInfoEntity", projectB),
        )
    }

    fun testInferDecoratedTableNameOmittedProjectKeepsFirstOpenProjectSemantics() {
        // 未传 project（省略）→ 沿用改动前的「首个打开项目」取值，不得串味到全局 g_
        assertEquals(
            "省略 project 应沿用「首个打开项目」（= 本测试项目 real_）的既有语义",
            "real_device_info_entity",
            EntityResolver.inferDecoratedTableName("DeviceInfoEntity"),
        )
        // 显式 null 与「显式传首个打开项目」等价（默认参数 null 的既有分支零改动）
        assertEquals(
            "显式传「首个打开项目」与省略 project 必须等价",
            EntityResolver.inferDecoratedTableName("DeviceInfoEntity"),
            EntityResolver.inferDecoratedTableName("DeviceInfoEntity", project),
        )
        assertEquals(
            "显式 null 与省略 project 必须等价",
            EntityResolver.inferDecoratedTableName("DeviceInfoEntity"),
            EntityResolver.inferDecoratedTableName("DeviceInfoEntity", null),
        )
    }

    // ------------------------------------------------------------------
    // 2. 4 处兜底路径：显式 project 决定表名修饰
    // ------------------------------------------------------------------

    /** `MpjJoinSupport.parseJoin`（WrapperConverter.kt:170）——MPJ 联表更新/删除的关联表类名兜底。 */
    fun testMpjJoinSupportFallbackHonorsExplicitProject() {
        val chain = WrapperChain(
            WrapperType.UPDATE_JOIN, "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "set",
                    listOf(
                        WrapperArg.ColumnArg(ColumnRef("station_id")),
                        WrapperArg.ValueArg(SqlValue.Placeholder("v")),
                    ),
                ),
                WrapperCall(
                    "leftJoin",
                    listOf(WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity.class"))),
                ),
            ),
        )
        val sqlA = SqlGenerator.generate(UpdateJoinConverter.INSTANCE.convert(chain, table, projectA))
        val sqlB = SqlGenerator.generate(UpdateJoinConverter.INSTANCE.convert(chain, table, projectB))
        assertTrue("关联表类名兜底应按 projectA 的项目级前缀修饰: $sqlA", sqlA.contains("LEFT JOIN pa_station_info_entity"))
        assertTrue("关联表类名兜底应按 projectB 的项目级前缀修饰: $sqlB", sqlB.contains("LEFT JOIN pb_station_info_entity"))
        assertFalse("两个项目的产出必须不同（否则口径未生效）", sqlA == sqlB)
        // 注册表路径与直调同源（生产链走 ConverterRegistry）
        assertEquals(
            "ConverterRegistry 分派路径应与直调逐字节一致",
            sqlB,
            SqlGenerator.generate(ConverterRegistry.INSTANCE.convert(chain, table, projectB)!!),
        )
    }

    /** `MpjLambdaConverter.parseJoin`（:493）——MPJ lambda 联表的关联表类名兜底。 */
    fun testMpjLambdaJoinFallbackHonorsExplicitProject() {
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "leftJoin",
                    listOf(WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity.class"))),
                ),
            ),
        )
        val sqlA = SqlGenerator.generate(MpjLambdaConverter.INSTANCE.convert(chain, table, projectA))
        val sqlB = SqlGenerator.generate(MpjLambdaConverter.INSTANCE.convert(chain, table, projectB))
        assertTrue("关联表类名兜底应按 projectA 修饰: $sqlA", sqlA.contains("LEFT JOIN pa_station_info_entity"))
        assertTrue("关联表类名兜底应按 projectB 修饰: $sqlB", sqlB.contains("LEFT JOIN pb_station_info_entity"))
    }

    /** `MpjLambdaConverter.parseCollection`（:878）——selectCollection 子表类名兜底。 */
    fun testMpjLambdaCollectionChildFallbackHonorsExplicitProject() {
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "selectCollection",
                    listOf(
                        WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity")),
                        WrapperArg.AliasArg("stations"),
                    ),
                ),
            ),
        )
        val sqlA = SqlGenerator.generate(MpjLambdaConverter.INSTANCE.convert(chain, table, projectA))
        val sqlB = SqlGenerator.generate(MpjLambdaConverter.INSTANCE.convert(chain, table, projectB))
        assertTrue("collection 子表类名兜底应按 projectA 修饰: $sqlA", sqlA.contains("FROM pa_station_info_entity"))
        assertTrue("collection 子表类名兜底应按 projectB 修饰: $sqlB", sqlB.contains("FROM pb_station_info_entity"))
    }

    /** `MpjLambdaConverter.renderCollectionSubSelect`（:933）——嵌套 collection 的孙表类名兜底。 */
    fun testMpjLambdaNestedCollectionGrandChildFallbackHonorsExplicitProject() {
        val mapping = WrapperChain(
            WrapperType.NESTED, "StationInfoEntity",
            listOf(
                WrapperCall(
                    "collection",
                    listOf(WrapperArg.ValueArg(SqlValue.Literal("MerchantInfoEntity"))),
                ),
            ),
        )
        val chain = WrapperChain(
            WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
            listOf(
                WrapperCall(
                    "selectCollection",
                    listOf(
                        WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity")),
                        WrapperArg.AliasArg("stations"),
                        WrapperArg.NestedArg(mapping),
                    ),
                ),
            ),
        )
        val sqlA = SqlGenerator.generate(MpjLambdaConverter.INSTANCE.convert(chain, table, projectA))
        val sqlB = SqlGenerator.generate(MpjLambdaConverter.INSTANCE.convert(chain, table, projectB))
        assertTrue("嵌套 collection 孙表类名兜底应按 projectA 修饰: $sqlA", sqlA.contains("FROM pa_merchant_info_entity"))
        assertTrue("嵌套 collection 孙表类名兜底应按 projectB 修饰: $sqlB", sqlB.contains("FROM pb_merchant_info_entity"))
    }

    // ------------------------------------------------------------------
    // 3. 未传路径逐字节不变（对齐 T-TS-07 自证口径：默认参数 null = 原行为）
    //
    // 定位说明（T-FS-04 独立复核指出）：本用例是「未传路径**不回归**」的守卫，**不是**「project 参数
    // 生效」的证据——它显式传的 `explicitFirstOpenProject` 恰是 null 回退本就会选中的同一个项目，
    // 故若实现整体忽略该参数，本例照样绿。区分力由上面 1./2. 段的 pa_/pb_ 用例承担（双项目 +
    // 两套项目级前缀 + `assertFalse(sqlA == sqlB)`）。引用本例论证「穿参生效」属误用。
    // ------------------------------------------------------------------

    fun testOmittedProjectPathByteIdenticalToBefore() {
        // 4 条兜底链逐条断言：省略 project ≡ 显式 null ≡ 显式「首个打开项目」，
        // 且产出仍按「首个打开项目」（real_）修饰——即改动前行为逐字节保持
        assertOmittedProjectPathUnchanged(
            omitted = { MpjLambdaConverter.INSTANCE.convert(joinChain(), table) },
            explicitNull = { MpjLambdaConverter.INSTANCE.convert(joinChain(), table, null) },
            explicitFirstOpenProject = { MpjLambdaConverter.INSTANCE.convert(joinChain(), table, project) },
        )
        assertOmittedProjectPathUnchanged(
            omitted = { MpjLambdaConverter.INSTANCE.convert(collectionChain(), table) },
            explicitNull = { MpjLambdaConverter.INSTANCE.convert(collectionChain(), table, null) },
            explicitFirstOpenProject = { MpjLambdaConverter.INSTANCE.convert(collectionChain(), table, project) },
        )
        assertOmittedProjectPathUnchanged(
            omitted = { MpjLambdaConverter.INSTANCE.convert(nestedCollectionChain(), table) },
            explicitNull = { MpjLambdaConverter.INSTANCE.convert(nestedCollectionChain(), table, null) },
            explicitFirstOpenProject = { MpjLambdaConverter.INSTANCE.convert(nestedCollectionChain(), table, project) },
        )
        assertOmittedProjectPathUnchanged(
            omitted = { UpdateJoinConverter.INSTANCE.convert(updateJoinChain(), table) },
            explicitNull = { UpdateJoinConverter.INSTANCE.convert(updateJoinChain(), table, null) },
            explicitFirstOpenProject = { UpdateJoinConverter.INSTANCE.convert(updateJoinChain(), table, project) },
        )
    }

    /**
     * 省略 project（2 参调用，走 SPI 默认值 null）与显式 null、显式「首个打开项目」
     * 三者必须逐字节一致，且兜底表名仍取「首个打开项目」（real_）的项目级配置。
     */
    private fun assertOmittedProjectPathUnchanged(
        omitted: () -> SqlStatement,
        explicitNull: () -> SqlStatement,
        explicitFirstOpenProject: () -> SqlStatement,
    ) {
        val omittedSql = SqlGenerator.generate(omitted())
        assertEquals(
            "显式 null 与省略 project 必须逐字节一致",
            omittedSql,
            SqlGenerator.generate(explicitNull()),
        )
        assertEquals(
            "省略 project 必须仍取「首个打开项目」的既有语义（与显式传首个打开项目等价）",
            omittedSql,
            SqlGenerator.generate(explicitFirstOpenProject()),
        )
        assertTrue("兜底表名应按「首个打开项目」（real_）修饰: $omittedSql", omittedSql.contains("real_"))
        assertFalse("未传路径不得取显式 projectB 的配置: $omittedSql", omittedSql.contains("pb_"))
    }

    // ------------------------------------------------------------------
    // 链构造（4 处兜底路径的**类名兜底形态**：类参为 ValueArg 字面量，无 EntityRefArg）
    // ------------------------------------------------------------------

    private fun joinChain(): WrapperChain = WrapperChain(
        WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
        listOf(
            WrapperCall(
                "leftJoin",
                listOf(WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity.class"))),
            ),
        ),
    )

    private fun collectionChain(): WrapperChain = WrapperChain(
        WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
        listOf(
            WrapperCall(
                "selectCollection",
                listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity")),
                    WrapperArg.AliasArg("stations"),
                ),
            ),
        ),
    )

    private fun nestedCollectionChain(): WrapperChain = WrapperChain(
        WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
        listOf(
            WrapperCall(
                "selectCollection",
                listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity")),
                    WrapperArg.AliasArg("stations"),
                    WrapperArg.NestedArg(
                        WrapperChain(
                            WrapperType.NESTED, "StationInfoEntity",
                            listOf(
                                WrapperCall(
                                    "collection",
                                    listOf(WrapperArg.ValueArg(SqlValue.Literal("MerchantInfoEntity"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun updateJoinChain(): WrapperChain = WrapperChain(
        WrapperType.UPDATE_JOIN, "DeviceInfoEntity",
        listOf(
            WrapperCall(
                "set",
                listOf(
                    WrapperArg.ColumnArg(ColumnRef("station_id")),
                    WrapperArg.ValueArg(SqlValue.Placeholder("v")),
                ),
            ),
            WrapperCall(
                "leftJoin",
                listOf(WrapperArg.ValueArg(SqlValue.Literal("StationInfoEntity.class"))),
            ),
        ),
    )
}
