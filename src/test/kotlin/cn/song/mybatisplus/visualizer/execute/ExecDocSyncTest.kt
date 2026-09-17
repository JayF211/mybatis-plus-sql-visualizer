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

    // ============ T-ExecDocSync3：VFS 已有该文件即绝不裸写（T-EC-02 守卫退役） ============

    /**
     * 核心回归（第四轮真机日志实证的收口）：WriteAction 失败/EDT 派发超时 → **磁盘一律不动**，
     * 且不再抛异常（旧 writeConflictGuard 已退役）。
     *
     * 真机失败链（2026-09-17 真实 IDE 2024.3 日志）：EDT 忙（冷启动索引）→ dispatch 有界等待 2s
     * 超时返回 false → 旧守卫查 getCachedDocument 得 null（彼时排队中的 EDT 写任务尚未执行、
     * document 还没建）→ 放行裸写改盘 → 排队任务随后建出 document 并标脏 → 裸写引发的 VFS 变更
     * 落到这个新文档上 → MemoryDiskConflictResolver 弹「重载 mpsql-exec.sql？」。即插件与自己的
     * 排队写赛跑。此用例钉死「VFS 有该文件时不裸写」。
     */
    fun testNoRawWriteWhenDocWriteFailsAndVfExists() {
        writeIo("SELECT 1")
        refreshVf() // vf 在 VFS（= 存在 document 可能），不预加载 document —— 正是真机超时那一刻的形态
        DataSourceSelector.execWriteActionFailureInjection = true
        try {
            val f = DataSourceSelector.projectExecFile(project, "SELECT 2")
            assertNotNull("应返回文件路径（调用方需要 ioFile 做 refresh/映射）", f)
            assertEquals(
                "T-ExecDocSync3：裸写必须被跳过——磁盘保持旧值（宁旧一拍，由后续 console doc 同步链收敛）",
                "SELECT 1",
                diskText(),
            )
        } finally {
            DataSourceSelector.execWriteActionFailureInjection = false
        }
    }

    /** document 已加载时同样不裸写（原守卫拒绝路径的等价保证，改为不抛异常、不落盘）。 */
    fun testNoRawWriteWhenDocWriteFailsAndDocumentLoaded() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        FileDocumentManager.getInstance().getDocument(vf!!)
        DataSourceSelector.execWriteActionFailureInjection = true
        try {
            val f = DataSourceSelector.projectExecFile(project, "SELECT 2")
            assertNotNull(f)
            assertEquals("有 document 时不得裸写（旧守卫语义保留，只是不再抛异常）", "SELECT 1", diskText())
        } finally {
            DataSourceSelector.execWriteActionFailureInjection = false
        }
    }

    /** 首次创建（VFS 无条目）→ 裸写仍合法（无 vf 可挂 document，无冲突面）。 */
    fun testRawWriteStillUsedWhenVfMissing() {
        assertFalse("前置：文件应不存在", execFile.exists())
        val f = DataSourceSelector.projectExecFile(project, "SELECT 1")
        assertNotNull(f)
        assertEquals("VFS 无条目时裸写创建（首次）", "SELECT 1", diskText())
    }

    // ======================== T-PreviewFast：差量快路径（P1）与判据（P3） ========================

    /** P1 判据表驱动：内容一致（document 源 / vf 源）→ true；不一致 → false。 */
    fun testExecFileContentMatchesTableDriven() {
        writeIo("SELECT 1")
        val vf = refreshVf()!!
        assertTrue("无 document 时读 vf 内容：一致 → true", DataSourceSelector.execFileContentMatches(project, vf, "SELECT 1"))
        assertFalse("无 document 时读 vf 内容：不一致 → false", DataSourceSelector.execFileContentMatches(project, vf, "SELECT 2"))

        FileDocumentManager.getInstance().getDocument(vf)
        assertTrue("有 document 时以 document 文本为准：一致 → true", DataSourceSelector.execFileContentMatches(project, vf, "SELECT 1"))
        assertFalse("有 document 时以 document 文本为准：不一致 → false", DataSourceSelector.execFileContentMatches(project, vf, "SELECT 2"))
    }

    /**
     * P1 核心：目标内容与现状一致 → **不派发、不落盘**（真机连续预览同一 SQL 的主路径）。
     * 断言手段：注入 WriteAction 失败——若快路径生效，注入根本不会执行，磁盘与文档均无变化且不抛。
     */
    fun testIdenticalContentShortCircuitsWithoutWrite() {
        writeIo("SELECT 1")
        val vf = refreshVf()!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        assertNotNull(doc)
        DataSourceSelector.execWriteActionFailureInjection = true
        try {
            val f = DataSourceSelector.projectExecFile(project, "SELECT 1")
            assertNotNull("快路径应正常返回文件路径", f)
            assertEquals("内容一致：磁盘不动", "SELECT 1", diskText())
            assertFalse("内容一致：文档不得被标脏", FileDocumentManager.getInstance().isDocumentUnsaved(doc!!))
        } finally {
            DataSourceSelector.execWriteActionFailureInjection = false
        }
    }

    /** P3 判据：exec 文件「正被编辑器打开」才为真——文档加载但无编辑器时仍为 false（预热可刷新）。 */
    fun testIsExecDocOpenInEditorReflectsEditorPresence() {
        writeIo("SELECT 1")
        val vf = refreshVf()!!
        assertFalse("无已加载 document → false", DataSourceSelector.isExecDocOpenInEditor(vf))
        FileDocumentManager.getInstance().getDocument(vf)
        assertFalse("document 已加载但无编辑器 → false", DataSourceSelector.isExecDocOpenInEditor(vf))
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
