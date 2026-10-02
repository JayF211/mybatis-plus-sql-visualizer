package cn.song.mybatisplus.visualizer.psi;

import com.intellij.psi.PsiFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Wrapper 解析缓存（F-20）：按 PsiFile + 锚点偏移 + modificationStamp 缓存解析结果。
 *
 * <p>目的：光标悬停/快捷键/图标点击高频触发时避免重复做 PSI 引用搜索（ReferencesSearch 较重）。</p>
 *
 * <p>策略：</p>
 * <ul>
 *   <li>键为「PsiFile + 锚点元素起始偏移」——定位结果<b>依赖锚点</b>（同一文件可含多个 Wrapper，
 *       如查询 + 删除 + 修改，各自锚点定位到各自的调用链），只按文件缓存会让后续锚点
 *       命中首个 Wrapper 的结果（回归：删除/修改被串成查询，2026-08-12）</li>
 *   <li>文件内容变更（modificationStamp 变化）即失效重建</li>
 *   <li>T-M19.4（用户实测：表名配置修改后预览不生效——设置变更不触发文件戳变化，旧缓存命中）：
 *       <b>配置签名维度</b>——影响解析结果的设置（表名推断策略/忽略字段/添加移除表前缀后缀）作为缓存键
 *       一部分，配置变更后不再命中旧结果（沿用 M14.6 resolveVariables 维度同一模式）</li>
 *   <li>线程安全（UI 触发方经
 *       {@code cn.song.mybatisplus.visualizer.ui.SqlPreviewService#previewAsync}
 *       在后台线程 + EDT 并发调用）</li>
 * </ul>
 *
 * <p>T-PerfTail #1：容量上界与淘汰——原实现无容量上限（条目仅随用户显式「清除缓存」消失，极端
 * 长会话 + 大量不同锚点场景条目无界增长），改为 LRU 缓存：{@code LinkedHashMap(accessOrder=true)} +
 * {@code removeEldestEntry} 按<b>访问序</b>淘汰最旧条目，{@code Collections.synchronizedMap} 包装保持线程安全
 * （accessOrder 下读也会改链表结构，包装器逐方法互斥保证）。三维修失效机制（文件
 * modificationStamp + resolveVariables 开关 + 表配置签名）不动；{@link #clear} 行为不变（设置页
 * 「清除缓存」入口不受影响）。</p>
 */
public class WrapperCache {

    /** T-PerfTail #1：默认容量上限（LRU 淘汰）。 */
    public static final int DEFAULT_MAX_ENTRIES = 512;

    private final int maxEntries;

    /**
     * 缓存键：文件 + 锚点偏移 + 配置维度（同文件不同锚点独立缓存；解析开关/表配置变化也独立缓存）。
     * T-Perf-18：文件维度改 VirtualFile.url 字符串——不持 PsiFile 强引用（关闭文件的 PsiFile
     * 可回收，靠 modificationStamp 维度在 PSI 重建后自然失效重算）。
     */
    private static final class CacheKey {

        private final String fileUrl;
        /** T-Perf-18：文件实例同一性辅助键（light/mock 文件 url 可重复，防跨实例误命中）。 */
        private final int fileIdentity;
        private final int anchorOffset;
        private final boolean resolveVariables;
        private final String configSignature;

        private CacheKey(String fileUrl, int fileIdentity, int anchorOffset, boolean resolveVariables, String configSignature) {
            this.fileUrl = fileUrl;
            this.fileIdentity = fileIdentity;
            this.anchorOffset = anchorOffset;
            this.resolveVariables = resolveVariables;
            this.configSignature = configSignature;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CacheKey)) {
                return false;
            }
            CacheKey that = (CacheKey) o;
            return anchorOffset == that.anchorOffset
                    && resolveVariables == that.resolveVariables
                    && fileIdentity == that.fileIdentity
                    && Objects.equals(fileUrl, that.fileUrl)
                    && Objects.equals(configSignature, that.configSignature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fileUrl, fileIdentity, anchorOffset, resolveVariables, configSignature);
        }
    }

    /** 缓存条目：文件修改戳 + 解析结果。 */
    private static final class Entry {

        private final long stamp;
        private final Object value;

        private Entry(long stamp, Object value) {
            this.stamp = stamp;
            this.value = value;
        }
    }

    /** 键 → 条目（LRU：按访问序淘汰最旧；逐方法互斥，线程安全）。 */
    private final Map<CacheKey, Entry> cache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                // T-K2JWalkMcp262：Entry 必须显式限定为 WrapperCache.Entry——JDK 25 javac（262 工具链）
                // 在匿名子类里把裸 Entry 解析成 java.util.Map.Entry，与本方法签名构成「相同疑符但
                // 互不覆盖」的名称冲突（243/JDK 21 无此歧义）；显式限定后两代编译器一致
                protected boolean removeEldestEntry(Map.Entry<CacheKey, WrapperCache.Entry> eldest) {
                    return size() > maxEntries;
                }
            }
    );

    /** Kotlin 默认参数等价重载：{@code maxEntries} 缺省 {@link #DEFAULT_MAX_ENTRIES}。 */
    public WrapperCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public WrapperCache(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * 获取或计算缓存。
     *
     * @param file             当前文件
     * @param anchorOffset     锚点元素起始偏移（定位结果依赖锚点，同文件多 Wrapper 必须区分）
     * @param resolveVariables 「替换 ? 占位符」开关（M14.6）：作为缓存维度，开关切换后不命中旧结果
     * @param configSignature  表配置签名（T-M19.4）：影响解析结果的设置拼接串——设置变更后
     *                         不命中旧结果（文件戳不变的场景，如设置页修改后同文件再次预览）
     * @param compute          解析函数（仅在缓存未命中时调用）
     * @param <T>              解析结果类型
     * @return 解析结果（可能为 null，表示该锚点未找到 Wrapper）
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrCompute(
            PsiFile file,
            int anchorOffset,
            boolean resolveVariables,
            String configSignature,
            Supplier<T> compute) {
        CacheKey key = new CacheKey(
                file.getVirtualFile() != null ? file.getVirtualFile().getUrl() : null,
                System.identityHashCode(file), anchorOffset, resolveVariables, configSignature);
        long stamp = file.getModificationStamp();
        Entry entry = cache.get(key);
        if (entry != null && entry.stamp == stamp) {
            return (T) entry.value;
        }
        T value = compute.get();
        cache.put(key, new Entry(stamp, value));
        return value;
    }

    /**
     * T-PerfTail #1：按文件 URL 清理该文件全部条目（编辑器关闭回调触发——长会话中关闭文件
     * 即回收其全部锚点缓存，条目数随打开文件集有界）。fileUrl 为 null 的键（mock/light 文件）
     * 无法按文件定位，靠 modificationStamp 维度自然失效。
     */
    public void evictFile(String fileUrl) {
        synchronized (cache) {
            cache.keySet().removeIf(key -> Objects.equals(key.fileUrl, fileUrl));
        }
    }

    /** 清空缓存（文件关闭/项目关闭时调用）。 */
    public void clear() {
        cache.clear();
    }

    /** 当前缓存条目数（测试/诊断用）。 */
    public int size() {
        return cache.size();
    }

    /**
     * 缓存大小估算（设置页展示）：值为任意解析模型对象，按每条目固定开销近似——
     * 展示用，非精确 JVM 内存（PsiFile 引用本身不计）。
     */
    public long sizeBytes() {
        return cache.size() * 192L;
    }
}
