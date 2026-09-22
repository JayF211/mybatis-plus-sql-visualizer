package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * 预览临时文件目录级兜底清扫测试（T-PerfSweep6 T-PS6-04 / 性能报告发现 4）。
 *
 * 缺陷面：唯一命名（T-FixN2）后每次预热新建一个文件，异常路径（kill / 崩溃 / 未走 removeNotify）
 * 留下的文件不再被复用，`preview/` 只增不减。修法是目录级兜底三件按性价比取二——
 * ①年龄（> 7 天）②条数（> 200 按 mtime 删最旧），另加 ③dispose 补同款回收。
 *
 * **本类的硬判据是「宁漏删不误删」**：只删本插件自有命名（[cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope.isPrimaryPreviewFileName]）
 * 的文件，用户文件 / 二级内存名 / 原生控制台临时文件一律零删除。
 *
 * 注：`dispose()` 补回收（③）由既有面板生命周期用例覆盖（同款 `recoverPreviewTempFiles`），
 * 本类只直测目录清扫面（生产入口在 `projectPreviewFile` 首次解析预热目录时，经测试缝调用同一份实现）。
 *
 * k2j 适配：测试缝 `sweepStalePreviewFilesForTest` 与常量 `PREVIEW_DIR_MAX_FILES` 是 Java 侧
 * 包级可见成员，同包直访（与 master 同形）。
 */
class SqlPreviewTempSweepTest : BasePlatformTestCase() {

    private var dir: File? = null

    override fun setUp() {
        super.setUp()
        dir = java.nio.file.Files.createTempDirectory("mpsql-preview-sweep").toFile()
    }

    override fun tearDown() {
        try {
            dir?.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun pluginFile(name: String, ageMs: Long): File =
        File(dir, name).apply {
            writeText("select 1")
            setLastModified(System.currentTimeMillis() - ageMs)
        }

    private fun panel(): SqlPreviewPanel = SqlPreviewPanel(project)

    private val day = 24L * 60 * 60 * 1000

    fun test按年龄回收残留_且非本插件命名零删除() {
        val stalePlugin = pluginFile("mpsql-preview-12480-1.sql", 8 * day)
        val freshPlugin = pluginFile("mpsql-preview-12480-2.sql", 0L)
        // 非本插件命名的文件（一律零删除，即便同样「很旧」）
        val userSql = pluginFile("UserMapper.sql", 30 * day)
        val userTxt = pluginFile("notes.txt", 30 * day)
        val memoryName = pluginFile("mpsql-preview-memory.sql", 30 * day)
        val execTemp = pluginFile("mpsql-exec.sql", 30 * day)

        panel().sweepStalePreviewFilesForTest(dir)

        assertFalse("年龄 > 7 天的本插件命名残留应被回收", stalePlugin.exists())
        assertTrue("新鲜的本插件命名文件不得被删（宁漏删不误删）", freshPlugin.exists())
        assertTrue("用户 .sql 文件零删除", userSql.exists())
        assertTrue("无关扩展名零删除", userTxt.exists())
        assertTrue("二级内存名（mpsql-preview-memory.sql）不是一级形态，零删除", memoryName.exists())
        assertTrue("原生控制台临时文件（mpsql-exec.sql）零删除", execTemp.exists())
    }

    fun test按条数上限回收_删最旧且恰留上限() {
        val count = SqlPreviewPanel.PREVIEW_DIR_MAX_FILES + 5
        val files = (0 until count).map { i ->
            // mtime 递增：序号越小越旧
            pluginFile("mpsql-preview-777-${i + 1}.sql", (count - i).toLong() * 60_000L)
        }
        panel().sweepStalePreviewFilesForTest(dir)

        val remaining = dir?.listFiles { f -> f.isFile }?.toList() ?: emptyList()
        assertEquals("应恰留条数上限个文件", SqlPreviewPanel.PREVIEW_DIR_MAX_FILES, remaining.size)
        assertFalse("最旧的 5 个应被删除", files.take(5).any { it.exists() })
        assertTrue("最新的文件不得被删", files.last().exists())
    }

    fun test低于上限时不触发条数回收() {
        val files = (0 until 3).map { i -> pluginFile("mpsql-preview-777-${i + 1}.sql", 0L) }
        panel().sweepStalePreviewFilesForTest(dir)
        assertTrue("未撞上限时零删除", files.all { it.exists() })
    }

    fun test目录不存在时静默返回() {
        // 清扫是兜底，不得因目录缺失影响预览主链
        panel().sweepStalePreviewFilesForTest(File(dir, "not-exists"))
    }
}
