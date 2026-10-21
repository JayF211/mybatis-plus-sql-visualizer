// 自 master ui/annotate/PreviewSchemaHighlighter.kt 移植（UI025：配置目录 .idea 兜底的编辑器级标注通道）
package cn.song.mybatisplus.visualizer.ui.annotate;

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.SchemaValidationGate;
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.codeInsight.hint.TooltipController;
import com.intellij.codeInsight.hint.TooltipGroup;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.JBColor;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 预览编辑器错列的<b>编辑器级标注通道</b>（UI025，配置目录 {@code .idea} 兜底）。
 *
 * <h2>为什么需要第二条通道</h2>
 *
 * <p>平台不把项目配置目录（{@code .idea}）里的文件交给 daemon：测试实证同一份 SQL 放在
 * {@code sql-visualizer/exec/mpsql-exec.sql} 下 {@code ExternalAnnotator} 立即触发，放到
 * {@code .idea/sql-visualizer/exec/mpsql-exec.sql} 下永不触发（{@code inContent=true / excluded=false /
 * inSource=true} 均满足，仍被跳过）。本插件的预览文件与原生控制台 exec 文件恰好都在
 * {@code .idea/sql-visualizer/} 下——只靠 {@link PreviewUnknownColumnAnnotator} 在真机上不会出任何波浪线。</p>
 *
 * <p>本对象因此直接操作编辑器的 {@code MarkupModel}：</p>
 * <ul>
 *  <li>红色波浪线：{@code TextAttributes.effectType = WAVE_UNDERSCORE}、{@code effectColor = JBColor.RED}，
 *      图层 {@code HighlighterLayer.ERROR + 1}（压在语法高亮之上）；</li>
 *  <li>悬停信息：鼠标移到标注区间时经 {@code TooltipController} 弹出与 ExternalAnnotator 同款文案；</li>
 *  <li>右侧错误条：{@code errorStripeMarkColor} + {@code errorStripeTooltip}（悬停右侧条同样可见）。</li>
 * </ul>
 *
 * <h2>数据与作用域（与 ExternalAnnotator 完全同源）</h2>
 *
 * <p>只对 {@link PreviewSchemaAnnotationScope#isPreviewSurface} 且位于 {@code .idea} 下的文件生效；目标优先取
 * {@link DataSourceSelector#schemaValidationTarget}（已备控制台的执行文档身份），仅在其为 null 时回落
 * {@link PreviewFallbackTarget}（面板「数据源（上次使用）」记忆，UI029 日志条目预览场景）；列集仍只读
 * {@code SchemaValidationGate.check} 三参入口的已预热缓存，位点仍由
 * {@link PreviewUnknownColumnPlanner} 从 AST token 换算。无目标/缓存冷/作用域不可证 → 清空标注。</p>
 *
 * <h2>生命周期</h2>
 *
 * <p>面板装配编辑器时 {@link #install}；文档变更经 {@link Alarm} 防抖后重算并<b>先清旧标注再投新标注</b>
 * （编辑后不残留）。编辑器释放/面板销毁时 {@link #uninstall}。缓存预热完成后由面板调用 {@link #refresh}
 * 补一拍（{@code refreshCompletionContext}），保证「控制台就绪/缓存变热」后无需用户输入即可出现标注。</p>
 */
public final class PreviewSchemaHighlighter {

    /** 红色波浪线属性（前景/背景不改，只叠效果）。 */
    private static final TextAttributes ATTRS = createAttrs();

    private static final Map<Editor, Session> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PreviewSchemaHighlighter() {
    }

    @NotNull
    private static TextAttributes createAttrs() {
        TextAttributes attrs = new TextAttributes();
        attrs.setEffectType(EffectType.WAVE_UNDERSCORE);
        attrs.setEffectColor(JBColor.RED);
        attrs.setErrorStripeColor(JBColor.RED);
        return attrs;
    }

    /** 为编辑器安装标注通道（幂等；重复安装直接返回）。 */
    public static void install(@NotNull Editor editor) {
        synchronized (SESSIONS) {
            if (SESSIONS.containsKey(editor)) {
                return;
            }
            SESSIONS.put(editor, new Session(editor));
        }
    }

    /** 触发一次重算（缓存预热完成/控制台就绪后由面板调用）。 */
    public static void refresh(@Nullable Editor editor) {
        if (editor == null) {
            return;
        }
        Session session = SESSIONS.get(editor);
        if (session != null) {
            session.schedule(0);
        }
    }

    /** 编辑器释放/面板销毁时摘除监听与标注（幂等）。 */
    public static void uninstall(@Nullable Editor editor) {
        if (editor == null) {
            return;
        }
        Session session;
        synchronized (SESSIONS) {
            session = SESSIONS.remove(editor);
        }
        if (session != null) {
            session.dispose();
        }
    }

    /** 测试缝：同步触发一次重算（结果经 {@code invokeLater} 落盘，测试侧 dispatch 后断言）。 */
    public static void refreshNowForTest(@NotNull Editor editor) {
        Session session = SESSIONS.get(editor);
        if (session != null) {
            session.recomputeForTest();
        }
    }

    private static final class Session implements Disposable {

        private final Editor editor;
        private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
        private final List<RangeHighlighter> highlighters = new ArrayList<>();

        /** 代际：文档连续变更时旧一轮结果不得覆盖新一轮（池化线程与 EDT 并发读写）。 */
        private final AtomicInteger generation = new AtomicInteger();

        /** 当前悬停的标注起点（避免同位置反复弹提示）。 */
        private int hoverAnchor = -1;

        private final DocumentListener documentListener = new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                schedule(300);
            }
        };

        private final EditorMouseMotionListener mouseListener = new EditorMouseMotionListener() {
            @Override
            public void mouseMoved(@NotNull EditorMouseEvent event) {
                showHover(event);
            }
        };

        private Session(@NotNull Editor editor) {
            this.editor = editor;
            editor.getDocument().addDocumentListener(documentListener, this);
            editor.addEditorMouseMotionListener(mouseListener, this);
            // 首拍：控制台 preparedEditor 在本编辑器就绪回调返回后才赋值，故延迟一拍；
            // 1.5s 再补一拍，覆盖「目标/缓存晚到」的窗口（缓存预热另有 refresh 显式触发）。
            schedule(300);
            alarm.addRequest(() -> recompute(generation.incrementAndGet()), 1500L);
        }

        void schedule(long delayMs) {
            int token = generation.incrementAndGet();
            alarm.cancelAllRequests();
            alarm.addRequest(() -> recompute(token), delayMs);
        }

        /** 测试缝：在当前线程直接算一次（apply 仍经 invokeLater，保持 EDT 语义）。 */
        void recomputeForTest() {
            recompute(generation.incrementAndGet());
        }

        private void recompute(int token) {
            com.intellij.openapi.editor.Document document = editor.getDocument();
            String text = document.getText();
            com.intellij.openapi.project.Project project = editor.getProject();
            if (project == null || project.isDisposed() || editor.isDisposed()) {
                clear(token);
                return;
            }
            PsiFile psi = ReadAction.compute(
                    () -> PsiDocumentManager.getInstance(project).getPsiFile(document));
            // 只接配置目录（.idea）内的预览载体；其余文件由 ExternalAnnotator 通道负责，避免双标。
            if (psi == null || !PreviewSchemaAnnotationScope.isPreviewSurface(psi)
                    || !PreviewSchemaAnnotationScope.isProjectConfigSurface(psi)) {
                clear(token);
                return;
            }
            // 旧入口零变化：仅在其返回 null 时，才用面板自身已确定的目标（last-used 记忆）回落。
            SchemaValidationGate.Target legacyTarget =
                    quietly(() -> DataSourceSelector.schemaValidationTarget(document));
            SchemaValidationGate.Target target = legacyTarget != null
                    ? legacyTarget
                    : quietly(() -> PreviewFallbackTarget.resolve(project, text));
            List<PreviewUnknownColumnPlanner.Mark> marks;
            if (target == null) {
                marks = List.of();
            } else {
                SchemaValidationGate.Outcome outcome =
                        quietly(() -> SchemaValidationGate.check(project, text, target));
                List<SchemaValidator.Issue> issues = outcome instanceof SchemaValidationGate.Checked checked
                        ? checked.issues()
                        : List.of();
                marks = PreviewUnknownColumnPlanner.plan(text, issues);
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (token != generation.get()) {
                    return;
                }
                apply(marks);
            }, ModalityState.any());
        }

        private void clear(int token) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (token == generation.get()) {
                    apply(List.of());
                }
            }, ModalityState.any());
        }

        private void apply(@NotNull List<PreviewUnknownColumnPlanner.Mark> marks) {
            if (editor.isDisposed()) {
                return;
            }
            for (RangeHighlighter highlighter : highlighters) {
                try {
                    editor.getMarkupModel().removeHighlighter(highlighter);
                } catch (Throwable t) {
                    if (FatalErrors.isFatal(t)) {
                        throw (Error) t;
                    }
                }
            }
            highlighters.clear();
            int length = editor.getDocument().getTextLength();
            for (PreviewUnknownColumnPlanner.Mark mark : marks) {
                if (mark.startOffset() < 0 || mark.endOffset() > length
                        || mark.startOffset() >= mark.endOffset()) {
                    continue;
                }
                RangeHighlighter highlighter = editor.getMarkupModel().addRangeHighlighter(
                        mark.startOffset(),
                        mark.endOffset(),
                        HighlighterLayer.ERROR + 1,
                        ATTRS,
                        HighlighterTargetArea.EXACT_RANGE);
                String message = PreviewSchemaHighlighterKt.previewUnknownColumnMessage(mark);
                highlighter.setErrorStripeTooltip(message);
                highlighter.setErrorStripeMarkColor(JBColor.RED);
                highlighters.add(highlighter);
            }
        }

        private void showHover(@NotNull EditorMouseEvent event) {
            int offset = event.getOffset();
            if (offset < 0) {
                return;
            }
            RangeHighlighter hit = null;
            for (RangeHighlighter highlighter : highlighters) {
                if (offset >= highlighter.getStartOffset()
                        && offset < highlighter.getEndOffset()) {
                    hit = highlighter;
                    break;
                }
            }
            if (hit == null) {
                hoverAnchor = -1;
                return;
            }
            if (hoverAnchor == hit.getStartOffset()) {
                return;
            }
            hoverAnchor = hit.getStartOffset();
            if (!(hit.getErrorStripeTooltip() instanceof String message)) {
                return;
            }
            TooltipController.getInstance()
                    .showTooltip(editor, event.getMouseEvent().getPoint(), message, false,
                            new TooltipGroup("MP_SQL_UNKNOWN_COLUMN", 0));
        }

        @Override
        public void dispose() {
            alarm.cancelAllRequests();
            try {
                editor.getDocument().removeDocumentListener(documentListener);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) {
                    throw (Error) t;
                }
            }
            try {
                editor.removeEditorMouseMotionListener(mouseListener);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) {
                    throw (Error) t;
                }
            }
            for (RangeHighlighter highlighter : highlighters) {
                try {
                    editor.getMarkupModel().removeHighlighter(highlighter);
                } catch (Throwable t) {
                    if (FatalErrors.isFatal(t)) {
                        throw (Error) t;
                    }
                }
            }
            highlighters.clear();
        }
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
