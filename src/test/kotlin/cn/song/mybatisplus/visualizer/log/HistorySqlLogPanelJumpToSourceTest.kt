package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 历史面板「跳转到源码」真实场景测试（F-44-5 全插件 UI 测试）：
 *
 * 用户反馈「sql 日志和历史缺代码位置导致无法跳转到代码位置」——
 * 本测试锁定 [HistorySqlLogPanel] 跳转分支（[HistorySqlLogPanel.resolveJumpTarget]）的确定性行为：
 *
 *  - 缺源码位置（sourceFile=null，旧版 F-43 保存的条目）→ NoSource（无法跳转，应提示）；
 *  - 源码路径无效 / 文件不存在 → NotFound（无法跳转，应提示）；
 *  - 源码位置有效且文件存在 → Navigate(virtualFile, line)（可跳转，line = sourceLine-1）；
 *  - 源码位置经「自动保存 → 读取」往返后保留 → 仍可跳转（证明 sourceFile/sourceLine 未丢失）。
 *
 * 用真实磁盘文件 + VFS 刷新构造可跳转目标，避免 headless 下 Messages 弹窗阻塞。
 */
class HistorySqlLogPanelJumpToSourceTest : BasePlatformTestCase() {

    private fun makeEntry(rawSql: String, sourceFile: String? = null, sourceLine: Int = 0): LogSqlEntry =
        LogSqlEntry(
            rawSql = rawSql,
            restoredSql = rawSql,
            params = emptyList(),
            timestamp = System.currentTimeMillis(),
            sourceFile = sourceFile,
            sourceLine = sourceLine,
        )

    /** 在项目根目录写出真实源文件并刷新 VFS，使其可被 LocalFileSystem 解析到。 */
    private fun writeRealSource(relativePath: String, content: String): File {
        val f = File(project.basePath!!, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
        VfsUtil.markDirtyAndRefresh(false, true, true, f)
        return f
    }

    fun test缺源码位置_nullSource_无法跳转_NoSource() {
        // T-JumpCapture：NoSource 的生产侧行为 = SourceCaptureGuide 引导弹框（headless 不触弹窗，仅断言解析分支）
        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            val entry = makeEntry("SELECT * FROM user WHERE id = 1", sourceFile = null)
            val target = panel.resolveJumpTarget(entry)
            assertTrue("缺源码位置应判定为 NoSource（无法跳转）", target is HistorySqlLogPanel.HistoryJumpTarget.NoSource)
        } finally {
            panel.dispose()
        }
    }

    fun test源码路径无效_文件不存在_无法跳转_NotFound() {
        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            val entry = makeEntry("SELECT * FROM user WHERE id = 1", sourceFile = "src/missing/MissingMapper.java", sourceLine = 5)
            val target = panel.resolveJumpTarget(entry)
            assertTrue("路径无效/文件不存在应判定为 NotFound", target is HistorySqlLogPanel.HistoryJumpTarget.NotFound)
            assertEquals("NotFound 携带原始 sourceFile", "src/missing/MissingMapper.java", (target as HistorySqlLogPanel.HistoryJumpTarget.NotFound).sourceFile)
        } finally {
            panel.dispose()
        }
    }

    fun test源码位置有效_文件存在_可跳转_Navigate且行号正确() {
        writeRealSource("UserMapper.java", "public class UserMapper {}\n")
        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            val entry = makeEntry("SELECT * FROM user WHERE id = 1", sourceFile = "UserMapper.java", sourceLine = 10)
            val target = panel.resolveJumpTarget(entry)
            assertTrue("源码存在应可跳转(Navigate)", target is HistorySqlLogPanel.HistoryJumpTarget.Navigate)
            val nav = target as HistorySqlLogPanel.HistoryJumpTarget.Navigate
            assertEquals("行号应为 sourceLine-1（1-based→0-based）", 9, nav.line)
            // virtualFile 必须真实解析到磁盘文件
            val vf = LocalFileSystem.getInstance().findFileByPath(File(project.basePath!!, "UserMapper.java").absolutePath)
            assertEquals("Navigate 的 virtualFile 应与磁盘文件一致", vf, nav.virtualFile)
        } finally {
            panel.dispose()
        }
    }

    fun test源码行号0_不崩溃_跳转到第0行() {
        writeRealSource("ZeroLineMapper.java", "public class ZeroLineMapper {}\n")
        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            val entry = makeEntry("SELECT 1", sourceFile = "ZeroLineMapper.java", sourceLine = 0)
            val target = panel.resolveJumpTarget(entry)
            assertTrue("行号为 0 也应可跳转", target is HistorySqlLogPanel.HistoryJumpTarget.Navigate)
            assertEquals("line 应被 maxOf(0, -1) 钳到 0", 0, (target as HistorySqlLogPanel.HistoryJumpTarget.Navigate).line)
        } finally {
            panel.dispose()
        }
    }

    fun test历史文件往返_源码位置保留_可跳转() {
        // 写出真实源文件（与历史文件头部声明的 source 一致）
        writeRealSource("UserMapper.java", "public class UserMapper {\n  void selectById(Long id) {}\n}\n")

        // 模拟自动保存写入历史文件：-- source: UserMapper.java:10 | time: 3ms | ts --\nSQL
        val dir = File(project.basePath!!, ".idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history")
        dir.mkdirs()
        val histFile = File(dir, "sql-history-2026-08-31.sql")
        histFile.writeText(
            "-- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --\n" +
                "SELECT * FROM user WHERE id = 1\n\n",
        )
        VfsUtil.markDirtyAndRefresh(false, true, true, histFile)

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            assertTrue("默认一级", panel.isShowingFiles)
            assertEquals("应读到 1 个历史文件", 1, panel.fileListEntries.size)
            panel.openFileForTest(panel.fileListEntries.first())

            val entry = panel.sqlListEntries.first()
            assertEquals("源码位置经往返保留", "UserMapper.java", entry.sourceFile)
            assertEquals("源码行号经往返保留", 10, entry.sourceLine)

            val target = panel.resolveJumpTarget(entry)
            assertTrue("往返后源码位置有效 → 可跳转", target is HistorySqlLogPanel.HistoryJumpTarget.Navigate)
            assertEquals(9, (target as HistorySqlLogPanel.HistoryJumpTarget.Navigate).line)
        } finally {
            panel.dispose()
        }
    }

    fun test可跳转条目_jumpToSource不抛异常_跳转链路可达() {
        writeRealSource("NavMapper.java", "public class NavMapper {}\n")
        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            val entry = makeEntry("SELECT * FROM nav WHERE id = 1", sourceFile = "NavMapper.java", sourceLine = 3)
            // 先确定性确认目标是可跳转的
            val target = panel.resolveJumpTarget(entry)
            assertTrue("目标应可跳转", target is HistorySqlLogPanel.HistoryJumpTarget.Navigate)
            // 实际执行跳转（等价右键「跳转到源码」）：真实文件下不抛异常即证明 navigate 链路可达
            panel.jumpToSourceForTest(entry)
        } finally {
            panel.dispose()
        }
    }
}
