package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.sql.SqlClause;
import cn.song.mybatisplus.visualizer.sql.SqlTextAnalysisKt;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import kotlin.Pair;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * SQL 预览代码补全（M9-03，方案 A：Community 兼容自定义补全）。
 *
 * 为原生 SQL 编辑器（插件预览面板，language="SQL" + IS_PREVIEW_EDITOR 守卫）提供 Ctrl+Space 补全：
 *  - SQL 关键字（{@link SqlLexer#KEYWORDS}）
 *  - 当前预览实体的表名 / 列名 / 表别名（来自 {@link SqlCompletionContext}，经 Editor/PSI 双载体 userData 传递）
 *  - M16：列名/表名补全项**展示注释**（withTailText 灰色尾缀）且**支持注释关键词检索**
 *    （withLookupString——输入"设备名"可命中 {@code name} 列）；**位置感知**过滤
 *    （FROM/JOIN 后只提示表名、WHERE/ON/HAVING/GROUP BY/ORDER BY 后只提示列名）
 *
 * M16.2 修复（M16 回归）：
 *  - 上下文载体由**仅 PSI 文件 userData** 改为 **Editor userData 优先 + PSI 兜底**（{@link #resolveContext}）——
 *    嵌入式编辑器中用户输入后 {@code PsiDocumentManager} commit 会重建非项目 PSI 文件（userData 丢失），
 *    而 Editor 对象编辑期间稳定不重建；
 *  - **ctx 为 null 时不再直接 return**：关键字补全不依赖上下文（恢复 M9 语义，{@code if (ctx != null)} 包裹实体补全）；
 *  - {@link SqlTextAnalysisKt#detectClause} 防御：offset clamp 到文本长度 + 空文本/异常回退 UNKNOWN
 *    （旧 PSI offset 与新文档长度不一致时 {@code substring} 越界抛异常 → Contributor 异常被 IDE 吞掉 → 补全静默失效）。
 *
 * T-M27-01（M27）：{@code detectClause} / {@code SqlClause} 迁入 {@code sql.SqlTextAnalysis} 纯函数模块
 * （零 PSI/IDE 依赖），本类改调公共 API——语义与防御行为不变（预览编辑器补全依赖，回归测试兜底）。
 *
 * 补全来源抽象见 {@link SqlCompletionProvider}（预留 Database 方案 B 切换点）。
 * 注册见 plugin.xml {@code <completion.contributor>}。
 */
public class SqlCompletionContributor extends CompletionContributor {

    private static final Logger LOG = Logger.getInstance(SqlCompletionContributor.class);

    /**
     * Document → SqlCompletionContext 映射（WeakHashMap，文档释放时自动清除）。
     *
     * 解决原生 SQL PSI 文件 commitDocument 重建后 PSI userData 丢失的问题：
     * 预览编辑器创建时存入，补全时按 Document 查找。
     */
    private static final WeakHashMap<Document, SqlCompletionContext> documentContexts = new WeakHashMap<>();

    public static void registerContext(Document document, SqlCompletionContext ctx) {
        documentContexts.put(document, ctx);
        LOG.info("[SqlCompletion] registerContext: doc=" + document.hashCode() + ", table=" + ctx.getTableName() + ", mapSize=" + documentContexts.size());
    }

    public SqlCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(CompletionParameters parameters, ProcessingContext context, CompletionResultSet result) {
                PrefixMatcher prefix = result.getPrefixMatcher();
                SqlCompletionProvider provider = SqlCompletionProvider.Companion.getInstance();

                // ★ 调试：每次补全触发时打印关键信息
                Editor editor = parameters.getEditor();
                PsiElement position = parameters.getPosition();
                PsiFile containingFile = position.getContainingFile();
                LOG.info("[SqlCompletion] addCompletions triggered");
                LOG.info("[SqlCompletion]   editor=" + editor.getClass().getSimpleName() + ", doc=" + editor.getDocument().hashCode());
                String posText = position.getText();
                LOG.info("[SqlCompletion]   position=" + position.getClass().getSimpleName() + ", text='" + (posText != null ? posText.substring(0, Math.min(30, posText.length())) : "") + "'");
                LOG.info("[SqlCompletion]   containingFile=" + (containingFile != null ? containingFile.getName() : null) + ", lang=" + (containingFile != null && containingFile.getLanguage() != null ? containingFile.getLanguage().getID() : null));
                SqlCompletionContext ctxEditor = editor.getUserData(SqlCompletionContext.KEY);
                LOG.info("[SqlCompletion]   editorUserData=" + (ctxEditor != null ? ctxEditor.getTableName() : null));
                SqlCompletionContext ctxPsi = containingFile != null ? containingFile.getUserData(SqlCompletionContext.KEY) : null;
                LOG.info("[SqlCompletion]   psiUserData=" + (ctxPsi != null ? ctxPsi.getTableName() : null));
                LOG.info("[SqlCompletion]   documentContexts.size=" + documentContexts.size());
                Document docFromViewProvider = containingFile != null && containingFile.getViewProvider() != null ? containingFile.getViewProvider().getDocument() : null;
                LOG.info("[SqlCompletion]   docFromViewProvider=" + (docFromViewProvider != null ? docFromViewProvider.hashCode() : null) + ", inMap=" + (docFromViewProvider != null && documentContexts.containsKey(docFromViewProvider)));

                // 关键字（M16.2：不依赖上下文——ctx 缺失/丢失时关键字补全仍生效，恢复 M9 语义）
                for (String word : provider.keywords()) {
                    if (prefix.prefixMatches(word)) {
                        result.addElement(LookupElementBuilder.create(word));
                    }
                }

                // 当前实体表/列/别名（M16：位置感知 + 注释展示/检索）
                // M16.2：双载体读取（Editor userData 优先、PSI 兜底）；ctx 为 null 时跳过实体补全
                // M34 修复：level 3 兜底再补一次 editor.document 直接查（PSI viewProvider document
                // 与注册 document 可能因 commit 重建不同实例）
                SqlCompletionContext ctx = resolveContext(parameters.getEditor().getUserData(SqlCompletionContext.KEY), parameters.getPosition());
                if (ctx == null) {
                    ctx = documentContexts.get(parameters.getEditor().getDocument());
                }
                LOG.info("[SqlCompletion]   resolveContext result=" + (ctx != null ? ctx.getTableName() : null));
                if (ctx == null) {
                    return;
                }
                // 子句判定（T-M27-01：纯函数迁入 SqlTextAnalysis，语义与 M16.2 防御行为不变；异常/无文件回退 UNKNOWN）
                SqlClause clause;
                try {
                    PsiFile cf = parameters.getPosition().getContainingFile();
                    String fileText = cf != null ? cf.getText() : "";
                    int off = parameters.getPosition().getTextRange().getStartOffset();
                    clause = SqlTextAnalysisKt.detectClause(fileText, off);
                } catch (Throwable e) {
                    clause = SqlClause.UNKNOWN;
                }

                // 表名：全位置提示（主表 + 联表候选，前缀过滤——T-M27.4 用户需求
                // "表名也支持提示"：输入表名前缀即可命中，不再限定 FROM/JOIN 后；候选与主表去重）
                String tableName = provider.tableName(ctx);
                if (tableName != null && prefix.prefixMatches(tableName)) {
                    result.addElement(withComment(LookupElementBuilder.create(tableName), provider.tableComment(ctx)));
                }
                for (TableInfo t : provider.candidateTables(ctx)) {
                    if (!t.getTableName().equals(ctx.getTableName()) && prefix.prefixMatches(t.getTableName())) {
                        result.addElement(withComment(LookupElementBuilder.create(t.getTableName()), t.getComment()));
                    }
                }
                // 列名：WHERE/ON/HAVING/GROUP BY/ORDER BY 后、SELECT 列位或子句无法判定
                if (clause != SqlClause.TABLE) {
                    List<Pair<String, String>> colComments = provider.columnsWithComments(ctx);
                    // M35 方案 B：数据源 schema 注释优先（DB COMMENT 比实体注释权威）；
                    // 按实体表名查数据源列注释，命中则覆盖同名列的实体注释。
                    Project project = parameters.getEditor().getProject();
                    Map<String, String> schemaComments;
                    if (project != null && ctx.getTableName() != null) {
                        schemaComments = DatabaseSchemaResolver.columnComments(project, ctx.getTableName());
                    } else {
                        schemaComments = Map.of();
                    }
                    List<Pair<String, String>> merged;
                    if (schemaComments.isEmpty()) {
                        merged = colComments;
                    } else {
                        merged = colComments.stream()
                                .map(p -> new Pair<>(p.getFirst(), schemaComments.getOrDefault(p.getFirst(), p.getSecond())))
                                .toList();
                    }
                    for (Pair<String, String> p : merged) {
                        String col = p.getFirst();
                        String comment = p.getSecond();
                        if (prefix.prefixMatches(col)) {
                            result.addElement(withComment(LookupElementBuilder.create(col), comment));
                        }
                    }
                }
                // SELECT 列位：聚合函数（M16 位置感知补充）
                if (clause == SqlClause.SELECT_COLUMN) {
                    for (String fn : AGGREGATE_FUNCTIONS) {
                        if (prefix.prefixMatches(fn)) {
                            result.addElement(LookupElementBuilder.create(fn));
                        }
                    }
                }
                // 表别名：仅子句无法判定时（全量候选）
                if (clause == SqlClause.UNKNOWN) {
                    String tableAlias = provider.tableAlias(ctx);
                    if (tableAlias != null && prefix.prefixMatches(tableAlias)) {
                        result.addElement(LookupElementBuilder.create(tableAlias));
                    }
                }
            }
        });
    }

    /**
     * 补全项注释装饰（M16 注释展示/检索；T-M16.3-02 修复）：
     * {@link LookupElementBuilder} **不可变**——{@code withTailText}/{@code withLookupString} 返回**新实例**，
     * 原实现丢弃返回值（{@code builder.withTailText(...)} 结果未使用）导致注释 tailText 从不展示——
     * 用户实测「字段可补全但看不到注释」根因。链式应用：注释灰色尾缀 + 注释关键词检索。
     */
    public LookupElementBuilder withComment(LookupElementBuilder builder, String comment) {
        return comment != null ? builder.withTailText("  " + comment).withLookupString(comment) : builder;
    }

    /** SELECT 列位补充的聚合函数（M16）。 */
    private static final List<String> AGGREGATE_FUNCTIONS = List.of("COUNT", "SUM", "MAX", "MIN", "AVG");

    /**
     * 上下文读取（三级回退）：
     *  1. Editor userData（编辑器实例未变时最快）
     *  2. PSI 文件 userData（编辑器重建但 PSI 未变时）
     *  3. Document → Context 映射（PSI 文件 commitDocument 重建后最终兜底）
     *
     * 抽为 internal 纯函数供测试。
     */
    public SqlCompletionContext resolveContext(SqlCompletionContext editorContext, PsiElement position) {
        if (editorContext != null) {
            LOG.info("[SqlCompletion] resolveContext: hit level 1 (editor userData), table=" + editorContext.getTableName());
            return editorContext;
        }
        PsiFile cf = position.getContainingFile();
        SqlCompletionContext ctxPsi = cf != null ? cf.getUserData(SqlCompletionContext.KEY) : null;
        if (ctxPsi != null) {
            LOG.info("[SqlCompletion] resolveContext: hit level 2 (PSI userData), table=" + ctxPsi.getTableName());
            return ctxPsi;
        }
        // 最终兜底：通过 Document 映射查找
        Document doc = cf != null && cf.getViewProvider() != null ? cf.getViewProvider().getDocument() : null;
        if (doc == null) {
            LOG.info("[SqlCompletion] resolveContext: miss (doc is null)");
            return null;
        }
        SqlCompletionContext ctx = documentContexts.get(doc);
        LOG.info("[SqlCompletion] resolveContext: hit level 3 (document map)=" + (ctx != null) + ", table=" + (ctx != null ? ctx.getTableName() : null));
        return ctx;
    }
}
