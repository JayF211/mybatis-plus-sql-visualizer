package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 实时日志「精确跳转」回归（F-44-6）：[SqlLogPanel] 在条目携带源码位置时，
 * 应走与历史面板一致的 file:line 精确跳转（[LogJumpTarget.Precise]），
 * 而非退化为不可靠的表名搜索；仅在无源码位置时回退表名启发式。
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

    fun test无源码位置_回退表名搜索_SearchByTable() {
        val panel = SqlLogPanel(project, manager)
        try {
            val target = panel.resolveEntryJump(entry("SELECT * FROM user u WHERE u.id = 1"))
            // 无源码位置 → 走原表名启发式（尽力而为，不可靠）
            assertTrue("无源码位置应回退表名搜索", target is SqlLogPanel.LogJumpTarget.SearchByTable)
            assertEquals("user", (target as SqlLogPanel.LogJumpTarget.SearchByTable).tableName)
        } finally {
            panel.dispose()
        }
    }

    fun test无源码位置_无表名_NoTable() {
        val panel = SqlLogPanel(project, manager)
        try {
            val target = panel.resolveEntryJump(entry("SELECT 1"))
            assertTrue(target is SqlLogPanel.LogJumpTarget.NoTable)
        } finally {
            panel.dispose()
        }
    }

    fun test旧版条目_sourceFile为null_回退路径不崩() {
        // 旧版 F-43 保存的实时条目 sourceFile 恒为 null → 必须回退表名搜索，不得 NPE
        // （headless 下不实际调用 jumpToSourceForTest，因其会触发 Messages 弹窗；仅断言决策分支）
        val panel = SqlLogPanel(project, manager)
        try {
            val target = panel.resolveEntryJump(entry("SELECT * FROM t", sourceFile = null, sourceLine = 0))
            assertTrue(
                "无源码位置应回退（SearchByTable / NoTable），不得抛异常",
                target is SqlLogPanel.LogJumpTarget.SearchByTable || target is SqlLogPanel.LogJumpTarget.NoTable,
            )
        } finally {
            panel.dispose()
        }
    }
}
