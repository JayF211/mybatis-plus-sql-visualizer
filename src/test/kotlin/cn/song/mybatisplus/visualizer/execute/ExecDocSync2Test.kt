package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-ExecDocSync2：执行文件 File Cache Conflict 第四轮（未保存窗口清零）守护测试。
 *
 * 第四轮根因（开发计划 §1.2）：T-EC-01 纪律只覆盖了插件自有写点（projectExecFile /
 * executeOnConsole），漏了 console 侧四个同步点——console 编辑器文档即 exec 文件 document
 * （attachConsole 挂载点同文件），这些点只 commit 不落盘，document 滞留「已修改未保存」+
 * 窗口期任意外部改盘 + VFS refresh = 冲突弹窗。
 *
 * 沙盒无 Database 插件：G1-G4 弹窗链与 contentAdded 回调不可直测，测同步原语与过滤器
 * （ExecDocSyncTest 同惯例，测试文件走生产同款路径）。
 */
class ExecDocSync2Test : BasePlatformTestCase() {

    private lateinit var execFile: File

    override fun setUp() {
        super.setUp()
        val base = project.basePath ?: error("project.basePath null")
        execFile = File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")
        // 测试隔离：VFS 内删除重建全新 vf，防应用级 document 缓存跨用例串场（ExecDocSyncTest 同款）
        val stale = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
        }
        if (stale != null) {
            WriteAction.compute<Unit, Throwable> { stale.delete(this) }
        }
    }

    // ======================== F1/F2：console vf 同步落盘收口 ========================

    /**
     * F1 核心：doc 已改未存且**内容恰等于目标**（reuseSharedConsole G1 setText 后 vf 同步块
     * 的真实形态）——收口后 text 相等分支也必须落盘清 unsaved 态（旧实现只 commit，窗口留存）。
     */
    fun testSyncConsoleVfPersistsAndClearsUnsavedFlag() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(vf!!)
        assertNotNull(doc)
        // 模拟 G1：编辑器 setText 新 SQL（已改未存，磁盘仍 SELECT 1）
        WriteCommandAction.runWriteCommandAction(project) { doc!!.setText("SELECT 2") }
        assertTrue("前置：document 应处于已修改未保存态", fdm.isDocumentUnsaved(doc!!))

        // 生产 F1/F2 调用形态 = WriteAction.compute 内调 writeVfTextViaDocument（裸调 setText
        // 硬抛 write-access，第二跑实证），测试照抄同款包装
        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
        WriteAction.compute<Unit, Throwable> {
            DataSourceSelector.writeVfTextViaDocument(project, vf, "SELECT 2")
        }

        assertEquals("text 相等也必须落盘（T-EC-01 同临界区落盘语义）", "SELECT 2", diskText())
        assertFalse("F1 核心：不得滞留已修改未保存态", fdm.isDocumentUnsaved(doc))
    }

    /** F1 覆盖分支：doc 已改成别的文本 → 同步以 sql 为准收敛（覆盖用户手改，既有裁定）。 */
    fun testSyncConsoleVfOverwritesDivergedDoc() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(vf!!)
        assertNotNull(doc)
        WriteCommandAction.runWriteCommandAction(project) { doc!!.setText("user typed") }

        // 生产 F1/F2 调用形态 = WriteAction.compute 内调 writeVfTextViaDocument（裸调 setText
        // 硬抛 write-access，第二跑实证），测试照抄同款包装
        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
        WriteAction.compute<Unit, Throwable> {
            DataSourceSelector.writeVfTextViaDocument(project, vf, "SELECT 9")
        }

        assertEquals("分叉文档以 sql 为准", "SELECT 9", diskText())
        assertEquals("SELECT 9", doc!!.text)
        assertFalse(fdm.isDocumentUnsaved(doc))
    }

    // ======================== F4：refresh 前收敛原语补用例 ========================

    /**
     * F4 依赖形态：doc 已改未存（内存=新 SQL）+ VFS/磁盘陈旧（旧 SQL）——
     * syncExecVfContentIfNeeded 须经 vfsContent≠sql 分叉探测触发 EDT 收敛并落盘。
     */
    fun testSyncVfContentIfNeededConvergesUnsavedDocWithStaleVfs() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(vf!!)
        assertNotNull(doc)
        WriteCommandAction.runWriteCommandAction(project) { doc!!.setText("SELECT 2") }
        assertEquals("前置：磁盘应仍为旧 SQL", "SELECT 1", diskText())

        val repaired = DataSourceSelector.syncExecVfContentIfNeeded(project, vf, "SELECT 2")

        assertTrue("分叉应触发修复同步", repaired)
        assertEquals("F4：收敛须落盘（refresh 前消除断言条件）", "SELECT 2", diskText())
        assertFalse("收敛后不得滞留已修改未保存态", fdm.isDocumentUnsaved(doc!!))
        assertFalse("已收敛 → 再检应为 no-op", DataSourceSelector.syncExecVfContentIfNeeded(project, vf, "SELECT 2"))
    }

    // ======================== F5：防抖自动落盘原语 ========================

    /** F5 原语：unsaved doc → 落盘对齐并清 unsaved；未改 doc → no-op（false）。 */
    fun testAutosaveExecDocNowSavesUnsavedDocument() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(vf!!)
        assertNotNull(doc)

        assertFalse("未修改文档应 no-op", DataSourceSelector.autosaveExecDocNow(doc!!))

        WriteCommandAction.runWriteCommandAction(project) { doc!!.setText("SELECT 3") }
        assertTrue("unsaved doc 应触发落盘", DataSourceSelector.autosaveExecDocNow(doc))
        assertEquals("SELECT 3", diskText())
        assertFalse(fdm.isDocumentUnsaved(doc))
    }

    /** F5 原语：无主文件/失效文档 → 安全 no-op（false），不抛。 */
    fun testAutosaveExecDocNowNoopForOrphanDocument() {
        // 工厂孤儿文档（未关联任何文件的 document 是编辑期常态产物）。
        // 262 平台 EditorFactory 已无 releaseDocument（javap 实测，仅存 releaseEditor）——
        // 孤儿 document 由平台自管，测试内不再显式释放
        val orphan = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument("orphan")
        assertFalse("无主文档应 no-op", DataSourceSelector.autosaveExecDocNow(orphan))
    }

    // ======================== F5/F6：路径过滤器表驱动 ========================

    fun testIsExecVfPathTableDriven() {
        val dir = cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME
        val cases = listOf(
            "/proj/.idea/$dir/exec/mpsql-exec.sql" to true,
            "F:/code/proj/.idea/$dir/exec/mpsql-exec.sql" to true,
            "/proj/.idea/$dir/sql-history/20260917.sql" to false,
            "/proj/src/Main.sql" to false,
            "/proj/.idea/$dir/exec/mpsql-exec.sql.bak" to false,
            "/proj/.idea/$dir/exec/other.sql" to false,
        )
        cases.forEach { (path, expected) ->
            assertEquals("isExecVfPath($path)", expected, DataSourceSelector.isExecVfPath(path))
        }
    }

    // ======================== F6：探针订阅 smoke ========================

    /** F6 smoke：探针安装幂等 + beforeFileContentReload 广播不抛（日志取证路径）。 */
    fun testConflictProbePublishSmoke() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        assertNotNull(vf)
        DataSourceSelector.markCachedTempPsiFileForTest(project, vf!!)
        // 幂等：重复挂载不重复订阅（内部 set 守卫；不抛即通过）
        DataSourceSelector.markCachedTempPsiFileForTest(project, vf)
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        assertNotNull(doc)
        // 直接经 topic 广播一次 reload 前事件（生产由平台 reload 发起）。FILE_DOCUMENT_MANAGER
        // topic 声明为 TO_DIRECT_CHILDREN（app→project 单向），须在应用级总线发布——项目级总线
        // 发布硬抛 IllegalArgumentException（第三跑实证）；生产探针只订阅项目级（app 广播可达）
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .syncPublisher(com.intellij.openapi.fileEditor.FileDocumentManagerListener.TOPIC)
            .beforeFileContentReload(vf, doc!!)
        assertTrue("探针挂载后缓存槽应可用", DataSourceSelector.cachedTempPsiFileForTest())
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
