package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.context.ModuleIdentity
import cn.song.mybatisplus.visualizer.context.ModuleIdentityResolution
import cn.song.mybatisplus.visualizer.context.ModuleKeySource
import cn.song.mybatisplus.visualizer.context.PreviewSourceContext
import cn.song.mybatisplus.visualizer.context.PreviewSourceOrigin
import com.intellij.openapi.module.ModuleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class ModuleDataSourceBindingStoreTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        ModuleDataSourceBindingStore.getInstance(project).clear()
    }

    override fun tearDown() {
        ModuleDataSourceBindingStore.getInstance(project).clear()
        super.tearDown()
    }

    fun testRememberForgetAndClearUseStableDatasourceIdsOnly() {
        val store = ModuleDataSourceBindingStore.getInstance(project)
        store.remember("gradle:app:/repo/app", "uuid-1")
        store.remember("gradle:api:/repo/api", "uuid-2")
        assertEquals("uuid-1", store.dataSourceId("gradle:app:/repo/app"))
        assertFalse(store.state.bindings.values.any { it.contains("jdbc:") })
        store.forget("gradle:app:/repo/app")
        assertNull(store.dataSourceId("gradle:app:/repo/app"))
        store.clear()
        assertTrue(store.state.bindings.isEmpty())
    }

    fun testLearnedUuidOverridesConflictingProjectCandidatesAndClearRestoresAmbiguity() {
        val settings = PluginSettings.getInstance()
        val globalSnapshot = settings.state.copy()
        val projectSettings = ProjectPluginSettings.getInstance(project)
        val projectSnapshot = projectSettings.state.copy()
        val store = ModuleDataSourceBindingStore.getInstance(project)
        class StubDs(var jdbcUrl: String) {
            @Suppress("unused") fun getUrl(): String = jdbcUrl
        }
        val ds = StubDs("jdbc:postgresql://localhost/app")
        val key = "gradle:app:/repo/app"
        try {
            settings.state.dialectAuto = true
            projectSettings.state.useProjectSettings = false
            DataSourceUrlProbe.probeCandidatesForTest = {
                listOf(DialectDecisionEngine.Candidate("mysql", "MySQL"),
                    DialectDecisionEngine.Candidate("oracle", "Oracle"))
            }
            DataSourceUrlProbe.invalidate(project)
            ModuleDataSourceBindingStore.dataSourceByIdForTest = { p, id ->
                if (p === project && id == "uuid-1") ds else null
            }
            val file = myFixture.addFileToProject("app/src/User.java", "class User {}")
            val module = ModuleManager.getInstance(project).modules.single()
            val context = PreviewSourceContext(
                project = project,
                origin = PreviewSourceOrigin.PSI,
                hostPsiFile = file,
                moduleResolution = ModuleIdentityResolution.Unique(
                    ModuleIdentity(module, key, ModuleKeySource.EXTERNAL_SYSTEM_ID)),
            )
            assertEquals(DialectDecisionEngine.Status.AMBIGUOUS, DialectRegistry.decision(context).status)
            store.remember(key, "uuid-1")
            val learned = DialectRegistry.decision(context)
            assertEquals(DialectDecisionEngine.Source.LEARNED_MODULE, learned.source)
            assertEquals("PostgreSQL", learned.dialectName)
            assertEquals(listOf("uuid-1"), learned.dataSourceIds)
            ds.jdbcUrl = "jdbc:oracle:thin:@localhost:1521/app"
            assertEquals("Oracle", DialectRegistry.decision(context).dialectName)
            store.clear()
            assertEquals(DialectDecisionEngine.Status.AMBIGUOUS, DialectRegistry.decision(context).status)
            store.remember(key, "deleted-uuid")
            assertEquals(DialectDecisionEngine.Status.AMBIGUOUS, DialectRegistry.decision(context).status)
            assertNull("被删除数据源的学习键必须失效", store.dataSourceId(key))
        } finally {
            ModuleDataSourceBindingStore.dataSourceByIdForTest = null
            DataSourceUrlProbe.probeCandidatesForTest = null
            DataSourceUrlProbe.invalidate(project)
            store.clear()
            settings.loadState(globalSnapshot)
            projectSettings.loadState(projectSnapshot)
        }
    }

    fun testExecutionLearnsOnlyAfterFinalConsoleGateAllows() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "src/main/kotlin").isDirectory }
        val console = File(root,
            "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt").readText()
        val gate = console.indexOf("if (!allowDialectGate")
        val remember = console.indexOf(".remember(moduleKey, dataSourceId)", gate)
        val mutation = console.indexOf("wholeScriptExecOption", gate)
        assertTrue("模块绑定必须在最终 console 闸放行后调用 remember", gate >= 0 && remember > gate)
        assertTrue("模块学习必须早于提交链，但不得发生在 session prepare", remember in (gate + 1) until mutation)
        val panel = File(root,
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt").readText()
        assertTrue("面板执行请求须携带当前来源模块稳定键",
            panel.contains("moduleKey = sourceModuleKey"))
    }
}
