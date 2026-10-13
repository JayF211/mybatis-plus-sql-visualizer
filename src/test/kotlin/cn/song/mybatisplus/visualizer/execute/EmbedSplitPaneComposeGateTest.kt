package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★A49：整控台 SplitPane 组入判定 [shouldComposeEmbedSplitPane] 测试。
 *
 * 背景（本轮录制沙盒实测 + 截图）：SQL 快捷模板三动作 / 日志转 SQL 的「执行」都不传嵌入宿主
 * （`SqlExecutor.execute(onConsole = null)`），只把语句送原生 Database 控制台执行，控制台由
 * DBN 在 IDE 底部 Services 视图展示。此前 prepare 链**无条件** `buildConsoleSplitPane`，构造
 * JSplitPane 会把 DBN 结果区组件（`ui.getComponent()`）从原生宿主 `ServiceViewComponentWrapper`
 * 摘进一个永不展示的容器（onConsole 为 null、pendingOnConsole 也为 null 故无人回收）→ 原生
 * 控制台结果网格停在 "0 rows of 0+" 不渲染（★A48 家族的另一条路径）。本测试锁定「无宿主不组
 * 不摘」这一判据，并锁定有宿主（预览面板）时既有组入行为不回退。
 *
 * 该判据同时把守**两个组入点**：`prepareAfterEditorResolved`（全链首执，组 SplitPane）与
 * `reuseSharedConsole`（复用执行，swap 重摘）——两处都用 [shouldComposeEmbedSplitPane]，缺任一处
 * 都会让结果组件被摘进永不展示的容器。
 */
class EmbedSplitPaneComposeGateTest : BasePlatformTestCase() {

    /** 无嵌入宿主（onConsole=null）——不得组 SplitPane、不得摘 DBN 结果组件。 */
    @Test
    fun testNoEmbedHostDoesNotCompose() {
        assertFalse(
            "无嵌入宿主（SQL 快捷模板 / 日志转 SQL 执行链）时不得组 SplitPane 摘走 DBN 结果组件",
            shouldComposeEmbedSplitPane(hasEmbedHost = false, hasResultComponent = true),
        )
    }

    /** 结果区组件取不到（DBN 尚未建出）——无从组入。 */
    @Test
    fun testNoResultComponentDoesNotCompose() {
        assertFalse(
            "结果区组件缺席时无从组入",
            shouldComposeEmbedSplitPane(hasEmbedHost = true, hasResultComponent = false),
        )
    }

    /** 有嵌入宿主（预览面板）且结果区组件在场——必须组入（既有行为不回退）。 */
    @Test
    fun testEmbedHostWithResultComposes() {
        assertTrue(
            "有嵌入宿主且结果区组件在场时必须组入（预览路径不回退）",
            shouldComposeEmbedSplitPane(hasEmbedHost = true, hasResultComponent = true),
        )
    }
}
