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

    private fun entryWithCandidates(
        restoredSql: String,
        sourceFile: String?,
        sourceLine: Int,
        candidates: List<Pair<String, Int>>,
    ): LogSqlEntry = LogSqlEntry(
        rawSql = restoredSql,
        restoredSql = restoredSql,
        params = emptyList(),
        timestamp = 1L,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
        sourceCandidates = candidates,
    )

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

    fun test主位置不在工程内_回退候选_首个可解析项() {
        // T-AgentSourceJump：主位置误选第三方插件帧（PageHelper 的 PageInterceptor 等）
        // → 按栈序候选取首个工程内可解析项
        writeRealSource("com/example/service/UserServiceImpl.java", "class UserServiceImpl {}\n")
        val panel = SqlLogPanel(project, manager)
        try {
            val e = entryWithCandidates(
                "SELECT COUNT(*) FROM user",
                "com/github/pagehelper/PageInterceptor.java", 132,
                listOf(
                    "com/example/service/PageHelper.java" to 7,          // 工程内不存在 → 跳过
                    "com/example/service/UserServiceImpl.java" to 36,    // 命中
                ),
            )
            val target = panel.resolveEntryJump(e)
            assertTrue("主位置缺失应回退到候选的 Precise，而非 NotFound", target is SqlLogPanel.LogJumpTarget.Precise)
            assertEquals("行号 = 候选 line-1", 35, (target as SqlLogPanel.LogJumpTarget.Precise).line)
        } finally {
            panel.dispose()
        }
    }

    fun test主位置与候选全不可解析_NotFound不劣化() {
        val panel = SqlLogPanel(project, manager)
        try {
            val e = entryWithCandidates(
                "SELECT 1",
                "missing/Primary.java", 5,
                listOf("missing/A.java" to 1, "missing/B.java" to 2),
            )
            val target = panel.resolveEntryJump(e)
            assertTrue("候选全落空仍应 NotFound（真实失效场景不劣化）", target is SqlLogPanel.LogJumpTarget.NotFound)
            assertEquals("missing/Primary.java", (target as SqlLogPanel.LogJumpTarget.NotFound).sourceFile)
        } finally {
            panel.dispose()
        }
    }

    fun test无主位置_候选可解析_落候选() {
        // agent 侧主位置无调试信息（file=null）时，候选首位顶上
        writeRealSource("com/example/service/NavServiceImpl.java", "class NavServiceImpl {}\n")
        val panel = SqlLogPanel(project, manager)
        try {
            val e = entryWithCandidates(
                "SELECT 1",
                null, 0,
                listOf("com/example/service/NavServiceImpl.java" to 12),
            )
            val target = panel.resolveEntryJump(e)
            assertTrue("主位置 null 但有可解析候选 → Precise", target is SqlLogPanel.LogJumpTarget.Precise)
            assertEquals(11, (target as SqlLogPanel.LogJumpTarget.Precise).line)
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
