package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
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
}
