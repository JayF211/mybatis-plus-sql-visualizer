package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-ExecCopyFix：执行临时文件链路修复测试（Bug 2 回归）。
 *
 * 用户实测：点击「执行 SQL」报 "Failed to create temporary SQL file"——原实现
 * 整段 runCatching{}.getOrNull() 吞异常零日志，失败阶段无法归因。修复后：
 *  - F2a：失败分级记录 + onError 文案携带阶段原因（execute.tempFile.failed）；
 *  - F2b：冷路径 refresh 段包 WriteIntentReadAction（262 write-safe 硬抛先例 T-PreviewWiLock）；
 *  - F2c：clearPrepared 不清 cachedTempPsiFile（缓存跨预览存续，执行不再必落冷路径）。
 *
 * 沙盒无 Database 插件（selectAndExecute 走 execute.noDatabase 早退），到不了
 * createTempPsiFile——因此缓存存续用等价链路直测（文件+refresh+缓存行为与生产同 API），
 * 失败文案断言 bundle 键与 chooseAndPrepare 的消息装配点。
 */
class ExecTempFileFixTest : BasePlatformTestCase() {

    // ======================== F2c：缓存跨 clearPrepared 存续 ========================

    /**
     * 等价链路：写 mpsql-exec.sql → WIRA refresh → 缓存槽记录 → clearPrepared 后
     * 缓存**仍在**（F2c 核心断言；原实现此处清缓存，执行必落冷路径触发 262 硬抛面）。
     */
    fun testCachedTempPsiFileSurvivesClearPrepared() {
        val f = writeExecFile("SELECT 1")
        val vf = WriteIntentReadAction.compute<com.intellij.openapi.vfs.VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
        }
        assertNotNull("refresh 后 VFS 应可见（F2b WIRA 语境）", vf)

        // 通过等价链路把缓存槽置为已持有——直接走生产私有槽没有测试缝，用
        // markCachedTempPsiFileForTest 建一个最小缝（仅测试用，语义同生产命中前置态）
        DataSourceSelector.markCachedTempPsiFileForTest(project, vf!!)
        assertTrue("缓存槽应持有 vf+psi 配对", DataSourceSelector.cachedTempPsiFileForTest())

        DataSourceSelector.clearPrepared()
        assertTrue(
            "F2c：clearPrepared 不应清 cachedTempPsiFile（执行冷路径 = 262 write-safe 硬抛面）",
            DataSourceSelector.cachedTempPsiFileForTest(),
        )

        DataSourceSelector.clearCachedTempPsiFileForTest()
        assertFalse("测试缝清理后缓存应为空", DataSourceSelector.cachedTempPsiFileForTest())
    }

    // ======================== F2a：失败文案携带阶段原因 ========================

    /**
     * execute.tempFile.failed 消息携带 {0} 阶段原因——用户真机的笼统
     * "Failed to create temporary SQL file" 自此可归因（本地化 + 带因）。
     * 断言与语言环境解耦（沙盒随系统走 zh bundle）：占位参数必须出现在文案里。
     */
    fun testTempFileFailedMessageCarriesReason() {
        val msg = MpSqlVisualizerBundle.message("execute.tempFile.failed", "refreshVfs: boom")
        assertTrue("文案应携带失败阶段原因（{0} 装配）", msg.contains("refreshVfs: boom"))
        assertFalse(
            "占位符 {0} 不应原样泄漏到用户文案",
            msg.contains("{0}"),
        )
    }

    // ======================== 基线不回归：clearPrepared 仍重置就绪态 ========================

    fun testClearPreparedStillResetsReadiness() {
        DataSourceSelector.clearPrepared()
        assertFalse("clearPrepared 后 isPrepared 应为 false（既有语义不变）", DataSourceSelector.isPrepared())
    }

    // ======================== T-PreviewExecDs-02 F1：跨项目缓存隔离 ========================

    /**
     * isCacheUsable 表驱动：项目内文件命中；项目外文件拒绝（跨项目泄漏判定——
     * 开发计划 §2.2 根因：旧实现只查 vf.isValid，B 项目会拿到 A 项目的 vf+psi）。
     */
    fun testIsCacheUsableProjectBound() {
        val inProject = writeExecFile("SELECT 1")
        val inVf = WriteIntentReadAction.compute<com.intellij.openapi.vfs.VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(inProject)
        }
        assertNotNull("项目内文件应 VFS 可见", inVf)
        assertTrue("项目内 vf 应判可用", DataSourceSelector.isCacheUsable(project, inVf!!))

        val foreign = foreignTempFile("foreign-isCacheUsable.sql", "SELECT 2")
        assertFalse("项目外 vf 应判不可用（跨项目拒绝）", DataSourceSelector.isCacheUsable(project, foreign))

        DataSourceSelector.clearCachedTempPsiFileForTest()
    }

    /** 缓存槽残留项目外文件（模拟跨项目泄漏的缓存）→ consume 拒绝并清空缓存，不再返回别项目 vf。 */
    fun testConsumeCachedTempPsiFileRejectsForeignProjectFile() {
        val foreign = foreignTempFile("foreign-consume.sql", "SELECT 3")
        DataSourceSelector.markCachedTempPsiFileForTest(project, foreign)
        assertTrue("缓存槽应持有配对", DataSourceSelector.cachedTempPsiFileForTest())

        val consumed = DataSourceSelector.consumeCachedTempPsiFile(project)
        assertNull("跨项目缓存应被拒绝（不返回别项目 vf+psi）", consumed)
        assertFalse("跨项目缓存应被清空（走冷路径重建）", DataSourceSelector.cachedTempPsiFileForTest())

        DataSourceSelector.clearCachedTempPsiFileForTest()
    }

    /** 项目内缓存 → consume 正常命中（F1 不破坏既有跨点击复用语义）。 */
    fun testConsumeCachedTempPsiFileHitsWithinProject() {
        val inProject = writeExecFile("SELECT 4")
        val inVf = WriteIntentReadAction.compute<com.intellij.openapi.vfs.VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(inProject)
        }
        assertNotNull(inVf)
        DataSourceSelector.markCachedTempPsiFileForTest(project, inVf!!)
        val consumed = DataSourceSelector.consumeCachedTempPsiFile(project)
        assertNotNull("项目内缓存应命中复用", consumed)
        DataSourceSelector.clearCachedTempPsiFileForTest()
    }

    // ======================== 辅助 ========================

    /** 项目外临时文件（System tmpdir，不在 fixture 项目 basePath 下）——跨项目模拟用。 */
    private fun foreignTempFile(name: String, sql: String): com.intellij.openapi.vfs.VirtualFile {
        val io = java.io.File(System.getProperty("java.io.tmpdir"), name)
        io.writeText(sql)
        io.deleteOnExit()
        val vf = WriteIntentReadAction.compute<com.intellij.openapi.vfs.VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
        }
        assertNotNull("项目外文件应 VFS 可见", vf)
        return vf!!
    }

    // ======================== 辅助 ========================

    private fun writeExecFile(sql: String): File {
        val base = project.basePath ?: error("project.basePath null")
        val dir = File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec")
        assertTrue("exec 目录应可创建", dir.exists() || dir.mkdirs())
        val f = File(dir, "mpsql-exec.sql")
        f.writeText(sql)
        return f
    }
}
