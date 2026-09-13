package cn.song.mybatisplus.visualizer.psi;

import com.intellij.psi.PsiFile;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Wrapper 解析缓存（F-20）：按 PsiFile + 锚点偏移 + modificationStamp 缓存解析结果。
 *
 * <p>目的：光标悬停/快捷键/图标点击高频触发时避免重复做 PSI 引用搜索（ReferencesSearch 较重）。
 *
 * <p>策略：
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
 */
public class WrapperCache {

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

    /** 键 → 条目。 */
    private final ConcurrentHashMap<CacheKey, Entry> cache = new ConcurrentHashMap<>();

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
