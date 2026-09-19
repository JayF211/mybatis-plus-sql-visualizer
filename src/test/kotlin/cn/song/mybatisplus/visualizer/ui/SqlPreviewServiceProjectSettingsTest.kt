package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-SettingsTruth P4：项目级设置覆盖预览验证——
 *  - 项目级开启 + replaceParams=false → 预览保留 ? 占位符（项目级覆盖全局 true）
 *  - 项目级关闭 → 回退全局 replaceParams=true → 占位符被替换
 *  - 项目级表前缀覆盖：开启 + tablePrefix → 表名带项目前缀；关闭 → 回退全局（无前缀）
 */
class SqlPreviewServiceProjectSettingsTest : BasePlatformTestCase() {

    private var savedProjectState: ProjectPluginSettings.State? = null
    private var savedGlobalState: PluginSettings.State? = null

    override fun setUp() {
        super.setUp()
        savedProjectState = ProjectPluginSettings.getInstance(project).state.copy()
        savedGlobalState = PluginSettings.getInstance().state.copy()
    }

    override fun tearDown() {
        ProjectPluginSettings.getInstance(project).loadState(savedProjectState!!)
        PluginSettings.getInstance().loadState(savedGlobalState!!)
        super.tearDown()
    }

    private fun newService() = SqlPreviewService(project)

    private fun anchor(): PsiElement {
        val methodCall = PsiTreeUtil.findChildOfType(myFixture.file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        val variable = PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(myFixture.file)
    }

    private fun configureWrapper() {
        myFixture.configureByText("Demo.java", demoFile)
    }

    private val demoFile get() = """
        import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

        public class Demo {
            public void query() {
                LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                    .eq(DeviceInfoEntity::getId, 1001L);
            }

            static class DeviceInfoEntity { private Long id; }
        }
        """.trimIndent()

    fun test项目级开启_replaceParamsFalse_覆盖全局保留占位符() {
        // 全局 replaceParams=true；项目级开启且 replaceParams=false → 预览保留 ?
        // 注意锚点须选 eq(1001L) 的方法调用：字面量常量恒内联是 ArgResolver 既有语义
        // （M14.6 起字面量不受开关影响，开关只拦变量追踪），断言须用变量实参面。
        // 原实现直读全局变量已删除？——否：本用例变量锚点取 PsiMethodCallExpression
        // （eq 调用本身），变量求值链路经 WrapperLocator.resolveVariables 门。
        PluginSettings.getInstance().state.replaceParams = true
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = true
        ps.replaceParams = false

        val demo = """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    long pid = 1001L;
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, pid);
                }

                static class DeviceInfoEntity { private Long id; }
            }
        """.trimIndent()
        myFixture.configureByText("Demo.java", demo)
        // 锚点=eq 调用（PsiMethodCallExpression 首个即 eq；实体类声明在文件后部无方法调用干扰）
        val eqCall = PsiTreeUtil.findChildOfType(myFixture.file, PsiMethodCallExpression::class.java)!!
        // 每次新建 service 实例现读现生效（项目级 apply 后无需广播）
        val result = newService().preview(eqCall)!!
        assertTrue("项目级 replaceParams=false 应保留 ? 占位符: ${result.sql}", result.sql.contains("?"))
        assertTrue("不应出现全局替换后的 1001: ${result.sql}", !result.sql.contains("1001"))
    }

    fun test项目级关闭_回退全局_占位符被替换() {
        PluginSettings.getInstance().state.replaceParams = true
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = false
        ps.replaceParams = false // 关闭时项目值不生效

        val demo = """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    long pid = 1001L;
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, pid);
                }

                static class DeviceInfoEntity { private Long id; }
            }
        """.trimIndent()
        myFixture.configureByText("Demo.java", demo)
        val eqCall = PsiTreeUtil.findChildOfType(myFixture.file, PsiMethodCallExpression::class.java)!!
        val result = newService().preview(eqCall)!!
        assertTrue("项目级关闭应回退全局 true（占位符被替换）: ${result.sql}", result.sql.contains("1001"))
    }

    fun test项目级表前缀覆盖与关闭回退() {
        PluginSettings.getInstance().state.tablePrefix = ""
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = true
        ps.tablePrefix = "p_"

        myFixture.configureByText("Demo.java", demoFile)
        // 同一文件内切换项目配置 → 缓存签名含项目维，必须失效重算
        val service = newService()
        assertEquals(
            "项目级表前缀应覆盖全局",
            "SELECT id FROM p_device_info_entity WHERE id = 1001",
            service.preview(anchor())!!.compactSql.trim(),
        )

        // 关闭项目设置 → 回退全局（无前缀），缓存签名同步回退
        ps.useProjectSettings = false
        assertEquals(
            "项目级关闭应回退全局（无前缀）",
            "SELECT id FROM device_info_entity WHERE id = 1001",
            service.preview(anchor())!!.compactSql.trim(),
        )
    }

    fun test项目级表前缀修改_同文件缓存失效() {
        PluginSettings.getInstance().state.tablePrefix = ""
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = true
        ps.tablePrefix = ""

        myFixture.configureByText("Demo.java", demoFile)
        val service = newService()
        assertEquals(
            "SELECT id FROM device_info_entity WHERE id = 1001",
            service.preview(anchor())!!.compactSql.trim(),
        )

        // 项目级前缀变更（同文件同 service）→ TableConfigSignature 项目维失效 → 反映新配置
        ps.tablePrefix = "sys_"
        assertEquals(
            "项目级表配置变更后预览必须更新",
            "SELECT id FROM sys_device_info_entity WHERE id = 1001",
            service.preview(anchor())!!.compactSql.trim(),
        )
    }

    // ------------------------------------------------------------------
    // T-TS-07：类名兜底路径的显式 project 取值口径
    // ------------------------------------------------------------------

    /**
     * 实体类**不可解析**（`UnknownEntity` 未声明）→ 表名走类名兜底推断——
     * 该路径原先不传 project（取「首个打开项目」），T-TS-07 改为传宿主 PSI 所属项目。
     *
     * 单项目夹具无法区分「本项目」与「首个打开项目」（二者同指本测试项目），故本用例钉的是
     * **兜底路径确实读项目级表配置**（传参链未断）；多项目区分能力见验收留档。
     */
    fun test类名兜底推断_按项目级表前缀修饰() {
        PluginSettings.getInstance().state.tablePrefix = ""
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = true
        ps.tablePrefix = "proj_"

        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<UnknownEntity> w = new LambdaQueryWrapper<UnknownEntity>()
                        .eq(UnknownEntity::getId, 1001L);
                }
            }
            """.trimIndent(),
        )
        val sql = newService().preview(anchor())!!.compactSql
        assertTrue(
            "类名兜底推断（实体类不可解析）应按项目级表前缀修饰表名: $sql",
            sql.contains("FROM proj_unknown_entity"),
        )
    }

    // ------------------------------------------------------------------
    // T-TS-FixC：方言模式（auto/manual）四组合矩阵 —— 项目级 auto × 全局 auto
    // ------------------------------------------------------------------

    /**
     * 测试环境的推断链必然未命中：无 agent 会话（`AgentManager.lastConnectionUrl` 为 null）、
     * 无 IDE 数据源（DatabaseTools 不在测试类路径 → `DataSourceUrlProbe.firstUrl` 为 null），
     * 故 auto=true 的期望值 = `byName(null)` = MySQL。这样「推断生效」与「手动值生效」在断言上
     * 可区分（全局手动值刻意设为 PostgreSQL，与 MySQL 不同）。
     */
    private val inferredFallback = DialectRegistry.INSTANCE.byName(null)

    /**
     * 核心缺陷用例（T-TS-FixC）：项目级 auto=true + 全局 auto=false。
     *
     * 修复前 `DialectRegistry.effective` 只读全局 `PluginSettings.dialectAuto`，而
     * `SqlPreviewService.currentDialect()` 已按项目级判为 auto → 进入 effective 后又被全局
     * false 拉回手动分支，返回全局手动值 PostgreSQL——项目级「智能推断」被静默忽略。
     */
    fun test项目级auto为真_全局auto为假_项目级推断必须生效() {
        val global = PluginSettings.getInstance().state
        global.dialectAuto = false
        global.dialect = "PostgreSQL"
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = true
        ps.dialectAuto = true
        // 项目级 dialect 仅在项目显式关闭自身 auto 时生效（见 State.dialectAuto KDoc）——
        // 此处非空 Oracle 属「不生效的残值」，不得被 auto 分支读走
        ps.dialect = "Oracle"

        assertEquals(
            "项目级 auto=true 应压过全局 auto=false（推断未命中 → MySQL 回退），不得返回全局手动值",
            inferredFallback,
            newService().currentDialect(),
        )
    }

    /** 项目级 auto=false + 全局 auto=true → 项目手动值（项目级显式关闭压过全局推断）。 */
    fun test项目级auto为假_全局auto为真_项目手动方言生效() {
        val global = PluginSettings.getInstance().state
        global.dialectAuto = true
        global.dialect = "PostgreSQL"
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = true
        ps.dialectAuto = false
        ps.dialect = "Oracle"

        assertEquals("项目级手动值应生效", OracleDialect.INSTANCE, newService().currentDialect())
    }

    /** 项目未覆盖 auto（null）+ 全局 auto=false → 跟随全局手动模式，手动值取项目级 effectiveDialect。 */
    fun test项目未覆盖auto_跟随全局为假_项目手动方言值生效() {
        val global = PluginSettings.getInstance().state
        global.dialectAuto = false
        global.dialect = "PostgreSQL"
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = true
        ps.dialectAuto = null
        ps.dialect = "Oracle"

        assertEquals(
            "项目未覆盖 auto → 跟随全局 false；手动值取项目级 dialect（非全局 PostgreSQL）",
            OracleDialect.INSTANCE,
            newService().currentDialect(),
        )
    }

    /** 总开关关闭 + 全局 auto=false → 项目级全不生效，取全局手动值。 */
    fun test总开关关闭_全局auto为假_取全局手动值() {
        val global = PluginSettings.getInstance().state
        global.dialectAuto = false
        global.dialect = "PostgreSQL"
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = false
        ps.dialectAuto = true // 总开关关闭 → 残值不生效
        ps.dialect = "Oracle"

        assertEquals("总开关关闭应回退全局手动值", PostgreSQLDialect.INSTANCE, newService().currentDialect())
    }

    /** 项目与全局均 auto=true → 推断链生效（未命中 → MySQL 回退），全局手动值不得泄漏。 */
    fun test项目与全局auto均为真_推断回退MySQL() {
        val global = PluginSettings.getInstance().state
        global.dialectAuto = true
        global.dialect = "PostgreSQL"
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = false
        ps.dialect = "Oracle"

        assertEquals("auto=true 应走推断链（未命中回退 MySQL）", inferredFallback, newService().currentDialect())
    }
}
