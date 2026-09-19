package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.psi.PsiSnapshot;
import com.intellij.openapi.diagnostic.Logger;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code SqlGutterLineMarkerProvider.kt} 顶层函数（防抖纯函数 {@code shouldNavigate}、
 * 路径相对化 {@code relativeToProjectPath}、T-PCP-02 锚点校验 {@code validatedRecentAnchor}）
 * 的 Java 承载类。
 */
public final class SqlGutterLineMarkerProviderKt {

    /** gutter 侧诊断日志（快照失效按 debug 留痕——标记/记录缺失无害，不打扰用户）。 */
    private static final Logger GUTTER_LOG = Logger.getInstance(SqlGutterLineMarkerProvider.class);

    private SqlGutterLineMarkerProviderKt() {
    }

    /** 默认防抖窗口（毫秒），对应原 Kotlin 顶层函数缺省参数 {@code windowMs = 200L}。 */
    public static boolean shouldNavigate(long lastTimeMs, long nowMs) {
        return shouldNavigate(lastTimeMs, nowMs, 200L);
    }

    public static boolean shouldNavigate(long lastTimeMs, long nowMs, long windowMs) {
        return nowMs - lastTimeMs >= windowMs;
    }

    /** 绝对路径 → 相对项目根路径（项目外文件返回 null）。 */
    @Nullable
    public static String relativeToProjectPath(@Nullable String base, @NotNull String absolutePath) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String normAbs = absolutePath.replace('\\', '/');
        String normBase = base.replace('\\', '/');
        while (normBase.endsWith("/")) {
            normBase = normBase.substring(0, normBase.length() - 1);
        }
        String prefix = normBase + "/";
        return normAbs.startsWith(prefix) ? normAbs.substring(prefix.length()) : null;
    }

    /**
     * T-PCP-02：锚点快照的<b>后台校验 + 行号解析</b>收口（须在读权限内调用，生产侧由 {@code ReadAction.compute} 包裹）。
     *
     * <p>捕获与读取之间的写操作会让元素失效——校验不过即返回 null，调用方按「无最近记录」如实降级
     * （原实现直接读 {@code elt.textRange}，文档已被替换时会算出错行号或抛失效异常）。
     *
     * <p>单独成函数（而非内联进池化块）是为了让失效路径可被单测直接驱动：合成「捕获 → 改文档 → 解析」
     * 三态无需真点 gutter 图标。守卫规则 {@code psiCapture}（convention/BannedApiSourceScanTest）要求
     * 跨线程读 PSI 的池化块出现本收口（或 {@code PsiSnapshot} 判定）。
     *
     * @param projectBasePath 项目根绝对路径（生产侧传 {@code project.getBasePath()}；显式入参便于 fixture 用例驱动）
     */
    @Nullable
    public static Pair<String, Integer> validatedRecentAnchor(
            @Nullable String projectBasePath,
            @Nullable PsiSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        PsiSnapshot.Validity validity = snapshot.validate();
        if (validity instanceof PsiSnapshot.Validity.Invalid invalid) {
            GUTTER_LOG.debug("[gutter] 锚点快照已失效（" + invalid.reason().getDescription()
                    + "），跳过最近执行记录反查：" + snapshot.getFilePath());
            return null;
        }
        String relative = relativeToProjectPath(projectBasePath, snapshot.getFilePath());
        if (relative == null) {
            return null;
        }
        return new Pair<>(relative, snapshot.lineNumber());
    }
}
