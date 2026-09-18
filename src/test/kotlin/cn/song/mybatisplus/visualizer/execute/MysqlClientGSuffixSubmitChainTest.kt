package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import com.intellij.openapi.application.WriteAction
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
 * T-MysqlGStrip T-MG-02/T-MG-03：提交链挂点的 `\G` 剥离 headless 用例。
 *
 * 沙盒默认 test 任务无 Database 插件（`selectAndExecute` 走 `execute.noDatabase` 早退），
 * `chooseAndPrepare`/`executeOnConsole` 的写点不可直达——本用例照 ExecTempFileFixTest /
 * ExecDocSync2Test 的既有口径，测「提交链总口决策 + 生产同款 document 写路径 + 历史两态」：
 *  1. [DataSourceSelector.stripGSuffixForSubmit] 的剥离决策（提交链两个挂点共用同一入口）；
 *  2. 剥后文本经 [DataSourceSelector.writeVfTextViaDocument]（exec 文件/控制台文档唯一写路径）
 *     落盘 → 断言 exec 文件内容 = 剥后文本（「剥离后进 exec 文件」）；
 *  3. 历史链记原文——SqlHistoryManager 取 [MysqlClientGSuffix.Result.original]，两态分离断言；
 *  4. 非 MySQL 方言不剥（验收 §3：非 MySQL 方言行为不变）。
 *
 * 落盘比对一律走 [lf] 归一：平台 document 落盘在本机（Windows）按系统行分隔符写出 CRLF
 * （既有 T-ExecDocSync 写路径的平台行为，与本任务无关；ExecDocSync2Test 只断言单行内容故未暴露）。
 */
class MysqlClientGSuffixSubmitChainTest : BasePlatformTestCase() {

    private lateinit var execFile: File

    override fun setUp() {
        super.setUp()
        val base = project.basePath ?: error("project.basePath null")
        execFile = File(
            base,
            ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql",
        )
        // 测试隔离：VFS 内删除重建全新 vf，防应用级 document 缓存跨用例串场（ExecDocSync2Test 同款）
        val stale = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
        }
        if (stale != null) {
            WriteAction.compute<Unit, Throwable> { stale.delete(this) }
        }
    }

    // ======================== 剥离后进 exec 文件 + 历史记原文（两态分离） ========================

    /**
     * 核心两态断言：MySQL 数据源 URL → 提交文本剥离并落进 exec 文件；同一结果对象的
     * original 仍是原文，历史链（SqlHistoryManager）记的是它。
     */
    fun testStrippedTextLandsInExecFileWhileHistoryKeepsOriginal() {
        val raw = "SELECT id FROM t WHERE a = 1\\G"
        val r = DataSourceSelector.stripGSuffixForSubmit(raw, "jdbc:mysql://localhost:3306/jpa_demo")
        assertTrue("MySQL 数据源应剥离", r.stripped)
        assertEquals("SELECT id FROM t WHERE a = 1", r.submitText)
        assertEquals("历史链取原文", raw, r.original)

        writeViaProductionPath(r.submitText)
        assertEquals("剥离后文本进 exec 文件", "SELECT id FROM t WHERE a = 1", lf(diskText()))

        val manager = SqlHistoryManager()
        manager.add(SqlHistoryEntry(sql = r.original, compactSql = r.original, timestamp = 1L))
        assertEquals("历史记剥离前原文", raw, manager.entries.first().sql)
        assertFalse("两态确实不同（防实现退化为单态）", manager.entries.first().sql == r.submitText)
    }

    /** 方言不可得（控制台 URL 取不到）→ 默认剥离（计划 §三定案），落盘同样是剥后文本。 */
    fun testUnknownDialectStillStrips() {
        val raw = "SELECT 1\\G"
        val r = DataSourceSelector.stripGSuffixForSubmit(raw, null)
        assertTrue("方言不可得 → 默认剥离", r.stripped)
        writeViaProductionPath(r.submitText)
        assertEquals("SELECT 1", lf(diskText()))
    }

    /** 验收 §3：非 MySQL 方言行为不变——不剥，exec 文件内容与原文逐字节一致。 */
    fun testNonMySqlDialectUnchanged() {
        val raw = "SELECT 1\\G"
        val r = DataSourceSelector.stripGSuffixForSubmit(raw, "jdbc:postgresql://localhost:5432/demo")
        assertFalse("非 MySQL 方言不剥", r.stripped)
        assertEquals(raw, r.submitText)
        writeViaProductionPath(r.submitText)
        assertEquals("exec 文件与原文逐字节一致", raw, lf(diskText()))
    }

    /** 无 `\G` 的常规 SQL：决策为未剥离，写路径零额外动作（既有行为不回归）。 */
    fun testPlainSqlUnchanged() {
        val raw = "SELECT 1;\nSELECT 2;"
        val r = DataSourceSelector.stripGSuffixForSubmit(raw, "jdbc:mysql://localhost:3306/db")
        assertFalse("无后缀不剥", r.stripped)
        assertEquals(raw, r.submitText)
        writeViaProductionPath(r.submitText)
        assertEquals(raw, lf(diskText()))
    }

    // ======================== 辅助 ========================

    /** 生产同款写路径（WriteAction.compute 内调 writeVfTextViaDocument，ExecDocSync2Test 同款包装）。 */
    private fun writeViaProductionPath(sql: String) {
        assertTrue("exec 目录应可创建", execFile.parentFile.exists() || execFile.parentFile.mkdirs())
        if (!execFile.exists()) execFile.writeText("")
        val vf = refreshVf()
        assertNotNull("exec vf 应 VFS 可见", vf)
        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
        WriteAction.compute<Unit, Throwable> {
            DataSourceSelector.writeVfTextViaDocument(project, vf!!, sql)
        }
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(vf!!)
        assertNotNull(doc)
        assertFalse("写路径应清「已修改未保存」态（T-EC-01 语义）", fdm.isDocumentUnsaved(doc!!))
    }

    private fun refreshVf(): VirtualFile? = WriteIntentReadAction.compute<VirtualFile?> {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
    }

    private fun diskText(): String = execFile.readText()

    /** 行分隔符归一（平台落盘在 Windows 写 CRLF，见类 KDoc）。 */
    private fun lf(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')
}
