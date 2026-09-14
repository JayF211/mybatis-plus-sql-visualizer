package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiFile
import java.util.LinkedHashMap

/**
 * Wrapper 解析缓存（F-20）：按 PsiFile + 锚点偏移 + modificationStamp 缓存解析结果。
 *
 * 目的：光标悬停/快捷键/图标点击高频触发时避免重复做 PSI 引用搜索（ReferencesSearch 较重）。
 * 策略：
 *  - 键为「PsiFile + 锚点元素起始偏移」——定位结果**依赖锚点**（同一文件可含多个 Wrapper，
 *    如查询 + 删除 + 修改，各自锚点定位到各自的调用链），只按文件缓存会让后续锚点
 *    命中首个 Wrapper 的结果（回归：删除/修改被串成查询，2026-08-12）
 *  - 文件内容变更（modificationStamp 变化）即失效重建
 *  - T-M19.4（用户实测：表名配置修改后预览不生效——设置变更不触发文件戳变化，旧缓存命中）：
 *    **配置签名维度**——影响解析结果的设置（表名推断策略/忽略字段/添加移除表前缀后缀）作为缓存键
 *    一部分，配置变更后不再命中旧结果（沿用 M14.6 resolveVariables 维度同一模式）
 *  - 线程安全（UI 触发方经 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService.previewAsync]
 *    在后台线程 + EDT 并发调用）
 *
 * T-PerfTail #1：容量上界与淘汰——原实现无容量上限（条目仅随用户显式「清除缓存」消失，极端
 * 长会话 + 大量不同锚点场景条目无界增长），改为 LRU 缓存：`LinkedHashMap(accessOrder=true)` +
 * `removeEldestEntry` 按**访问序**淘汰最旧条目，`Collections.synchronizedMap` 包装保持线程安全
 * （accessOrder 下读也会改链表结构，包装器逐方法互斥保证）。三维修失效机制（文件
 * modificationStamp + resolveVariables 开关 + 表配置签名）不动；[clear] 行为不变（设置页
 * 「清除缓存」入口不受影响）。
 */
class WrapperCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    /**
     * 缓存键：文件 + 锚点偏移 + 配置维度（同文件不同锚点独立缓存；解析开关/表配置变化也独立缓存）。
     * T-Perf-18：文件维度改 VirtualFile.url 字符串——不持 PsiFile 强引用（关闭文件的 PsiFile
     * 可回收，靠 modificationStamp 维度在 PSI 重建后自然失效重算）。
     */
    private data class CacheKey(
        val fileUrl: String?,
        /** T-Perf-18：文件实例同一性辅助键（light/mock 文件 url 可重复，防跨实例误命中）。 */
        val fileIdentity: Int,
        val anchorOffset: Int,
        val resolveVariables: Boolean,
        val configSignature: String,
    )

    /** 缓存条目：文件修改戳 + 解析结果。 */
    private data class Entry(val stamp: Long, val value: Any?)

    /** 键 → 条目（LRU：按访问序淘汰最旧；逐方法互斥，线程安全）。 */
    private val cache: MutableMap<CacheKey, Entry> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<CacheKey, Entry>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Entry>): Boolean =
                    size > maxEntries
            },
        )

    /**
     * 获取或计算缓存。
     *
     * @param file             当前文件
     * @param anchorOffset     锚点元素起始偏移（定位结果依赖锚点，同文件多 Wrapper 必须区分）
     * @param resolveVariables 「替换 ? 占位符」开关（M14.6）：作为缓存维度，开关切换后不命中旧结果
     * @param configSignature  表配置签名（T-M19.4）：影响解析结果的设置拼接串——设置变更后
     *                         不命中旧结果（文件戳不变的场景，如设置页修改后同文件再次预览）
     * @param compute          解析函数（仅在缓存未命中时调用）
     * @return 解析结果（可能为 null，表示该锚点未找到 Wrapper）
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCompute(
        file: PsiFile,
        anchorOffset: Int,
        resolveVariables: Boolean,
        configSignature: String,
        compute: () -> T?,
    ): T? {
        val key = CacheKey(file.virtualFile?.url, System.identityHashCode(file), anchorOffset, resolveVariables, configSignature)
        val stamp = file.modificationStamp
        cache[key]?.let { entry ->
            if (entry.stamp == stamp) return entry.value as T?
        }
        val value = compute()
        cache[key] = Entry(stamp, value)
        return value
    }

    /**
     * T-PerfTail #1：按文件 URL 清理该文件全部条目（编辑器关闭回调触发——长会话中关闭文件
     * 即回收其全部锚点缓存，条目数随打开文件集有界）。fileUrl 为 null 的键（mock/light 文件）
     * 无法按文件定位，靠 modificationStamp 维度自然失效。
     */
    fun evictFile(fileUrl: String) {
        synchronized(cache) {
            cache.keys.removeAll { it.fileUrl == fileUrl }
        }
    }

    /** 清空缓存（文件关闭/项目关闭时调用）。 */
    fun clear() = cache.clear()

    /** 当前缓存条目数（测试/诊断用）。 */
    fun size(): Int = cache.size

    /**
     * 缓存大小估算（设置页展示）：值为任意解析模型对象，按每条目固定开销近似——
     * 展示用，非精确 JVM 内存（PsiFile 引用本身不计）。
     */
    fun sizeBytes(): Long = cache.size * 192L

    companion object {
        /** T-PerfTail #1：默认容量上限（LRU 淘汰）。 */
        const val DEFAULT_MAX_ENTRIES = 512
    }
}
