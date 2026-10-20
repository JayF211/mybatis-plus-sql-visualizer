// 自 master ui/annotate/PreviewFallbackTarget.kt 移植（UI029：预览面板自身已确定的目标回落）
package cn.song.mybatisplus.visualizer.ui.annotate;

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.DataSourceSettings;
import cn.song.mybatisplus.visualizer.execute.SchemaValidationGate;
import cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe;
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator;
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * UI029：预览面板<b>自身已确定的目标</b>回落（仅当旧入口
 * {@link DataSourceSelector#schemaValidationTarget} 返回 null 时使用）。
 *
 * <h2>为什么需要回落</h2>
 *
 * 从 SQL 日志条目打开的预览由面板自建编辑器展示 SQL（{@code SqlLogPanel.openPreview} →
 * {@code SqlPreviewService.showResult} → {@code SqlPreviewDialog}），整条链路<b>不准备控制台</b>；
 * 旧入口要求「当前执行文档 === 已备控制台文档」，因此 target 恒为 null，错列标注整链被跳过。
 * 面板此时仍在绑定指示行展示「数据源（上次使用）：mp_demo@localhost」
 * （{@link DataSourceSelector#rememberedDataSourceName}，{@code panel.bindstate.associated}）——这是面板
 * 对当前 SQL 已确定的唯一数据源事实，可作为回落来源。
 *
 * <h2>fail-closed 五道闸（任一不满足 → null）</h2>
 *
 * <ol>
 *  <li><b>只认 last-used 记忆</b>：活控制台绑定由旧入口负责；此处不借其他面板/其他预览的控制台；</li>
 *  <li><b>在籍数据源可解析</b>（{@link DataSourceSettings#getAllDataSources} 按名唯一命中，拿对象身份——
 *      {@link DatabaseSchemaResolver#columnNamesCachedForTarget} 按 {@code ===} 认源）；</li>
 *  <li><b>方言可证为 MySQL/MariaDB</b>（{@link DataSourceUrlProbe#urlByName} 的 JDBC URL 前缀）；</li>
 *  <li><b>库名非空</b>（{@link DatabaseSchemaResolver#databaseNameOf}，与预热缓存键同源）；</li>
 *  <li><b>缓存已热</b>（{@link DatabaseSchemaResolver#columnNamesCachedForTarget} 对本 SQL 的表命中）。</li>
 * </ol>
 *
 * <h2>红线</h2>
 *
 * 全程只读：记忆读 PropertiesComponent、数据源枚举/URL 走反射、列集只读已预热缓存——
 * <b>不建连、不内省、不发任何 DB 请求</b>。SQL 作用域（JOIN/CTE/子查询/限定列/多语句/显式库名）
 * 仍由 {@link SchemaValidationGate#check(Project, String, SchemaValidationGate.Target)} 三参入口判定；
 * 本对象只负责「目标是否存在」。
 */
public final class PreviewFallbackTarget {

    private PreviewFallbackTarget() {
    }

    /**
     * 测试缝：按名解析在籍数据源对象（headless 无 Database 插件时
     * {@link DataSourceSettings#getAllDataSources} 恒空）。生产为 null 走真链。
     */
    public static volatile java.util.function.BiFunction<Project, String, Object> dataSourceByNameForTest = null;

    /**
     * 解析预览面板自身已确定的校验目标；证据不足一律 null（调用方保持「不标注」）。
     *
     * @param project 当前项目
     * @param sql     预览编辑器当前文本（只为读表名做缓存热度证明，不参与作用域判定）
     */
    @Nullable
    public static SchemaValidationGate.Target resolve(@Nullable Project project, @NotNull String sql) {
        if (project == null || project.isDisposed() || sql.isBlank()) {
            return null;
        }
        // 1) 面板指示行第二优先级：last-used 记忆（只读，歧义即弃）。
        String name = quietly(() -> DataSourceSelector.rememberedDataSourceName(project));
        if (name == null || name.isBlank()) {
            return null;
        }
        // 2) 方言必须可证为 MySQL/MariaDB；取不到 URL 视为未知 → 不猜。
        String url = quietly(() -> DataSourceUrlProbe.urlByName(project, name));
        if (url == null || !isMySqlFamily(url)) {
            return null;
        }
        // 3) 解析在籍数据源对象（缓存按对象身份匹配）。
        Object dataSource = quietly(() -> {
            java.util.function.BiFunction<Project, String, Object> forTest = dataSourceByNameForTest;
            if (forTest != null) {
                return forTest.apply(project, name);
            }
            for (Object ds : DataSourceSettings.getAllDataSources(project)) {
                if (name.equals(DataSourceSettings.getName(ds))) {
                    return ds;
                }
            }
            return null;
        });
        if (dataSource == null) {
            return null;
        }
        // 4) 库名非空（与预热缓存键同源）。
        String database = quietly(() -> DatabaseSchemaResolver.databaseNameOf(dataSource));
        if (database == null || database.isBlank()) {
            return null;
        }
        // 5) 缓存必须已热：至少命中本 SQL 引用的一个表，否则交给调用方保持零标注。
        java.util.List<String> tables = quietly(() -> SchemaValidator.tableNames(sql));
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        java.util.Map<String, java.util.Set<String>> warm = quietly(
                () -> DatabaseSchemaResolver.columnNamesCachedForTarget(dataSource, database, tables));
        if (warm == null || warm.isEmpty()) {
            return null;
        }
        return new SchemaValidationGate.Target(dataSource, database);
    }

    /** MySQL 家族判定（与 {@code cn.song.mybatisplus.visualizer.execute.ExecConsoleContext} 同口径）。 */
    private static boolean isMySqlFamily(@NotNull String url) {
        return url.regionMatches(true, 0, "jdbc:mysql:", 0, "jdbc:mysql:".length())
                || url.regionMatches(true, 0, "jdbc:mariadb:", 0, "jdbc:mariadb:".length());
    }

    /** Kotlin {@code runCatching { ... }.getOrNull()} 等价：fatal 透传，其余异常归 null。 */
    @Nullable
    private static <T> T quietly(@NotNull java.util.concurrent.Callable<T> body) {
        try {
            return body.call();
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return null;
        }
    }
}
