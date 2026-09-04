package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator;
import com.intellij.psi.PsiClass;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator;
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.PagesXmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import cn.song.mybatisplus.visualizer.ui.gutter.PagesXmlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 转换 Action（M3-02，F-04 右键菜单 + F-05 快捷键）。
 *
 * 触发方式（plugin.xml 注册）：
 *  - 编辑器右键菜单：EditorPopupMenu → "MP-SQL-Preview → Convert to SQL"
 *  - 快捷键：默认 Ctrl+Alt+S（Windows/Linux）/ Cmd+Option+S（macOS）
 *
 * 行为（T-M23.7）：
 *  - 选择代码块 → 扫描选择区内全部 Wrapper 链首：0 个 → 未找到提示；1 个 → 直接预览；
 *    多个 → SqlSelectionDialog 选择后预览；
 *  - 光标处 → 取光标 PSI 元素 → SqlPreviewService.preview（内部定位增强）。
 * 未找到 Wrapper 时给出友好提示，不抛异常。
 */
public final class ConvertToSqlAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (file == null) return;
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) return;

        // F-46：当前代码位置（文件名:行号），来源列默认值（XML/Java 共用）
        String sourceLocation = file.getName() + ":" + (editor.getDocument().getLineNumber(editor.getCaretModel().getOffset()) + 1);

        // T-M24-04：XML mapper 文件 → 预览光标所在语句；F-45：<pages> XML 文件 → maintainPage 预览
        if (file instanceof XmlFile) {
            XmlFile xmlFile = (XmlFile) file;
            XmlTag rootTag = xmlFile.getRootTag();
            if (rootTag != null && "pages".equals(rootTag.getName())) {
                SelectionModelOrSelection sm = new SelectionModelOrSelection(editor);
                if (sm.hasSelection) {
                    previewPagesSelection(project, xmlFile, editor, sm.selectionStart, sm.selectionEnd);
                } else {
                    previewPagesStatement(project, xmlFile, editor);
                }
                return;
            }
            SelectionModelOrSelection sm = new SelectionModelOrSelection(editor);
            if (sm.hasSelection) {
                previewXmlSelection(project, xmlFile, editor, sm.selectionStart, sm.selectionEnd, sourceLocation);
            } else {
                previewXmlStatement(project, xmlFile, editor, sourceLocation);
            }
            return;
        }

        SqlPreviewService service = project.getService(SqlPreviewService.class);

        // 分支 A（T-M23.7）：选择代码块 → 扫描选择区内 SQL 候选（wrapper 链首 + 批量方法），0/1/多分发
        SelectionModelOrSelection sm = new SelectionModelOrSelection(editor);
        if (sm.hasSelection) {
            final int selStart = sm.selectionStart;
            final int selEnd = sm.selectionEnd;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                Pair<List<PsiMethodCallExpression>, List<String>> payload;
                try {
                    payload = ReadAction.compute(() -> {
                        List<PsiMethodCallExpression> heads =
                                WrapperLocator.sqlCandidatesInRange(file, selStart, selEnd);
                        List<String> texts = heads.stream().map(head ->
                                SqlSelectionDialog.displayText(
                                        WrapperLocator.sqlCandidateSummary(head),
                                        WrapperLocator.sqlCandidateLineText(head))
                        ).collect(Collectors.toList());
                        return Pair.create(heads, texts);
                    });
                } catch (Throwable t) {
                    payload = Pair.create(Collections.emptyList(), Collections.emptyList());
                }
                List<PsiMethodCallExpression> chainHeads = payload.getFirst();
                List<String> displayTexts = payload.getSecond();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (chainHeads.isEmpty()) {
                        // T-M29.6：选择区内无 Wrapper 候选 → 回落选择区内 SQL 注解候选
                        showAnnotationSelectionCandidates(project, file, editor, selStart, selEnd, sourceLocation);
                    } else if (chainHeads.size() == 1) {
                        preview(service, chainHeads.get(0), project, sourceLocation);
                    } else {
                        new SqlSelectionDialog<>(project, chainHeads, displayTexts, (PsiMethodCallExpression head) -> {
                            // 选中确定 → 编辑器定位（光标到行首 + 滚动居中 + 整行选中）→ 预览 SQL
                            locateEditorTo(editor, head);
                            preview(service, head, project, sourceLocation);
                        }).show();
                    }
                });
            });
            return;
        }

        // 分支 B（现状）：光标处定位（内部增强：eq/list 调用处同样可触发）
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        if (element == null) return;

        // T-M25-03：光标在 MyBatis SQL 注解内 → 注解预览
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class, false);
        if (annotation != null && AnnotationSqlPreviewService.INSTANCE.isSqlAnnotation(annotation)) {
            SqlPreviewService.previewAsync(project,
                    () -> AnnotationSqlPreviewService.INSTANCE.preview(annotation, project),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return;
        }

        // PSI 转换在后台线程执行（EDT 上慢操作会被 2025.3+ 禁止），回调在 EDT 渲染
        SqlPreviewService.previewAsync(project,
                () -> service.preview(element),
                (PreviewResult result) -> {
                    if (result != null) {
                        SqlPreviewService.showResult(project, result, sourceLocation);
                    } else if (!tryHqlFallback(service, project, element, sourceLocation)
                            && !tryNamedQueryFallback(service, project, element, sourceLocation)
                            && !tryJdbcTemplateFallback(project, element, sourceLocation)
                            && !tryJpaFallback(service, project, element, sourceLocation)) {
                        // 均未命中 → 回落文件级 SQL 注解候选
                        showAnnotationCandidates(project, file, editor, sourceLocation);
                    }
                    return Unit.INSTANCE;
                });
    }

    /** 小工具：抽取编辑器选区状态。 */
    private static final class SelectionModelOrSelection {
        boolean hasSelection;
        int selectionStart;
        int selectionEnd;
        SelectionModelOrSelection(Editor editor) {
            com.intellij.openapi.editor.SelectionModel m = editor.getSelectionModel();
            hasSelection = m.hasSelection();
            selectionStart = m.getSelectionStart();
            selectionEnd = m.getSelectionEnd();
        }
    }

    /**
     * HQL 兜底（T-PreviewShortcut）：标准 HQL/JPQL 运行时字符串调用 + 自定义 DAO HQL。
     * 两定位器 locate(任意 PSI 元素) 均可从光标元素定位到宿主方法体内匹配调用，命中任一即触发预览并返回 true。
     * 与 CriteriaQuery（首参 Class/CriteriaQuery 变量）无冲突——hasStringHqlArgument 已排除。
     */
    private boolean tryHqlFallback(SqlPreviewService service, Project project, PsiElement element, String sourceLocation) {
        // 1. 标准 HQL/JPQL：session/em.createQuery / createNativeQuery / createNamedQuery（字符串首参）
        if (HqlQueryLocator.INSTANCE.locate(element) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewHql(element),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        // 2. 自定义 DAO HQL：commonDao.findByQuery(...) / findByQueryUniqueResult(...) 等
        if (CustomDaoHqlLocator.INSTANCE.locate(element) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewCustomDaoHql(element),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        return false;
    }

    /**
     * @NamedQuery 实体级兜底（T-PreviewShortcut）：光标落在实体类 @NamedQuery(...) 注解内时，
     * 反查实体类 → previewNamedQuery 转换对应命名查询的 JPQL。
     */
    private boolean tryNamedQueryFallback(SqlPreviewService service, Project project, PsiElement element, String sourceLocation) {
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class, false);
        if (annotation == null) return false;
        String qualified = annotation.getQualifiedName();
        String simpleName = qualified != null
                ? qualified.substring(qualified.lastIndexOf('.') + 1)
                : (annotation.getNameReferenceElement() != null
                        ? annotation.getNameReferenceElement().getReferenceName() : null);
        if (!"NamedQuery".equals(simpleName)) return false;
        PsiClass cls = PsiTreeUtil.getParentOfType(annotation, PsiClass.class, false);
        if (cls == null) return false;
        boolean match = false;
        for (NamedQueryInfo info : NamedQueryLocator.INSTANCE.locate(cls)) {
            if (info.getAnnotation() == annotation) {
                match = true;
                break;
            }
        }
        if (!match) return false;
        SqlPreviewService.previewAsync(project,
                () -> service.previewNamedQuery(annotation),
                (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
        return true;
    }

    /**
     * JDBC 模板兜底（T-PreviewShortcut）：jdbcTemplate.queryForList / update 等 →
     * JdbcTemplateSqlPreviewService.preview（支持命名参数 / 字符串拼接）。
     */
    private boolean tryJdbcTemplateFallback(Project project, PsiElement element, String sourceLocation) {
        if (JdbcTemplateSqlLocator.INSTANCE.locate(element) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> JdbcTemplateSqlPreviewService.preview(element, project),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        return false;
    }

    /**
     * JPA 三态回落：派生查询 / @Query / CriteriaQuery —— 命中任一即触发预览并返回 true。
     */
    private boolean tryJpaFallback(SqlPreviewService service, Project project, PsiElement element, String sourceLocation) {
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        // 1. JPA 回落：@Query
        if (method != null && JpqlQueryLocator.INSTANCE.hasQueryAnnotation(method)) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewJpql(method),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        // 2. JPA 回落：派生查询
        if (method != null && DerivedMethodLocator.INSTANCE.isDerivedQuery(method)) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewDerivedQuery(method),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        // 3. JPA 回落：CriteriaQuery
        if (CriteriaQueryLocator.INSTANCE.locate(element) != null) {
            SqlPreviewService.previewAsync(project,
                    () -> service.previewCriteriaQuery(element),
                    (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
            return true;
        }
        return false;
    }

    /** M26-A：文件级 SQL 注解候选——收集文件内全部 @Select/@Update 等注解（0/1/多分发）。 */
    private void showAnnotationCandidates(Project project, PsiFile file, Editor editor, String sourceLocation) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<PsiAnnotation>, List<String>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    List<PsiAnnotation> annotations = AnnotationSqlPreviewService.INSTANCE.annotationCandidates(file);
                    List<String> texts = annotations.stream().map(anno -> {
                        PsiMethod m = PsiTreeUtil.getParentOfType(anno, PsiMethod.class);
                        String qn = anno.getQualifiedName();
                        String shortName = qn != null ? qn.substring(qn.lastIndexOf('.') + 1) : "";
                        return SqlSelectionDialog.displayText(
                                (m != null ? m.getName() : "?") + "（" + shortName + "）",
                                AnnotationSqlPreviewService.INSTANCE.sqlSnippet(anno, 80));
                    }).collect(Collectors.toList());
                    return Pair.create(annotations, texts);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            List<PsiAnnotation> annotations = payload.getFirst();
            List<String> displayTexts = payload.getSecond();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (annotations.isEmpty()) {
                    SqlPreviewService.showResult(project, null, sourceLocation);
                } else if (annotations.size() == 1) {
                    previewAnnotation(project, annotations.get(0), sourceLocation);
                } else {
                    new SqlSelectionDialog<>(project, annotations, displayTexts, (PsiAnnotation selected) -> {
                        locateEditorTo(editor, selected);
                        previewAnnotation(project, selected, sourceLocation);
                    }).show();
                }
            });
        });
    }

    /** T-M29.6：选择区内 SQL 注解候选回落。 */
    private void showAnnotationSelectionCandidates(
            Project project, PsiFile file, Editor editor, int selStart, int selEnd, String sourceLocation) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<PsiAnnotation>, List<String>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    List<PsiAnnotation> annotations =
                            AnnotationSqlPreviewService.INSTANCE.annotationCandidatesInRange(file, selStart, selEnd);
                    List<String> texts = annotations.stream().map(anno -> {
                        PsiMethod m = PsiTreeUtil.getParentOfType(anno, PsiMethod.class);
                        String qn = anno.getQualifiedName();
                        String shortName = qn != null ? qn.substring(qn.lastIndexOf('.') + 1) : "";
                        return SqlSelectionDialog.displayText(
                                (m != null ? m.getName() : "?") + "（" + shortName + "）",
                                AnnotationSqlPreviewService.INSTANCE.sqlSnippet(anno, 80));
                    }).collect(Collectors.toList());
                    return Pair.create(annotations, texts);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            List<PsiAnnotation> annotations = payload.getFirst();
            List<String> displayTexts = payload.getSecond();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (annotations.isEmpty()) {
                    SqlPreviewService.showResult(project, null, sourceLocation);
                } else if (annotations.size() == 1) {
                    previewAnnotation(project, annotations.get(0), sourceLocation);
                } else {
                    new SqlSelectionDialog<>(project, annotations, displayTexts, (PsiAnnotation selected) -> {
                        locateEditorTo(editor, selected);
                        previewAnnotation(project, selected, sourceLocation);
                    }).show();
                }
            });
        });
    }

    /** 预览单个 SQL 注解（后台转换，EDT 渲染）。 */
    private void previewAnnotation(Project project, PsiAnnotation annotation, String sourceLocation) {
        SqlPreviewService.previewAsync(project,
                () -> AnnotationSqlPreviewService.INSTANCE.preview(annotation, project),
                (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
    }

    /** T-M29.6：XML 选择区语句候选。 */
    private void previewXmlSelection(Project project, XmlFile file, Editor editor, int selStart, int selEnd, String sourceLocation) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<XmlTag>, List<String>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    List<XmlTag> tags = XmlSqlGutterLineMarkerProvider.statementsInRange(file, selStart, selEnd);
                    List<String> texts = tags.stream().map(t -> {
                        int line = lineOf(t);
                        String text = t.getText() != null ? t.getText().trim() : "";
                        String id = t.getAttributeValue("id");
                        return SqlSelectionDialog.displayText(
                                t.getName() + " " + (id != null ? id : "") + "（第 " + line + " 行）", text);
                    }).collect(Collectors.toList());
                    return Pair.create(tags, texts);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            List<XmlTag> tags = payload.getFirst();
            List<String> displayTexts = payload.getSecond();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (tags.isEmpty()) {
                    SqlPreviewService.showResult(project, null, sourceLocation);
                } else if (tags.size() == 1) {
                    previewXml(project, file, tags.get(0), sourceLocation);
                } else {
                    new SqlSelectionDialog<>(project, tags, displayTexts, (XmlTag selected) -> {
                        locateEditorTo(editor, selected);
                        previewXml(project, file, selected, sourceLocation);
                    }).show();
                }
            });
        });
    }

    /** XML mapper 语句预览——光标所在语句优先；不在语句内时收集全部语句（0/1/多分发）。 */
    private void previewXmlStatement(Project project, XmlFile file, Editor editor, String sourceLocation) {
        PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        XmlTag tag = element != null ? PsiTreeUtil.getParentOfType(element, XmlTag.class) : null;
        XmlTag statement = tag;
        while (statement != null && !XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(statement.getName())) {
            statement = statement.getParentTag();
        }
        if (statement != null) {
            previewXml(project, file, statement, sourceLocation);
            return;
        }
        // 光标不在语句内 → 全部语句候选
        List<XmlTag> statements = PsiTreeUtil.findChildrenOfType(file, XmlTag.class).stream()
                .filter(it -> XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(it.getName()))
                .collect(Collectors.toList());
        if (statements.isEmpty()) {
            SqlPreviewService.showResult(project, null, sourceLocation);
        } else if (statements.size() == 1) {
            previewXml(project, file, statements.get(0), sourceLocation);
        } else {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                final List<String>[] displayTextsHolder = new List[1];
                try {
                    displayTextsHolder[0] = ReadAction.compute(() -> statements.stream().map(t -> {
                        int line = lineOf(t);
                        String text = t.getText() != null ? t.getText().trim() : "";
                        String id = t.getAttributeValue("id");
                        return SqlSelectionDialog.displayText(
                                t.getName() + " " + (id != null ? id : "") + "（第 " + line + " 行）", text);
                    }).collect(Collectors.toList()));
                } catch (Throwable t) {
                    displayTextsHolder[0] = Collections.emptyList();
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (displayTextsHolder[0].size() != statements.size()) {
                        SqlPreviewService.showResult(project, null, sourceLocation);
                        return;
                    }
                    new SqlSelectionDialog<>(project, statements, displayTextsHolder[0], (XmlTag selected) -> {
                        locateEditorTo(editor, selected);
                        previewXml(project, file, selected, sourceLocation);
                    }).show();
                });
            });
        }
    }

    /** 预览单个 XML 语句（注入实体上下文）。 */
    private void previewXml(Project project, XmlFile file, XmlTag tag, String sourceLocation) {
        SqlPreviewService.previewAsync(project, () -> {
            SourceSqlContext src = XmlSqlCompletionContextResolver.INSTANCE.resolveXml(project, tag);
            SqlCompletionContext entity = null;
            if (src.getMain() != null) {
                SqlCompletionContext m = src.getMain();
                entity = new SqlCompletionContext(
                        m.tableName, m.columns, m.tableAlias,
                        m.columnComments, m.tableComment, src.getCandidates());
            }
            return XmlPreviewService.INSTANCE.preview(file.getText(), tag.getAttributeValue("id"), project, entity);
        }, (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
    }

    /** F-45：maintainPage 语句预览。 */
    private void previewPagesStatement(Project project, XmlFile file, Editor editor) {
        PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        XmlTag tag = element != null ? PsiTreeUtil.getParentOfType(element, XmlTag.class) : null;
        XmlTag statement = tag;
        while (statement != null && !"maintainPage".equals(statement.getName())) {
            statement = statement.getParentTag();
        }
        if (statement != null) {
            previewPages(project, file, statement);
            return;
        }
        // 光标不在 maintainPage 内 → 全部候选
        List<XmlTag> statements = PsiTreeUtil.findChildrenOfType(file, XmlTag.class).stream()
                .filter(it -> "maintainPage".equals(it.getName()))
                .collect(Collectors.toList());
        if (statements.isEmpty()) {
            SqlPreviewService.showResult(project, null, null);
        } else if (statements.size() == 1) {
            previewPages(project, file, statements.get(0));
        } else {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                final List<String>[] displayTextsHolder = new List[1];
                try {
                    displayTextsHolder[0] = ReadAction.compute(() -> statements.stream().map(t -> {
                        int line = lineOf(t);
                        String text = t.getText() != null ? t.getText().trim() : "";
                        String id = t.getAttributeValue("id");
                        return SqlSelectionDialog.displayText(
                                (id != null ? id : "") + "（第 " + line + " 行）", text);
                    }).collect(Collectors.toList()));
                } catch (Throwable t) {
                    displayTextsHolder[0] = Collections.emptyList();
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (displayTextsHolder[0].size() != statements.size()) {
                        SqlPreviewService.showResult(project, null, null);
                        return;
                    }
                    new SqlSelectionDialog<>(project, statements, displayTextsHolder[0], (XmlTag selected) -> {
                        locateEditorTo(editor, selected);
                        previewPages(project, file, selected);
                    }).show();
                });
            });
        }
    }

    /** F-45：选择区内 maintainPage 候选。 */
    private void previewPagesSelection(Project project, XmlFile file, Editor editor, int selStart, int selEnd) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Pair<List<XmlTag>, List<String>> payload;
            try {
                payload = ReadAction.compute(() -> {
                    List<XmlTag> tags = PagesXmlGutterLineMarkerProvider.statementsInRange(file, selStart, selEnd);
                    List<String> texts = tags.stream().map(t -> {
                        int line = lineOf(t);
                        String text = t.getText() != null ? t.getText().trim() : "";
                        String id = t.getAttributeValue("id");
                        return SqlSelectionDialog.displayText(
                                (id != null ? id : "") + "（第 " + line + " 行）", text);
                    }).collect(Collectors.toList());
                    return Pair.create(tags, texts);
                });
            } catch (Throwable t) {
                payload = Pair.create(Collections.emptyList(), Collections.emptyList());
            }
            List<XmlTag> tags = payload.getFirst();
            List<String> displayTexts = payload.getSecond();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (tags.isEmpty()) {
                    SqlPreviewService.showResult(project, null, null);
                } else if (tags.size() == 1) {
                    previewPages(project, file, tags.get(0));
                } else {
                    new SqlSelectionDialog<>(project, tags, displayTexts, (XmlTag selected) -> {
                        locateEditorTo(editor, selected);
                        previewPages(project, file, selected);
                    }).show();
                }
            });
        });
    }

    /** 预览单个 maintainPage。 */
    private void previewPages(Project project, XmlFile file, XmlTag tag) {
        SqlPreviewService.previewAsync(project,
                () -> PagesXmlPreviewService.INSTANCE.preview(file.getText(), tag.getAttributeValue("id"), project),
                (PreviewResult result) -> { SqlPreviewService.showResult(project, result, null); return Unit.INSTANCE; });
    }

    /** 预览单个链首调用（后台转换，EDT 渲染）。 */
    private void preview(SqlPreviewService service, PsiElement anchor, Project project, String sourceLocation) {
        SqlPreviewService.previewAsync(project,
                () -> service.preview(anchor),
                (PreviewResult result) -> { SqlPreviewService.showResult(project, result, sourceLocation); return Unit.INSTANCE; });
    }

    /** T-M23.8：编辑器定位跳转到候选元素所在行。 */
    private void locateEditorTo(Editor editor, PsiElement element) {
        try {
            kotlin.Pair<Integer, Integer> off = WrapperLocator.lineOffsetsOf(element);
            int start = off.getFirst();
            int end = off.getSecond();
            editor.getCaretModel().moveToOffset(start);
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER_UP);
            editor.getSelectionModel().setSelection(start, end);
        } catch (Throwable ignored) {
            // 失败兜底（不中断预览）
        }
    }

    /** 取 XML 标签所在行号（1 起）。 */
    private static int lineOf(XmlTag tag) {
        try {
            com.intellij.openapi.editor.Document document = tag.getContainingFile().getViewProvider().getDocument();
            if (document != null) {
                return document.getLineNumber(tag.getTextRange().getStartOffset()) + 1;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0;
    }

    // update() 读取 PSI，必须在后台线程执行
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(file instanceof PsiJavaFile || file instanceof XmlFile);
    }

    /** 插件动作 ID（plugin.xml 中注册）。 */
    public static final String ACTION_ID = "cn.song.mybatisplus.visualizer.ConvertToSql";
}
