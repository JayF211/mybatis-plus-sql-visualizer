// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/XmlInlineSqlPreviewService.kt 移植
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector;
import com.intellij.openapi.project.Project;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通用 XML 内嵌 SQL 预览服务（O-72 T-XmlInlineSql）：载体标签 → {@link PreviewResult}。
 *
 * <ul>
 *   <li>无 {@code type} 或 type 非 HQL 族 → 原生 SQL 直显（pages 同款：{@code ?} 占位符 → param 表）；</li>
 *   <li>{@code type} ∈ {hql, jpql}（就近祖先优先）→ 复用 hbm 命名查询的 HQL 管道
 *       （{@link SqlPreviewService#previewHbmNamedQuery}，name 传空即走 FROM 子句实体提取，
 *       O-57 别名作用域列名映射自动生效）；<b>管道抛异常 → 回退原文直显 + warning，
 *       不抛错</b>（验收底线：实体不可解析时至少展示原文）。</li>
 * </ul>
 *
 * <p>输入是已定位的载体 XmlTag（gutter / Action 负责定位），本服务在后台线程调用。</p>
 *
 * <p>Kotlin object → 本类 + {@link #INSTANCE} + 私有构造。</p>
 */
public final class XmlInlineSqlPreviewService {

    public static final XmlInlineSqlPreviewService INSTANCE = new XmlInlineSqlPreviewService();

    private static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");

    private XmlInlineSqlPreviewService() {
    }

    @NotNull
    public PreviewResult preview(@Nullable Project project, @NotNull XmlTag tag) {
        String sql = XmlInlineSqlDetector.extractSqlText(tag);
        if (sql.isBlank()) {
            return emptyResult(MpSqlVisualizerBundle.message("xmlInlineSql.empty.warning"));
        }
        if (project != null && XmlInlineSqlDetector.isHqlType(tag)) {
            PreviewResult converted = null;
            try { // Kotlin runCatching.getOrNull() 语义（含 Error 全吞，回退不抛错）
                converted = project.getService(SqlPreviewService.class)
                        .previewHbmNamedQuery(new HbmXmlMapping.NamedQuery("", HbmXmlMapping.QueryKind.HQL, sql));
            } catch (Throwable ignored) {
                // HQL 管道异常 → 回退原文直显（不抛错）
            }
            if (converted != null) return converted;
            return plainResult(sql, MpSqlVisualizerBundle.message("xmlInlineSql.hqlFallback.warning"));
        }
        return plainResult(sql, null);
    }

    /** 原生 SQL 直显（PagesXmlPreviewService 同款：{@code ?} 计数 → param1..N）。 */
    @NotNull
    private static PreviewResult plainResult(@NotNull String sql) {
        return plainResult(sql, null);
    }

    /** plainResult + warning（Kotlin data class {@code copy(warning=...)} 等价形态）。 */
    @NotNull
    private static PreviewResult plainResult(@NotNull String sql, @Nullable String warning) {
        List<String> params = extractPlaceholders(sql);
        return new PreviewResult(
                sql,
                WHITESPACE_REGEX.matcher(sql).replaceAll(" ").trim(),
                params,
                ParameterReplacer.buildParamTable(params),
                null,
                null,
                warning,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
    }

    private static List<String> extractPlaceholders(@NotNull String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') count++;
        }
        if (count == 0) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) result.add("param" + i);
        return result;
    }

    private static PreviewResult emptyResult(@NotNull String message) {
        return new PreviewResult(
                "",
                "",
                List.of(),
                null,
                null,
                null,
                message,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
    }
}
