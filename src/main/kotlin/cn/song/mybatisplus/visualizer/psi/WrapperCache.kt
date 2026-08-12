package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Wrapper 解析缓存（F-20）：按 PsiFile + 锚点偏移 + modificationStamp 缓存解析结果。
 *
 * 目的：光标悬停/快捷键/图标点击高频触发时避免重复做 PSI 引用搜索（ReferencesSearch 较重）。
 * 策略：
 *  - 键为「PsiFile + 锚点元素起始偏移」——定位结果**依赖锚点**（同一文件可含多个 Wrapper，
 *    如查询 + 删除 + 修改，各自锚点定位到各自的调用链），只按文件缓存会让后续锚点
 *    命中首个 Wrapper 的结果（回归：删除/修改被串成查询，2026-08-12）
 *  - 文件内容变更（modificationStamp 变化）即失效重建
 *  - 线程安全（UI 触发方经 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService.previewAsync]
 *    在后台线程 + EDT 并发调用）
 */
class WrapperCache {

    /** 缓存键：文件 + 锚点起始偏移（同文件不同锚点独立缓存）。 */
    private data class CacheKey(val file: PsiFile, val anchorOffset: Int)

    /** 缓存条目：文件修改戳 + 解析结果。 */
    private data class Entry(val stamp: Long, val value: Any?)

    /** 键 → 条目。 */
    private val cache = ConcurrentHashMap<CacheKey, Entry>()

    /**
     * 获取或计算缓存。
     *
     * @param file         当前文件
     * @param anchorOffset 锚点元素起始偏移（定位结果依赖锚点，同文件多 Wrapper 必须区分）
     * @param compute      解析函数（仅在缓存未命中时调用）
     * @return 解析结果（可能为 null，表示该锚点未找到 Wrapper）
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCompute(file: PsiFile, anchorOffset: Int, compute: () -> T?): T? {
        val key = CacheKey(file, anchorOffset)
        val stamp = file.modificationStamp
        cache[key]?.let { entry ->
            if (entry.stamp == stamp) return entry.value as T?
        }
        val value = compute()
        cache[key] = Entry(stamp, value)
        return value
    }

    /** 清空缓存（文件关闭/项目关闭时调用）。 */
    fun clear() = cache.clear()

    /** 当前缓存条目数（测试/诊断用）。 */
    fun size(): Int = cache.size
}
