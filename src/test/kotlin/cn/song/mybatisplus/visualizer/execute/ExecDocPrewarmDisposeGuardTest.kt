package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.lang.reflect.Proxy

/**
 * T-UiFixGate9：exec 预暖链/文档写的**项目 dispose 生命周期守卫**守护测试（第二冲突源收口）。
 *
 * 根因链（20261018 门禁 shard5 三轮实录 + 243 平台 app-client.jar javap 实证）：
 *  1. 面板测试（CrudSql/FieldFill）毫秒级跑完，`rerenderForExecutionTarget` 经
 *     `executeOnPooledThread` 派出的 BGT 预暖链（fire-and-forget 形态 waitBudget=0）仍在跑；
 *  2. light 测试项目收尾进入 **temporarily-disposed 窗口**——`ProjectImpl.isTemporarilyDisposed`
 *     标志置真（idea.log 实证 `containerState=disposed temporarily`），而
 *     `DocWriteContext.invokeLaterWriteSafe` 的 `Disposer.isDisposed`（ObjectTree 判定）对该
 *     窗口**失明**；
 *  3. 排队的 EDT 写/链内裸写在死项目上照写：轻则半途抛 AlreadyDisposed 留「已修改未保存」脏
 *     文档（setText 已过、commitDocument/saveDocument 未到），重则裸写改盘；后续任一 VFS
 *     refresh 命中 MemoryDiskConflictResolver → 冲突收集后 EDT 延迟处理，杀掉「当下正在收尾」
 *     的无辜测试（victim 随分片组合漂移）。
 *
 * 守卫点（`project.isDisposed`：243 平台 javap 实证 = 容器态≥DISPOSE_IN_PROGRESS **OR**
 * isTemporarilyDisposed，故 temporarily 窗口内判定可达）：`prewarmExecTempFileInner` 入口+阶段、
 * `projectExecFile` 入口（挡裸写首建）、`docWriteOnce` 派发拍执行时刻、`writeVfTextViaDocument`
 * 原语。
 *
 * **构造方式（为何用 Project 动态代理而非真的翻 temporarily 标志）**：light 项目在同一测试类内
 * 跨用例复用同一 ProjectImpl，框架以 `setTemporarilyDisposed(true/false)` 的 CAS 配对做收尾/复活
 * （javap：false 分支 `CAS earlyDisposable null→newDisposable`，不配对即抛 "earlyDisposable must
 * be null on second opening of light project"）；测试中途手动翻标志破坏配对，且 temp-disposed 窗口
 * 内做平台动作（WriteAction/delete）会触发 LocalHistory「not balanced begin/end-change set」、
 * inSmartMode 约束等平台断言噪音。故以 `Proxy` 实现 Project（isDisposed 恒 true，其余委托真实
 * 项目）驱动**同一套守卫判定**：守卫读的是 `project.isDisposed`，代理即该判定点的精确模拟。
 * 反向验证（摘掉全部四处守卫）：四用例全部转为对真实文件/文档的真实写入 → 必红（见各用例注释）。
 */
class ExecDocPrewarmDisposeGuardTest : BasePlatformTestCase() {

    private lateinit var execFile: File

    override fun setUp() {
        super.setUp()
        val base = project.basePath ?: error("project.basePath null")
        execFile = File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")
        // 测试隔离：VFS 内删除重建全新 vf，防应用级 document 缓存跨用例串场（ExecDocSync2Test 同款）
        val stale = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
        }
        if (stale != null) {
            WriteAction.compute<Unit, Throwable> { stale.delete(this) }
        }
        assertTrue("exec 目录应可创建", execFile.parentFile.exists() || execFile.parentFile.mkdirs())
        execFile.writeText("SELECT baseline")
    }

    // ======================== 核心：isDisposed=true 时零写 ========================

    /**
     * 预暖链入口守卫：文件已存在且内容≠目标 SQL（链内会派发 doc 写的形态），isDisposed=true
     * 时整链放弃——磁盘保持 baseline。
     * 反向红路径：摘守卫 → projectExecFile 派发写（EDT 直调内联或 fire-and-forget 排队后抽 EDT
     * 执行）真实落盘 → 磁盘被改 → 红。
     */
    fun testPrewarmChainAbandonedOnDisposedProject() {
        refreshVf() // 让 VFS 见到现存文件（走「已有条目」路径）
        val disposed = disposedProjectProxy()

        DataSourceSelector.run {
            prewarmExecTempFileInner(disposed, "SELECT target-sql", Logger.getInstance(DataSourceSelector::class.java))
        }
        UIUtil.dispatchAllInvocationEvents() // 抽干 EDT：fire-and-forget 排队写若未被守卫拦下，在此执行

        assertEquals("预暖链在项目 dispose 后不得改写 exec 文件", "SELECT baseline", execFile.readText())
    }

    /**
     * 首建形态裸写守卫：VFS 无条目时 projectExecFile 走裸写 FileUtil.writeToFile（不经 document、
     * 原先无任何 dispose 检查），isDisposed=true 时不得创建文件。
     * 反向红路径：摘守卫 → 裸写创建文件 → 红。
     */
    fun testPrewarmRawCreateRefusedOnDisposedProject() {
        assertTrue(execFile.delete())
        refreshVf()?.let { vf -> WriteAction.compute<Unit, Throwable> { vf.delete(this) } } // VFS 条目一并清掉
        assertFalse(execFile.exists())
        val disposed = disposedProjectProxy()

        DataSourceSelector.run {
            prewarmExecTempFileInner(disposed, "SELECT target-sql", Logger.getInstance(DataSourceSelector::class.java))
        }
        UIUtil.dispatchAllInvocationEvents()

        assertFalse("首建裸写路径在项目 dispose 后不得创建 exec 文件", execFile.exists())
    }

    /**
     * docWriteOnce 派发拍咽喉守卫：fire-and-forget 写排队期间项目死亡，执行时刻必须放弃。
     * 刻意保持无已加载 document——无文档分支走 setBinaryContent（纯 VFS+磁盘，无项目访问），
     * 摘守卫时必写盘，反向红是确定性的（有文档分支会半途抛 AlreadyDisposed，红得「碰巧」）。
     * 反向红路径：摘守卫 → setBinaryContent 落盘 → 返回 true 且磁盘被改 → 红。
     */
    fun testDocWriteOnceRefusedOnDisposedProject() {
        val vf = refreshVf() ?: error("exec vf 应可刷新")
        val fdm = FileDocumentManager.getInstance()
        assertTrue(
            "前置：本用例需要无已加载 document（裸 setBinaryContent 分支才具备确定性反向红）",
            fdm.getCachedDocument(vf) == null,
        )
        val disposed = disposedProjectProxy()

        val ok = DataSourceSelector.docWriteOnce(disposed, vf, "SELECT target-sql")
        UIUtil.dispatchAllInvocationEvents()

        assertFalse("dispose 后 docWriteOnce 必须拒绝写", ok)
        assertEquals("docWriteOnce 在项目 dispose 后不得改写 exec 文件", "SELECT baseline", execFile.readText())
    }

    /**
     * writeVfTextViaDocument 原语守卫（console 同步等直调面，ExecDocSync2Test 同款 WriteAction
     * 包装）：isDisposed=true 时原语 no-op。
     * 反向红路径：摘守卫 → setText+commitDocument+saveDocument 全链真实执行（代理委托真实项目，
     * 平台调用全部可用）→ 文档与磁盘均被改 → 红。这正是毒链本体的直测形态。
     */
    fun testWriteVfTextViaDocumentNoopOnDisposedProject() {
        val vf = refreshVf() ?: error("exec vf 应可刷新")
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(vf)
        assertTrue("前置：document 应可加载", doc != null)
        assertFalse(fdm.isDocumentUnsaved(doc!!))
        val disposed = disposedProjectProxy()

        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
        WriteAction.compute<Unit, Throwable> {
            DataSourceSelector.writeVfTextViaDocument(disposed, vf, "SELECT target-sql")
        }
        UIUtil.dispatchAllInvocationEvents()

        assertFalse("dispose 后原语写不得置脏文档", fdm.isDocumentUnsaved(doc))
        assertEquals("dispose 后原语写不得改文档内容", "SELECT baseline", doc.text)
        assertEquals("dispose 后原语写不得改磁盘内容", "SELECT baseline", execFile.readText())
    }

    // ======================== 辅助 ========================

    /**
     * 「已 dispose 项目」精确模拟：isDisposed 恒 true（守卫读的唯一判定），其余一切委托真实
     * 项目——反向（摘守卫）时链内 basePath/findPsi 等照常工作，写路径真实落盘，反向红确定。
     */
    private fun disposedProjectProxy(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, args ->
        when (method.name) {
            "isDisposed" -> true
            "toString" -> "DisposedProjectProxy(real=${project.name})"
            else -> method.invoke(project, *(args ?: emptyArray()))
        }
    } as Project

    private fun refreshVf(): VirtualFile? = WriteIntentReadAction.compute<VirtualFile?> {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
    }
}
