package cn.song.mybatisplus.visualizer.sql

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
            panel.contains("moduleKey = sourceContext?.canonicalModuleKey"))
    }
}
