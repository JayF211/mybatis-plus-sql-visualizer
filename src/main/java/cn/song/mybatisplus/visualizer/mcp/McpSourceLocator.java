// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSourceLocator.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator;
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import com.intellij.mcpserver.McpExpectedError;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import java.util.List;
import java.util.Locale;

/**
 * T-McpToolset mpsv_render_sql 定位与渲染适配层。
 *
 * 复刻 {@code cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction} 光标分支的服务层等价物：
 * 「文件 + 行号」→ PSI 锚点 → 13 类触发面定位链（Wrapper 链 / MyBatis 注解 / mapper XML /
 * HQL / 自定义 DAO HQL / JdbcTemplate / @Query / 派生查询 / CriteriaQuery / 方法体字符串 SQL）。
 * 与 Action 的差异（MCP 约束）：
 * <ul>
 *  <li>全程 {@link ReadAction}（MCP 调用在平台协程线程，读 PSI 必须持读锁）；</li>
 *  <li>零弹窗：多候选不弹选择框，取首个候选并在结果中说明（方法体多 SQL / 文件级注解候选
 *    场景由 agent 换行号重试）；</li>
 *  <li>Repository 调用点回落（Action 的 tryRepositoryCallSiteFallback）与文件级注解候选弹窗
 *    一期不带，命中失败即降级文案（写进实施记录）。</li>
 * </ul>
 */
public final class McpSourceLocator {

    private McpSourceLocator() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpSourceLocator INSTANCE = new McpSourceLocator();

    /** 单次渲染结果（SQL 缺失时 error 非空——适配层转 McpExpectedError）。 */
    public record Outcome(
        /** 渲染出的可执行 SQL（失败时 null）。 */
        String sql,
        /** 触发面类型（agent 面展示用）。 */
        String sourceType,
        /** 目标表名（可得时非空）。 */
        String tableName,
        /** 渲染警示（如占位符未回填）。 */
        String warning,
        /** 参数对照表文本。 */
        String paramTable,
        /** 降级说明（多候选取首个等；无则 null）。 */
        String degraded,
        /** 结构化错误文本（成功时 null）。 */
        String error
    ) {
        /** Kotlin 默认参数等价重载：degraded/error 缺省 null。 */
        public Outcome(String sql, String sourceType, String tableName, String warning, String paramTable) {
            this(sql, sourceType, tableName, warning, paramTable, null, null);
        }
    }

    private static Outcome fail(String code, String message) {
        return new Outcome(
            null, null, null, null, null,
            null,
            McpToolErrors.code(code, message)
        );
    }

    /** 「项目相对文件路径 + 行号」渲染（内部 ReadAction；行号 1-based，null = 首个可预览语句）。 */
    public static Outcome renderAtLine(Project project, String filePath, Integer line) {
        return ReadAction.compute(() -> {
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(normalizeAbsolute(project, filePath));
            if (vFile == null) {
                return fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "file not found in project: " + filePath + " (use a project-relative path)."
                );
            }
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
            if (psiFile == null) {
                return fail(McpToolErrors.SOURCE_NOT_FOUND, "cannot parse file: " + filePath);
            }
            var document = psiFile.getViewProvider().getDocument();
            int offset;
            if (document == null) {
                offset = 0;
            } else if (line == null) {
                offset = 0;
            } else {
                int lastLine = document.getLineNumber(document.getTextLength());
                if (line - 1 > lastLine) {
                    return fail(
                        McpToolErrors.SOURCE_NOT_FOUND,
                        "line " + line + " is beyond the end of " + filePath + " (" + (lastLine + 1) + " lines)."
                    );
                }
                offset = document.getLineStartOffset(Math.max(line - 1, 0));
            }
            return renderAtOffset(project, psiFile, offset, filePath, line);
        });
    }

    /** mapper XML 片段直渲（XmlPreviewService 为 text 形态入口，零 PSI 锚点需求）。 */
    public static Outcome renderXmlSnippet(Project project, String snippet) {
        PreviewResult result = XmlPreviewService.INSTANCE.preview(snippet, null, project);
        return toOutcome(result, "mapper XML (snippet)", null);
    }

    /** 统一入口：在 psiFile 的 offset 处按 13 类触发面定位渲染。 */
    private static Outcome renderAtOffset(
        Project project,
        PsiFile psiFile,
        int offset,
        String filePath,
        Integer line
    ) {
        String location = filePath + (line != null ? ":" + line : "");
        PsiElement element = psiFile.findElementAt(Math.max(Math.min(offset, psiFile.getTextLength() - 1), 0));
        if (element == null) {
            return fail(McpToolErrors.SOURCE_NOT_FOUND, "no code element at the given location (" + location + ").");
        }

        // mapper XML：光标所在语句 tag → 语句渲染（同 ConvertToSqlAction 分发语义）
        if (psiFile instanceof XmlFile xmlFile) {
            if (xmlFile.getRootTag() != null && "pages".equals(xmlFile.getRootTag().getName())) {
                return fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "<pages> XML preview is not exposed via MCP; open it in the IDE."
                );
            }
            XmlTag tag = PsiTreeUtil.getParentOfType(element, XmlTag.class);
            while (tag != null && !XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(tag.getName())) {
                tag = tag.getParentTag();
            }
            if (tag == null) {
                return fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "no mapper statement (select/insert/update/delete) at the given location (" + location + ")."
                );
            }
            String statementId = tag.getAttributeValue("id");
            // T-M27.3/4 实体上下文注入（ConvertToSqlAction.previewXml 同链）：SourceSqlContext →
            // SqlCompletionContext（namespace → Mapper 接口 → 主实体 + @TableName 联表候选）
            SourceSqlContext src = null;
            try {
                src = XmlSqlCompletionContextResolver.INSTANCE.resolveXml(project, tag);
            } catch (Throwable ignored) {
                // 解析失败降级为无实体上下文（runCatching 语义）
            }
            SqlCompletionContext entity = null;
            if (src != null && src.getMain() != null) {
                SqlCompletionContext m = src.getMain();
                // 直读公有字段（SqlCompletionContext 为公有 final 字段值对象，无 getter；master Kotlin 同形）
                entity = new SqlCompletionContext(m.tableName, m.columns, m.tableAlias,
                    m.columnComments, m.tableComment, src.getCandidates());
            }
            PreviewResult result = XmlPreviewService.INSTANCE.preview(psiFile.getText(), statementId, project, entity);
            return toOutcome(result, "mapper XML" + (statementId != null ? " (" + statementId + ")" : ""), null);
        }

        // MyBatis SQL 注解（@Select/@Update/Provider 族）——先于 Wrapper 链（注解内光标无链可定位）
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class, false);
        if (annotation != null && AnnotationSqlPreviewService.INSTANCE.isSqlAnnotation(annotation)) {
            return toOutcome(
                AnnotationSqlPreviewService.INSTANCE.preview(annotation, project),
                "annotation SQL",
                null
            );
        }

        SqlPreviewService service = project.getService(SqlPreviewService.class);
        PreviewResult wrapperPreview = service.preview(element);
        if (wrapperPreview != null) {
            return toOutcome(wrapperPreview, sourceTypeOf(wrapperPreview), null);
        }

        // HQL / 自定义 DAO HQL（同 Action 的 tryHqlFallback 顺序）
        if (HqlQueryLocator.locate(element) != null) {
            return toOutcome(service.previewHql(element), "HQL", null);
        }
        if (CustomDaoHqlLocator.locate(element) != null) {
            return toOutcome(service.previewCustomDaoHql(element), "custom DAO HQL", null);
        }

        // JdbcTemplate 字符串 SQL
        if (JdbcTemplateSqlLocator.locate(element) != null) {
            return toOutcome(JdbcTemplateSqlPreviewService.preview(element, project), "JdbcTemplate", null);
        }

        // JPA：@Query → 派生查询 → CriteriaQuery（同 Action 优先序）
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        if (method != null && JpqlQueryLocator.hasQueryAnnotation(method)) {
            return toOutcome(service.previewJpql(method), "JPA @Query", null);
        }
        if (method != null && DerivedMethodLocator.isDerivedQuery(method)) {
            return toOutcome(service.previewDerivedQuery(method), "JPA derived query", null);
        }
        if (CriteriaQueryLocator.locate(element) != null) {
            return toOutcome(service.previewCriteriaQuery(element), "CriteriaQuery", null);
        }

        // 方法体字符串 SQL 多候选：零弹窗取首个（Action 弹选择框；agent 可换行号取其他候选）
        if (method != null && method.getBody() != null) {
            List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall> candidates =
                JdbcTemplateSqlPreviewService.collectMethodCandidates(method);
            if (!candidates.isEmpty()) {
                PreviewResult result = JdbcTemplateSqlPreviewService.preview(candidates.get(0), method);
                String degraded = null;
                if (candidates.size() > 1) {
                    degraded = "method body has " + candidates.size() + " string-SQL candidates; rendering the first one — "
                        + "pass a line number inside another call to pick it.";
                }
                return toOutcome(result, "string SQL in method body", degraded);
            }
        }

        // 兜底线索：该行是不是 Wrapper 链候选（给 agent 一个可操作的提示）
        boolean chainHint = !WrapperLocator.sqlCandidatesInRange(psiFile, offset, offset).isEmpty();
        return fail(
            McpToolErrors.SOURCE_NOT_FOUND,
            "no renderable SQL source at " + location + "." + (chainHint
                ? " A Wrapper chain was detected nearby; retry with the exact line of the chain head call."
                : " Supported: MyBatis-Plus Wrapper chains, mapper XML statements, MyBatis annotation SQL, "
                    + "JPA (@Query/derived/CriteriaQuery), HQL, JdbcTemplate string SQL.")
        );
    }

    /** Wrapper 预览的触发面描述（wrapperType 小写 + 下划线转空格；无类型回落 Wrapper chain）。 */
    private static String sourceTypeOf(PreviewResult result) {
        if (result.getWrapperType() != null) {
            return result.getWrapperType().name().toLowerCase(Locale.ROOT).replace("_", " ");
        }
        return "Wrapper chain";
    }

    /** PsiElement 锚点直渲（Wrapper 链头已定位场景——供 toolset 内部复用）。 */
    public static Outcome previewAnchor(Project project, PsiElement anchor) {
        SqlPreviewService service = project.getService(SqlPreviewService.class);
        PreviewResult result = service.preview(anchor);
        if (result == null) {
            return fail(
                McpToolErrors.SOURCE_NOT_FOUND,
                "the given location does not resolve to a renderable SQL source."
            );
        }
        return toOutcome(result, sourceTypeOf(result), null);
    }

    private static Outcome toOutcome(PreviewResult result, String sourceType, String degraded) {
        if (result == null) {
            return fail(
                McpToolErrors.SOURCE_NOT_FOUND,
                "the source at the given location resolved to no SQL (unsupported shape)."
            );
        }
        return new Outcome(
            result.getSql(),
            sourceType,
            result.getTableName(),
            result.getWarning(),
            result.getParamTable(),
            degraded,
            null
        );
    }

    private static String normalizeAbsolute(Project project, String relative) {
        String normalized = McpRenderInput.normalizeFilePath(relative);
        if (normalized == null) {
            // McpExpectedError 第二参（mcpErrorStructureContent）在平台 Kotlin 声明中带默认值 null，
            // Java 无默认参数、只能走全参构造器 → 显式补 null（与桩签名/平台擦除签名一致）。
            // 经 sneakyThrow 逃逸：本方法被工具方法（override 平台接口）调用，不能声明受检异常。
            throw McpToolsetSupport.<RuntimeException>sneakyThrow(new McpExpectedError(
                McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "invalid file path: " + relative),
                null
            ));
        }
        String base = project.getBasePath();
        if (base != null) {
            base = stripTrailing(base, '/', '\\');
            return base + "/" + normalized;
        }
        return normalized;
    }

    /** Kotlin trimEnd(vararg chars) 等价：剥除尾部指定字符。 */
    private static String stripTrailing(String value, char... chars) {
        int end = value.length();
        outer:
        while (end > 0) {
            char c = value.charAt(end - 1);
            for (char candidate : chars) {
                if (c == candidate) {
                    end--;
                    continue outer;
                }
            }
            break;
        }
        return value.substring(0, end);
    }
}
