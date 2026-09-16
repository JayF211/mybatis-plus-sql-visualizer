package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/**
 * T-ExecDocSync：执行文件 File Cache Conflict 根治测试。
 *
 * 用户真机（easy-code-demo）：mpsql-exec.sql 弹「File Cache Conflict——Changes have been
 * made ... in memory and on disk」。根因三点（同问题族第三轮，前两轮 M36.5.13/M36.5.43）：
 *  - T-EC-01：document 驱动写只改内存不落盘，document 恒留「已修改未保存」窗口；
 *  - T-EC-02：WriteAction 失败兜底无条件裸写盘（绕过 document = 冲突的外部改写条件）；
 *  - T-EC-03：BGT 预热与 EDT 冷路径双写方不互斥；T-EC-04：裸写路径防御收口。
 *
 * 沙盒无 Database 插件，projectExecFile 全链纯 VFS/PSI 可直测（ExecTempFileFixTest 同惯例）。
 * 测试文件走生产同款路径 `<projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql`。
 */
class ExecDocSyncTest : BasePlatformTestCase() {

    private lateinit var execFile: File

    override fun setUp() {
        super.setUp()
        val base = project.basePath ?: error("project.basePath null")
        execFile = File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")
        // 测试隔离：BasePlatformTestCase 各用例共用 fixture 目录，且 FileDocumentManager 的
        // document 缓存是应用级（按 vf 对象）——上一用例加载的 document 会经同路径 vf 泄入
        // 本用例（首轮实证：canFallBack 首断言/裸写兜底/并发用例全被残留 document 污染）。
        // VFS 内删除（非裸 IO delete）→ 下次 refresh 重建全新 vf，缓存不跨用例串场。
        val stale = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
        }
        if (stale != null) {
            com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> { stale.delete(this) }
        }
    }

    // ======================== T-EC-01：document 分支同临界区落盘 ========================

    /**
     * 核心回归：projectExecFile 走 document 分支后**磁盘立即**是新 SQL，document 不滞留
     * 「已修改未保存」态——修复前 setText+commit 只改内存，磁盘靠 IDE 自动保存追赶，
     * 窗口期内任何外部改盘即弹 File Cache Conflict（真机截图实证）。
     */
    fun testDocBranchPersistsSqlToDiskImmediately() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        // 模拟编辑器持有 document（已加载 = 有冲突面）
        val doc = FileDocumentManager.getInstance().getDocument(vf!!)
        assertNotNull("document 应已加载", doc)
        assertEquals("SELECT 1", doc!!.text)

        DataSourceSelector.projectExecFile(project, "SELECT 2")

        assertEquals("T-EC-01：落盘不依赖 IDE 自动保存——写后磁盘立即是新 SQL", "SELECT 2", diskText())
        assertEquals("document 同步为新 SQL", "SELECT 2", doc.text)
        // 262 平台 Document 接口已无 isModified()（javap 实证，仅余 isLineModified）——
        // 「已修改未保存」态的正确查询是 FileDocumentManager.isDocumentUnsaved
        assertFalse(
            "T-EC-01 核心：document 不得滞留「已修改未保存」态",
            FileDocumentManager.getInstance().isDocumentUnsaved(doc),
        )
    }

    // ======================== T-EC-02：裸写兜底守卫 ========================

    /** canFallBackToRawWrite 表驱动：无已加载 document → 允许；有 → 禁止。 */
    fun testCanFallBackToRawWriteTableDriven() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        assertTrue(
            "无已加载 document → 允许裸写兜底（无冲突面）",
            DataSourceSelector.canFallBackToRawWrite(vf!!),
        )
        FileDocumentManager.getInstance().getDocument(vf)
        assertFalse(
            "有已加载 document → 禁止裸写兜底（绕过 document 改盘 = 冲突外部条件）",
            DataSourceSelector.canFallBackToRawWrite(vf),
        )
    }

    /** WriteAction 失败（注入缝）+ document 已加载 → writeConflictGuard 拒写，磁盘不被改。 */
    fun testGuardRefusesRawWriteWhenDocumentLoaded() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        FileDocumentManager.getInstance().getDocument(vf!!)
        DataSourceSelector.execWriteActionFailureInjection = true
        try {
            try {
                DataSourceSelector.projectExecFile(project, "SELECT 2")
                fail("守卫必须拒绝裸写（writeConflictGuard）")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "拒绝原因应携带 writeConflictGuard 阶段标识，实际：${e.message}",
                    e.message!!.contains("writeConflictGuard"),
                )
            }
            assertEquals("守卫拒绝后磁盘不得被改（宁旧一拍，不弹冲突模态窗）", "SELECT 1", diskText())
        } finally {
            DataSourceSelector.execWriteActionFailureInjection = false
        }
    }

    /** WriteAction 失败（注入缝）+ 无已加载 document → 裸写兜底合法放行。 */
    fun testRawWriteFallbackWhenNoDocumentLoaded() {
        writeIo("SELECT 1")
        refreshVf() // vf 在 VFS，但 document 不加载（不调 getDocument）
        DataSourceSelector.execWriteActionFailureInjection = true
        try {
            val f = DataSourceSelector.projectExecFile(project, "SELECT 2")
            assertNotNull("放行路径应返回文件", f)
            assertEquals("无已加载 document 时裸写兜底合法（无冲突面）", "SELECT 2", diskText())
        } finally {
            DataSourceSelector.execWriteActionFailureInjection = false
        }
    }

    // ======================== T-EC-04：裸写路径防御收口 ========================

    /** 文件不存在（首次执行）：裸写创建 + refresh 可见 + 防御收口 no-op。 */
    fun testFreshFileRawWriteThenVfsVisibleAndSyncNoop() {
        assertFalse("前置：文件应不存在", execFile.exists())

        val f = DataSourceSelector.projectExecFile(project, "SELECT 1")
        assertNotNull(f)
        assertTrue("裸写应已创建文件", f!!.exists())

        val vf = refreshVf()
        assertNotNull("refresh 后 VFS 应可见", vf)
        assertFalse(
            "刚裸写的内容与 sql 一致 → syncExecVfContentIfNeeded 应为 no-op",
            DataSourceSelector.syncExecVfContentIfNeeded(project, vf!!, "SELECT 1"),
        )
        assertEquals("SELECT 1", diskText())
    }

    /** 内容分叉（VFS 陈旧/外部残留）→ 修复同步触发且落盘（T-EC-01 同款收口）。 */
    fun testSyncVfContentIfNeededRepairsDivergence() {
        writeIo("SELECT 1")
        val vf = refreshVf()

        val repaired = DataSourceSelector.syncExecVfContentIfNeeded(project, vf!!, "SELECT 2")

        assertTrue("内容分叉应触发修复同步", repaired)
        assertEquals("修复须落盘（同 T-EC-01 收口语义）", "SELECT 2", diskText())
        assertFalse("修复后已收敛 → 再检应为 no-op", DataSourceSelector.syncExecVfContentIfNeeded(project, vf, "SELECT 2"))
    }

    // ======================== T-EC-03：双写方并发（smoke） ========================

    /**
     * 两线程并发写同一执行文件不抛错、终态为某一写方的完整内容（execFileWriteLock 串行化）。
     * 并发全程在 BGT 裸调（与生产 prewarmExecTempFileInner 同形态）；文件未 refresh 期间
     * existing==null 恒走裸写路径，无需平台写锁，不会与 EDT 测试线程互锁。撕裂检测受
     * OS 单次 write 粒度限制，锁的强保证另由临界区结构审查背书——本测试为无异常一致性 smoke。
     */
    fun testConcurrentWritersNoTornContent() {
        val p1 = "A".repeat(65536) + "SELECT_1;"
        val p2 = "B".repeat(65536) + "SELECT_2;"
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = listOf(p1, p2).map { payload ->
            Thread {
                repeat(4) {
                    try {
                        DataSourceSelector.projectExecFile(project, payload)
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertTrue("并发写不应抛错：${errors.joinToString { "${it.javaClass.simpleName}: ${it.message}" }}", errors.isEmpty())
        threads.forEach { assertFalse("工作线程不应卡死", it.isAlive) }

        val final = diskText()
        assertTrue(
            "终态内容必须是某一写方的完整 payload（不得交错混写）",
            final == p1 || final == p2,
        )
    }

    // ======================== 辅助 ========================

    private fun writeIo(content: String) {
        assertTrue("exec 目录应可创建", execFile.parentFile.exists() || execFile.parentFile.mkdirs())
        execFile.writeText(content)
    }

    private fun refreshVf(): VirtualFile? = WriteIntentReadAction.compute<VirtualFile?> {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
    }

    private fun diskText(): String = execFile.readText()
}
