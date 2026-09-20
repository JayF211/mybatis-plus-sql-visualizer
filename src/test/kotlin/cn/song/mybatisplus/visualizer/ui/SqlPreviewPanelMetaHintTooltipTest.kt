package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-SourceJumpFunnel P6 回归守护：参数导入「空剪贴板」行内提示态**不得丢掉 meta 行 tooltip**。
 *
 * 缺陷形态：`showImportResult` 的空剪贴板分支旧写法 `metaLabel.toolTipText = hint` 把
 * [SqlPreviewPanel.updateMeta] 组装的 meta tooltip **整个覆写**掉了——而该 tooltip 是「悬停见完整
 * 调用链」（§3.3 验收项后半句）的唯一载体（可见 meta 行受 F3 宽度预算截断），于是提示态一挂起，
 * 悬停就只剩提示本身，完整链丢失；且提示文案本就在可见行上全文可见，覆写并未换来任何信息。
 *
 * 修复：提示态 tooltip 由「覆写」改「拼接」（提示首行 + `updateMeta` 缓存的基线 metaTooltipBase），
 * 基线取自缓存字段而非当前 tooltip，故重复触发幂等（不叠加自身）。
 *
 * 可测性：该提示是**纯行内状态更新**（不弹任何窗），故已从 `showImportResult` 的「测试模式跳过弹窗」
 * 闸门之前分离出来（闸门只为避开 Messages/TestDialog 拦截器），本用例据此可在 headless 下驱动。
 */
class SqlPreviewPanelMetaHintTooltipTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun hint(): String = MpSqlVisualizerBundle.message("panel.param.import.result.empty")

    private fun stackLabel(): String = MpSqlVisualizerBundle.message("panel.meta.stackDigest")

    private fun makeResult(stackDigest: String) = PreviewResult(
        sql = "SELECT id\nFROM user\nWHERE username = ?",
        compactSql = "SELECT id FROM user WHERE username = ?",
        unresolvedParams = listOf("username"),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "user",
        columns = listOf("id", "username"),
        stackDigest = stackDigest,
    )

    private fun chain(): String {
        val unit = "com.corp.biz.Svc#m:1 <- "
        val sb = StringBuilder()
        while (sb.length < 200) sb.append(unit)
        return sb.substring(0, 200)
    }

    fun test空剪贴板提示_可见行显提示且tooltip保留完整调用链() {
        val panel = SqlPreviewPanel(project)
        try {
            val digest = chain()
            panel.showResult(makeResult(digest))
            waitForEdt()
            val baseTooltip = panel.metaTooltipForTest() ?: ""
            assertTrue("前提：预览态 tooltip 已含完整调用链", baseTooltip.contains(digest))

            // 空参数导入 = 空剪贴板提示路径（importParams(emptyMap())）
            panel.importParamsForTest(emptyMap())
            waitForEdt()

            assertEquals("可见 meta 行应切到行内提示", "⚠ ${hint()}", panel.metaLabelForTest())
            val tooltip = panel.metaTooltipForTest() ?: ""
            assertTrue("提示态 tooltip 首行应为提示本身", tooltip.startsWith(hint()))
            assertTrue("提示态 tooltip 不得丢掉完整调用链（P6 回归点）", tooltip.contains(digest))
            assertTrue("提示态 tooltip 不得丢掉调用栈节点文案", tooltip.contains(stackLabel()))
            // 拼接而非覆写：原基线全文完整保留在其后
            assertTrue("提示态 tooltip 应保留 updateMeta 的完整基线", tooltip.endsWith(baseTooltip))
        } finally {
            panel.clear()
        }
    }

    fun test空剪贴板提示_重复触发不叠加自身() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(makeResult(chain()))
            waitForEdt()
            panel.importParamsForTest(emptyMap())
            waitForEdt()
            val once = panel.metaTooltipForTest() ?: ""

            panel.importParamsForTest(emptyMap())
            waitForEdt()
            assertEquals("重复触发应幂等（基线取自缓存字段，不把上次拼接结果当基线）", once, panel.metaTooltipForTest())
        } finally {
            panel.clear()
        }
    }

    fun test空剪贴板提示后_再次预览恢复纯metaTooltip() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(makeResult(chain()))
            waitForEdt()
            panel.importParamsForTest(emptyMap())
            waitForEdt()
            assertTrue("前提：提示态 tooltip 含提示文案", (panel.metaTooltipForTest() ?: "").contains(hint()))

            panel.showResult(makeResult(chain()))
            waitForEdt()
            val tooltip = panel.metaTooltipForTest() ?: ""
            assertFalse("toast 语义：下次 updateMeta 覆盖提示态（可见行同步回纯 meta）", panel.metaLabelForTest().contains(hint()))
            assertFalse("toast 语义：下次 updateMeta 覆盖提示态（tooltip 同步回纯 meta）", tooltip.contains(hint()))
            assertTrue("恢复后 tooltip 仍含完整调用链", tooltip.contains(chain()))
        } finally {
            panel.clear()
        }
    }
}
