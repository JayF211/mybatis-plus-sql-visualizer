package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 自动保存历史 SQL 到文件（F-43）：[SqlLogManager] 装配为 buffer 的 onEvicted 回调。
 *
 * 文件规则：
 *  - 默认路径：.idea/mybatis-plus-sql-visualizer/sql-history/
 *  - 文件名模板：sql-history-$yyyy-MM-dd$.sql（支持 $yyyy$ $MM$ $dd$ $HH$ $mm$ $ss$ 占位符）
 *  - 文件滚动：单文件 ≥100MB 时滚动（.2.sql、.3.sql…）
 *  - 格式：`-- 时间戳\nSQL\n\n`（纯文本，可直接在数据库工具中打开）
 *
 * 生命周期跟随 [SqlLogManager]：manager dispose 时 [close] 释放文件句柄。
 */
class SqlLogAutoSaver {

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var saveDir: String = ""
    private var fileNamePattern: String = ""
    private var currentResolvedName: String = ""  // 当前解析后的文件名（不含滚动序号）
    private var currentIndex: Int = 1  // 当前滚动序号（1=无后缀）

    /** 单文件滚动阈值（100MB）。 */
    private val rollThresholdBytes = 100L * 1024 * 1024

    /**
     * 配置保存参数（设置变更时调用）。
     * @param customPath 用户自定义目录（空串 = 默认 .idea 路径）
     * @param pattern 文件名模板（空串 = 默认模板）
     * @param projectBasePath 项目基路径（默认路径计算用）
     */
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
     * 首次写入时懒打开文件。
     */
    fun write(entry: LogSqlEntry) {
        // 确保 writer 就绪，并处理文件切换/滚动
        val w = ensureWriter() ?: return
        // 再次检查切换/滚动（ensureWriter 可能刚切换了文件）
        val resolved = resolveFileName()
        if (resolved != currentResolvedName || (currentFile?.length() ?: 0) >= rollThresholdBytes) {
            // 切换后重新写入（递归一次）
            write(entry)
            return
        }
        writeEntry(w, entry)
    }

    /** 确保 writer 就绪（首次打开 / 切换文件 / 滚动）。 */
    private fun ensureWriter(): BufferedWriter? {
        if (writer != null) return writer
        // 首次写入：打开新文件
        currentResolvedName = resolveFileName()
        currentIndex = 1
        openWriterForCurrent()
        return writer
    }

    /** 根据 currentResolvedName + currentIndex 打开文件。 */
    private fun openWriterForCurrent() {
        val file = File(currentFileName())
        runCatching {
            file.parentFile?.mkdirs()
            writer = Files.newBufferedWriter(file.toPath(), Charsets.UTF_8)
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

    /** 写入单条条目到文件。 */
    private fun writeEntry(w: BufferedWriter, entry: LogSqlEntry) {
        runCatching {
            synchronized(w) {
                w.write("-- ${formatTimestamp(entry.timestamp)}\n")
                w.write(entry.restoredSql)
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
     */
    fun resolveFileName(): String {
        val now = System.currentTimeMillis()
        return fileNamePattern.replace(PLACEHOLDER_REGEX) { mr ->
            val formatStr = mr.groupValues[1]
            try {
                val formatted = SimpleDateFormat(formatStr).format(Date(now))
                // 若格式化结果与输入一致（说明无日期字符），原样保留
                if (formatted == formatStr) mr.value else formatted
            } catch (_: Exception) {
                // 非法日期格式原样保留
                mr.value
            }
        }
    }

    /** 当前打开的保存文件（测试访问 + 状态展示用）。 */
    fun currentFile(): File? = currentFile

    /** 关闭文件句柄（manager dispose 或路径变更时调用）。 */
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

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp))

    companion object {
        private val LOG = Logger.getInstance(SqlLogAutoSaver::class.java)
        private val DEFAULT_DIR = "mybatis-plus-sql-visualizer${File.separator}sql-history"
        /** 匹配 $变量$ 占位符。 */
        private val PLACEHOLDER_REGEX = Regex("""\$([^$]+)\$""")
    }
}
