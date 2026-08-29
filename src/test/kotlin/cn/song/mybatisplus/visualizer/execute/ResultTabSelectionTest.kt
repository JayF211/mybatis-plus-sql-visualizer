package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 结果 Tab 选择判定测试（M36.5.22b）。
 *
 * 背景：沙盒实测（17:25 会话）暴露「执行后显示上一次结果」——DBN 产生新结果时**复用同一个
 * Content 实例**并改名（Result 1 → Result 2），此前按实例身份过滤的新旧判定失效，
 * `retry#1 contents=[Output, Result 2], select=null`。
 *
 * 本测试用动态代理精确复现该行为（同一实例、displayName 变更），锁定按 displayName
 * 判定的修复不再回归。判定逻辑已抽为纯函数 [DataSourceSelector.pickResultTab]，
 * 不依赖 Database 插件运行时（单测沙盒无 DBN，计划文档 M36.5.20 已记录该边界）。
 */
class ResultTabSelectionTest : BasePlatformTestCase() {

    /**
     * 可改名的 Content 代理——模拟 DBN 复用实例改名的真实行为。
     * equals/hashCode 保持实例身份语义（与真实 Content 一致），确保测试
     * 锁定的是"名称判定"而非实例判定。
     */
    private class FakeContent(initialName: String) {
        var displayName: String = initialName

        val proxy: Content = Proxy.newProxyInstance(
            FakeContent::class.java.classLoader,
            arrayOf(Content::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getDisplayName" -> displayName
                "setDisplayName" -> {
                    displayName = args?.get(0) as String
                    null
                }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeContent($displayName)"
                else -> null
            }
        } as Content
    }

    /** 反射 getContents() 的假 RunnerLayoutUi（resultContents 经反射调用）。 */
    private class FakeUi(private val tabs: Array<Content>) {
        fun getContents(): Array<Content> = tabs
    }

    // ==================== 场景一：实例复用改名（M36.5.22b 回归核心） ====================

    /**
     * DBN 复用同一个 Content 实例并改名：快照时是 Result 1，提交后变为 Result 2。
     * 必须第 1 拍即选中（此前实例身份过滤会返回 null → 滞后一轮）。
     */
    @Test
    fun testRenamedContentInstancePickedImmediately() {
        val reused = FakeContent("Result 1")
        val preExisting = setOf("Result 1") // 提交时刻的名称快照
        reused.displayName = "Result 2" // DBN 复用实例改名（沙盒 17:25 实证行为）

        val picked = DataSourceSelector.pickResultTab(arrayOf(reused.proxy), preExisting, attempts = 1)

        assertEquals("改名后的实例应第 1 拍即选中（滞后一轮回归点）", "Result 2", picked?.displayName)
    }

    /** 同上场景，但通过 resultContents 快照 + rename 验证完整判定链（名称快照来源）。 */
    @Test
    fun testSnapshotThenRenameFullChain() {
        val reused = FakeContent("Result 1")
        val ui = FakeUi(arrayOf(reused.proxy))
        val preExistingNames = DataSourceSelector.resultContents(ui).map { it.displayName }.toSet()
        assertEquals(setOf("Result 1"), preExistingNames)

        reused.displayName = "Result 2" // 执行后 DBN 改名
        val picked = DataSourceSelector.pickResultTab(
            DataSourceSelector.resultContents(ui).toTypedArray(),
            preExistingNames,
            attempts = 1,
        )
        assertEquals("Result 2", picked?.displayName)
    }

    // ==================== 场景二：新 Tab 新名称 ====================

    @Test
    fun testNewTabNameWinsImmediately() {
        val old = FakeContent("Result 1").proxy
        val newTab = FakeContent("Result 2").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(old, newTab), setOf("Result 1"), attempts = 1)
        assertEquals("Result 2", picked?.displayName)
    }

    @Test
    fun testMultipleNewTabsPicksFirst() {
        val a = FakeContent("Result 2").proxy
        val b = FakeContent("Result 3").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(a, b), setOf("Result 1"), attempts = 1)
        assertEquals("Result 2", picked?.displayName)
    }

    // ==================== 场景三：旧 Tab 复用阈值（M36.5.22 防抢跑） ====================

    /** 只有旧名称 Tab 时，阈值前不得选中——否则把上一轮结果顶进面板（滞后一轮真因）。 */
    @Test
    fun testStaleTabNotPickedBeforeThreshold() {
        val stale = FakeContent("Result 1").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(stale), setOf("Result 1"), attempts = 14)
        assertNull("阈值前不应选中旧 Tab（防旧结果抢跑）", picked)
    }

    /** 阈值后接受复用（DBN 可能不建新 Tab；打点 result tab reused，避免永远等待）。 */
    @Test
    fun testStaleTabAcceptedAtThreshold() {
        val stale = FakeContent("Result 1").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(stale), setOf("Result 1"), attempts = 15)
        assertEquals("Result 1", picked?.displayName)
    }

    // ==================== 场景四：Output 过滤 ====================

    @Test
    fun testOutputTabExcluded() {
        val output = FakeContent("Output").proxy
        val result = FakeContent("Result 1").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(output, result), emptySet(), attempts = 1)
        assertEquals("Result 1", picked?.displayName)
    }

    @Test
    fun testChineseOutputTabExcluded() {
        val output = FakeContent("输出").proxy
        val result = FakeContent("Result 1").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(output, result), emptySet(), attempts = 1)
        assertEquals("Result 1", picked?.displayName)
    }

    @Test
    fun testOnlyOutputYieldsNull() {
        val output = FakeContent("Output").proxy
        assertNull(DataSourceSelector.pickResultTab(arrayOf(output), emptySet(), attempts = 40))
    }

    @Test
    fun testEmptyContentsYieldsNull() {
        assertNull(DataSourceSelector.pickResultTab(emptyArray(), emptySet(), attempts = 40))
    }

    // ==================== resultContents 反射读取 ====================

    @Test
    fun testResultContentsFiltersOutputTabs() {
        val output = FakeContent("Output").proxy
        val outputCn = FakeContent("输出").proxy
        val result = FakeContent("Result 1").proxy
        val list = DataSourceSelector.resultContents(FakeUi(arrayOf(output, outputCn, result)))
        assertEquals(listOf("Result 1"), list.map { it.displayName })
    }

    @Test
    fun testResultContentsNullUiYieldsEmpty() {
        assertTrue(DataSourceSelector.resultContents(null).isEmpty())
    }

    @Test
    fun testResultContentsNonReflectiveUiYieldsEmpty() {
        // 无 getContents 方法的对象：反射失败 → 空列表（不抛异常）
        assertTrue(DataSourceSelector.resultContents(Any()).isEmpty())
    }
}
