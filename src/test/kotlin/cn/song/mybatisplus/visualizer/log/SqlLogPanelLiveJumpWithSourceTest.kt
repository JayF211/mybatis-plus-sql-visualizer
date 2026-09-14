package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 实时日志「跳转到源码」解析回归（F-44-6 精确跳转 + T-JumpCapture 语义定案）：
 *
 * 用户定性「按表名搜索 Wrapper 定位」不可用（同表名 ≠ 同调用点，命中即误导），
 * [SqlLogPanel.resolveEntryJump] 现仅依赖 [SqlSourceInterceptor] 捕获的源码位置：
 *  - 携带位置 → Precise（file:line 精确跳转，行号 = sourceLine-1）；
 *  - 位置指向的文件缺失 → NotFound（仅提示，与拦截器无关）；
 *  - MP 分页 COUNT 标记 → NoSource（无用户调用点，纯提示，不得引导开拦截器）;
 *  - 无位置（未开拦截器 / 旧版条目）→ MissingSource（[SourceCaptureGuide] 引导弹框）。
 *
 * headless 下不触发 jumpToSource 弹窗副作用，仅断言确定性解析分支。
 */
class SqlLogPanelLiveJumpWithSourceTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    private fun entry(restoredSql: String, sourceFile: String? = null, sourceLine: Int = 0): LogSqlEntry =
        LogSqlEntry(rawSql = restoredSql, restoredSql = restoredSql, params = emptyList(), timestamp = 1L, sourceFile = sourceFile, sourceLine = sourceLine)

    private fun writeRealSource(relativePath: String, content: String): File {
        val f = File(project.basePath!!, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
        VfsUtil.markDirtyAndRefresh(false, true, true, f)
        return f
    }

    fun test携带源码位置_精确跳转_Precise() {
        writeRealSource("com/example/service/UserService.java", "class UserService {}\n")
        val panel = SqlLogPanel(project, manager)
        try {
            val target = panel.resolveEntryJump(entry("SELECT * FROM user WHERE id = 1", "com/example/service/UserService.java", 10))
            assertTrue("有源码位置应精确跳转", target is SqlLogPanel.LogJumpTarget.Precise)
            val precise = target as SqlLogPanel.LogJumpTarget.Precise
            assertEquals("行号 = sourceLine-1", 9, precise.line)
            val vf = LocalFileSystem.getInstance().findFileByPath(File(project.basePath!!, "com/example/service/UserService.java").absolutePath)
            assertEquals(vf, precise.virtualFile)
        } finally {
            panel.dispose()
        }
    }

    fun test携带源码位置_真实跳转不抛异常_链路可达() {
        writeRealSource("com/example/service/NavService.java", "class NavService {}\n")
        val panel = SqlLogPanel(project, manager)
        try {
            val e = entry("SELECT * FROM nav WHERE id = 1", "com/example/service/NavService.java", 3)
            assertTrue("解析应为 Precise", panel.resolveEntryJump(e) is SqlLogPanel.LogJumpTarget.Precise)
            panel.jumpToSourceForTest(e) // 等价右键跳转，真实文件下不抛异常即证明 navigate 链路可达
        } finally {
            panel.dispose()
        }
    }

    fun test源码文件不存在_NotFound_提示() {
        val panel = SqlLogPanel(project, manager)
        try {
            val target = panel.resolveEntryJump(entry("SELECT 1", "missing/Missing.java", 5))
            assertTrue(target is SqlLogPanel.LogJumpTarget.NotFound)
            assertEquals("missing/Missing.java", (target as SqlLogPanel.LogJumpTarget.NotFound).sourceFile)
        } finally {
            panel.dispose()
        }
    }

    fun testMP分页COUNT标记_NoSource_不引导开拦截器() {
        val panel = SqlLogPanel(project, manager)
        try {
            val target = panel.resolveEntryJump(entry("SELECT COUNT(*) FROM user", SourceMarkers.MP_PAGINATION_COUNT, 0))
            assertTrue(
                "MP 分页 COUNT 应为 NoSource（纯提示），不得判为 MissingSource（引导开拦截器对其无意义）",
                target is SqlLogPanel.LogJumpTarget.NoSource,
            )
        } finally {
            panel.dispose()
        }
    }

    fun test无源码位置_有表名_MissingSource_引导弹框() {
        val panel = SqlLogPanel(project, manager)
        try {
            // 旧行为回退表名搜索 SearchByTable，T-JumpCapture 后同输入必须判 MissingSource（防回退复活）
            val target = panel.resolveEntryJump(entry("SELECT * FROM user u WHERE u.id = 1"))
            assertTrue("无源码位置应判 MissingSource（引导开启源码捕获）", target is SqlLogPanel.LogJumpTarget.MissingSource)
        } finally {
            panel.dispose()
        }
    }

    fun test无源码位置_无表名_MissingSource() {
        val panel = SqlLogPanel(project, manager)
        try {
            val target = panel.resolveEntryJump(entry("SELECT 1"))
            assertTrue("提不到表名同样 MissingSource（不再区分 NoTable）", target is SqlLogPanel.LogJumpTarget.MissingSource)
        } finally {
            panel.dispose()
        }
    }

    fun test旧版条目_sourceFile为null_不崩() {
        // 旧版 F-43 保存的实时条目 sourceFile 恒为 null → MissingSource，不得 NPE
        // （headless 下不实际调用 jumpToSourceForTest，因其触发 Messages 弹窗；仅断言决策分支）
        val panel = SqlLogPanel(project, manager)
        try {
            val target = panel.resolveEntryJump(entry("SELECT * FROM t", sourceFile = null, sourceLine = 0))
            assertTrue("无源码位置应判 MissingSource，不得抛异常", target is SqlLogPanel.LogJumpTarget.MissingSource)
        } finally {
            panel.dispose()
        }
    }
}
