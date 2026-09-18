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
        PluginSettings.getInstance().state.replaceParams = true
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = true
        ps.replaceParams = false

        myFixture.configureByText("Demo.java", demoFile)
        // 每次新建 service 实例现读现生效（项目级 apply 后无需广播）
        val result = newService().preview(anchor())!!
        assertTrue("项目级 replaceParams=false 应保留 ? 占位符: ${result.sql}", result.sql.contains("?"))
        assertTrue("不应出现全局替换后的 1001: ${result.sql}", !result.sql.contains("1001"))
    }

    fun test项目级关闭_回退全局_占位符被替换() {
        PluginSettings.getInstance().state.replaceParams = true
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = false
        ps.replaceParams = false // 关闭时项目值不生效

        myFixture.configureByText("Demo.java", demoFile)
        val result = newService().preview(anchor())!!
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
}
