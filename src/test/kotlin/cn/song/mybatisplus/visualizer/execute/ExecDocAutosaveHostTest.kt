package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-PS4-11：F5 防抖自动落盘监听挂 Disposable + 快判守护测试。
 *
 * 两件事：
 * 1. 监听随 [ExecDocAutosaveHost]（应用级轻服务 Disposable parent）卸载后 documentChanged
 *    不再进入防抖链（install → dispose → 变更断言零效果）——此前单参裸装永不卸载，插件动态
 *    卸载后残活监听钉住旧 classloader。
 * 2. 快判路径行为与原路径等价：exec 文档命中照常排防抖（首探建缓存/二次命中短路两形态）、
 *    非 exec 文档零动作、闸门（T-PreviewFast P4）先于快判。
 *
 * 沙盒惯例同 [ExecDocSync2Test]：测试文件走生产同款 exec 路径；安装入口用生产挂载点
 * markCachedTempPsiFileForTest（exec 路径才触发监听安装）。
 */
class ExecDocAutosaveHostTest : BasePlatformTestCase() {

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
        // 重建全新 exec 文件（refreshVf/文档加载前提，ExecDocSync2Test writeIo 同款）
        assertTrue("exec 目录应可创建", execFile.parentFile.exists() || execFile.parentFile.mkdirs())
        execFile.writeText("SELECT baseline")
        DataSourceSelector.resetExecDocAutosaveForTest()
    }

    override fun tearDown() {
        runCatching { DataSourceSelector.resetExecDocAutosaveForTest() }
        runCatching { ExecDocAutosaveHost.getInstance().detachListenerForTest() }
        super.tearDown()
    }

    // ======================== 核心：监听随 Disposable 卸载 ========================

    /**
     * install → dispose → 变更断言零效果；卸载后重挂可用且恢复排任务（重挂只服务测试卸载
     * 模拟/极端重装，容器级卸载见 ExecDocAutosaveHost KDoc）。
     */
    fun testListenerDetachedWithDisposableStopsScheduling() {
        ensureInstalled()
        val doc = execDoc()
        assertNotNull("前置：exec 文档应可取", doc)

        changeDoc(doc!!, "SELECT detach-1")
        assertTrue("挂载期：exec 文档变更应排防抖任务", DataSourceSelector.execDocAutosaveScheduledForTest())
        DataSourceSelector.resetExecDocAutosaveForTest()

        ExecDocAutosaveHost.getInstance().detachListenerForTest()
        assertFalse("卸载后挂载态应复位", ExecDocAutosaveHost.getInstance().isListenerAttached)
        changeDoc(doc, "SELECT detach-2")
        assertFalse(
            "T-PS4-11 核心：监听随 Disposable 卸载后变更不得再进防抖链",
            DataSourceSelector.execDocAutosaveScheduledForTest(),
        )

        // 卸载后重挂可用，行为恢复等价
        ensureInstalled()
        changeDoc(doc, "SELECT detach-3")
        assertTrue("重挂后 exec 文档变更应恢复排防抖任务", DataSourceSelector.execDocAutosaveScheduledForTest())
    }

    // ======================== 快判路径行为等价 ========================

    /** exec 文档首次变更（原链探测 + 建缓存）与二次变更（快判短路）都照常排防抖。 */
    fun testFastPathSchedulesForExecDocBothProbes() {
        ensureInstalled()
        val doc = execDoc()!!

        changeDoc(doc, "SELECT first")
        assertTrue("首次变更（原链探测建缓存）应排防抖任务", DataSourceSelector.execDocAutosaveScheduledForTest())
        DataSourceSelector.resetExecDocAutosaveForTest()

        changeDoc(doc, "SELECT second")
        assertTrue("二次变更（快判身份命中短路）行为等价：仍应排防抖任务", DataSourceSelector.execDocAutosaveScheduledForTest())
    }

    /** 非 exec 文档变更零动作（原链回退路径不命中）；孤儿文档零动作。 */
    fun testNonExecDocStillNoop() {
        ensureInstalled()
        val otherFile = File(project.basePath!!, "plain-not-exec.sql")
        otherFile.writeText("SELECT 1")
        val otherVf = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(otherFile)
        }
        assertNotNull(otherVf)
        val otherDoc = FileDocumentManager.getInstance().getDocument(otherVf!!)
        assertNotNull(otherDoc)
        changeDoc(otherDoc!!, "SELECT 2")
        assertFalse("非 exec 文档变更不得排防抖任务", DataSourceSelector.execDocAutosaveScheduledForTest())

        // 工厂孤儿文档（无主文件）零动作——原链 getFile null 早退，缓存不得误建
        val orphan = EditorFactory.getInstance().createDocument("orphan")
        changeDoc(orphan, "orphan changed")
        assertFalse("孤儿文档变更不得排防抖任务", DataSourceSelector.execDocAutosaveScheduledForTest())
    }

    /** 闸门（T-PreviewFast P4）先于快判：闸门开着时即便快判命中也不排防抖（程序性写入语义不变）。 */
    fun testGateCheckPrecedesFastPath() {
        ensureInstalled()
        val doc = execDoc()!!
        changeDoc(doc, "SELECT warm")
        DataSourceSelector.resetExecDocAutosaveForTest() // 快判缓存已命中该 doc

        ExecDocAutosaveGate.suppressDuring {
            changeDoc(doc, "SELECT suppressed")
        }
        assertFalse(
            "闸门开着时快判命中也不得排防抖（与原路径等价）",
            DataSourceSelector.execDocAutosaveScheduledForTest(),
        )
    }

    // ======================== 辅助 ========================

    /** 经生产挂载点安装监听（exec 路径 vf 才触发；幂等）。 */
    private fun ensureInstalled() {
        val vf = refreshVf() ?: error("exec vf 应可刷新")
        DataSourceSelector.markCachedTempPsiFileForTest(project, vf)
        assertTrue("前置：监听应已挂载", ExecDocAutosaveHost.getInstance().isListenerAttached)
    }

    private fun execDoc(): Document? {
        val vf = refreshVf() ?: return null
        return FileDocumentManager.getInstance().getDocument(vf)
    }

    private fun changeDoc(doc: Document, text: String) {
        WriteCommandAction.runWriteCommandAction(project) { doc.setText(text) }
    }

    private fun refreshVf(): VirtualFile? = WriteIntentReadAction.compute<VirtualFile?> {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
    }
}
