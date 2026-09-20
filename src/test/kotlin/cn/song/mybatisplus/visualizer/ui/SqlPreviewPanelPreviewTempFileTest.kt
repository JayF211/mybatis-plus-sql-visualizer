package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * T-FixN2（N2 预览临时文件 File Cache Conflict）回归守护。
 *
 * 缺陷形态（真机必现，第二轮复核 3 次冲突询问 / 2 次模态弹窗）：预览临时文件名固定为
 * `mpsql-preview.sql`，每次预热重写同一路径，而该路径的 document 里存着上一轮装配写入的真 SQL
 * （`assemblePreviewEditor` 只 `setText`，从不落盘）→ 平台 `MemoryDiskConflictResolver` 判定
 * 「内存与磁盘均变」→ 弹模态「File Cache Conflict」并抢焦点；选「Load File System Changes」
 * 还会把真 SQL 换成磁盘上的占位空内容。
 *
 * 本测试锁两条不变式：
 *  - **命名唯一**：同一面板连续两次装配不得落到同一个文件（固定名即缺陷本体）；
 *  - **清理闭环**：上一轮的文件在下一轮 [SqlPreviewPanel.clear] 时被回收，预览目录不随预览次数增长。
 *
 * 另加一条**机制断言**：方言映射改指父目录后，目录内新建的文件应被目录映射继承（平台
 * `PerFileMappingsBase.getMappingForHierarchy` 自下而上逐级查表），否则每轮预热都会白付一次
 * `setMapping`（push all dumb）+ workspace.xml 按文件名逐个累积。
 *
 * headless 前提与 [SqlPreviewPanelPrewarmConsumeTest] 一致：预热跑在池线程（无 EDT 泵），故用例
 * 边抽 EDT 队列边有界轮询 [PrewarmAwait] 出结果，再驱动真实消费链取「编辑器文档落在哪个文件上」。
 */
class SqlPreviewPanelPreviewTempFileTest : BasePlatformTestCase() {

    private fun result() = PreviewResult(
        sql = "SELECT id\nFROM user\nWHERE username = ?",
        compactSql = "SELECT id FROM user WHERE username = ?",
        unresolvedParams = listOf("username"),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "user",
        columns = listOf("id", "username"),
    )

    /** 预览临时文件目录（生产同款路径：`<project>/.idea/sql-visualizer/preview/`）。 */
    private fun previewDir(): File = File(
        project.basePath!!,
        ".idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}preview",
    )

    /** 预览目录内本插件的临时文件（前缀 `mpsql-preview-`，与生产命名同口径）。 */
    private fun previewDirTempFiles(): List<File> =
        previewDir().listFiles { f -> f.isFile && f.name.startsWith("mpsql-preview-") && f.name.endsWith(".sql") }
            ?.toList().orEmpty()

    /** 有界等待预热线程出结果（边等边抽 EDT 队列——方言预映射两段式要 EDT 才推进）。 */
    private fun awaitPrewarm(panel: SqlPreviewPanel) {
        val await = panel.prewarmAwaitForTest()
        assertNotNull("面板构造期应已发起预热", await)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && !await!!.isDone()) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("预热应在预算内出结果（成功或失败）", await!!.isDone())
        assertNotNull(
            "headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）",
            await.joinOrNull(),
        )
    }

    /** 驱动一次真实装配，返回编辑器文档落在的项目内临时文件（降级分支返回 null）。 */
    private fun assembleAndCaptureFile(panel: SqlPreviewPanel): File {
        panel.showResult(result())
        UIUtil.dispatchAllInvocationEvents()
        val doc = panel.editorForTest()?.document
        assertNotNull("应已创建预览编辑器", doc)
        val vf = FileDocumentManager.getInstance().getFile(doc!!)
        assertNotNull("预热产物可用 → 装配应落在项目内预览临时文件上（降级分支无 VFS 文件）", vf)
        return File(vf!!.path)
    }

    // ------------------------------------------------------------------
    // 一、命名形态与唯一性
    // ------------------------------------------------------------------

    fun test临时文件命名_带进程与序号且落在预览目录() {
        val panel = SqlPreviewPanel(project)
        try {
            awaitPrewarm(panel)
            val f = assembleAndCaptureFile(panel)
            assertEquals(
                "临时文件应落在预览目录内，实际：${f.path}",
                previewDir().canonicalPath,
                f.parentFile?.canonicalPath,
            )
            assertTrue(
                "命名形态应为 mpsql-preview-<pid>-<seq>.sql（可读可定位），实际：${f.name}",
                Regex("^mpsql-preview-\\d+-\\d+\\.sql$").matches(f.name),
            )
        } finally {
            panel.clear()
        }
    }

    fun test连续两次装配_临时文件不同名且旧文件被回收() {
        val panel = SqlPreviewPanel(project)
        try {
            awaitPrewarm(panel)
            val first = assembleAndCaptureFile(panel)
            assertTrue("首次装配的临时文件应存在：${first.path}", first.isFile)

            // clear() = releaseEditor（回收本面板临时文件）+ 重新预热（新 await、新文件名）
            panel.clear()
            awaitPrewarm(panel)
            val second = assembleAndCaptureFile(panel)

            assertNotEquals(
                "同一面板连续两次装配不得落到同一个文件——固定名（原 mpsql-preview.sql）会让预热重写" +
                    "上一轮装配留下的「已修改未保存」文档所在路径，即 N2 File Cache Conflict 的本体",
                first.path,
                second.path,
            )
            assertFalse("上一轮装配的临时文件应在 clear() 时被回收，实际仍在：${first.path}", first.exists())
            assertTrue("当前装配的临时文件应存在：${second.path}", second.isFile)
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // 二、清理闭环：预览目录不随预览次数增长
    // ------------------------------------------------------------------

    fun test重复预览_预览目录不随预览次数增长() {
        val panel = SqlPreviewPanel(project)
        try {
            // 先让**构造期**预热落地再进循环：clear() 会立刻发起新一轮预热，若此刻构造期预热仍在途，
            // 两个预热各自 publish 会让 prewarmedTempFile 被晚到者覆盖，而消费侧用引用相等判「产物
            // 未被消费」→ 装配降级。那是既有 P9 竞态（releaseEditor 作废产物却不重臂 prewarmAwait，
            // 见 SqlPreviewPanelPrewarmConsumeTest 类注释），与本项无关，本用例不制造该交错——
            // 否则断言测的是竞态而不是「命名唯一 + 清理闭环」。
            awaitPrewarm(panel)
            val baseline = previewDirTempFiles().size
            val seen = mutableListOf<File>()
            repeat(3) {
                panel.clear()
                awaitPrewarm(panel)
                val cur = assembleAndCaptureFile(panel)
                // 本轮装配前，此前每一轮的文件都应已被回收（唯一名 + 面板生命周期删除）
                seen.forEach { old ->
                    assertFalse("第 ${seen.size} 轮起旧临时文件应已回收，实际仍在：${old.path}", old.exists())
                }
                seen.add(cur)
            }
            val after = previewDirTempFiles()
            assertTrue(
                "预览目录文件数不得随预览次数增长（基线 $baseline → 实测 ${after.size}）：" +
                    after.joinToString { it.name },
                after.size <= baseline + 1,
            )
            assertEquals("目录里留下的应是当前这一轮的临时文件", seen.last().path, after.singleOrNull()?.path ?: seen.last().path)
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // 三、机制断言：方言映射对象是父目录，且目录 vf 实例跨文件名稳定
    // ------------------------------------------------------------------

    /**
     * 身份判定改指目录后的两条前提，逐条锁死：
     *  - 映射对象**不是文件本身**（旧行为）而是预览目录；
     *  - 目录 vf 实例在 VFS 里按路径唯一——下一轮预热的新文件名拿到的是**同一个实例**，
     *    否则 [SqlPreviewPanel.mapPreviewDialectBeforePsi] 的 `===` 幂等短路恒假、每轮预热白付一次
     *    `setMapping`（push all dumb）+ workspace.xml 按文件名逐个累积。
     *
     * 说明：映射本身（setMapping 生效 → 新文件继承 MySQL）**不在单测可断言范围**——
     * `com.intellij.sql.dialects.SqlDialectMappings` 不在测试运行时 classpath
     * （实测 `ClassNotFoundException`），真机由装配链的补全/注释行为覆盖。
     */
    fun test方言映射对象_取父目录且跨文件名稳定() {
        val panel = SqlPreviewPanel(project)
        try {
            awaitPrewarm(panel)
            val current = assembleAndCaptureFile(panel)
            val currentVf = LocalFileSystem.getInstance().findFileByIoFile(current)
            assertNotNull("当前预览临时文件应在 VFS 中可见：${current.path}", currentVf)
            val target = SqlPreviewPanel.previewDialectMappingTarget(currentVf!!)
            assertNotEquals("映射对象不得是文件本身（那是唯一文件名前的旧行为）", currentVf, target)
            assertEquals(
                "映射对象应是预览目录",
                previewDir().canonicalPath,
                File(target.path).canonicalPath,
            )

            // 下一轮预热的文件名（同目录、不同名）→ 必须拿到同一个目录 vf 实例。
            // 注意：light fixture 的 addFileToProject 落在 temp:// 文件系统，与生产路径
            // （java.io 写在 project.basePath 下的本地目录）不是同一 vf 树，故此处按生产同款手法造文件。
            val nextIo = File(previewDir(), SqlPreviewPanel.nextPreviewTempFileName())
            nextIo.writeText("")
            val next = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(nextIo)
            assertNotNull("下一轮临时文件应在 VFS 中可见：${nextIo.path}", next)
            assertSame(
                "预览目录 vf 实例须跨文件名稳定（幂等短路靠引用相等）",
                target,
                SqlPreviewPanel.previewDialectMappingTarget(next!!),
            )
        } finally {
            panel.clear()
        }
    }
}
