package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Wrapper 解析缓存（F-20）：按 PsiFile + modificationStamp 缓存解析结果。
 *
 * 目的：光标悬停/快捷键高频触发时避免重复做 PSI 引用搜索（ReferencesSearch 较重）。
 * 策略：
 *  - 键为 PsiFile（弱引用持有，避免阻止文件 GC）
 *  - 文件内容变更（modificationStamp 变化）即失效重建
 *  - 线程安全（UI 触发方经 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService.previewAsync]
 *    在后台线程 + EDT 并发调用）
 *
 * M1 为文件级缓存（一个文件通常一个 Wrapper 查询场景）；M3 接入 UI 层后按需演进。
 */
class WrapperCache {

    /** 缓存条目：文件修改戳 + 解析结果。 */
    private data class Entry(val stamp: Long, val value: Any?)

    /** PsiFile → 条目（弱引用键，文件释放时条目可被回收）。 */
    private val cache = ConcurrentHashMap<PsiFile, Entry>()

    /**
     * 获取或计算缓存。
     *
     * @param file    当前文件
     * @param compute 解析函数（仅在缓存未命中时调用）
     * @return 解析结果（可能为 null，表示未找到 Wrapper）
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCompute(file: PsiFile, compute: () -> T?): T? {
        val stamp = file.modificationStamp
        cache[file]?.let { entry ->
            if (entry.stamp == stamp) return entry.value as T?
        }
        val value = compute()
        cache[file] = Entry(stamp, value)
        return value
    }

    /** 清空缓存（文件关闭/项目关闭时调用）。 */
    fun clear() = cache.clear()

    /** 当前缓存条目数（测试/诊断用）。 */
    fun size(): Int = cache.size
}
