package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import cn.song.mybatisplus.visualizer.log.withTerminator
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/**
 * 自动保存历史 SQL 到文件（F-43）：[SqlLogManager] 装配为 buffer 的 onEvicted 回调。
 *
 * 文件规则：
 *  - 默认路径：.idea/sql-visualizer/sql-history/（F-44-4：随插件更名由旧名
 *    mybatis-plus-sql-visualizer 改为 sql-visualizer，**不考虑向前兼容**——旧目录数据不再读取）
 *  - 文件名模板：sql-history-$yyyy-MM-dd$.sql（支持 $yyyy$ $MM$ $dd$ $HH$ $mm$ $ss$ 占位符）
 *  - 文件滚动：单文件 ≥100MB 时滚动（.2.sql、.3.sql…）
 *  - 格式：`-- 时间戳\nSQL\n\n`（纯文本，可直接在数据库工具中打开）
 *
 * 生命周期跟随 [SqlLogManager]：manager dispose 时 [close] 释放文件句柄。
 */
class SqlLogAutoSaver(
    /** 时钟（测试注入用），默认系统时钟。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 单文件滚动阈值（默认 100MB），测试注入小值用。 */
    internal val rollThresholdBytes: Long = 100L * 1024 * 1024,
) {

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var saveDir: String = ""
    private var fileNamePattern: String = ""
    private var currentResolvedName: String = ""  // 当前解析后的文件名（不含滚动序号）
    private var currentIndex: Int = 1  // 当前滚动序号（1=无后缀）

    /**
     * 配置保存参数（设置变更时调用）。
     * @param customPath 用户自定义目录（空串 = 默认 .idea 路径）
     * @param pattern 文件名模板（空串 = 默认模板）
     * @param projectBasePath 项目基路径（默认路径计算用）
     */
    @Synchronized
    fun configure(customPath: String, pattern: String, projectBasePath: String?) {
        close()
        saveDir = resolveDir(customPath, projectBasePath)
        fileNamePattern = pattern.ifBlank { PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN }
        currentResolvedName = ""
        currentIndex = 1
    }

    /** 解析最终保存目录。 */
    private fun resolveDir(customPath: String, projectBasePath: String?): String {
        if (customPath.isNotBlank()) return customPath.trim()
        return if (projectBasePath != null) {
            "$projectBasePath${File.separator}.idea${File.separator}$DEFAULT_DIR"
        } else {
            "${System.getProperty("user.home")}${File.separator}.idea${File.separator}$DEFAULT_DIR"
        }
    }

    /**
     * 写入被淘汰的条目（buffer.onEvicted 回调）。
     * 懒打开文件；文件名变化（跨天/模板变更）时重开并复位滚动序号，
     * 单文件超限时递增滚动序号。
     */
    @Synchronized
    fun write(entry: LogSqlEntry) {
        val resolved = resolveFileName()
        if (writer == null || resolved != currentResolvedName) {
            // 首次写入 / 文件名切换：重开文件，滚动序号复位
            close()
            currentResolvedName = resolved
            currentIndex = 1
            openWriterForCurrent()
        } else if ((currentFile?.length() ?: 0L) >= rollThresholdBytes) {
            // 单文件超限：滚动（xxx.sql → xxx.2.sql）
            close()
            currentIndex += 1
            openWriterForCurrent()
        }
        val w = writer ?: return
        writeEntry(w, entry)
    }

    /** 根据 currentResolvedName + currentIndex 打开文件。 */
    private fun openWriterForCurrent() {
        val file = File(currentFileName())
        runCatching {
            file.parentFile?.mkdirs()
            // APPEND 模式：多次启动后历史内容追加而非覆盖
            writer = Files.newBufferedWriter(
                file.toPath(), Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
            currentFile = file
        }.onFailure {
            LOG.warn("自动保存历史 SQL：无法打开文件 ${file.absolutePath}", it)
            writer = null
            currentFile = null
        }
    }

    /** 当前应写入的完整文件名（含目录 + 滚动序号后缀）。 */
    private fun currentFileName(): String {
        val name = currentResolvedName
        val fullName = if (name.contains(File.separator)) name else "$saveDir${File.separator}$name"
        if (currentIndex <= 1) return fullName
        // 在扩展名前插入滚动序号：xxx.sql → xxx.2.sql
        val dotIdx = fullName.lastIndexOf('.')
        return if (dotIdx > 0) {
            "${fullName.substring(0, dotIdx)}.$currentIndex${fullName.substring(dotIdx)}"
        } else {
            "$fullName.$currentIndex"
        }
    }

    /**
     * 写入单条条目到文件。
     * 格式（F-44-2）：-- source: xxx | time: xxx | timestamp --\nSQL\n
     *
     * 旧版格式（F-43）：-- timestamp\nSQL\n（向后兼容，Reader 同时支持）
     * 旧版 F-44 一度使用 `--- source: ... ---`（三个 `-`），本方法回归为标准 SQL 单行注释 `--`
     * （更严格 SQL 方言对 3 个 `-` 整行注释兼容性差，且与历史 F-43 注释一致便于人眼识读）。
     * Reader 正则兼容 2/3 个 `-` 起结，已存在的旧 3 `-` 文件仍可读。
     */
    private fun writeEntry(w: BufferedWriter, entry: LogSqlEntry) {
        runCatching {
            synchronized(w) {
                // 头部：源码位置 | 耗时 | 时间戳
                val source = entry.sourceFile?.let { file ->
                    if (entry.sourceLine > 0) "$file:${entry.sourceLine}" else file
                } ?: "unknown"
                val duration = entry.executionTimeMs?.let { "${it}ms" } ?: "—"
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).format(Date(entry.timestamp))
                w.write("-- source: $source | time: $duration | $timestamp --\n")
                w.write(withTerminator(entry.restoredSql))
                w.write("\n\n")
                w.flush()
            }
        }.onFailure {
            LOG.warn("自动保存历史 SQL：写入失败", it)
        }
    }

    /**
     * 解析文件名模板（替换 $日期格式$ 占位符为 SimpleDateFormat 格式化结果）。
     * 若占位符内容不含日期模式字符（如 $unknown$），则原样保留。
     * Windows 文件名不允许 :，自动替换为 -（macOS 也兼容）。
     */
    fun resolveFileName(): String = resolveFileName(clock())

    internal fun resolveFileName(now: Long): String {
        return fileNamePattern.replace(PLACEHOLDER_REGEX) { mr ->
            val formatStr = mr.groupValues[1]
            try {
                val formatted = formatter(formatStr).format(Date(now))
                // 若格式化结果与输入一致（说明无日期字符），原样保留
                if (formatted == formatStr) mr.value else formatted
            } catch (_: Exception) {
                // 非法日期格式原样保留
                mr.value
            }
        }.replace(':', '-')
    }

    /** SimpleDateFormat 非线程安全，按线程+模板缓存，避免每条日志重建。 */
    private val formatterCache =
        ThreadLocal.withInitial<HashMap<String, SimpleDateFormat>> { HashMap() }

    private fun formatter(formatStr: String): SimpleDateFormat =
        formatterCache.get().getOrPut(formatStr) { SimpleDateFormat(formatStr, Locale.ENGLISH) }

    /** 当前打开的保存文件（测试访问 + 状态展示用）。 */
    fun currentFile(): File? = currentFile

    /** 关闭文件句柄（manager dispose 或路径变更时调用）。 */
    @Synchronized
    fun close() {
        writer?.let { w ->
            runCatching {
                synchronized(w) { w.close() }
            }.onFailure {
                LOG.debug("自动保存历史 SQL：关闭文件异常", it)
            }
        }
        writer = null
        currentFile = null
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogAutoSaver::class.java)

        /**
         * 项目内插件数据根目录名（F-44-4）：随插件更名「SQL Visualizer」由旧名
         * `mybatis-plus-sql-visualizer` 改为 `sql-visualizer`，**不考虑向前兼容**
         * （旧 `.idea/mybatis-plus-sql-visualizer/` 数据不再读取，历史 SQL 与新捕获从新目录起算）。
         * 其他模块（HistorySqlLogPanel / SqlLogPanel.hasHistoryFiles / DataSourceSelector）统一引用本常量。
         */
        const val DATA_DIR_NAME = "sql-visualizer"

        private val DEFAULT_DIR = "$DATA_DIR_NAME${File.separator}sql-history"
        /** 匹配 $变量$ 占位符。 */
        private val PLACEHOLDER_REGEX = Regex("""\$([^$]+)\$""")
    }
}
