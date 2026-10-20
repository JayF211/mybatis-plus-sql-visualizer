package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.sql.schema.SchemaSnapshot;
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator;
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.update.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R46 执行前 Schema 校验的 <b>IDE 侧门面</b>（V65：introspection 三通道 + metaCache + 未绑定降级全在位）。
 *
 * <p>与本链同源的 {@link SchemaValidator} 承担纯逻辑比对；本对象只做三件平台侧的事：</p>
 * <ol>
 * <li><b>取快照</b>：读 {@link DatabaseSchemaResolver#columnNamesCached}（<b>只读缓存，零 DB I/O、零阻塞</b>——
 *     预热由预览打开/JOIN 富化链后台完成）；缓存里没有的表一律不判（不误报「表不存在」，
 *     也不需要表全集枚举）；</li>
 * <li><b>未绑定降级</b>：无数据源 / database 插件缺席 / 缓存为空 → {@link Outcome.Skipped}（自动跳过不阻塞）；</li>
 * <li><b>渲染文案</b>：发现 → bundle 双语行（{@code schema.check.*}），供执行确认弹窗与轻通知共用。</li>
 * </ol>
 *
 * <p>设计纪律：本对象<b>不触发内省</b>（不调 prefetchTables/tableMeta）——执行路径在 EDT 上，任何 DB
 * 往返都会卡 UI；只读已预热缓存是「纯元数据只读」与「不阻塞」两条红线的唯一交点。</p>
 */
public final class SchemaValidationGate {

    private SchemaValidationGate() {
    }

    /** 单次校验涉及表数上限（防超长多表脚本拖慢缓存读取）。 */
    private static final int MAX_TABLES = 20;

    /** 校验结果。 */
    public sealed interface Outcome permits Skipped, Checked {
    }

    /** 未绑定数据源 / 插件缺席 / 缓存未预热——不校验（不阻塞，不误报）。 */
    public static final class Skipped implements Outcome {
        public static final Skipped INSTANCE = new Skipped();

        private Skipped() {
        }
    }

    /** 已校验：发现清单（可能为空＝全部命中）。 */
    public record Checked(List<SchemaValidator.Issue> issues) implements Outcome {
        public Checked {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    /**
     * 校验 {@code sql} 的对象引用（只读缓存）。
     *
     * @param project 当前项目（null → {@link Skipped}）
     * @return {@link Skipped} 或 {@link Checked}
     */
    @NotNull
    public static Outcome check(@Nullable Project project, @NotNull String sql) {
        if (project == null || sql.isBlank()) {
            return Skipped.INSTANCE;
        }
        List<String> allTables = SchemaValidator.tableNames(sql);
        List<String> tables = allTables.size() > MAX_TABLES ? allTables.subList(0, MAX_TABLES) : allTables;
        if (tables.isEmpty()) {
            return Skipped.INSTANCE;
        }
        // 只读缓存：未预热/未绑定/插件缺席一律空 Map → 跳过（诚实降级）
        Map<String, Set<String>> cached;
        try {
            cached = DatabaseSchemaResolver.columnNamesCached(project, tables);
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            cached = null;
        }
        if (cached == null || cached.isEmpty()) {
            return Skipped.INSTANCE;
        }
        SchemaSnapshot snapshot = SchemaSnapshot.of(cached);
        return new Checked(SchemaValidator.validate(sql, snapshot));
    }

    /** UI023：执行控制台已证实的目标；不从项目首个数据源或历史记忆猜测。 */
    record Target(Object dataSource, String database) {
    }

    /**
     * UI023：主预览执行入口的限定目标校验。目标缺席、目标未预热或 SQL 作用域不明确时跳过。
     * 只读该对象/库的缓存；旧两参入口的既有消费方保持原行为，不借本次接线扩大改造范围。
     */
    @NotNull
    static Outcome check(@Nullable Project project, @NotNull String sql, @Nullable Target target) {
        if (project == null || target == null || target.database().isBlank()) {
            return Skipped.INSTANCE;
        }
        List<Statement> statements;
        try {
            statements = SqlAstParser.INSTANCE.parseStatements(sql);
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return Skipped.INSTANCE;
        }
        if (statements == null || statements.size() != 1) {
            return Skipped.INSTANCE;
        }
        Statement statement = statements.get(0);
        Table table;
        if (statement instanceof Update update) {
            if (update.getFromItem() != null
                    || (update.getStartJoins() != null && !update.getStartJoins().isEmpty())
                    || (update.getWithItemsList() != null && !update.getWithItemsList().isEmpty())) {
                return Skipped.INSTANCE;
            }
            table = update.getTable();
        } else if (statement instanceof Delete delete) {
            if ((delete.getUsingList() != null && !delete.getUsingList().isEmpty())
                    || (delete.getWithItemsList() != null && !delete.getWithItemsList().isEmpty())) {
                return Skipped.INSTANCE;
            }
            table = delete.getTable();
        } else if (statement instanceof PlainSelect select) {
            if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
                return Skipped.INSTANCE;
            }
            table = select.getFromItem() instanceof Table fromTable ? fromTable : null;
        } else {
            table = null;
        }
        if (table == null) {
            return Skipped.INSTANCE;
        }
        // 单条、裸表名才可借控制台当前库解释；USE/多语句/显式库.schema 均不猜。
        if (!table.getFullyQualifiedName().equals(table.getName())) {
            return Skipped.INSTANCE;
        }
        List<String> tables = SchemaValidator.tableNames(sql);
        if (tables.size() != 1) {
            return Skipped.INSTANCE;
        }
        Map<String, Set<String>> cached;
        try {
            cached = DatabaseSchemaResolver.columnNamesCachedForTarget(target.dataSource(), target.database(),
                    tables);
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            cached = null;
        }
        if (cached == null || cached.isEmpty()) {
            return Skipped.INSTANCE;
        }
        return new Checked(SchemaValidator.validate(sql, SchemaSnapshot.of(cached)));
    }

    /** 发现清单 → 确认弹窗/通知的展示行（带 {@code • } 前缀，供 {@link ExecuteConfirmMessages} 直接拼接）。 */
    @NotNull
    public static List<String> renderLines(@NotNull List<SchemaValidator.Issue> issues) {
        List<String> lines = new ArrayList<>(issues.size());
        for (SchemaValidator.Issue issue : issues) {
            if (issue instanceof SchemaValidator.UnknownColumn unknown) {
                if (unknown.suggestion() != null) {
                    lines.add("• " + MpSqlVisualizerBundle.message(
                            "schema.check.unknownColumnSuggestion",
                            unknown.column(), unknown.table(), unknown.suggestion()));
                } else {
                    lines.add("• " + MpSqlVisualizerBundle.message(
                            "schema.check.unknownColumn", unknown.column(), unknown.table()));
                }
            }
        }
        return lines;
    }

    /** 发现清单 → 单行摘要（轻通知正文；无发现返回 null）。 */
    @Nullable
    public static String renderNotification(@NotNull List<SchemaValidator.Issue> issues) {
        if (issues.isEmpty()) {
            return null;
        }
        return String.join("\n", renderLines(issues));
    }
}
