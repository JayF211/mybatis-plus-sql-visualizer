package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PreviewSideQuery：只读护栏必须先于数据源访问——非 SELECT 输入直接 Blocked。
 */
class PreviewSideQueryTest : BasePlatformTestCase() {

    fun testNonSelectIsBlockedBeforeDataSourceAccess() {
        val request = ExecutionDialectGate.Request(
            renderMode = ExecutionDialectGate.RenderMode.AUTO,
            renderedDialectName = "MySQL",
            intent = ExecutionDialectGate.Intent.QUERY,
            canLosslesslyRerender = false,
            userTextUntouched = true,
        )
        var result: PreviewSideQuery.Result? = null
        PreviewSideQuery.run(
            project,
            Any(),
            "UPDATE t_order SET status = 1 WHERE id = 1",
            request,
            scalar = true,
            requireSelectGuard = true,
        ) { result = it }
        assertTrue("非 SELECT 必须被只读护栏阻断：$result", result is PreviewSideQuery.Result.Blocked)
        assertTrue(
            "阻断原因不能为空",
            (result as PreviewSideQuery.Result.Blocked).message.isNotBlank(),
        )
    }
}
