// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt 移植（T-ST-02b2：历史耗时补全读路径改 sqlLogManagerIfCreated）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings;
import cn.song.mybatisplus.visualizer.config.TableNameRule;
import cn.song.mybatisplus.visualizer.converter.ConverterRegistry;
import cn.song.mybatisplus.visualizer.converter.EasyQueryConverter;
import cn.song.mybatisplus.visualizer.converter.MdsConverter;
import cn.song.mybatisplus.visualizer.converter.QuerydslConverter;
import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser;
import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter;
import cn.song.mybatisplus.visualizer.sql.GeneratedSql;
import cn.song.mybatisplus.visualizer.converter.jpa.DerivedQueryConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.JpaQueryEnhancer;
import cn.song.mybatisplus.visualizer.converter.jpa.PartTreeParser;
import cn.song.mybatisplus.visualizer.converter.jpa.PartTree;
import cn.song.mybatisplus.visualizer.converter.jpa.QueryByExampleConverter;
import cn.song.mybatisplus.visualizer.converter.jpa.SpecificationQueryConverter;
import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry;
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager;
import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.log.SqlLogManagerLookupKt;
import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.ConditionToggle;
import cn.song.mybatisplus.visualizer.model.ConditionView;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.model.BatchPreview;
import cn.song.mybatisplus.visualizer.psi.BatchOperationConverter;
import cn.song.mybatisplus.visualizer.psi.WrapperCache;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import cn.song.mybatisplus.visualizer.psi.LocatedWrapper;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.DaoQueryKind;
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlParameter;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer;
import cn.song.mybatisplus.visualizer.sql.interceptor.DataPermissionPlaceholder;
import cn.song.mybatisplus.visualizer.sql.interceptor.DynamicTableNameProcessor;
import cn.song.mybatisplus.visualizer.sql.interceptor.TenantLineEnhancer;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.TableConfigSignature;
import cn.song.mybatisplus.visualizer.xml.XmlDirectText;
import cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 预览服务（M3-01 整合层，F-07 统一入口）。
 *
 * <p>职责：把编辑器中的任意 PSI 元素转换为可展示的 {@link PreviewResult}。
 * 集成链路：WrapperLocator 定位 → ConverterRegistry 分发（SPI）→ SqlGenerator 生成 →
 * ParameterReplacer 参数对照 → WrapperCache 缓存（F-20）。
 *
 * <p>M5 起读取 {@link PluginSettings} 配置：方言（分页子句）、格式化开关（多行/压缩）。
 * 轻服务（M16）：{@code @Service(Service.Level.PROJECT)} 注解声明，无需 plugin.xml
 * {@code <projectService>} 注册（IDE 自动发现；构造函数仅依赖 Project，满足轻服务条件），
 * 获取方式不变（project.getService）。
 */
@Service(Service.Level.PROJECT)
public final class SqlPreviewService {

    /** 文件级解析缓存（F-20）：文件内容变更（modificationStamp）即失效。 */
    private final WrapperCache cache = new WrapperCache();

    /**
     * T-ErrorLocate T-EL-03：最近一次预览的源锚点（文件 + 0 基光标行），错误定位动作消费。
     * 预览触发入口（ConvertToSqlAction）刷新；null = 本会话尚未有可解析锚点（定位按钮不出现）。
     * 单槽即可：预览弹窗每项目至多一个（M36.5.47 单例化），同刻只有一个错误面板可定位。
     */
    @Nullable
    public SourceAnchor lastPreviewSourceAnchor = null;

    /** 预览源锚点（文件 + 0 基行）。 */
    public record SourceAnchor(
            /** 预览源文件。 */
            @NotNull com.intellij.openapi.vfs.VirtualFile file,
            /** 0 基光标行。 */
            int line0) {
    }

    /**
     * Kotlin {@code var lastPreviewSourceAnchor} 的 setter 形态——Java 调用方（ConvertToSqlAction）
     * 按属性赋值语义调用本方法；字段本身保持 public 供 {@link SqlPreviewPanel} 直读（同一落点）。
     */
    public void setLastPreviewSourceAnchor(@Nullable SourceAnchor anchor) {
        this.lastPreviewSourceAnchor = anchor;
    }

    /** 当前项目（主要用于获取项目级设置）。 */
    private final Project project;

    /**
     * 全局配置（测试环境未注册 service 时为 null，使用默认值）。
     */
    private final PluginSettings settings = safeGet(PluginSettings::getInstance);

    /**
     * O-5：项目级配置（project-level 轻服务；未启用时回退全局）。
     */
    private final ProjectPluginSettings projectSettings;

    public SqlPreviewService(@NotNull Project project) {
        this.project = project;
        this.projectSettings = safeGet(() -> ProjectPluginSettings.getInstance(project));
        // T-PerfTail #1：编辑器关闭即清理该文件的 WrapperCache 条目——长会话中条目数随打开
        // 文件集有界（LRU 512 上限之外的主动回收）。连接绑定 project Disposable，项目关闭自动注销。
        // FileEditorManagerListener 的 fileClosed 携带 VirtualFile，与 WrapperCache 键的
        // VirtualFile.url 字符串维度（T-Perf-18）直接对齐。
        project.getMessageBus().connect(project).subscribe(
                com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new com.intellij.openapi.fileEditor.FileEditorManagerListener() {
                    @Override
                    public void fileClosed(@NotNull com.intellij.openapi.fileEditor.FileEditorManager source,
                                           @NotNull com.intellij.openapi.vfs.VirtualFile file) {
                        cache.evictFile(file.getUrl());
                    }
                });
    }

    /**
     * T-M19.4（用户实测：表名配置修改后预览不生效——缓存按文件戳失效，设置变更不触发）：
     * 影响解析结果的表相关设置签名，作为 {@link WrapperCache} 缓存维度。
     * 复用 {@link TableConfigSignature#projectSignature()} 统一来源，
     * 与 EntityResolver 的解析缓存防漂移。
     *
     * <p>O-5：项目级设置开启时追加项目级表配置签名——项目设置变更后缓存同样失效。
     */
    private String tableConfigSignature() {
        String base = TableConfigSignature.projectSignature();
        ProjectPluginSettings ps = projectSettings;
        if (ps == null || !ps.isEnabled()) return base;
        ProjectPluginSettings.State s = ps.getState();
        List<String> parts = new ArrayList<>();
        parts.add(blank(s.getTableNameInference()));
        parts.add(blank(s.getIgnoredFields()));
        parts.add(blank(s.getTablePrefix()));
        parts.add(blank(s.getTableSuffix()));
        String projectPart = String.join("|", parts);
        if (projectPart.isBlank() || projectPart.equals("|||")) return base;
        return base + "|proj:" + projectPart;
    }

    /**
     * O-5：获取有效的设置值（项目级优先，关闭/空值回退全局）。
     */
    private boolean injectLogicDelete() {
        return (settings != null && settings.getState() != null) ? settings.getState().getInjectLogicDelete() : true;
    }

    private boolean injectTenantLine() {
        return (settings != null && settings.getState() != null) ? settings.getState().getInjectTenantLine() : false;
    }

    /**
     * O-5：是否格式化输出（项目级优先，回退全局）。
     */
    private boolean formatSql() {
        boolean global = (settings != null && settings.getState() != null) ? settings.getState().getFormatSql() : true;
        if (projectSettings != null && projectSettings.isEnabled()) {
            return projectSettings.effectiveFormatSql();
        }
        return global;
    }

    // T-SettingsTruth P4：以下 7 项直读点全部改走项目级 effective——
    // useProjectSettings 开启时项目级覆盖全局，关闭/null/空回退全局（回退语义在
    // ProjectPluginSettings.effectiveXxx 内置，此处仅透传；effectiveString 由零调用转正）。

    /** 有效参数替换开关（项目级优先，回退全局）。 */
    private boolean effectiveReplaceParams() {
        if (projectSettings != null && projectSettings.isEnabled()) return projectSettings.effectiveReplaceParams();
        return (settings != null && settings.getState() != null) ? settings.getState().getReplaceParams() : true;
    }

    /** 有效逻辑删除注入开关（项目级优先，回退全局）。 */
    private boolean effectiveInjectLogicDelete() {
        if (projectSettings != null && projectSettings.isEnabled()) return projectSettings.effectiveInjectLogicDelete();
        return (settings != null && settings.getState() != null) ? settings.getState().getInjectLogicDelete() : true;
    }

    /** 有效逻辑删除值（项目级优先，空回退全局）。 */
    @NotNull
    private String effectiveLogicDeleteValue() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getLogicDeleteValue() : "",
                projectSettings != null ? projectSettings.effectiveLogicDeleteValue() : null);
    }

    /** 有效表前缀（项目级优先，空回退全局）。 */
    @NotNull
    private String effectiveTablePrefix() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getTablePrefix() : "",
                projectSettings != null ? projectSettings.effectiveTablePrefix() : null);
    }

    /** 有效表后缀（项目级优先，空回退全局）。 */
    @NotNull
    private String effectiveTableSuffix() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getTableSuffix() : "",
                projectSettings != null ? projectSettings.effectiveTableSuffix() : null);
    }

    /** 有效表名推断策略（项目级优先，空回退全局）。 */
    @NotNull
    private String effectiveTableNameInference() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getTableNameInference() : PluginSettings.INFERENCE_SNAKE,
                projectSettings != null ? projectSettings.effectiveTableNameInference() : null);
    }

    /** 有效忽略字段名（项目级优先，空回退全局）。 */
    @NotNull
    private String effectiveIgnoredFields() {
        return effectiveString((settings != null && settings.getState() != null) ? settings.getState().getIgnoredFields() : "",
                projectSettings != null ? projectSettings.effectiveIgnoredFields() : null);
    }

    /** O-5：获取有效的字符串设置值（项目级优先，空值回退全局）。 */
    @NotNull
    private String effectiveString(@NotNull String global, @Nullable String project) {
        return (projectSettings != null && projectSettings.isEnabled() && project != null && !project.isBlank())
                ? project : global;
    }

    /**
     * 预览锚点元素处的 Wrapper 转换结果。
     *
     * <p>识别顺序：
     * <ol>
     *   <li>Wrapper 调用链（SELECT/UPDATE/DELETE/MPJ）</li>
     *   <li>批量操作调用（F-15，{@code service.updateBatchById(list)} 等）</li>
     * </ol>
     *
     * @param anchor 光标处 / 悬停处的 PSI 元素
     * @return 预览结果；两者均未命中时返回 null
     */
    @Nullable
    public PreviewResult preview(@NotNull PsiElement anchor) {
        // JDBC 模板 SQL 字符串预览（正向）：queryForList/update 等方法的字符串 SQL 实参 → 预览
        // 放在最前：JDBC 分支不写 Wrapper 缓存、不依赖实体解析，与既有路径完全隔离
        boolean jdbcPreviewOff = settings != null && settings.getState() != null
                && !settings.getState().getJdbcSqlPreviewEnabled();
        if (!jdbcPreviewOff) {
            JdbcTemplateSqlLocator.JdbcTemplateSqlCall jdbcCall = JdbcTemplateSqlLocator.INSTANCE.locate(anchor);
            if (jdbcCall != null) {
                return JdbcTemplateSqlPreviewService.preview(jdbcCall, anchor);
            }
        }

        PsiFile file = anchor.getContainingFile();
        if (file == null) return null;
        // 缓存键含锚点偏移：同文件多个 Wrapper（查询/删除/修改）各自独立缓存，避免串结果
        // M14.6：缓存键含「替换 ? 占位符」开关，开关切换后不命中旧结果
        // T-M19.4（用户实测：表名配置修改后预览不生效）：缓存键含**表配置签名**——设置变更后不再命中旧结果
        boolean resolveVariables = effectiveReplaceParams();
        LocatedWrapper located = cache.getOrCompute(
                file, anchor.getTextRange().getStartOffset(), resolveVariables, tableConfigSignature(),
                () -> WrapperLocator.locateWithTable(anchor, resolveVariables));
        // T-M19.4（用户实测：实体文件新增/修改 @TableName 后预览不生效——缓存按**锚点文件**戳失效，
        // 实体文件变化不触发）：命中缓存时校验实体文件戳，变更则重算
        LocatedWrapper effectiveLocated = (located != null && !located.entityFileValid())
                ? WrapperLocator.locateWithTable(anchor, resolveVariables) : located;

        // 1. Wrapper 转换（优先）
        if (effectiveLocated != null) {
            TableInfo table = effectiveLocated.getTable();
            if (table == null) {
                // O-8（缺陷 B）：字符串服务链实体解析失败（三通道与方法引用推断全失败）→
                // 返回带显式警示的空结果（面板红字），不再静默 return null——
                // 「返回 null = 非 wrapper 锚点」契约收窄保留：仅 located 带 entityResolutionFailed
                // 标记（服务链形态）时降级，工厂/Kotlin/构造形态与其余锚点行为不变
                if (effectiveLocated.entityResolutionFailed()) {
                    LOG.info("preview: DEGRADE table=null, wrapperType="
                            + (effectiveLocated.getChain() != null ? effectiveLocated.getChain().getWrapperType() : null)
                            + ", anchor=" + anchor.getTextRange());
                    return new PreviewResult(
                            "",
                            "",
                            List.of(),
                            null,
                            effectiveLocated.getChain() != null ? effectiveLocated.getChain().getWrapperType() : null,
                            null,
                            "⚠ 字符串服务链无法推断实体表：Wrapper 链未携带实体类型"
                                    + "（无 <实体> 类型实参，服务接口/调用返回类型也无实体泛型），SQL 无法生成",
                            List.of(), Map.of(), null, null, List.of(), List.of(), null, null, Map.of(),
                            List.of(), List.of(), null, null);
                }
                LOG.info("preview: RETURN_NULL table=null, wrapperType="
                        + (effectiveLocated.getChain() != null ? effectiveLocated.getChain().getWrapperType() : null)
                        + ", anchor=" + anchor.getTextRange());
                return null;
            }
            WrapperChain chain = effectiveLocated.getChain();
            SqlStatement statement;
            if (chain.getCountConsumed() && isQueryLike(chain.getWrapperType())) {
                // 计数消费：service.count(wrapper) / selectJoinCount(wrapper) / lambdaQuery()...count() → SELECT COUNT(*) FROM ...
                SelectStatement base = parseQueryLike(chain, table);
                statement = new SelectStatement(
                        table,
                        List.of(new ColumnRef(null, "*", null, "COUNT", null, null, null)),
                        base.getTableAlias(), base.getJoins(), false, base.getConditions(),
                        List.of(), null, List.of(), null, null, null, null, List.of(), null, null);
            } else if (chain.getExistsConsumed() && isQueryLike(chain.getWrapperType())) {
                // exists 链式终结点消费：service.lambdaQuery()...exists() → SELECT EXISTS (子查询)
                statement = parseQueryLike(chain, table);
            } else if (chain.getUpdateEntityConsumed() && !UPDATE_DELETE_JOIN_TYPES.contains(chain.getWrapperType())) {
                // 实体更新消费：service.update(entity, wrapper) 双参——wrapper 无 set 时用实体字段展开 SET 列
                SelectStatement parsed = new QueryWrapperParser(table).parse(chain);
                List<SetClause> sets = new ArrayList<>();
                for (ColumnInfo col : table.getColumns()) {
                    if (col.isId()) continue;
                    // @Version 列排除——实体 SET 不含 version（对齐 MP OptimisticLockerInnerInterceptor）
                    if (col.getColumnName().equals(table.getVersionColumn())) continue;
                    sets.add(new SetClause(
                            new ColumnRef(null, col.getColumnName(), null, null, null, null, null),
                            new SqlValue.Placeholder("?"), null));
                }
                statement = new UpdateStatement(table, sets, parsed.getConditions(), null, null, List.of());
            } else if (chain.getDeleteConsumed()
                    && (isQueryLike(chain.getWrapperType()) || chain.getWrapperType() == WrapperType.LAMBDA_UPDATE)
                    && !UPDATE_DELETE_JOIN_TYPES.contains(chain.getWrapperType())) {
                // MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造，service.remove/delete 消费
                SelectStatement parsed = new QueryWrapperParser(table).parse(chain);
                // O-8（缺陷 C）：`last("LIMIT n")` 尾段透传 DELETE 渲染（此前落在 SelectStatement
                // 独有字段被静默丢弃；跨方言渲染与否由 SqlGenerator 按 Dialect.supportsDeleteLimit 决定）
                statement = new DeleteStatement(
                        table, parsed.getConditions(), List.of(), null, List.of(), parsed.getLimit());
            } else {
                // T-FS-04：显式传本服务绑定的 project——converter 内「关联/collection 子表实体类 PSI
                // 不可解析」的类名兜底推断据此读项目级表配置（不再受「首个打开项目」影响）
                SqlStatement converted = ConverterRegistry.INSTANCE.convert(chain, table, project);
                if (converted == null) return null;
                statement = converted;
            }

            // T-M15-07：逻辑删除注入（@TableLogic 实体 + 配置开启）
            LogicDeleteState logicDelete = evaluateLogicDeleteState(chain.getCalls());
            SqlStatement baseStatement = effectiveInjectLogicDelete() ? injectLogicDeleteCondition(table, statement, logicDelete) : statement;
            // T-M22-02：乐观锁注入（@Version 实体 UPDATE）
            SqlStatement finalStatement = injectOptimisticLockCondition(table, baseStatement);
            // T-TenantLine：多租户注入
            boolean hasInterceptorIgnore = hasInterceptorIgnoreAnnotation(anchor, "tenantLine");
            SqlStatement tenantWhereStatement = injectTenantLineCondition(table, finalStatement, hasInterceptorIgnore);
            // F-6 Phase A：UPDATE 追加租户列 SET
            SqlStatement tenantStatement;
            if (injectTenantLine() && tenantWhereStatement instanceof UpdateStatement) {
                String tenantColumn = blank(settings != null && settings.getState() != null
                        ? settings.getState().getTenantColumnName() : null);
                tenantStatement = TenantLineEnhancer.enhanceUpdateSet(
                        (UpdateStatement) tenantWhereStatement, tenantColumn,
                        blank(settings != null && settings.getState() != null
                                ? settings.getState().getTenantColumnDefault() : null));
            } else {
                tenantStatement = tenantWhereStatement;
            }
            // F-6 Phase B：动态表名替换
            SqlStatement tableNameStatement = processDynamicTableName(table, tenantStatement);
            // F-6 Phase C：数据权限占位提示
            SqlStatement dataPermissionStatement = injectDataPermissionCondition(table, tableNameStatement);
            // T-M29-02/03：分页消费（service.page / selectPage / 链式 page()）→ LIMIT 子句
            SqlStatement pagedStatement = (chain.getCountConsumed() || chain.getExistsConsumed())
                    ? dataPermissionStatement : applyPageLimit(dataPermissionStatement, chain.getPageLimit());
            PreviewResult result = buildPreviewResult(chain.getWrapperType(), table, pagedStatement);
            recordHistory(result, anchor);
            // exists 消费：外层包裹 SELECT EXISTS
            if (chain.getExistsConsumed() && isQueryLike(chain.getWrapperType())) {
                return new PreviewResult(
                        "SELECT EXISTS (" + result.getSql() + ")",
                        "SELECT EXISTS (" + result.getCompactSql() + ")",
                        result.getUnresolvedParams(), result.getParamTable(), result.getWrapperType(), result.getTableName(),
                        result.getWarning(), result.getColumns(), result.getColumnComments(), result.getTableComment(),
                        result.getPreviewModel(), result.getParamHints(), result.getCandidateTables(), result.getBatchModel(),
                        result.getXmlModel(), result.getInitialParamValues(), result.getPerfWarnings(), result.getFillClauses(),
                        result.getPreviousSql(), result.getPagesColumns());
            }
            return result;
        }

        // 2. 批量操作识别（anchor 或其父链上的方法调用）
        PsiMethodCallExpression batchCall = null;
        PsiElement cur = anchor;
        while (cur != null) {
            if (cur instanceof PsiMethodCallExpression) { batchCall = (PsiMethodCallExpression) cur; break; }
            cur = cur.getParent();
        }
        // T-J1/T-J6/T-J21：JPA CRUD / EntityManager 写路径 / HibernateTemplate 实体操作派发
        // （receiver 家族感知，消歧规则见 JpaCrudOperationConverter KDoc）——receiver 判定为
        // JPA 家族时必须走 JpaEntityResolver：MP EntityResolver 只认 @TableName，JPA 实体
        // （@Table(name="t_user")）会被输出成 `INSERT INTO user_entity` 错表错列；
        // 非 JPA 家族 convert 返回 null，维持下方 MP 批量路径现状（存量行为零变化）
        // T-J5/T-J20 前置分支：Specification / Query By Example executor 调用先于 JPA CRUD 家族判定——
        // `findAll(spec)` / `findAll(example)` 的方法名同在 JpaCrudOperationConverter.REPOSITORY_METHODS
        // 名单内，后置会被渲染成「无 WHERE 全表 SELECT」（JpaCrudOperationConverter KDoc 建议的扩面方式：
        // 在 SqlPreviewService 侧新增前置分支，不改 Wave2 交付文件）
        if (batchCall != null) {
            SpecificationQueryConverter.SpecificationPreview specPreview =
                    SpecificationQueryConverter.convert(batchCall, resolveVariables);
            if (specPreview != null) {
                PreviewResult result = new PreviewResult(
                        specPreview.getSql(),
                        specPreview.getSql().replaceAll("\\s+", " ").trim(),
                        specPreview.getParamSources(),
                        ParameterReplacer.buildParamTable(specPreview.getParamSources()),
                        null,
                        specPreview.getTableName(),
                        specPreview.getWarning(),
                        specPreview.getColumns(),
                        Map.of(),
                        null,
                        null,
                        specPreview.getParamHints(),
                        List.of(),
                        null,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null);
                recordHistory(result, anchor);
                return result;
            }
            QueryByExampleConverter.ExamplePreview examplePreview =
                    QueryByExampleConverter.convert(batchCall, resolveVariables);
            if (examplePreview != null) {
                PreviewResult result = new PreviewResult(
                        examplePreview.getSql(),
                        examplePreview.getSql().replaceAll("\\s+", " ").trim(),
                        examplePreview.getParamSources(),
                        ParameterReplacer.buildParamTable(examplePreview.getParamSources()),
                        null,
                        examplePreview.getTableName(),
                        null,
                        examplePreview.getColumns(),
                        Map.of(),
                        null,
                        null,
                        examplePreview.getParamHints(),
                        List.of(),
                        null,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null);
                recordHistory(result, anchor);
                return result;
            }
            // EQ/DS/QD（M44 其他 ORM 第一批）前置派发：Easy-Query proxy 链 / MyBatis Dynamic SQL /
            // Querydsl——三者均非 MP Wrapper 链（locateWithTable 不命中），按 entry+receiver 启发认领；
            // 不认领返回 null 走后续 JPA CRUD / MP 批量管道（存量行为零变化）
            PreviewResult ormPreview = convertOtherOrm(batchCall, resolveVariables);
            if (ormPreview != null) {
                recordHistory(ormPreview, anchor);
                return ormPreview;
            }
        }
        JpaCrudOperationConverter.JpaCrudPreview jpaCrud = batchCall != null
                ? JpaCrudOperationConverter.convert(batchCall, resolveVariables)
                : null;
        if (jpaCrud != null) {
            PreviewResult result = new PreviewResult(
                    jpaCrud.getSql(),
                    jpaCrud.getSql(),
                    jpaCrud.getParamSources(),
                    jpaCrud.getParamGroups(),
                    null,
                    jpaCrud.getTableName(),
                    jpaCrud.getWarning(),
                    List.of(),
                    Map.of(),
                    null,
                    null,
                    jpaCrud.getParamHints(),
                    List.of(),
                    jpaCrud.getBatchModel(),
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null);
            recordHistory(result, anchor);
            return result;
        }
        // T-M23-01/02：批量路径接线——逻辑删除注入开关（settings.injectLogicDelete）与变量求值开关
        // （settings.replaceParams，实参可静态求值时内联）对齐 Wrapper 路径；参数来源/回填提示透传面板
        BatchPreview batch = batchCall != null
                ? BatchOperationConverter.convert(
                batchCall,
                effectiveInjectLogicDelete(),
                effectiveReplaceParams(),
                // D2：INSERT 租户列追加 = 主开关 AND 子开关（injectTenantOnInsert）；UPDATE 的 SET 租户注入
                // 走主开关（见上方 enhanceUpdateSet 链），不受本参数影响
                injectTenantLine() && ((settings != null && settings.getState() != null) ? settings.getState().getInjectTenantOnInsert() : true))
                : null;
        if (batch == null) return null;
        PreviewResult result = new PreviewResult(
                batch.getSql(),
                batch.getSql(),
                batch.getParamSources(),
                batch.getParamGroups(),
                null,
                batch.getTableName(),
                null,
                List.of(),
                Map.of(),
                null,
                null,
                batch.getParamHints(),
                List.of(),
                batch.getBatchModel(),
                null,
                Map.of(),
                null,
                null,
                null,
                null);
        recordHistory(result, anchor);
        return result;
    }

    /**
     * 其他 ORM 第一批（M44）前置派发：Easy-Query proxy（EQ_PROXY）/ MyBatis Dynamic SQL（MDS）/
     * Querydsl（QD）。三者按 entry + receiver 启发认领（普通类同名方法不命中，负例守卫），
     * 不认领返回 null（交回 JPA CRUD / MP 批量管道）。
     */
    private PreviewResult convertOtherOrm(PsiMethodCallExpression call, boolean resolveVariables) {
        EasyQueryConverter.EqPreview eq = EasyQueryConverter.convert(call, resolveVariables);
        if (eq != null) {
            return new PreviewResult(
                    eq.getSql(),
                    eq.getSql().replaceAll("\\s+", " ").trim(),
                    eq.getParamSources(),
                    ParameterReplacer.buildParamTable(eq.getParamSources()),
                    WrapperType.EQ_PROXY,
                    eq.getTableName(),
                    eq.getWarning(),
                    eq.getColumns(),
                    Map.of(),
                    null,
                    null,
                    eq.getParamHints(),
                    List.of(),
                    eq.getBatchModel(),
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null);
        }
        MdsConverter.MdsPreview mds = MdsConverter.convert(call, resolveVariables);
        if (mds != null) {
            return new PreviewResult(
                    mds.getSql(),
                    mds.getSql().replaceAll("\\s+", " ").trim(),
                    mds.getParamSources(),
                    ParameterReplacer.buildParamTable(mds.getParamSources()),
                    WrapperType.MDS,
                    mds.getTableName(),
                    mds.getWarning(),
                    mds.getColumns(),
                    Map.of(),
                    null,
                    null,
                    mds.getParamHints(),
                    List.of(),
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null);
        }
        QuerydslConverter.QdPreview qd = QuerydslConverter.convert(call, resolveVariables);
        if (qd != null) {
            return new PreviewResult(
                    qd.getSql(),
                    qd.getSql().replaceAll("\\s+", " ").trim(),
                    qd.getParamSources(),
                    ParameterReplacer.buildParamTable(qd.getParamSources()),
                    WrapperType.QD,
                    qd.getTableName(),
                    qd.getWarning(),
                    qd.getColumns(),
                    Map.of(),
                    null,
                    null,
                    qd.getParamHints(),
                    List.of(),
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null);
        }
        return null;
    }

    /**
     * JPA 推导查询预览（F-14 阶段四）：解析 Repository 方法名 → PartTree → SqlStatement → PreviewResult。
     *
     * <p>供 cn.song.mybatisplus.visualizer.ui.gutter.JpaGutterLineMarkerProvider 点击调用与
     * T-J9 调用点反查（ConvertToSqlAction）调用。
     *
     * @param method   Repository 声明方法
     * @param callArgs T-J9 调用点实参（{@code userRepository.findByName(x, PageRequest.of(0, 20))} 的实参列表）；
     *                 非 null 时对 PageRequest.of / Sort.by 做静态求值（有字面量就内联渲染，无则 ? 占位）
     */
    @Nullable
    public PreviewResult previewDerivedQuery(@NotNull PsiMethod method) {
        return previewDerivedQuery(method, null);
    }

    @Nullable
    public PreviewResult previewDerivedQuery(@NotNull PsiMethod method, @Nullable List<PsiExpression> callArgs) {
        PsiFile file = method.getContainingFile();
        if (file == null) return null;
        DerivedMethodInfo methodInfo = DerivedMethodLocator.locate(file, method.getTextRange().getStartOffset());
        if (methodInfo == null) return null;
        PsiClass entityClass = methodInfo.getEntityClass();
        if (entityClass == null) return null;
        TableInfo table = new JpaEntityResolver().resolve(entityClass);
        if (table == null) return null;
        PartTree partTree = PartTreeParser.parse(methodInfo.getMethodName());
        SqlStatement statement = DerivedQueryConverter.INSTANCE.convert(partTree, table);
        // T-PS5-03：入口求一次方言（分页分析/方法级增强/结果构建共用同一实例，不再各求一次）
        Dialect dialect = currentDialect();
        // T-J10：Pageable/Page/Sort/Limit 参数全路径——Pageable/Page → LIMIT 占位（调用点形态静态求值）、
        // Sort → ORDER BY（Sort.by 字面量内联）、Limit（3.2） → 仅 LIMIT 无 OFFSET
        JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(method, callArgs, dialect, table);
        if (pageSort != null) {
            SelectStatement select = (statement instanceof SelectStatement) ? (SelectStatement) statement : null;
            if (pageSort.getLimit() != null && select != null) {
                select = copySelectWithLimit(select, pageSort.getLimit());
            }
            if (pageSort.getOrderBy() != null && !pageSort.getOrderBy().isEmpty() && select != null) {
                select = copySelectWithOrderBy(select, pageSort.getOrderBy());
            }
            if (select != null) statement = select;
        }
        // 使用实际方法参数名（而非通用的 param1/param2）
        // 分页/排序/限流参数：排除参数本身，追加分页占位参数行（pageOffset/pageSize 等）
        List<String> paramNames = new ArrayList<>();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            String typeName = param.getType().getCanonicalText();
            int lt = typeName.indexOf('<');
            if (lt >= 0) typeName = typeName.substring(0, lt);
            if (JpaQueryEnhancer.isPageableType(typeName)
                    || JpaQueryEnhancer.isSortType(typeName)
                    || JpaQueryEnhancer.isLimitType(typeName)) {
                continue;
            }
            paramNames.add(param.getName());
        }
        if (pageSort != null && pageSort.getExtraParamNames() != null) {
            paramNames.addAll(pageSort.getExtraParamNames());
        }
        // DerivedQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        String sql = SqlGenerator.generate(statement);
        // T-J4：IN/NOT IN 条件参数接线——inClauseParameterOrdinals 序号对齐方法参数名
        // （过滤 Pageable 后的 paramNames 序），把 IN 参数挂进 buildJpaPreviewResult 的
        // inClauseParams（面板回填按逗号分割多值，对齐 JPQL/HQL 路径的 Result.inClauseParams 惯例）
        Set<String> inClauseParams = new LinkedHashSet<>();
        for (int ordinal : DerivedQueryConverter.inClauseParameterOrdinals(methodInfo.getMethodName())) {
            if (ordinal >= 0 && ordinal < paramNames.size()) {
                inClauseParams.add(paramNames.get(ordinal));
            }
        }
        // T-J15/J22：@Lock（FOR UPDATE）/ @QueryHints / @EntityGraph（LEFT JOIN）/ 返回类型 notes；
        // pageNotes：分页/排序分析已在上方完成（含调用点实参求值），透传语义注释、跳过二次分析
        String enhancedSql = applyJpaMethodEnhancements(
                method, sql, table, false,
                true,
                pageSort != null && pageSort.getNotes() != null ? pageSort.getNotes() : List.of(),
                dialect);
        return buildJpaPreviewResult(statement, enhancedSql, table.getTableName(), paramNames, method, inClauseParams, dialect);
    }

    /** {@link SelectStatement} 的 Kotlin {@code copy(limit = ...)} Java 等价（显式全参重建）。 */
    private static SelectStatement copySelectWithLimit(@NotNull SelectStatement sel, @Nullable LimitClause limit) {
        return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), sel.getJoins(),
                sel.getDistinct(), sel.getConditions(), sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(),
                limit, sel.getRawFirst(), sel.getRawAround(), sel.getComment(), sel.getUnions(),
                sel.getFromSubQuery(), sel.getFillClauses());
    }

    /** {@link SelectStatement} 的 Kotlin {@code copy(orderBy = ...)} Java 等价（显式全参重建）。 */
    private static SelectStatement copySelectWithOrderBy(@NotNull SelectStatement sel, @NotNull List<OrderByItem> orderBy) {
        return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), sel.getJoins(),
                sel.getDistinct(), sel.getConditions(), sel.getGroupBy(), sel.getHaving(), orderBy,
                sel.getLimit(), sel.getRawFirst(), sel.getRawAround(), sel.getComment(), sel.getUnions(),
                sel.getFromSubQuery(), sel.getFillClauses());
    }

    /**
     * JPA 方法级 SQL 增强（T-J10/J15/J22，文本级后处理，previewDerivedQuery / previewJpql /
     * previewHql / previewCriteriaQuery 各路径共用）：
     *
     * <ul>
     *  <li><b>T-J10</b>：宿主方法 {@code Pageable}/{@code Page}/{@code Limit} 参数 → LIMIT/OFFSET 占位（方言形态）；
     *      {@code Sort} 参数静态求值 → ORDER BY（与查询内已有子句并存时降级注释，避免双子句）；</li>
     *  <li><b>T-J15</b>：{@code @Lock(PESSIMISTIC_WRITE)} → SELECT 尾部 {@code FOR UPDATE}（共享锁形态注释说明方言差异）；
     *      {@code @QueryHints} 逐条注释；{@code @EntityGraph(attributePaths)} → LEFT JOIN 注入（按关联元数据解析
     *      真实表/列；解析失败 / 原生 SQL 路径降级注释）；</li>
     *  <li><b>T-J22</b>：返回类型 notes（Page count 二连 / Slice 多取一条 / Stream / 接口投影）；
     *      {@code Page} 返回 + {@code @Query.countQuery} 存在时一并展示 count SQL。</li>
     * </ul>
     *
     * <p>注释行以 {@code -- } 前缀追加 SQL 尾部（对齐 JpaCrudOperationConverter notes 惯例）；
     * 全程无注解/无参数时返回原文（存量字节级行为零变化）。
     */
    private String applyJpaMethodEnhancements(
            @Nullable PsiMethod method,
            @NotNull String sql,
            @Nullable TableInfo table,
            boolean nativeQuery) {
        return applyJpaMethodEnhancements(method, sql, table, nativeQuery, false, null);
    }

    /** Kotlin 默认实参形态 {@code applyJpaMethodEnhancements(…, native = x, dialectOverride = d)} 的便捷重载。 */
    private String applyJpaMethodEnhancements(
            @Nullable PsiMethod method,
            @NotNull String sql,
            @Nullable TableInfo table,
            boolean nativeQuery,
            @Nullable Dialect dialectOverride) {
        return applyJpaMethodEnhancements(method, sql, table, nativeQuery, false, null, dialectOverride);
    }

    private String applyJpaMethodEnhancements(
            @Nullable PsiMethod method,
            @NotNull String sql,
            @Nullable TableInfo table,
            boolean nativeQuery,
            boolean limitAppliedInModel,
            @Nullable List<String> pageNotes) {
        return applyJpaMethodEnhancements(method, sql, table, nativeQuery, limitAppliedInModel, pageNotes, null);
    }

    private String applyJpaMethodEnhancements(
            @Nullable PsiMethod method,
            @NotNull String sql,
            @Nullable TableInfo table,
            boolean nativeQuery,
            boolean limitAppliedInModel,
            @Nullable List<String> pageNotes,
            @Nullable Dialect dialectOverride) {
        try {
            if (method == null) return sql;
            // T-PS5-03：方言由调用入口求一次后透传（未传则本地求一次，语义不变）——单次预览的
            // `DialectRegistry.effective` 调用数由「增强/分页/结果构建各一次」收敛为入口一次
            Dialect dialect = dialectOverride != null ? dialectOverride : currentDialect();
            List<String> notes = new ArrayList<>();
            String enhanced = sql;

            // T-J15：@EntityGraph → LEFT JOIN（JPQL/HQL 非原生路径；原生查询运行时不应用实体图）
            List<String> joinLines = List.of();
            JpaQueryEnhancer.EntityGraphInfo graph = JpaQueryEnhancer.entityGraph(method);
            if (graph != null) {
                if (nativeQuery || table == null) {
                    notes.add("@EntityGraph(attributePaths)：原生 SQL 查询运行时不应用实体图，预览不追加 JOIN");
                } else {
                    JpaQueryEnhancer.GraphJoinResult graphResult =
                            JpaQueryEnhancer.resolveEntityGraphJoins(graph, table);
                    notes.addAll(graphResult.getNotes());
                    String mainAlias = detectMainAlias(enhanced, table);
                    List<String> rendered = new ArrayList<>();
                    for (JpaQueryEnhancer.GraphJoin join : graphResult.getJoins()) {
                        rendered.add(JpaQueryEnhancer.renderJoinLine(join, mainAlias, dialect));
                    }
                    joinLines = rendered;
                    if (!graphResult.getJoins().isEmpty()) {
                        notes.add("@EntityGraph(attributePaths=" + String.join(", ", graph.getPaths())
                                + ")：按实体图 LEFT JOIN 抓取（type=" + (graph.getType() != null ? graph.getType() : "LOAD") + "）");
                    }
                }
            }

            // T-J10：Pageable/Limit → LIMIT；Sort → ORDER BY（查询内已有子句时降级注释）。
            // limitAppliedInModel：派生/Criteria 路径 LIMIT 已在语句模型级挂载（传 true 跳过文本追加，
            // 避免同语句双 LIMIT）；
            // pageNotes 非 null：调用方已完成分页/排序分析（含调用点实参求值，T-J9/J10），notes 直接透传、
            // 跳过本方法内的二次分析（callArgs=null 的重分析会产出与实际内联结果相悖的「静态不可解析」注释）
            if (pageNotes != null) {
                notes.addAll(pageNotes);
            } else {
                JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(method, null, dialect, table);
                if (pageSort != null) {
                    notes.addAll(pageSort.getNotes());
                    if (pageSort.getLimit() != null) {
                        if (limitAppliedInModel
                                || JpaQueryEnhancer.containsTopLevelClause(enhanced,
                                Pattern.compile("(?i)\\b(LIMIT|OFFSET|FETCH)\\b"))) {
                            if (!limitAppliedInModel) {
                                notes.add("查询内已含分页子句，方法分页参数以查询内为准（预览不重复追加）");
                            }
                        } else {
                            enhanced = enhanced + "\n" + dialect.renderLimit(pageSort.getLimit());
                        }
                    }
                    if (pageSort.getOrderBy() != null && !pageSort.getOrderBy().isEmpty()) {
                        if (JpaQueryEnhancer.containsTopLevelClause(enhanced, Pattern.compile("(?i)\\bORDER\\s+BY\\b"))) {
                            notes.add("查询内已含 ORDER BY，Sort 参数运行时与其合并");
                        } else {
                            StringBuilder ob = new StringBuilder();
                            for (OrderByItem item : pageSort.getOrderBy()) {
                                if (ob.length() > 0) ob.append(", ");
                                ob.append(item.getColumn().getQualifiedName()).append(item.isAsc() ? " ASC" : " DESC");
                            }
                            enhanced = enhanced + "\nORDER BY " + ob;
                        }
                    }
                }
            }

            // JOIN 行插入（置于 LIMIT/ORDER BY 之前的顶层位置）
            enhanced = JpaQueryEnhancer.insertJoinLines(enhanced, joinLines);

            // T-J15：@Lock → FOR UPDATE / 共享锁方言差异注释
            JpaQueryEnhancer.LockClause lock = JpaQueryEnhancer.lockClause(method);
            if (lock != null) {
                notes.add(lock.getNote());
                if (lock.getClause() != null) enhanced = enhanced + " " + lock.getClause();
            }
            // T-J15：@QueryHints 逐条注释
            notes.addAll(JpaQueryEnhancer.queryHintNotes(method));
            // T-J22：返回类型 notes + Page 返回时展示 @Query.countQuery 的 count SQL
            notes.addAll(JpaQueryEnhancer.returnTypeNotes(method));
            notes.addAll(countQueryNotes(method, table));

            if (notes.isEmpty()) return sql;
            StringBuilder suffix = new StringBuilder();
            for (String note : notes) {
                if (!note.isBlank()) suffix.append("\n-- ").append(note);
            }
            return enhanced + suffix;
        } catch (Throwable t) {
            return sql;
        }
    }

    /** T-J22：@Query.countQuery → count SQL 注释行（Page 返回语义；转换失败降级原文注释）。 */
    private List<String> countQueryNotes(@Nullable PsiMethod method, @Nullable TableInfo table) {
        try {
            String countJpql = JpaQueryEnhancer.countQueryAttribute(method);
            if (countJpql == null) return List.of();
            // 注释前缀 "-- " 由 applyJpaMethodEnhancements 统一追加，此处只给内容行
            List<String> out = new ArrayList<>();
            out.add("@Query.countQuery（Page 返回的 count 伴随查询）：");
            try {
                String sqlText = countJpql;
                if (table != null) {
                    sqlText = JpqlConverter.INSTANCE.convert(countJpql, table, project).getSql();
                }
                out.addAll(sqlText.lines().toList());
            } catch (Throwable t) {
                out.clear();
                out.add("@Query.countQuery（Page 返回的 count 伴随查询）：");
                out.addAll(countJpql.lines().toList());
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * 从 SQL 文本探测主表别名（EntityGraph JOIN 的 ON 左值用）：
     * {@code FROM t_user u} → {@code u}；无别名/别名是 SQL 关键字 → 表名本名。
     */
    private String detectMainAlias(@NotNull String sql, @NotNull TableInfo table) {
        try {
            java.util.regex.Pattern regex =
                    Pattern.compile("(?i)\\bFROM\\s+[`\"(]*([\\w.]+)[`\"]*)\\s+([a-zA-Z_][\\w$]*)");
            java.util.regex.Matcher match = regex.matcher(sql);
            String alias = match.find() ? match.group(2) : null;
            boolean isKeyword = false;
            if (alias != null) {
                for (String kw : SQL_CLAUSE_KEYWORDS) {
                    if (alias.equalsIgnoreCase(kw)) {
                        isKeyword = true;
                        break;
                    }
                }
            }
            return (alias == null || isKeyword) ? table.getTableName() : alias;
        } catch (Throwable t) {
            return table.getTableName();
        }
    }

    /**
     * JPQL/@Query 预览（波次一）：解析 @Query 注解方法 → JPQL 字符串 → SQL → PreviewResult。
     *
     * <p>供 cn.song.mybatisplus.visualizer.ui.gutter.JpqlGutterLineMarkerProvider 点击调用。
     * T-J10/J15/J22：方法级增强（Pageable/Limit → LIMIT 占位、@Lock → FOR UPDATE、
     * @QueryHints 注释、@EntityGraph → LEFT JOIN、Page 返回 → count SQL 展示）见
     * {@link #applyJpaMethodEnhancements}。
     */
    @Nullable
    public PreviewResult previewJpql(@NotNull PsiMethod method) {
        PsiFile file = method.getContainingFile();
        JpqlQueryInfo info = (file != null)
                ? JpqlQueryLocator.INSTANCE.locate(file, method.getTextRange().getStartOffset()) : null;
        if (info == null) return null;
        String jpql = info.getJpql();
        List<String> paramNames = extractJpqlParamNames(method);
        PsiClass entityClass = info.getEntityClass();
        TableInfo table = (entityClass != null) ? new JpaEntityResolver().resolve(entityClass) : null;
        // T-PS5-03：入口求一次方言（分页分析/方法级增强/结果构建共用）
        Dialect dialect = currentDialect();
        // T-J10：分页/排序参数占位名（无参数时空列表，存量行为零变化）
        JpaQueryEnhancer.PageSortEval pageSortEval = JpaQueryEnhancer.evaluatePageSort(method, null, dialect, table);
        List<String> extraParamNames = (pageSortEval != null && pageSortEval.getExtraParamNames() != null)
                ? pageSortEval.getExtraParamNames() : List.of();
        List<String> allParamNames = new ArrayList<>(paramNames);
        allParamNames.addAll(extraParamNames);
        // 原生 SQL：转换命名参数 :param → ? 后展示（保持可读性）
        if (info.isNative()) {
            String convertedSql = convertNativeSqlParameters(jpql);
            // 检测 IN 子句参数（在命名参数替换前检测 :paramName）
            Set<String> inParams = JpqlConverter.findInClauseParams(jpql);
            List<ParamHint> hints = !inParams.isEmpty()
                    ? allParamNames.stream().map(n -> new ParamHint(true, null, inParams.contains(n))).toList()
                    : List.of();
            // T-J15：@EntityGraph 对原生查询运行时不生效 → 仅注释说明；LIMIT/@Lock/notes 照常
            String enhancedSql = applyJpaMethodEnhancements(method, convertedSql, table, true, dialect);
            String tableName;
            if (table != null) tableName = table.getTableName();
            else if (info.getEntityClass() != null) tableName = JpaEntityResolver.inferDecoratedTableName(info.getEntityClass().getName(), project);
            else tableName = "";
            return new PreviewResult(
                    enhancedSql,
                    // 无增强时保持旧字节级行为（convertedSql 原样），有增强时压缩多行空白
                    enhancedSql.equals(convertedSql) ? convertedSql : enhancedSql.replaceAll("\\s+", " ").trim(),
                    allParamNames,
                    ParameterReplacer.buildParamTable(allParamNames),
                    null, tableName, null,
                    table != null ? table.getAllColumnNames() : List.of(),
                    Map.of(), null, null,
                    hints, List.of(), null, null, Map.of(), null, null, null, null);
        }
        // Wave1 第三参 project：激活 PSI 级 JOIN ON（@JoinColumn 真实化）与子查询实体映射
        JpqlConverter.Result result = JpqlConverter.INSTANCE.convert(jpql, table, project);
        // T-J10/J15/J22：方法级增强（LIMIT/ORDER BY/FOR UPDATE/EntityGraph JOIN/notes/count SQL）
        String enhancedSql = applyJpaMethodEnhancements(method, result.getSql(), table, false, dialect);
        return buildJpaPreviewResult(result.getStatement(), enhancedSql, table != null ? table.getTableName() : "",
                allParamNames, method, result.getInClauseParams(), dialect);
    }

    /**
     * 将原生 SQL 中的命名参数 :paramName 转换为 ? 占位符。
     */
    private String convertNativeSqlParameters(@NotNull String nativeSql) {
        return nativeSql.replaceAll("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)", "?");
    }

    /**
     * 提取 JPQL/@Query 方法的参数名。
     * 优先 @Param 注解值（@Param("foo") → "foo"），无注解时用方法参数名。
     * T-J10：Pageable/Sort/Limit 类型参数本体不登记参数行（分页占位参数行 pageSize/pageOffset 等
     * 由 {@link JpaQueryEnhancer#evaluatePageSort} 的 extraParamNames 补充，避免同一参数双行）。
     */
    private List<String> extractJpqlParamNames(@NotNull PsiMethod method) {
        List<String> parameters = new ArrayList<>();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            String typeName = param.getType().getCanonicalText();
            int lt = typeName.indexOf('<');
            if (lt >= 0) typeName = typeName.substring(0, lt);
            if (JpaQueryEnhancer.isPageableType(typeName)
                    || JpaQueryEnhancer.isSortType(typeName)
                    || JpaQueryEnhancer.isLimitType(typeName)) {
                continue;
            }
            PsiModifierList modifierList = param.getModifierList();
            PsiAnnotation annotation = (modifierList != null)
                    ? modifierList.findAnnotation("org.springframework.data.repository.query.Param") : null;
            if (annotation == null && modifierList != null) {
                for (PsiAnnotation a : modifierList.getAnnotations()) {
                    PsiJavaCodeReferenceElement ref = a.getNameReferenceElement();
                    if (ref != null && "Param".equals(ref.getReferenceName())) { annotation = a; break; }
                }
            }
            if (annotation != null) {
                PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
                if (value instanceof PsiLiteralExpression) {
                    Object v = ((PsiLiteralExpression) value).getValue();
                    parameters.add(v instanceof String ? (String) v : param.getName());
                } else {
                    parameters.add(param.getName());
                }
            } else {
                parameters.add(param.getName());
            }
        }
        return parameters;
    }

    /**
     * CriteriaQuery 预览（波次一）：定位 CriteriaQueryModel → CriteriaQueryConverter → SQL → PreviewResult。
     *
     * <p>供 cn.song.mybatisplus.visualizer.ui.gutter.CriteriaQueryGutterLineMarkerProvider 点击调用。
     * T-J10：宿主方法 Pageable/Limit 参数 → LIMIT 子句（语句模型级，进 previewModel 交互重生成）。
     */
    @Nullable
    public PreviewResult previewCriteriaQuery(@NotNull PsiElement anchor) {
        // 1. 用 CriteriaQueryLocator.locate(anchor) 获取 CriteriaQueryModel
        CriteriaQueryModel model = CriteriaQueryLocator.INSTANCE.locate(anchor);
        if (model == null) return null;
        // 2. CriteriaQueryConverter.convert(model) → SelectStatement
        // T-TS-07：显式传本服务所属项目——实体类/join/子查询的**类名兜底推断**按该项目读取
        // 项目级表配置（原先传 null → 取「首个打开项目」，多项目同开时表名修饰错项目）
        SqlStatement statement = CriteriaQueryConverter.INSTANCE.convert(model, null, project);
        PsiMethod hostMethod = PsiTreeUtil.getParentOfType(anchor, PsiMethod.class);
        // 3. 参数名：从包含 CriteriaQuery 的宿主方法参数列表提取
        List<String> paramNames = (hostMethod != null)
                ? Arrays.stream(hostMethod.getParameterList().getParameters()).map(PsiParameter::getName).toList()
                : List.<String>of();
        // T-PS5-03：入口求一次方言（分页分析/方法级增强/结果构建共用）
        Dialect dialect = currentDialect();
        // T-J10：宿主方法分页参数 → LIMIT 子句（criteria 路径是语句模型，直接挂 limit）
        JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(hostMethod, null, dialect, null);
        if (pageSort != null) {
            SelectStatement select = (statement instanceof SelectStatement) ? (SelectStatement) statement : null;
            SelectStatement limited = select;
            if (pageSort.getLimit() != null && select != null) {
                limited = copySelectWithLimit(select, pageSort.getLimit());
            }
            if (limited != null) statement = limited;
        }
        // CriteriaQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        String sql = SqlGenerator.generate(statement);
        TableInfo resolvedTable = (model.getEntityClass() != null)
                ? new JpaEntityResolver().resolve(model.getEntityClass()) : null;
        String tableName = (model.getEntityClassName() != null)
                ? JpaEntityResolver.inferDecoratedTableName(model.getEntityClassName(), project) : "";
        // T-J10/J15/J22：方法级增强（Sort ORDER BY/锁/hints/返回类型 notes；LIMIT 已在语句模型级挂载，
        // 分页/排序 notes 由上方分析透传）
        String enhancedSql = applyJpaMethodEnhancements(
                hostMethod, sql, resolvedTable, false,
                true,
                pageSort != null && pageSort.getNotes() != null ? pageSort.getNotes() : List.of(),
                dialect);
        List<String> allParamNames = new ArrayList<>(paramNames);
        if (pageSort != null && pageSort.getExtraParamNames() != null) {
            allParamNames.addAll(pageSort.getExtraParamNames());
        }
        return buildJpaPreviewResult(statement, enhancedSql, tableName, allParamNames, hostMethod, Set.of(), dialect);
    }

    /**
     * Hibernate HQL 预览（session.createQuery）。
     */
    @Nullable
    public PreviewResult previewHql(@NotNull PsiElement anchor) {
        HqlQueryInfo info = HqlQueryLocator.INSTANCE.locate(anchor);
        if (info == null) return null;
        // 双重检测：isNativeQuery 标志 + 方法名 fallback
        if (info.isNativeQuery() || "createNativeQuery".equals(info.getCreateQueryMethodName())) {
            return previewNativeSql(info);
        }
        // T-PreviewShortcut：createNamedQuery("name") 按名回查实体 @NamedQuery，解析真实 JPQL；
        // 跨文件 / 命名不规范解析不到时优雅降级为把名字当 HQL 处理（不抛、不挂起）。
        if ("createNamedQuery".equals(info.getCreateQueryMethodName())) {
            NamedQueryInfo named = null;
            try {
                named = NamedQueryLocator.INSTANCE.findByQueryName(project, info.getHql());
            } catch (Throwable t) {
                named = null;
            }
            if (named != null && !named.getQuery().isBlank()) {
                PsiClass entityClass = named.getAnnotation() != null
                        ? PsiTreeUtil.getParentOfType(named.getAnnotation(), PsiClass.class, false) : null;
                TableInfo namedTable = entityClass != null ? new JpaEntityResolver().resolve(entityClass) : null;
                JpqlConverter.Result namedResult = JpqlConverter.INSTANCE.convert(named.getQuery(), namedTable, project);
                List<String> namedParams = extractParamNamesFromHql(named.getQuery());
                // T-PS5-03：本分支入口求一次方言
                Dialect dialect = currentDialect();
                // T-J10/J15/J22：宿主方法级增强
                String namedEnhancedSql = applyJpaMethodEnhancements(info.getMethod(), namedResult.getSql(), namedTable, false, dialect);
                JpaQueryEnhancer.PageSortEval namedPageSort =
                        JpaQueryEnhancer.evaluatePageSort(info.getMethod(), null, dialect, namedTable);
                List<String> namedExtraParams = (namedPageSort != null && namedPageSort.getExtraParamNames() != null)
                        ? namedPageSort.getExtraParamNames() : List.of();
                List<String> namedAllParams = new ArrayList<>(namedParams);
                namedAllParams.addAll(namedExtraParams);
                return buildJpaPreviewResult(namedResult.getStatement(), namedEnhancedSql,
                        namedTable != null ? namedTable.getTableName() : "", namedAllParams,
                        info.getMethod(), namedResult.getInClauseParams(), dialect);
            }
            // T-H3：Java @NamedQuery 未命中 → hbm 命名查询 fallback（hbm 纯 POJO 项目无注解可查）
            HbmXmlMapping.NamedQuery hbmNamed = null;
            try {
                hbmNamed = HbmXmlEntityResolver.findNamedQuery(info.getHql(), project);
            } catch (Throwable t) {
                hbmNamed = null;
            }
            if (hbmNamed != null) {
                return previewHbmNamedQuery(hbmNamed, info.getMethod());
            }
        }
        TableInfo table = resolveHqlTable(info.getEntityClass(), info.getEntityClassName(), info.getHql());
        // Wave1 第三参 project：激活 PSI 级 JOIN ON 与子查询实体映射
        // T-HqlInhCols：@Entity(name=...) 实体别名透传，别名形态 HQL 实体名才能被表名替换
        String hqlEntityAlias = null;
        if (info.getEntityClass() != null) {
            try {
                hqlEntityAlias = JpaMetadataReader.entityNameOf(info.getEntityClass());
            } catch (Throwable t) {
                hqlEntityAlias = null;
            }
        }
        HqlConverter.Result result = HqlConverter.INSTANCE.convert(info.getHql(), table, project, hqlEntityAlias);
        // 参数名提取：
        // 1. 追踪 setParameter 结果（位置参数用 valueExpr 即变量名，命名参数用 paramName）
        // 2. 若为空（链式调用场景），直接从 HQL 提取
        List<String> paramNames = extractHqlParamNames(info.getParameters());
        if (paramNames.isEmpty()) paramNames = extractParamNamesFromHql(info.getHql());
        // T-PS5-03：本分支入口求一次方言
        Dialect dialect = currentDialect();
        // T-J10/J15/J22：宿主方法级增强
        String enhancedSql = applyJpaMethodEnhancements(info.getMethod(), result.getSql(), table, false, dialect);
        JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(info.getMethod(), null, dialect, table);
        List<String> extraParams = (pageSort != null && pageSort.getExtraParamNames() != null)
                ? pageSort.getExtraParamNames() : List.of();
        List<String> allParamNames = new ArrayList<>(paramNames);
        allParamNames.addAll(extraParams);
        // 关键：使用 HqlConverter 转换后的 SQL 字符串（含 ? 占位符），而非从 statement 重新生成。
        return buildJpaPreviewResult(result.getStatement(), enhancedSql, table != null ? table.getTableName() : "",
                allParamNames, info.getMethod(), result.getInClauseParams(), dialect);
    }

    /**
     * 原生 SQL 预览（createNativeQuery）。
     * 不做实体→表名映射，仅替换 :param → ? 并解析实体表名。
     * T-J10/J15：宿主方法级增强（Pageable/Limit → LIMIT、@Lock → FOR UPDATE、notes）。
     */
    private PreviewResult previewNativeSql(@NotNull HqlQueryInfo info) {
        String sql = convertNativeSqlParameters(info.getHql());
        List<String> paramNames = extractHqlParamNames(info.getParameters());
        if (paramNames.isEmpty()) paramNames = extractParamNamesFromHql(info.getHql());
        // 解析实体表名（用于显示）
        String tableName = resolveHqlEntityTable(info.getEntityClass());
        if (tableName == null) tableName = (info.getEntityClassName() != null) ? info.getEntityClassName() : "";
        // T-PS5-03：入口求一次方言（方法级增强与分页分析共用）
        Dialect dialect = currentDialect();
        // T-J10/J15/J22：方法级增强（native 路径：EntityGraph 仅注释说明）
        String enhancedSql = applyJpaMethodEnhancements(info.getMethod(), sql, null, true, dialect);
        JpaQueryEnhancer.PageSortEval pageSort = JpaQueryEnhancer.evaluatePageSort(info.getMethod(), null, dialect, null);
        List<String> extraParams = (pageSort != null && pageSort.getExtraParamNames() != null)
                ? pageSort.getExtraParamNames() : List.of();
        List<String> allParamNames = new ArrayList<>(paramNames);
        allParamNames.addAll(extraParams);
        return new PreviewResult(
                enhancedSql, enhancedSql.replaceAll("\\s+", " ").trim(), allParamNames,
                ParameterReplacer.buildParamTable(allParamNames),
                null, tableName, null,
                List.of(), Map.of(), null, null,
                allParamNames.stream().map(n -> new ParamHint(Boolean.valueOf(!isNumericParam(info.getMethod(), n)), null, false)).toList(),
                List.of(), null, null, Map.of(), null, null, null, null);
    }

    /**
     * 解析原生 SQL 对应的实体表名。
     */
    private String resolveHqlEntityTable(@Nullable PsiClass entityClass) {
        if (entityClass == null) return null;
        TableInfo table = new JpaEntityResolver().resolve(entityClass);
        return table != null ? table.getTableName() : null;
    }

    /**
     * 实体级 @NamedQuery/@NamedQueries 预览（T-PreviewShortcut）：
     * 从光标所在 @NamedQuery 注解反查实体类 → 定位对应命名查询 → JPQL 转 SQL。
     *
     * 供 NamedQueryGutterLineMarkerProvider 点击与快捷键调用。
     */
    @Nullable
    public PreviewResult previewNamedQuery(@NotNull PsiElement anchor) {
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(anchor, PsiAnnotation.class, false);
        if (annotation == null) return null;
        PsiClass cls = PsiTreeUtil.getParentOfType(annotation, PsiClass.class, false);
        if (cls == null) return null;
        List<NamedQueryInfo> infos = NamedQueryLocator.INSTANCE.locate(cls);
        if (infos.isEmpty()) return null;
        // 优先取光标所在那一条 @NamedQuery（注解引用匹配）；多选 fallback 取第一条
        NamedQueryInfo info = null;
        for (NamedQueryInfo it : infos) {
            if (it.getAnnotation() == annotation) {
                info = it;
                break;
            }
        }
        if (info == null) info = infos.get(0);
        String jpql = info.getQuery();
        TableInfo table = new JpaEntityResolver().resolve(cls);
        JpqlConverter.Result result = JpqlConverter.INSTANCE.convert(jpql, table, project);
        List<String> paramNames = extractParamNamesFromHql(jpql);
        return buildJpaPreviewResult(result.getStatement(), result.getSql(),
                table != null ? table.getTableName() : "", paramNames,
                null, result.getInClauseParams());
    }

    /**
     * 自定义 DAO HQL 预览（commonDao.findByQuery 等）。
     * 供 cn.song.mybatisplus.visualizer.ui.gutter.CustomDaoHqlGutterLineMarkerProvider 点击调用。
     *
     * <p>T-J21：按 {@link CustomDaoHqlLocator} 登记的 {@link DaoQueryKind} 分派——
     * <ul>
     *  <li>HQL（默认）：既有 HqlConverter 管道（行为不变）；</li>
     *  <li>NAMED_QUERY（findByNamedQuery 族）：hql 字段存查询名——复用 NamedQueryLocator.findByQueryName
     *      按名回查实体 @NamedQuery 的真实 JPQL 再走 JpqlConverter（参考 previewNamedQuery 既有做法）；
     *      回查失败优雅降级为可读占位（不抛异常、不出错误 SQL）；</li>
     *  <li>QBC（executeFind）：可读占位「QBC/DetachedCriteria 形态暂不支持静态解析」；</li>
     *  <li>ENTITY_OP（get/load/save/delete 等）：locate 不认领（无 SQL 字符串），由 preview 批量分支
     *      经 JpaCrudOperationConverter 派发，不经本方法。</li>
     * </ul>
     */
    @Nullable
    public PreviewResult previewCustomDaoHql(@NotNull PsiElement anchor) {
        CustomDaoHqlInfo info = CustomDaoHqlLocator.INSTANCE.locate(anchor);
        if (info == null) return null;
        if (info.getKind() == DaoQueryKind.QBC) {
            return placeholderPreview(
                    "-- QBC/DetachedCriteria 形态暂不支持静态解析（条件树运行时构建）");
        }
        if (info.getKind() == DaoQueryKind.NAMED_QUERY) {
            NamedQueryInfo named = null;
            try {
                named = NamedQueryLocator.INSTANCE.findByQueryName(project, info.getHql());
            } catch (Throwable t) {
                named = null;
            }
            if (named != null && !named.getQuery().isBlank()) {
                PsiClass entityClass = named.getAnnotation() != null
                        ? PsiTreeUtil.getParentOfType(named.getAnnotation(), PsiClass.class, false) : null;
                TableInfo namedTable = entityClass != null ? new JpaEntityResolver().resolve(entityClass) : null;
                JpqlConverter.Result namedResult = JpqlConverter.INSTANCE.convert(named.getQuery(), namedTable, project);
                List<String> namedParams = extractParamNamesFromHql(named.getQuery());
                return buildJpaPreviewResult(namedResult.getStatement(), namedResult.getSql(),
                        namedTable != null ? namedTable.getTableName() : "", namedParams,
                        info.getMethod(), namedResult.getInClauseParams());
            }
            // T-H3：Java @NamedQuery 未命中 → hbm 命名查询 fallback（与 previewHql 同款）
            HbmXmlMapping.NamedQuery hbmNamed = null;
            try {
                hbmNamed = HbmXmlEntityResolver.findNamedQuery(info.getHql(), project);
            } catch (Throwable t) {
                hbmNamed = null;
            }
            if (hbmNamed != null) {
                return previewHbmNamedQuery(hbmNamed, info.getMethod());
            }
            // 回查失败：优雅降级占位（跨文件/命名不规范），不出错误 SQL
            return placeholderPreview(
                    "-- 命名查询 \"" + info.getHql() + "\"：未能回查到 @NamedQuery 定义（跨文件/命名不规范），无法静态解析 JPQL");
        }
        TableInfo table = resolveHqlTable(null, info.getEntityClassName(), info.getHql());
        // Wave1 第三参 project：激活 PSI 级 JOIN ON 与子查询实体映射
        // T-HqlInhCols：别名反查命中时从解析出的 PsiClass 读实体别名透传（此链无 entityClass 入参）
        PsiClass resolvedEntityClass = null;
        if (table != null && table.getEntityClassName() != null && !table.getEntityClassName().isBlank()) {
            try {
                resolvedEntityClass = JavaPsiFacade.getInstance(project).findClass(
                        info.getEntityClassName() != null ? info.getEntityClassName() : table.getEntityClassName(),
                        GlobalSearchScope.allScope(project));
            } catch (Throwable t) {
                resolvedEntityClass = null;
            }
        }
        String daoEntityAlias = null;
        if (resolvedEntityClass != null) {
            try {
                daoEntityAlias = JpaMetadataReader.entityNameOf(resolvedEntityClass);
            } catch (Throwable t) {
                daoEntityAlias = null;
            }
        }
        HqlConverter.Result result = HqlConverter.INSTANCE.convert(info.getHql(), table, project, daoEntityAlias);
        List<String> paramNames = extractHqlParamNames(info.getParameters());
        if (paramNames.isEmpty()) paramNames = extractParamNamesFromHql(info.getHql());
        return buildJpaPreviewResult(result.getStatement(), result.getSql(), table != null ? table.getTableName() : "",
                paramNames, info.getMethod(), result.getInClauseParams());
    }

    /** T-J21：可读占位预览卡（优雅降级：无 SQL 模板时给出解释性注释，不抛异常）。 */
    private PreviewResult placeholderPreview(@NotNull String note) {
        return new PreviewResult(
                note,
                note,
                List.of(),
                null,
                null,
                null,
                null,
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

    /**
     * T-H3：hbm 命名查询预览——按种类分派：
     * <ul>
     *  <li>HQL（{@code <query>}）→ {@link HqlConverter} 管道（实体表经 resolveHqlTable 路径 3 hbm 映射取得）；</li>
     *  <li>原生（{@code <sql-query>}）→ 命名参数 {@code :param} → {@code ?} 替换（与 previewNativeSql 同款）。</li>
     * </ul>
     *
     * <p>供 hbm 文件 gutter（cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider）
     * 与 createNamedQuery / 自定义 DAO 回查 fallback 共用。
     */
    @Nullable
    public PreviewResult previewHbmNamedQuery(@NotNull HbmXmlMapping.NamedQuery query) {
        return previewHbmNamedQuery(query, null);
    }

    @Nullable
    public PreviewResult previewHbmNamedQuery(@NotNull HbmXmlMapping.NamedQuery query, @Nullable PsiMethod hostMethod) {
        if (query.getQueryText().isBlank()) return null;
        List<String> paramNames = extractParamNamesFromHql(query.getQueryText());
        if (query.getKind() == HbmXmlMapping.QueryKind.NATIVE) {
            String sql = convertNativeSqlParameters(query.getQueryText());
            String enhancedSql = applyJpaMethodEnhancements(hostMethod, sql, null, true);
            return new PreviewResult(
                    enhancedSql,
                    enhancedSql.replaceAll("\\s+", " ").trim(),
                    paramNames,
                    ParameterReplacer.buildParamTable(paramNames),
                    null,
                    null,
                    null,
                    List.of(),
                    Map.of(),
                    null,
                    null,
                    paramNames.stream().map(n -> new ParamHint(Boolean.TRUE, null, false)).toList(),
                    List.of(),
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    null,
                    null);
        }
        // HQL：查询名前缀（LegacyItem.findLowStockHql → LegacyItem）优先，回落 FROM 子句实体
        String namePrefix = query.getName();
        int dot = namePrefix.indexOf('.');
        String prefix = dot >= 0 ? namePrefix.substring(0, dot) : "";
        prefix = prefix.trim();
        String entityName = !prefix.isBlank() ? prefix : extractEntityNameFromHql(query.getQueryText());
                TableInfo table = resolveHqlTable(null, entityName, query.getQueryText());
                // T-HqlInhCols：hbm 项目实体别名多由 XML <entity-name> 承载，注解读不到即 null，安全
                HqlConverter.Result result = HqlConverter.INSTANCE.convert(query.getQueryText(), table, project);
        // T-PS5-03：本分支入口求一次方言
        Dialect dialect = currentDialect();
        String enhancedSql = applyJpaMethodEnhancements(hostMethod, result.getSql(), table, false, dialect);
        return buildJpaPreviewResult(
                result.getStatement(),
                enhancedSql,
                table != null ? table.getTableName() : "",
                paramNames,
                hostMethod,
                result.getInClauseParams(),
                dialect);
    }

    /** T-H2：hbm 文件 gutter 点击入口——从 {@code <query>}/{@code <sql-query>} XmlTag 构造命名查询后预览。 */
    @Nullable
    public PreviewResult previewHbmNamedQueryTag(@NotNull Project tagProject, @NotNull XmlTag tag) {
        String name = tag.getAttributeValue("name");
        if (name == null) return null;
        HbmXmlMapping.QueryKind kind = HbmNamedQueryGutterLineMarkerProvider.kindOf(tag.getName());
        if (kind == null) return null;
        // 文本提取复用 resolver 的解析口径（直属 XmlText、CDATA 剥包裹、排除子标签）；
        // tag 与 findNamedQuery 同源
        String text = XmlDirectText.directTextOf(tag);
        return previewHbmNamedQuery(new HbmXmlMapping.NamedQuery(name, kind, text));
    }

    /**
     * JpaSpecificationExecutor 调用预览（T-J5）：定位 anchor 所在方法调用 →
     * {@link SpecificationQueryConverter} 解析 Specification lambda/组合器 → SELECT/COUNT/DELETE + WHERE。
     *
     * <p>供 gutter 点击与 T-J9 调用点动作路径调用；不识别（非 executor 家族/非 Specification 实参）返回 null。
     */
    @Nullable
    public PreviewResult previewSpecification(@NotNull PsiElement anchor) {
        PsiMethodCallExpression call = null;
        PsiElement cur = anchor;
        while (cur != null) {
            if (cur instanceof PsiMethodCallExpression) { call = (PsiMethodCallExpression) cur; break; }
            cur = cur.getParent();
        }
        if (call == null) return null;
        SpecificationQueryConverter.SpecificationPreview preview =
                SpecificationQueryConverter.convert(call,
                        (settings != null && settings.getState() != null) ? settings.getState().getReplaceParams() : true);
        if (preview == null) return null;
        PreviewResult result = new PreviewResult(
                preview.getSql(),
                preview.getSql().replaceAll("\\s+", " ").trim(),
                preview.getParamSources(),
                ParameterReplacer.buildParamTable(preview.getParamSources()),
                null,
                preview.getTableName(),
                preview.getWarning(),
                preview.getColumns(),
                Map.of(),
                null,
                null,
                preview.getParamHints(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
        recordHistory(result, anchor);
        return result;
    }

    /**
     * QueryByExampleExecutor 调用预览（T-J20）：定位 anchor 所在方法调用 →
     * {@link QueryByExampleConverter} 解析 Example.of(probe[, matcher]) → probe 非空属性等值 WHERE。
     */
    @Nullable
    public PreviewResult previewQueryByExample(@NotNull PsiElement anchor) {
        PsiMethodCallExpression call = null;
        PsiElement cur = anchor;
        while (cur != null) {
            if (cur instanceof PsiMethodCallExpression) { call = (PsiMethodCallExpression) cur; break; }
            cur = cur.getParent();
        }
        if (call == null) return null;
        QueryByExampleConverter.ExamplePreview preview =
                QueryByExampleConverter.convert(call,
                        (settings != null && settings.getState() != null) ? settings.getState().getReplaceParams() : true);
        if (preview == null) return null;
        PreviewResult result = new PreviewResult(
                preview.getSql(),
                preview.getSql().replaceAll("\\s+", " ").trim(),
                preview.getParamSources(),
                ParameterReplacer.buildParamTable(preview.getParamSources()),
                null,
                preview.getTableName(),
                null,
                preview.getColumns(),
                Map.of(),
                null,
                null,
                preview.getParamHints(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
        recordHistory(result, anchor);
        return result;
    }

    /**
     * @Procedure 存储过程预览（T-J16）：repository 方法注解 → CALL 渲染
     * （procedureName/value 优先、方法名兜底）+ outputParameterName/name 引用标注。
     *
     * <p>供 gutter 点击与 T-J9 调用点动作路径调用；无 @Procedure 注解返回 null。
     */
    @Nullable
    public PreviewResult previewProcedure(@NotNull PsiMethod method) {
        JpaQueryEnhancer.ProcedureInfo info = JpaQueryEnhancer.procedureInfo(method);
        if (info == null) return null;
        StringBuilder placeholders = new StringBuilder();
        for (int i = 1; i <= info.getParamCount(); i++) {
            if (placeholders.length() > 0) placeholders.append(", ");
            placeholders.append("?");
        }
        StringBuilder sql = new StringBuilder();
        sql.append("CALL ").append(info.getProcName()).append('(').append(placeholders).append(')');
        if (info.getNameRef() != null) {
            sql.append("\n-- @Procedure(name=\"").append(info.getNameRef())
                    .append("\")：引用元数据具名存储过程查询，过程名以元数据注册为准");
        }
        if (info.getOutputParameterName() != null) {
            sql.append("\n-- 输出参数：").append(info.getOutputParameterName())
                    .append("（注册为 OUT 参数，执行后按名取值）");
        }
        String sqlText = sql.toString();
        PreviewResult result = new PreviewResult(
                sqlText,
                sqlText.replaceAll("\\s+", " ").trim(),
                List.of(),
                null,
                null,
                info.getProcName(),
                null,
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
        recordHistory(result, method);
        return result;
    }

    /**
     * 从 HQL 参数列表提取参数名（位置参数用 valueExpr，命名参数用 paramName）。
     */
    private List<String> extractHqlParamNames(@NotNull List<HqlParameter> parameters) {
        List<String> names = new ArrayList<>();
        for (HqlParameter p : parameters) {
            names.add(p.isPositional() ? p.getValueExpr() : p.getParamName());
        }
        return names;
    }

    /**
     * 从 HQL 字符串中提取参数名（支持命名参数和位置参数）。
     */
    private List<String> extractParamNamesFromHql(@NotNull String hql) {
        Set<String> named = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)").matcher(hql);
        while (m.find()) named.add(m.group(1));
        Set<String> positional = new LinkedHashSet<>();
        Matcher pm = Pattern.compile("\\?(\\d+)").matcher(hql);
        while (pm.find()) positional.add("param" + pm.group(1));
        List<String> all = new ArrayList<>(named);
        for (String p : positional) if (!all.contains(p)) all.add(p);
        return all;
    }

    /**
     * JPA 预览结果构建统一入口（派生查询 / JPQL / CriteriaQuery 共用）。
     */
    private PreviewResult buildJpaPreviewResult(
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method) {
        return buildJpaPreviewResult(statement, convertedSql, tableName, paramNames, method, Set.of());
    }

    /**
     * JPA 预览结果构建统一入口（带 IN 子句参数集合）。
     */
    private PreviewResult buildJpaPreviewResult(
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method,
            @NotNull Set<String> inClauseParams) {
        return buildJpaPreviewResult(statement, convertedSql, tableName, paramNames, method, inClauseParams, null);
    }

    /**
     * JPA 预览结果构建统一入口（带 IN 子句参数集合 + 方言透传）。
     */
    private PreviewResult buildJpaPreviewResult(
            @NotNull SqlStatement statement,
            @NotNull String convertedSql,
            @NotNull String tableName,
            @NotNull List<String> paramNames,
            @Nullable PsiMethod method,
            @NotNull Set<String> inClauseParams,
            @Nullable Dialect dialectOverride) {
        // T-PS5-03：方言由调用入口透传（未传则本地求一次）——见 applyJpaMethodEnhancements 同款说明
        Dialect dialect = dialectOverride != null ? dialectOverride : currentDialect();
        List<ConditionView> views = collectConditionViews(statement);
        List<ParamHint> paramHints;
        if (!paramNames.isEmpty()) {
            paramHints = new ArrayList<>();
            for (String name : paramNames) {
                boolean inList = inClauseParams.contains(name);
                boolean isNumeric = isNumericParam(method, name);
                paramHints.add(new ParamHint(Boolean.valueOf(!isNumeric), null, inList));
            }
        } else {
            paramHints = List.of();
        }
        TableInfo stmtTable = (statement.getTable() instanceof TableInfo) ? (TableInfo) statement.getTable() : null;
        List<String> columns = (stmtTable != null) ? stmtTable.getAllColumnNames() : List.of();
        return new PreviewResult(
                convertedSql,
                convertedSql.replaceAll("\\s+", " ").trim(),
                paramNames,
                ParameterReplacer.buildParamTable(paramNames),
                null,
                tableName,
                buildJpaWarning(statement),
                columns,
                Map.of(),
                null,
                views.isEmpty() ? null : new SqlPreviewModel(statement, dialect, formatSql(), views),
                paramHints,
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
    }

    /**
     * 判断方法参数是否为数值类型（Integer/Long/BigDecimal 等）。
     */
    private boolean isNumericParam(@Nullable PsiMethod method, @NotNull String paramName) {
        if (method == null) return false;
        PsiParameter param = null;
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (paramName.equals(p.getName())) { param = p; break; }
        }
        if (param == null) return false;
        PsiType type = param.getType();
        String elementType;
        if (type instanceof PsiClassType) {
            String className = ((PsiClassType) type).getCanonicalText();
            Matcher m = Pattern.compile("(?:Collection|List|Set)<(.+)>").matcher(className);
            elementType = m.find() ? m.group(1) : className;
        } else if (type instanceof PsiArrayType) {
            elementType = ((PsiArrayType) type).getComponentType().getCanonicalText();
        } else {
            elementType = type.getCanonicalText();
        }
        for (String t : NUMERIC_TYPES) {
            if (elementType.endsWith(t)) return true;
        }
        return false;
    }

    private static final Set<String> NUMERIC_TYPES = Set.of(
            "Integer", "int", "Long", "long", "Short", "short", "Byte", "byte",
            "Float", "float", "Double", "double", "BigDecimal", "BigInteger", "Number");

    /**
     * JPA 语句风险警示：DELETE 无 WHERE 时提示全表删除（BlockAttack 风格）。
     */
    private String buildJpaWarning(@NotNull SqlStatement statement) {
        if (statement instanceof DeleteStatement) {
            DeleteStatement del = (DeleteStatement) statement;
            if (del.getConditions().isEmpty()) return "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！";
        }
        return null;
    }

    /**
     * 由语句模型构建预览结果（应用方言与格式化配置）。
     */
    private PreviewResult buildPreviewResult(
            @Nullable WrapperType wrapperType,
            @NotNull TableInfo table,
            @NotNull SqlStatement statement) {
        Dialect dialect = currentDialect();
        boolean compact = !formatSql();
        GeneratedSql generated = SqlGenerator.generateWithParams(statement, compact, dialect, null);
        List<ConditionView> views = collectConditionViews(statement);
        Map<String, String> columnComments = new HashMap<>();
        for (ColumnInfo col : table.getColumns()) {
            if (col.getComment() != null && !col.getComment().isBlank()) {
                columnComments.put(col.getColumnName(), col.getComment());
            }
        }
        List<FillClause> fillClauses = (statement instanceof SelectStatement)
                ? ((SelectStatement) statement).getFillClauses() : null;
        return new PreviewResult(
                generated.getSql(),
                SqlGenerator.generate(statement, true, dialect, null),
                generated.getUnresolvedParams(),
                ParameterReplacer.buildParamTable(generated.getUnresolvedParams()),
                wrapperType,
                table.getTableName(),
                buildWarning(statement),
                table.getAllColumnNames(),
                columnComments,
                table.getComment(),
                views.isEmpty() ? null : new SqlPreviewModel(statement, dialect, formatSql(), views),
                generated.getParamHints(),
                List.of(),
                null,
                null,
                Map.of(),
                analyzePerf(generated.getSql(), statement),
                fillClauses,
                null,
                null);
    }

    /**
     * 性能分析（仅 SELECT）：委托 SqlPerfAnalyzer（F-2）。
     */
    private List<PerfWarning> analyzePerf(@NotNull String sql, @NotNull SqlStatement statement) {
        if (!(statement instanceof SelectStatement)) return null;
        List<PerfWarning> warnings = SqlPerfAnalyzer.analyze(sql);
        return warnings.isEmpty() ? null : warnings;
    }

    /**
     * 收集语句中的动态条件行（M12）：遍历 WHERE 条件树，扁平化 toggle 元数据（含嵌套 Group 内）。
     */
    private List<ConditionView> collectConditionViews(@NotNull SqlStatement statement) {
        if (statement instanceof SelectStatement) return flatMapConditionViews(((SelectStatement) statement).getConditions());
        if (statement instanceof UpdateStatement) return flatMapConditionViews(((UpdateStatement) statement).getConditions());
        if (statement instanceof DeleteStatement) return flatMapConditionViews(((DeleteStatement) statement).getConditions());
        return List.of();
    }

    private List<ConditionView> flatMapConditionViews(@NotNull List<Condition> conditions) {
        List<ConditionView> views = new ArrayList<>();
        for (Condition c : conditions) views.addAll(toConditionViews(c));
        return views;
    }

    /**
     * 逻辑删除条件注入（T-M15-07 + T-M20-05 精确化）。
     */
    private SqlStatement injectLogicDeleteCondition(
            @NotNull TableInfo table,
            @NotNull SqlStatement statement,
            @NotNull LogicDeleteState state) {
        // T-M31.18：逻辑删除值可配置（默认 0，用户可调整——对齐 MP logic-delete-value）
        // T-SettingsTruth P4：项目级 logicDeleteValue 覆盖（空回退全局，再兜底 "0"）
        String logicDeleteValue = effectiveLogicDeleteValue();
        if (logicDeleteValue.isBlank()) logicDeleteValue = "0";
        if (statement instanceof SelectStatement) {
            SelectStatement sel = (SelectStatement) statement;
            List<Condition> conditions = new ArrayList<>(sel.getConditions());
            if (state.mainActive && table.getLogicDeleteColumn() != null) {
                conditions.add(new Condition.Simple(
                        new ColumnRef(sel.getTableAlias(), table.getLogicDeleteColumn(), null, null, null, null, null),
                        "=", new SqlValue.Literal(logicDeleteValue, logicDeleteValue), null));
            }
            List<Condition> subWhere = new ArrayList<>();
            List<JoinClause> joins = new ArrayList<>();
            for (JoinClause join : sel.getJoins()) {
                String column = join.getLogicDeleteColumn();
                if (column == null) { joins.add(join); continue; }
                if (!state.subActive) { joins.add(join); continue; }
                Condition cond = new Condition.Simple(
                        new ColumnRef(join.getAlias(), column, null, null, null, null, null),
                        "=", new SqlValue.Literal(logicDeleteValue, logicDeleteValue), null);
                if (state.subToWhere) {
                    subWhere.add(cond);
                    joins.add(join);
                } else {
                    List<Condition> onConditions = new ArrayList<>(join.getOnConditions());
                    onConditions.add(cond);
                    joins.add(new JoinClause(join.getType(), join.getTableName(), join.getAlias(), onConditions,
                            join.getRawSql(), join.getCustomKeyword(), join.getLogicDeleteColumn(), join.getTableInfo()));
                }
            }
            if (!subWhere.isEmpty()) conditions.addAll(subWhere);
            return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), joins, sel.getDistinct(),
                    conditions, sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(), sel.getLimit(), sel.getRawFirst(),
                    sel.getRawAround(), sel.getComment(), sel.getUnions(), sel.getFromSubQuery(), sel.getFillClauses());
        } else if (statement instanceof DeleteStatement) {
            DeleteStatement del = (DeleteStatement) statement;
            String column = table.getLogicDeleteColumn();
            if (column == null || !state.mainActive) return del;
            List<Condition> conditions = new ArrayList<>(del.getConditions());
            conditions.add(new Condition.Simple(
                    new ColumnRef(del.getTableAlias(), column, null, null, null, null, null),
                    "=", new SqlValue.Literal(logicDeleteValue, logicDeleteValue), null));
            return new DeleteStatement(del.getTable(), conditions, del.getDeleteTargets(), del.getTableAlias(), del.getJoins());
        }
        return statement;
    }

    /**
     * T-M22-02：乐观锁条件注入（@Version 实体 + MP OptimisticLockerInnerInterceptor 语义）。
     */
    private SqlStatement injectOptimisticLockCondition(@NotNull TableInfo table, @NotNull SqlStatement statement) {
        if (!(statement instanceof UpdateStatement)) return statement;
        UpdateStatement update = (UpdateStatement) statement;
        String versionColumn = table.getVersionColumn();
        if (versionColumn == null) return update;
        for (SetClause set : update.getSets()) {
            if (set.getColumn() != null && versionColumn.equals(set.getColumn().getColumnName())) return update;
        }
        if (conditionReferencesColumn(update.getConditions(), versionColumn)) return update;
        String alias = update.getTableAlias();
        String prefix = (alias != null) ? alias + "." : "";
        List<SetClause> sets = new ArrayList<>(update.getSets());
        sets.add(new SetClause(
                new ColumnRef(alias, versionColumn, null, null, null, null, null), null,
                prefix + versionColumn + " = " + prefix + versionColumn + " + 1"));
        List<Condition> conditions = new ArrayList<>(update.getConditions());
        conditions.add(new Condition.Simple(
                new ColumnRef(alias, versionColumn, null, null, null, null, null), "=", new SqlValue.Placeholder("?"), null));
        return new UpdateStatement(update.getTable(), sets, conditions, update.getRawTail(), update.getTableAlias(), update.getJoins());
    }

    /**
     * T-TenantLine：多租户条件注入（TenantLineInnerInterceptor 模拟）。
     */
    private SqlStatement injectTenantLineCondition(
            @NotNull TableInfo table,
            @NotNull SqlStatement statement,
            boolean hasInterceptorIgnore) {
        PluginSettings.State s = PluginSettings.getInstance().getState();
        if (!s.getInjectTenantLine()) {
            LOG.info("injectTenantLine: SKIP switch=off, stmt=" + statement.getClass().getSimpleName() + ", table=" + table.getTableName());
            return statement;
        }
        if (hasInterceptorIgnore) {
            LOG.info("injectTenantLine: SKIP @InterceptorIgnore(tenantLine=true), stmt=" + statement.getClass().getSimpleName());
            return statement;
        }
        String tenantColumn = s.getTenantColumnName();
        if (tenantColumn == null || tenantColumn.isBlank()) tenantColumn = DEFAULT_TENANT_COLUMN;
        if (statement instanceof SelectStatement) {
            SelectStatement sel = (SelectStatement) statement;
            if (conditionReferencesColumn(sel.getConditions(), tenantColumn)) {
                LOG.info("injectTenantLine: SKIP column already in WHERE, col=" + tenantColumn + ", stmt=SELECT");
                return statement;
            }
            LOG.info("injectTenantLine: INJECT col=" + tenantColumn + ", stmt=SELECT, table=" + table.getTableName());
            List<Condition> conditions = new ArrayList<>(sel.getConditions());
            conditions.add(new Condition.Simple(
                    new ColumnRef(sel.getTableAlias(), tenantColumn, null, null, null, null, null),
                    "=", new SqlValue.Placeholder(tenantColumn + " (= ?)"), null));
            return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), sel.getJoins(), sel.getDistinct(),
                    conditions, sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(), sel.getLimit(), sel.getRawFirst(),
                    sel.getRawAround(), sel.getComment(), sel.getUnions(), sel.getFromSubQuery(), sel.getFillClauses());
        } else if (statement instanceof UpdateStatement) {
            UpdateStatement upd = (UpdateStatement) statement;
            for (SetClause set : upd.getSets()) {
                if (set.getColumn() != null && tenantColumn.equals(set.getColumn().getColumnName())) {
                    LOG.info("injectTenantLine: SKIP column in SET, col=" + tenantColumn + ", stmt=UPDATE");
                    return statement;
                }
            }
            if (conditionReferencesColumn(upd.getConditions(), tenantColumn)) {
                LOG.info("injectTenantLine: SKIP column already in WHERE, col=" + tenantColumn + ", stmt=UPDATE");
                return statement;
            }
            LOG.info("injectTenantLine: INJECT col=" + tenantColumn + ", stmt=UPDATE, table=" + table.getTableName());
            List<Condition> conditions = new ArrayList<>(upd.getConditions());
            conditions.add(new Condition.Simple(
                    new ColumnRef(upd.getTableAlias(), tenantColumn, null, null, null, null, null),
                    "=", new SqlValue.Placeholder(tenantColumn + " (= ?)"), null));
            return new UpdateStatement(upd.getTable(), upd.getSets(), conditions, upd.getRawTail(), upd.getTableAlias(), upd.getJoins());
        } else if (statement instanceof DeleteStatement) {
            DeleteStatement del = (DeleteStatement) statement;
            if (conditionReferencesColumn(del.getConditions(), tenantColumn)) {
                LOG.info("injectTenantLine: SKIP column already in WHERE, col=" + tenantColumn + ", stmt=DELETE");
                return statement;
            }
            LOG.info("injectTenantLine: INJECT col=" + tenantColumn + ", stmt=DELETE, table=" + table.getTableName());
            List<Condition> conditions = new ArrayList<>(del.getConditions());
            conditions.add(new Condition.Simple(
                    new ColumnRef(del.getTableAlias(), tenantColumn, null, null, null, null, null),
                    "=", new SqlValue.Placeholder(tenantColumn + " (= ?)"), null));
            return new DeleteStatement(del.getTable(), conditions, del.getDeleteTargets(), del.getTableAlias(), del.getJoins());
        }
        return statement;
    }

    /**
     * F-6 Phase B：动态表名替换。
     */
    private SqlStatement processDynamicTableName(@NotNull TableInfo table, @NotNull SqlStatement statement) {
        if (settings == null || settings.getState() == null) return statement;
        List<TableNameRule> rules = settings.getState().getDynamicTableNameRules();
        if (rules == null) return statement;
        rules = rules.stream().filter(r -> r.getEnabled() && r.getPattern() != null && !r.getPattern().isBlank()).toList();
        if (rules.isEmpty()) return statement;
        String newTableName = DynamicTableNameProcessor.process(table.getTableName(), rules);
        if (newTableName == null) return statement;
        return rewriteTableName(statement, newTableName);
    }

    /**
     * 将语句模型中的表名替换为新的表名。
     */
    private SqlStatement rewriteTableName(@NotNull SqlStatement statement, @NotNull String newTableName) {
        if (statement instanceof SelectStatement) {
            SelectStatement sel = (SelectStatement) statement;
            return new SelectStatement(copyTableWithName(sel.getTable(), newTableName), sel.getSelectColumns(), sel.getTableAlias(),
                    sel.getJoins(), sel.getDistinct(), sel.getConditions(), sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(),
                    sel.getLimit(), sel.getRawFirst(), sel.getRawAround(), sel.getComment(), sel.getUnions(),
                    sel.getFromSubQuery(), sel.getFillClauses());
        } else if (statement instanceof UpdateStatement) {
            UpdateStatement upd = (UpdateStatement) statement;
            return new UpdateStatement(copyTableWithName(upd.getTable(), newTableName), upd.getSets(), upd.getConditions(),
                    upd.getRawTail(), upd.getTableAlias(), upd.getJoins());
        } else if (statement instanceof DeleteStatement) {
            DeleteStatement del = (DeleteStatement) statement;
            return new DeleteStatement(copyTableWithName(del.getTable(), newTableName), del.getConditions(), del.getDeleteTargets(),
                    del.getTableAlias(), del.getJoins());
        }
        return statement;
    }

    /**
     * 以新表名重建 {@link TableInfo}（对应 Kotlin 的 {@code tableInfo.copy(tableName = newTableName)}）。
     * 注意：Kotlin data class 的 {@code copy} 为具名参数，Java 中无单参数重载，必须显式构造。
     */
    private static @NotNull TableInfo copyTableWithName(@NotNull TableInfo table, @NotNull String newTableName) {
        return new TableInfo(table.getEntityClassName(), newTableName, table.getColumns(),
                table.getLogicDeleteColumn(), table.getVersionColumn(), table.getComment(), table.getAssociations());
    }

    /**
     * F-6 Phase C：数据权限模拟条件注入。
     */
    private SqlStatement injectDataPermissionCondition(@NotNull TableInfo table, @NotNull SqlStatement statement) {
        if (settings == null || settings.getState() == null || !settings.getState().getDataPermissionHint()) return statement;
        return DataPermissionPlaceholder.injectMockCondition(statement, settings.getState().getDataPermissionMockCondition());
    }

    /**
     * T-TenantLine：检测调用链所在方法是否标注 @InterceptorIgnore(tenantLine=true)。
     */
    private boolean hasInterceptorIgnoreAnnotation(@NotNull PsiElement anchor, @NotNull String attrName) {
        try {
            PsiMethod method = PsiTreeUtil.getParentOfType(anchor, PsiMethod.class);
            if (method == null) return false;
            PsiModifierList modifierList = method.getModifierList();
            if (modifierList == null) return false;
            PsiAnnotation[] annotations = modifierList.getAnnotations();
            if (annotations == null) return false;
            for (PsiAnnotation annotation : annotations) {
                PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
                String simpleName = (ref != null) ? ref.getReferenceName() : null;
                if ("InterceptorIgnore".equals(simpleName)) {
                    PsiAnnotationMemberValue value = annotation.findAttributeValue(attrName);
                    if (value instanceof PsiLiteralExpression) {
                        Object v = ((PsiLiteralExpression) value).getValue();
                        if (Boolean.TRUE.equals(v)) return true;
                    }
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * T-M29-03：分页 LIMIT 注入（仅 SELECT 语句生效）。
     */
    private SqlStatement applyPageLimit(@NotNull SqlStatement statement, @Nullable LimitClause pageLimit) {
        if (pageLimit == null) return statement;
        if (statement instanceof SelectStatement) {
            SelectStatement sel = (SelectStatement) statement;
            return new SelectStatement(sel.getTable(), sel.getSelectColumns(), sel.getTableAlias(), sel.getJoins(), sel.getDistinct(),
                    sel.getConditions(), sel.getGroupBy(), sel.getHaving(), sel.getOrderBy(), pageLimit, sel.getRawFirst(),
                    sel.getRawAround(), sel.getComment(), sel.getUnions(), sel.getFromSubQuery(), sel.getFillClauses());
        }
        return statement;
    }

    /**
     * T-M22-02：条件树是否已提及指定列。
     */
    private boolean conditionReferencesColumn(@NotNull List<Condition> conditions, @NotNull String columnName) {
        for (Condition condition : conditions) {
            if (condition instanceof Condition.Simple) {
                if (((Condition.Simple) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.Unary) {
                if (((Condition.Unary) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.In) {
                if (((Condition.In) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.Between) {
                if (((Condition.Between) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.Like) {
                if (((Condition.Like) condition).getColumn().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.ColumnComparison) {
                Condition.ColumnComparison cc = (Condition.ColumnComparison) condition;
                if (cc.getLeft().getColumnName().equals(columnName) || cc.getRight().getColumnName().equals(columnName)) return true;
            } else if (condition instanceof Condition.Group) {
                if (conditionReferencesColumn(((Condition.Group) condition).getChildren(), columnName)) return true;
            }
        }
        return false;
    }

    /**
     * 单个条件节点 → 条件视图（Kotlin 扩展函数 toConditionViews 的 Java 等价实现）。
     */
    private List<ConditionView> toConditionViews(@NotNull Condition condition) {
        ConditionToggle ownToggle = condition.getToggle();
        if (condition instanceof Condition.Group && ownToggle != null) {
            List<String> sources = groupValueSources(condition);
            List<ConditionView> views = new ArrayList<>();
            views.add(new ConditionView(ownToggle.getId(), ownToggle.getLabel(), ownToggle.getFlagSource(),
                    ownToggle.getDefaultEnabled(), sources.isEmpty() ? null : sources.get(0), sources));
            return views;
        }
        ConditionView own = null;
        if (ownToggle != null) {
            List<String> sources = ownToggle.getValueSources();
            List<String> finalSources = sources.isEmpty()
                    ? (ownToggle.getValueSource() != null ? List.of(ownToggle.getValueSource()) : List.of())
                    : sources;
            own = new ConditionView(ownToggle.getId(), ownToggle.getLabel(), ownToggle.getFlagSource(),
                    ownToggle.getDefaultEnabled(), ownToggle.getValueSource(), finalSources);
        }
        List<ConditionView> nested = new ArrayList<>();
        if (condition instanceof Condition.Group) {
            for (Condition child : ((Condition.Group) condition).getChildren()) {
                nested.addAll(toConditionViews(child));
            }
        }
        List<ConditionView> result = new ArrayList<>();
        if (own != null) result.add(own);
        result.addAll(nested);
        return result;
    }

    /**
     * T-M29.1：组内子条件值来源递归收集。
     */
    private List<String> groupValueSources(@NotNull Condition condition) {
        List<String> result = new ArrayList<>();
        if (condition instanceof Condition.Simple) {
            SqlValue v = ((Condition.Simple) condition).getValue();
            if (v instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) v).getSource());
        } else if (condition instanceof Condition.Like) {
            SqlValue v = ((Condition.Like) condition).getPattern();
            if (v instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) v).getSource());
        } else if (condition instanceof Condition.Between) {
            Condition.Between b = (Condition.Between) condition;
            if (b.getLo() instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) b.getLo()).getSource());
            if (b.getHi() instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) b.getHi()).getSource());
        } else if (condition instanceof Condition.In) {
            for (SqlValue v : ((Condition.In) condition).getValues()) {
                if (v instanceof SqlValue.Placeholder) result.add(((SqlValue.Placeholder) v).getSource());
            }
        } else if (condition instanceof Condition.Group) {
            for (Condition child : ((Condition.Group) condition).getChildren()) result.addAll(groupValueSources(child));
        }
        // 去重保持渲染顺序
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    /**
     * O-5：当前方言（配置未加载时默认 MySQL）；T-DialectAutoInfer：auto 开启时智能推断。
     *
     * <p>T-TS-FixC：**方言模式（auto/manual）不在本层判定**，整段委托 {@link DialectRegistry#effective}，
     * 本方法只负责把宿主 project 传下去。为什么不像其余 effective* 那样写
     * 「项目值 ?: 全局值」两段式：方言的模式判定是**跨层**的——项目级 auto 与全局 auto 参与同一次
     * 判定（项目级 true 必须压过全局 false），而项目级手动方言名又要压过全局手动值；拆成
     * 「先在本层算 auto、再让下层算 manual」就会出现两个事实源，正是本次缺陷的成因
     * （本层按项目级判 auto=true 进 effective，下层却按全局 false 返回全局手动值）。
     * 收口后优先级语义见 {@code DialectRegistry.effective} 的 KDoc：模式与手动值均「项目级 &gt; 全局」。
     */
    @NotNull
    public Dialect currentDialect() {
        return DialectRegistry.INSTANCE.effective(project);
    }

    /**
     * 查询类 Wrapper 类型（可能被删除类消费方法复用为删除条件，M10）。
     */
    private boolean isQueryLike(@NotNull WrapperType type) {
        return type == WrapperType.LAMBDA_QUERY
                || type == WrapperType.QUERY
                || type == WrapperType.MPJ_LAMBDA
                || type == WrapperType.MPJ_QUERY
                || type == WrapperType.MPJ_LAMBDA_QUERY;
    }

    /**
     * 查询类解析统一入口：优先走 ConverterRegistry 注册的 converter（保留主表别名与 JOIN）。
     */
    private SelectStatement parseQueryLike(@NotNull WrapperChain chain, @NotNull TableInfo table) {
        // T-FS-04：显式传本服务绑定的 project（语义同 else -> ConverterRegistry.convert 分支）
        SqlStatement converted = ConverterRegistry.INSTANCE.convert(chain, table, project);
        if (converted instanceof SelectStatement) return (SelectStatement) converted;
        return new QueryWrapperParser(table).parse(chain);
    }

    /**
     * 逻辑删除注入状态（T-M20-05）：驱动 injectLogicDeleteCondition。
     */
    private static final class LogicDeleteState {
        final boolean mainActive;
        final boolean subActive;
        final boolean subToWhere;

        LogicDeleteState() {
            this(true, true, false);
        }

        LogicDeleteState(boolean mainActive, boolean subActive, boolean subToWhere) {
            this.mainActive = mainActive;
            this.subActive = subActive;
            this.subToWhere = subToWhere;
        }
    }

    /**
     * 逻辑删除控制求值（T-M20-05，对齐 MPJ 官方六方法语义）。
     */
    private LogicDeleteState evaluateLogicDeleteState(@NotNull List<WrapperCall> calls) {
        LogicDeleteState state = new LogicDeleteState();
        for (WrapperCall call : calls) {
            switch (call.getMethodName()) {
                case METHOD_DISABLE_LOGIC_DEL:
                    state = new LogicDeleteState(false, state.subActive, state.subToWhere);
                    break;
                case METHOD_ENABLE_LOGIC_DEL:
                    state = new LogicDeleteState(true, state.subActive, state.subToWhere);
                    break;
                case METHOD_DISABLE_SUB_LOGIC_DEL:
                    state = new LogicDeleteState(state.mainActive, false, state.subToWhere);
                    break;
                case METHOD_ENABLE_SUB_LOGIC_DEL:
                    state = new LogicDeleteState(state.mainActive, true, state.subToWhere);
                    break;
                case METHOD_LOGIC_DEL_TO_ON:
                    state = new LogicDeleteState(state.mainActive, state.subActive, false);
                    break;
                case METHOD_LOGIC_DEL_TO_WHERE:
                    state = new LogicDeleteState(state.mainActive, state.subActive, true);
                    break;
                default:
                    break;
            }
        }
        return state;
    }

    /**
     * 解析 HQL 对应的实体表信息。
     *
     * <p>优先使用 entityClass（PsiClass 直接解析）；
     * 否则按 entityClassName 查找 @Entity 类（含 {@code @Entity(name=...)} 实体别名反查）；
     * 最后从 HQL 的 FROM 子句提取类名查找 / hbm 映射兜底。
     *
     * <p>T-HqlInhCols：列空结果不再直接短路——JpaEntityResolver 异常路径返回
     * 非空 TableInfo 但列为空（如继承链解析失败/抽象父类映射异常），若在此短路
     * 会跳过 hbm 兜底与别名反查，下游展开器必出「列信息未解析」降级注释；
     * 判据改为「非 null 且列非空」，列空时继续走后续路径。
     */
    private TableInfo resolveHqlTable(@Nullable PsiClass entityClass, @Nullable String entityClassName, @NotNull String hql) {
        JpaEntityResolver resolver = new JpaEntityResolver();
        // T-Fix-SettingsScope：下方 project 一律取本服务绑定的 project，勿用
        // openProjects.firstOrNull()——多项目同开时后者取谁取决于 IDE 打开顺序，会在 B 项目里
        // 按 A 项目的项目级表配置修饰表名，并使 GlobalSearchScope/JavaPsiFacade 落到错误的项目范围。
        String className = (entityClassName != null) ? entityClassName : extractEntityNameFromHql(hql);

        // 路径 1：有 PsiClass 直接解析（JPA 注解）。列空不短路到「更弱的路径」——
        // 夹具/局部类场景路径 2/3 反而找不到该类；列空仅在「有更好的候选」时被替换：
        // 即后续路径找到的表列非空才覆盖，否则保留路径 1 结果（含表名与实体类名，
        // 展开器照常给「列信息未解析」诊断，不丢 FROM 表名替换）。
        TableInfo resolved = null;
        if (entityClass != null) {
            TableInfo table = resolver.resolve(entityClass);
            if (table != null && !table.getColumns().isEmpty()) return table;
            resolved = table;
        }

        // 路径 2：按类名查找 @Entity 类（JPA 注解）；列空不短路
        if (className != null) {
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(className, scope);
            if (psiClass == null) psiClass = findClassByShortName(project, className, scope);
            if (psiClass == null) psiClass = findClassByEntityName(project, className, scope);
            if (psiClass != null) {
                TableInfo table = resolver.resolve(psiClass);
                if (table != null && !table.getColumns().isEmpty()) return table;
                if (resolved == null) resolved = table;
            }
        }

        // 路径 3：Hibernate XML 映射文件（.hbm.xml）
        if (className != null) {
            HbmXmlMapping.ClassMapping hbmMapping = HbmXmlEntityResolver.resolve(className, project);
            if (hbmMapping != null) {
                return convertHbmMappingToTableInfo(hbmMapping);
            }
        }

        return resolved;
    }

    /**
     * T-HqlInhCols：按 {@code @Entity(name=...)} 实体别名查找实体类。
     *
     * <p>HQL {@code FROM Alias} 的 Alias 可为实体名（JPA 语义）而非类名——
     * 类名快路径（findClass/findClassByShortName）未命中时按注解值反查。
     * 扫描结果（实体名 → 全限定名）缓存于 Project userData，以
     * PsiModificationTracker.modificationCount 失效（照抄 EntityClassFinder 的
     * {@code @TableName} 反查同款缓存惯例；未命中也入缓存防重复全量扫描）。
     */
    @Nullable
    private PsiClass findClassByEntityName(@NotNull Project project, @NotNull String entityName, @NotNull GlobalSearchScope scope) {
        if (entityName.isBlank()) return null;
        long current = com.intellij.psi.util.PsiModificationTracker.getInstance(project).getModificationCount();
        EntityNameLookupCache existing = project.getUserData(ENTITY_NAME_CACHE_KEY);
        EntityNameLookupCache cache;
        if (existing != null && existing.count() == current) {
            cache = existing;
        } else {
            cache = new EntityNameLookupCache(current, new java.util.HashMap<>());
            project.putUserData(ENTITY_NAME_CACHE_KEY, cache);
        }

        String key = entityName.toLowerCase();
        String cached = cache.names().get(key);
        if (cached != null) {
            String shortName = substringAfterLast(cached, '.');
            PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(shortName, scope);
            for (PsiClass clazz : classes) {
                if (cached.equals(clazz.getQualifiedName())) return clazz;
            }
            return null;
        }
        if (cached != null || (cached == null && cache.names().containsKey(key) && cache.names().get(key) == null)) return null;
        // 已知未命中（空串哨兵值）快速返回
        if (cache.names().containsKey(key)) return null;

        // 慢路径：全量扫项目类短名，匹配 @Entity(name=...) 注解值（jakarta/javax/短名三轨）
        String matchedQName = null;
        PsiShortNamesCache namesCache = PsiShortNamesCache.getInstance(project);
        outer:
        for (String shortName : namesCache.getAllClassNames()) {
            for (PsiClass clazz : namesCache.getClassesByName(shortName, scope)) {
                if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotationType()) continue;
                String alias = null;
                try {
                    alias = JpaMetadataReader.entityNameOf(clazz);
                } catch (Throwable t) {
                    alias = null;
                }
                if (alias == null) continue;
                if (alias.equalsIgnoreCase(entityName)) {
                    matchedQName = clazz.getQualifiedName();
                    break outer;
                }
            }
        }
        cache.names().put(key, matchedQName != null ? matchedQName : "");
        if (matchedQName == null) return null;
        String shortName = substringAfterLast(matchedQName, '.');
        PsiClass[] classes = namesCache.getClassesByName(shortName, scope);
        for (PsiClass clazz : classes) {
            if (matchedQName.equals(clazz.getQualifiedName())) return clazz;
        }
        return null;
    }

    /** 实体别名反查缓存条目：modificationCount + 实体名→全限定名（空串=已知未命中）。 */
    private record EntityNameLookupCache(
            /** 取值时的 PsiModificationTracker.modificationCount。 */
            long count,
            /** 实体名（小写）→ 全限定名（空串 = 已知未命中）。 */
            @NotNull java.util.Map<String, String> names) {
    }

    /** 实体别名反查缓存 userData 键（Kotlin 为实例属性，Java 提为静态——Key 天然可共享）。 */
    private static final com.intellij.openapi.util.Key<EntityNameLookupCache> ENTITY_NAME_CACHE_KEY =
            com.intellij.openapi.util.Key.create("mp-visualizer.entityNameLookupCache");

    /**
     * 从 HQL 的 FROM 子句提取实体类名。
     */
    private String extractEntityNameFromHql(@NotNull String hql) {
        Matcher m = Pattern.compile("\\bFROM\\s+([A-Z][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE).matcher(hql);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 将 HBM XML 映射转换为 TableInfo。
     */
    private TableInfo convertHbmMappingToTableInfo(@NotNull HbmXmlMapping.ClassMapping mapping) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (HbmXmlMapping.PropertyMapping prop : mapping.getProperties()) {
            columns.add(new ColumnInfo(prop.getFieldName(), prop.getColumnName(), prop.isId(), false, null, null, null, ColumnRole.BUSINESS));
        }
        List<AssociationInfo> associations = new ArrayList<>();
        for (HbmXmlMapping.AssociationMapping assoc : mapping.getAssociations()) {
            String targetSimpleName = substringAfterLast(assoc.getTargetClassName(), '.');
            associations.add(new AssociationInfo(assoc.getFieldName(), assoc.getJoinColumn(), targetSimpleName,
                    decapitalize(targetSimpleName)));
        }
        return new TableInfo(mapping.getEntityClassName(), mapping.getTableName(), columns, null, null, null, associations);
    }

    /**
     * 按短类名查找 PsiClass。
     */
    private PsiClass findClassByShortName(@NotNull Project project, @NotNull String shortName, @NotNull GlobalSearchScope scope) {
        PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(shortName, scope);
        return (classes.length > 0) ? classes[0] : null;
    }

    /**
     * 清空缓存（编辑器文件切换/项目关闭时由持有方调用）。
     */
    public void clearCache() {
        cache.clear();
    }

    /** Wrapper 解析缓存条目数（设置页缓存统计，PluginCacheManager 调用）。 */
    public int wrapperCacheEntryCount() {
        return cache.size();
    }

    /** Wrapper 解析缓存大小估算（设置页缓存统计，PluginCacheManager 调用）。 */
    public long wrapperCacheSizeBytes() {
        return cache.sizeBytes();
    }

    /**
     * F-7：预览完成后自动记录历史。
     */
    private void recordHistory(@NotNull PreviewResult result, @NotNull PsiElement anchor) {
        try {
            SqlHistoryManager manager = SqlHistoryManager.getInstance();
            manager.setMaxSize((settings != null && settings.getState() != null)
                    ? settings.getState().getHistoryMaxSize() : SqlHistoryManager.DEFAULT_MAX_SIZE);
            PsiFile file = anchor.getContainingFile();
            String sourceFile = (file != null) ? file.getName() : "";
            int sourceLine;
            String text = (file != null) ? file.getText() : null;
            int offset = anchor.getTextRange().getStartOffset();
            if (text != null) {
                int count = 0;
                int lim = Math.min(offset, text.length());
                for (int i = 0; i < lim; i++) {
                    if (text.charAt(i) == '\n') count++;
                }
                sourceLine = count + 1;
            } else {
                sourceLine = 0;
            }
            String tableName = (result.getTableName() != null) ? result.getTableName() : "";
            // 尝试从最近日志条目补全耗时（SQL 指纹匹配）
            // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建）；取不到即不做耗时补全（等同未命中）
            Long executionTimeMs = null;
            SqlLogManager logManager = SqlLogManagerLookupKt.sqlLogManagerIfCreated(anchor.getProject());
            if (logManager != null) {
                executionTimeMs = logManager.getEntries().snapshot().stream()
                        .filter(e -> Objects.equals(e.restoredSql(), result.getSql()))
                        .findFirst()
                        .map(LogSqlEntry::executionTimeMs)
                        .orElse(null);
            }
            SqlHistoryEntry entry = new SqlHistoryEntry("", result.getSql(), result.getCompactSql(), System.currentTimeMillis(),
                    sourceFile, sourceLine, tableName, false, List.of());
            entry.setExecutionTimeMs(executionTimeMs);
            manager.add(entry);
        } catch (Throwable e) {
            // 服务不可用时静默跳过（不阻塞预览）
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String blank(@Nullable String s) {
        return (s == null || s.isBlank()) ? "" : s;
    }

    private static String substringAfterLast(@NotNull String s, char c) {
        int idx = s.lastIndexOf(c);
        return (idx >= 0) ? s.substring(idx + 1) : s;
    }

    private static String decapitalize(@NotNull String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toLowerCase() + s.substring(1);
    }

    private static <T> T safeGet(@NotNull ThrowableSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable e) {
            return null;
        }
    }

    private interface ThrowableSupplier<T> {
        T get() throws Throwable;
    }

    /**
     * 风险警示（F-09/F-10 + F-6 Phase C BlockAttack 增强 + DataPermission 占位提示）。
     */
    private String buildWarning(@NotNull SqlStatement statement) {
        String baseWarning = null;
        if (statement instanceof UpdateStatement) {
            if (((UpdateStatement) statement).getConditions().isEmpty()) {
                baseWarning = "⚠ [BlockAttack] 无 WHERE 条件，将更新全表数据！";
            }
        } else if (statement instanceof DeleteStatement) {
            if (((DeleteStatement) statement).getConditions().isEmpty()) {
                baseWarning = "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！";
            }
        }
        String dataPermissionWarning = DataPermissionPlaceholder.buildWarning(
                settings != null && settings.getState() != null && settings.getState().getDataPermissionHint());
        List<String> parts = new ArrayList<>();
        if (baseWarning != null) parts.add(baseWarning);
        if (dataPermissionWarning != null) parts.add(dataPermissionWarning);
        String joined = String.join("\n", parts);
        return joined.isEmpty() ? null : joined;
    }

    // ------------------------------------------------------------------
    // 伴侣对象（companion object）等价静态成员
    // ------------------------------------------------------------------

    private static final Logger LOG = Logger.getInstance(SqlPreviewService.class);

    /**
     * T-PreviewFast P7：最近一次预览请求的起始时刻（点击/快捷键入口记时）。三段耗时的锚点：
     * 请求 → 弹窗（{@link #showResult} 记）→ 控制台可见（面板 embedConsole 记）。仅诊断用，
     * 无锁 volatile 读写，取不到 0 即忽略。
     */
    public static volatile long lastPreviewRequestMs = 0L;

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** 逻辑删除控制方法名（T-M20-05，MPJ 官方六方法）。 */
    private static final String METHOD_DISABLE_LOGIC_DEL = "disableLogicDel";
    private static final String METHOD_ENABLE_LOGIC_DEL = "enableLogicDel";
    private static final String METHOD_DISABLE_SUB_LOGIC_DEL = "disableSubLogicDel";
    private static final String METHOD_ENABLE_SUB_LOGIC_DEL = "enableSubLogicDel";
    private static final String METHOD_LOGIC_DEL_TO_ON = "logicDelToOn";
    private static final String METHOD_LOGIC_DEL_TO_WHERE = "logicDelToWhere";

    /** 默认租户列名（T-TenantLine）：tenantColumnName 空白时回退此值。 */
    private static final String DEFAULT_TENANT_COLUMN = "tenant_id";

    /** SQL 子句关键字（detectMainAlias 探测主表别名时排除的关键字形态）。 */
    private static final Set<String> SQL_CLAUSE_KEYWORDS = Set.of(
            "WHERE", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "JOIN", "ON", "CROSS",
            "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH", "UNION", "SET", "AS"
    );

    /** 联表更新/删除 Wrapper 类型集合（T-M21-08）。 */
    private static final Set<WrapperType> UPDATE_DELETE_JOIN_TYPES = Set.of(WrapperType.UPDATE_JOIN, WrapperType.DELETE_JOIN);

    /**
     * 统一展示入口（M10-03）：恒以弹窗（SqlPreviewDialog）展示预览结果。
     *
     * <p>T-RefreshDbSteal F2：弹窗单例化——同项目已有预览弹窗时先 dispose 旧弹窗再开新。
     * 旧实现每次调用都 new 一个 MODELESS 弹窗且从不关闭，多弹窗各自持有 rehost 监听，
     * 互相抢占插件级共享控制台（真机 6 连抢根因）；单例化后同一时刻每个项目至多一个
     * 预览弹窗存活，旧面板的监听随 dispose 显式移除（F3），僵尸源头消灭。
     * 代价：旧弹窗的滚动/尺寸状态不保留（新弹窗重新初始化，尺寸走 DimensionService 记忆）。
     *
     * @param result         null 表示未找到可转换的 Wrapper → 弹友好提示（不抛异常）
     * @param sourceLocation 当前代码位置（文件名:行号），来源列默认值；null 表示未知
     */
    public static void showResult(@NotNull Project project, @Nullable PreviewResult result, @Nullable String sourceLocation) {
        // T-PreviewFast P7：请求→弹窗耗时（第二段锚点；后续控制台可见由面板记）
        try {
            long req = lastPreviewRequestMs;
            if (req > 0 && result != null) {
                LOG.info("[T-PreviewFast] request→dialog: " + (System.currentTimeMillis() - req) + "ms");
            }
        } catch (Throwable ignored) {
            // 与原 runCatching 等价（诊断打点不阻断主流程）
        }
        if (result == null) {
            // T-UiNative T-UN-02：未找到可转换 Wrapper 提示改轻通知（原模态弹窗——纯告知无需打断）；
            // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                try {
                    new Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("action.notFound.title"),
                            MpSqlVisualizerBundle.message("action.notFound.message"),
                            NotificationType.INFORMATION
                    ).notify(project);
                } catch (Throwable ignored) {
                    // headless 无通知系统依赖，与原 runCatching 等价
                }
            }
            return;
        }
        // 关旧开新：旧弹窗 dispose 级联释放面板（rehost 监听随之移除），再开新弹窗
        WeakReference<SqlPreviewDialog> previousRef = ACTIVE_PREVIEW_DIALOGS.get(project);
        SqlPreviewDialog previous = previousRef != null ? previousRef.get() : null;
        if (previous != null) {
            if (previous.isDisposed()) {
                LOG.info("[M36] preview dialog singleton: previous dialog already disposed");
            } else {
                LOG.info("[M36] preview dialog singleton: closing previous dialog (id=" + System.identityHashCode(previous) + ")");
                try {
                    previous.doDispose();
                } catch (Throwable t) {
                    // 关旧弹窗失败不阻断开新
                }
            }
        }
        SqlPreviewDialog dialog = new SqlPreviewDialog(project, result, sourceLocation);
        ACTIVE_PREVIEW_DIALOGS.put(project, new WeakReference<>(dialog));
        dialog.show();
    }

    /** T-RefreshDbSteal F2：项目 → 当前存活的预览弹窗（弱引用——弹窗被用户关闭后不阻回收）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Project, WeakReference<SqlPreviewDialog>> ACTIVE_PREVIEW_DIALOGS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** T-RefreshDbSteal F2：测试访问器——当前项目登记的存活预览弹窗（单例化断言用）。 */
    public static SqlPreviewDialog activePreviewDialogForTest(@NotNull Project project) {
        WeakReference<SqlPreviewDialog> ref = ACTIVE_PREVIEW_DIALOGS.get(project);
        return ref != null ? ref.get() : null;
    }

    /**
     * 后台线程 + 读操作执行转换，EDT 回调结果（Kotlin 协程的 Java 等价实现）。
     *
     * <p>PSI 定位/解析在 IDEA 2025.3+ 属于"慢操作"，禁止在 EDT 上执行；
     * 按钮/快捷键/编辑器跟随等 UI 触发方统一走此入口，避免 "Slow operations are prohibited on EDT"。
     *
     * @param project   项目
     * @param compute   在 ReadAction 内执行转换（锚点元素可先在 EDT 捕获，块内读取）
     * @param onResult 在 EDT 上回调转换结果（未找到 Wrapper 时为 null）
     */
    public static void previewAsync(
            @NotNull Project project,
            @NotNull Function0<? extends PreviewResult> compute,
            @NotNull Function1<? super PreviewResult, Unit> onResult) {
        // T-PreviewFast P7：三段耗时埋点起点（点击/快捷键触发即记时）——真机排障时直接给出
        // 「请求→弹窗」「弹窗→控制台可见」两段数字，取代靠多条阶段日志手工推时序
        lastPreviewRequestMs = System.currentTimeMillis();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            PreviewResult result;
            try {
                result = ReadAction.compute(() -> compute.invoke());
            } catch (Throwable e) {
                // T-FatalPassthrough T-FP-02b（独立复核条件项，收口原豁免）：本处是**预览主链**
                // （点击/快捷键 → PSI 转换 → 弹窗），不是「可选能力探测」——原豁免理由把主链写成
                // 探测，属借豁免放走真该收口的站点。fatal（VME/LinkageError/ThreadDeath）透传给池
                // 线程平台处理器（原状：任何失败都返回 null，用户只看到「未找到可转换的 Wrapper」
                // 提示，真实异常零日志且归因错误——真机排障无入口）。
                // 非 fatal 仍返回 null（既有降级语义逐字不变），但补一条 WARN 留排查锚点。
                if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(e)) throw e;
                LOG.warn("[M36] previewAsync compute FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                result = null;
            }
            final PreviewResult r = result;
            // T-ExecSlowOps F2：确定性 SQL（无参数开关/批量/XML 模型）预览成功即后台预热
            // exec 临时文件（写盘→refresh→方言映射→findFile→填缓存槽），弹窗打开后
            // prepareDefaultConsole 命中缓存，EDT 冷路径（refresh+findFile 慢操作 SEVERE 本体）
            // 退化为兜底。独立池线程 fire-and-forget——预热含 VFS refresh 有界耗时，不得推迟
            // 弹窗；带参/批量/XML 预览 SQL 随面板勾选变化，预热内容不可信，跳过。
            if (r != null && r.getPreviewModel() == null && r.getBatchModel() == null
                    && r.getXmlModel() == null
                    && (r.getFillClauses() == null || r.getFillClauses().isEmpty())
                    && cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()) {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prewarmExecTempFile(project, r.getSql());
                    } catch (Throwable t) {
                        // T-FatalPassthrough T-FP-02b（独立复核阻断 2 同族）：本 runCatching 包住整条
                        // 预热链——不放行 fatal 会让 DataSourceSelector 预热链的 6 处透传（缓存命中
                        // 刷新 / 写盘 / pre-refresh 收敛 / refresh / syncVf / findPsi）全部作废。
                        // 非 fatal 仍 warn 降级（fire-and-forget 语义不变）；fatal 透传到池线程
                        // 平台处理器记录现场。
                        if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw t;
                        LOG.warn("[M36] exec prewarm dispatch failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                });
            }
            ApplicationManager.getApplication().invokeLater(() -> onResult.invoke(r));
        });
    }

    /**
     * 简洁重载：无 sourceLocation 的展示入口。
     */
    public static void showResult(@NotNull Project project, @Nullable PreviewResult result) {
        showResult(project, result, null);
    }
}
