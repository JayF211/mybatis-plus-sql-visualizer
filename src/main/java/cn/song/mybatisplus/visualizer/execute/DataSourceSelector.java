package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageExtension;
import com.intellij.lang.LanguageUtil;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import com.intellij.openapi.application.AccessToken;
import com.intellij.util.SlowOperations;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 原生数据源选择器（M36，反射隔离）：选数据源 → Database 控制台直接执行。
 *
 * 流程（选中后自动执行，等价原生 Ctrl+Enter 全链路）：
 *  1. 创建临时 .sql 文件（含 SQL）+ PsiFile —— 同时作为 VF 控制台挂载文件
 *  2. 反射 DatabaseRunners.getAttachDataSourceRunners 获取原生 Runner 列表
 *  3. 选中 runner → run() 内部建 session 并回调 consumer
 *  4. consumer 内续接执行链：JdbcConsoleProvider.attachConsole + console.executeQueries
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class DataSourceSelector {

    /** DatabaseRunners 类名（反射入口）。 */
    private static final String RUNNERS_CLASS = "com.intellij.database.console.DatabaseRunners";

    /** 执行链反射目标类名。 */
    private static final String CONSOLE_PROVIDER_CLASS = "com.intellij.database.console.JdbcConsoleProvider";
    private static final String CONSOLE_CLASS = "com.intellij.database.console.JdbcConsole";
    private static final String SESSION_CLASS = "com.intellij.database.console.session.DatabaseSession";

    /** last-used 数据源记忆 key（prepareConsole 静默复用，PropertiesComponent per-project）。 */
    private static final String LAST_RUNNER_KEY = "mpsql.visualizer.last.runner";

    /** 构建版本戳。 */
    private static final String BUILD_STAMP = "M36.7-hql-select-expand";

    /** M36.5.76：OUTFILL 未锚定诊断日志节流（EDT 串行访问，无需同步）。 */
    private static long lastOutFillDiagAt = 0L;

    // M36.5：已准备控制台缓存
    private static Object preparedConsole = null;
    private static Editor preparedEditor = null;
    private static Object preparedScriptModel = null;
    private static Object preparedExecOption = null;
    private static Object preparedUi = null;
    private static JComponent preparedTopBlock = null;
    private static JComponent preparedResultComponent = null;
    private static JComponent preparedFullComponent = null;

    // M36.5.5：共享控制台缓存
    private static Object sharedConsole = null;
    private static Editor sharedEditor = null;
    private static Object sharedScriptModel = null;
    private static Object sharedExecOption = null;
    private static Object sharedUi = null;
    private static JComponent sharedFullComponent = null;
    private static String sharedDataSourceName = null;

    /** M36.5.6：结果区换入回调。 */
    private static Consumer<JComponent> pendingOnConsole = null;

    /** M36.5.9：最近一次换入宿主的结果区组件实例。 */
    private static Component lastLiveResultSwapped = null;

    /** M36.5.52：最近一次已上报的 SQL 错误文本——早检去重用。 */
    private static String lastReportedError = null;

    /** M36.5.57：本次执行开始时 console history 的长度基线。 */
    private static int consoleErrorBaseline = 0;

    /** M36.5.10：执行提交代际计数。 */
    @SuppressWarnings("VolatileArrayField")
    private static volatile int executionGeneration = 0;

    /** M36.5.44：结果换入代际。 */
    private static volatile int swapGeneration = 0;

    // M36.5.7：共享缓存的分件存储
    private static JComponent sharedTopBlock = null;

    public static final DataSourceSelector INSTANCE = new DataSourceSelector();
    private DataSourceSelector() {}

    /** 控制台是否已就绪（prepareConsole 成功后 true）。 */
    public static boolean isPrepared() {
        return preparedConsole != null;
    }

    /** 释放已准备控制台引用（面板清空/关闭时调用）。 */
    public static void clearPrepared() {
        executionGeneration++;
        preparedConsole = null;
        preparedEditor = null;
        preparedScriptModel = null;
        preparedExecOption = null;
        preparedUi = null;
        preparedTopBlock = null;
        preparedResultComponent = null;
        preparedFullComponent = null;
    }

    /** M36.5：准备控制台（不执行）。 */
    public static void prepareConsole(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback) {
        chooseAndPrepare(project, sql, false, onConsole, onEditor, onError, callback);
    }

    /** M36.5：在已准备的控制台上执行。 */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError) {
        Object console = preparedConsole;
        Editor editor = preparedEditor;
        Object scriptModel = preparedScriptModel;
        Object execOption = preparedExecOption;
        if (console == null || editor == null || scriptModel == null || execOption == null) return false;
        executeOnConsole(project, console, editor, scriptModel, execOption, preparedUi, onResultsReady, onError);
        return true;
    }

    /** 选择数据源并执行 SQL（准备 + 执行合一）。 */
    public static void selectAndExecute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback) {
        chooseAndPrepare(project, sql, true, onConsole, onEditor, onError, callback);
    }

    private static void chooseAndPrepare(
            @NotNull Project project,
            @NotNull String sql,
            boolean executeAfterPrepare,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        if (!ExecuteSqlSupport.isAvailable()) {
            LOG.warn("[M36] database plugin NOT available, abort");
            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.noDatabase"));
            if (callback != null) callback.accept(false);
            return;
        }

        if (reuseSharedConsole(project, sql, executeAfterPrepare, onConsole, onEditor, onError, callback)) return;

        TempPsiFile temp = createTempPsiFile(project, sql);
        if (temp == null) {
            LOG.warn("[M36] createTempPsiFile failed, abort");
            if (onError != null) onError.accept("Failed to create temporary SQL file");
            if (callback != null) callback.accept(false);
            return;
        }
        VirtualFile vf = temp.vf;
        PsiFile psiFile = temp.psi;

        final String[] chosenRunnerName = {null};
        com.intellij.util.Consumer<Object> sessionConsumer = session -> {
            LOG.info("[M36] session created: " + (session != null ? session.getClass().getName() : "null")
                    + ", attaching console (execute=" + executeAfterPrepare + ", ds=" + chosenRunnerName[0] + ")");
            prepareInConsole(project, session, vf, executeAfterPrepare, chosenRunnerName[0], onConsole, onEditor, onError);
        };

        List<Object> runners = getAttachDataSourceRunners(psiFile, sql, sessionConsumer);
        LOG.info("[M36] runners=" + runners.size());

        if (!executeAfterPrepare) {
            String last = lastRunnerName(project);
            if (last != null) {
                Object matched = null;
                for (Object r : runners) {
                    if (getRunnerName(r).equals(last)) { matched = r; break; }
                }
                if (matched != null) {
                    LOG.info("[M36] silent reuse last runner: " + last);
                    chosenRunnerName[0] = last;
                    runRunner(matched);
                    if (callback != null) callback.accept(true);
                    return;
                }
            }
        }

        if (runners.isEmpty()) {
            LOG.warn("[M36] runners empty -> open Database tool window");
            openDatabaseToolWindow(project);
            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.datasource.empty"));
            if (callback != null) callback.accept(false);
        } else if (runners.size() == 1 && shouldBeChosenIfAlone(runners.get(0))) {
            LOG.info("[M36] single runner, run directly");
            chosenRunnerName[0] = getRunnerName(runners.get(0));
            runRunner(runners.get(0));
            if (callback != null) callback.accept(true);
        } else {
            LOG.info("[M36] show popup with " + runners.size() + " runners");
            showRunnerPopup(project, runners, runner -> {
                LOG.info("[M36] popup selected: " + (runner != null ? getRunnerName(runner) : "null"));
                if (!executeAfterPrepare && runner != null) {
                    rememberRunnerName(project, getRunnerName(runner));
                }
                if (runner != null) chosenRunnerName[0] = getRunnerName(runner);
                if (callback != null) callback.accept(runner != null);
                if (runner != null) runRunner(runner);
            });
        }
    }

    private static boolean reuseSharedConsole(
            @NotNull Project project,
            @NotNull String sql,
            boolean executeAfterPrepare,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        Object console = sharedConsole;
        if (console == null) return false;
        Editor editor = sharedEditor;
        if (editor == null) return false;
        JComponent top = sharedTopBlock;
        if (top == null) return false;
        String dsName = sharedDataSourceName;
        if (dsName == null) return false;
        Object scriptModel = sharedScriptModel;
        if (scriptModel == null) return false;
        Object execOption = sharedExecOption;
        if (execOption == null) return false;
        if (!Objects.equals(lastRunnerName(project), dsName)) return false;

        executionGeneration++;
        boolean valid;
        try {
            Object isValid = console.getClass().getMethod("isValid").invoke(console);
            valid = Boolean.TRUE.equals(isValid) && !editor.isDisposed();
        } catch (Throwable ignored) {
            valid = false;
        }
        if (!valid) {
            LOG.info("[M36] shared console invalid (ds=" + dsName + "), drop and rebuild");
            dropSharedConsole();
            return false;
        }
        try {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                editor.getDocument().setText(sql);
            });
        } catch (Throwable ignored) {
            // ignore
        }
        Object refreshedModel = scriptModel;
        try {
            Object consoleVf = console.getClass().getMethod("getVirtualFile").invoke(console);
            if (consoleVf instanceof VirtualFile) {
                VirtualFile cvf = (VirtualFile) consoleVf;
                WriteAction.run(() -> {
                    Document fileDoc = FileDocumentManager.getInstance().getDocument(cvf);
                    if (fileDoc != null && !fileDoc.getText().equals(sql)) {
                        fileDoc.setText(sql);
                        PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                    } else if (fileDoc != null) {
                        PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                    } else {
                        try {
                            cvf.setBinaryContent(sql.getBytes(StandardCharsets.UTF_8));
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                });
                final Object consoleForModel = console;
                final VirtualFile vfForModel = cvf;
                DumbService.getInstance(project).runWhenSmart(() ->
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                refreshConsoleModel(project, consoleForModel, vfForModel, LOG);
                            } catch (Throwable e) {
                                LOG.warn("[M36] shared scriptModel refresh (smart) FAILED: " + e.getMessage());
                            }
                        }, ModalityState.any()));
            }
        } catch (Throwable e) {
            LOG.warn("[M36] shared scriptModel vf-sync FAILED (keep cached): " + e.getMessage());
        }
        if (!executeAfterPrepare) {
            clearResultContents(sharedUi, LOG);
            clearConsoleHistory(console, LOG);
        }
        JComponent liveResult = null;
        try {
            Object c = sharedUi != null ? sharedUi.getClass().getMethod("getComponent").invoke(sharedUi) : null;
            liveResult = c instanceof JComponent ? (JComponent) c : null;
        } catch (Throwable ignored) {
            // ignore
        }
        JComponent full;
        JComponent sharedFull = sharedFullComponent;
        if (liveResult == null) {
            full = sharedFull;
        } else {
            full = swapResultIntoPersistentConsole(sharedFull, liveResult, top, LOG);
        }
        if (full == null) return false;
        if (liveResult != null) lastLiveResultSwapped = liveResult;
        sharedFullComponent = full;
        preparedConsole = console;
        preparedEditor = editor;
        preparedScriptModel = refreshedModel != null ? refreshedModel : scriptModel;
        preparedExecOption = execOption;
        sharedScriptModel = refreshedModel != null ? refreshedModel : scriptModel;
        preparedUi = sharedUi;
        preparedTopBlock = top;
        preparedResultComponent = liveResult instanceof JComponent ? (JComponent) liveResult : null;
        preparedFullComponent = full;
        if (onEditor != null) onEditor.accept(editor);
        if (onConsole != null) onConsole.accept(full);
        if (!executeAfterPrepare) {
            if (full instanceof JSplitPane) {
                final JComponent fullX = full;
                ApplicationManager.getApplication().invokeLater(() -> {
                    ((JSplitPane) fullX).setDividerLocation(1.0);
                    ((JSplitPane) fullX).revalidate();
                    ((JSplitPane) fullX).repaint();
                }, ModalityState.any());
            }
        }
        pendingOnConsole = onConsole;
        logDataContextCheck(top, console, editor, execOption, LOG);
        clearConsolePrompt(console, LOG);
        LOG.info("[M36] shared console reused (ds=" + dsName + ", execute=" + executeAfterPrepare + ", liveResult=" + (liveResult != null) + ")");
        if (executeAfterPrepare) {
            executeOnConsole(project, console, editor, refreshedModel != null ? refreshedModel : scriptModel, execOption, sharedUi, null, onError);
        }
        if (callback != null) callback.accept(true);
        return true;
    }

    /** M36.5.5：释放共享控制台缓存。 */
    private static void dropSharedConsole() {
        sharedConsole = null;
        sharedEditor = null;
        sharedScriptModel = null;
        sharedExecOption = null;
        sharedUi = null;
        sharedTopBlock = null;
        sharedFullComponent = null;
        sharedDataSourceName = null;
    }

    /** M36.5.50：清空 RunnerLayoutUi 的结果 Tab。 */
    private static void clearResultContents(Object ui, Logger LOG) {
        if (ui == null) return;
        try {
            Object contents = ui.getClass().getMethod("getContents").invoke(ui);
            if (!(contents instanceof Content[])) return;
            Content[] arr = (Content[]) contents;
            Method remove = ui.getClass().getMethod("removeContent", Content.class, Boolean.TYPE);
            int removed = 0;
            for (Content content : arr) {
                if (!isOutputContent(content)) {
                    try {
                        remove.invoke(ui, content, true);
                        removed++;
                    } catch (Throwable ex) {
                        LOG.warn("[M36] clear result content FAILED: " + ex.getMessage());
                    }
                }
            }
            LOG.info("[M36] result contents cleared (preview switch, removed=" + removed + ")");
        } catch (Throwable e) {
            LOG.warn("[M36] clear result contents FAILED: " + e.getMessage());
        }
    }

    /** M36.5.57：取 console history 文档长度。 */
    private static int consoleHistoryLength(Object console) {
        if (console == null) return 0;
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return 0;
            Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
            if (historyViewer == null) return 0;
            Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
            if (doc == null) return 0;
            Object len = doc.getClass().getMethod("getTextLength").invoke(doc);
            return len instanceof Integer ? (Integer) len : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** M36.5.48：从 console 历史文档提取真实错误文本。 */
    @Nullable
    private static String extractConsoleError(Object console, Logger LOG) {
        if (console == null) return null;
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return null;
            Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
            if (historyViewer == null) return null;
            Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
            if (!(doc instanceof Document)) return null;
            String text = ((Document) doc).getText();
            int baseline = consoleErrorBaseline;
            if (baseline > 0 && text.length() > baseline) {
                text = text.substring(baseline);
            } else if (baseline > 0) {
                return null;
            }
            if (text.isBlank()) return null;
            List<String> lines = Arrays.stream(text.split("\n", -1))
                    .filter(s -> !s.isBlank()).collect(Collectors.toList());
            String errorLine = null;
            for (String line : lines) {
                boolean sig = false;
                for (String s : ERROR_SIGNATURES) {
                    if (line.toLowerCase().contains(s.toLowerCase())) { sig = true; break; }
                }
                if (sig && !isSqlLine(line)) { errorLine = line; break; }
            }
            if (errorLine == null) return null;
            int idx = lines.indexOf(errorLine);
            int end = Math.min(idx + 4, lines.size());
            String excerpt = String.join("\n", lines.subList(idx, end));
            LOG.info("[M36] extracted console error: " + (excerpt.length() > 160 ? excerpt.substring(0, 160) : excerpt));
            return excerpt;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** M36.5.48：明确 SQL 错误签名。 */
    private static final List<String> ERROR_SIGNATURES = List.of(
            "sql exception", "syntax error", "sqlstate", "unknown column",
            "unknown table", "doesn't exist", "does not exist", "access denied",
            "deadlock", "error code", "broken pipe", "connection refused",
            "[日本語エラー]", "sql（", "exception in thread"
    );

    /** M36.5.48：判断一行是否纯 SQL 语句。 */
    private static boolean isSqlLine(String line) {
        String lower = line.trim().toLowerCase();
        boolean starts = lower.startsWith("select") || lower.startsWith("from")
                || lower.startsWith("where") || lower.startsWith("join")
                || lower.startsWith("insert") || lower.startsWith("update")
                || lower.startsWith("delete") || lower.startsWith("create")
                || lower.startsWith("alter") || lower.startsWith("drop");
        if (!starts) return false;
        for (String s : ERROR_SIGNATURES) {
            if (line.toLowerCase().contains(s.toLowerCase())) return false;
        }
        return true;
    }

    /** M36.5.62：清空 console 历史文档。 */
    private static void clearConsoleHistory(Object console, Logger LOG) {
        if (console == null) return;
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return;
            Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
            if (historyViewer == null) return;
            Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
            if (doc == null) return;
            doc.getClass().getMethod("setText", String.class).invoke(doc, "");
            LOG.info("[M36] console history cleared (preview switch)");
        } catch (Throwable e) {
            LOG.warn("[M36] clear console history FAILED: " + e.getMessage());
        }
    }

    /** M36.5.8：清空控制台提示符。 */
    private static void clearConsolePrompt(Object console, Logger LOG) {
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return;
            consoleView.getClass().getMethod("setPrompt", String.class).invoke(consoleView, "");
            LOG.info("[M36] console prompt cleared");
        } catch (Throwable e) {
            LOG.warn("[M36] clear console prompt FAILED: " + e.getMessage());
        }
    }

    /** M36.5.70：用 DBN ConsoleView 原生 print API 把文本写入 Output 控制台可视区。 */
    private static void writeConsoleOutput(Object console, String text, boolean isError) {
        if (console == null || text.isBlank()) return;
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) { LOG.info("[M36] writeConsoleOutput: consoleView null"); return; }
            Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
            if (historyViewer != null) {
                Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
                if (doc instanceof Document) {
                    Document d = (Document) doc;
                    String append = (!d.getText().isEmpty() && !d.getText().endsWith("\n")) ? "\n" : "";
                    try {
                        Project proj = (console instanceof Project) ? (Project) console : null;
                        if (proj == null) {
                            try {
                                Object p = console.getClass().getMethod("getProject").invoke(console);
                                proj = p instanceof Project ? (Project) p : null;
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                        final Project fProj = proj;
                        WriteCommandAction.runWriteCommandAction(fProj, () -> d.insertString(d.getTextLength(), append + text));
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                Editor ed = Arrays.stream(EditorFactory.getInstance().getEditors(d)).findFirst().orElse(null);
                                if (ed != null) {
                                    ed.getCaretModel().moveToOffset(d.getTextLength());
                                    ed.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE);
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }, ModalityState.any());
                        LOG.info("[M36] writeConsoleOutput: appended to history doc len=" + d.getTextLength());
                        return;
                    } catch (Throwable e) {
                        LOG.info("[M36] writeConsoleOutput doc-append FAILED: " + e.getMessage());
                    }
                }
            }
            Class<?> contentClass = null;
            try {
                contentClass = Class.forName("com.intellij.execution.ui.ConsoleViewContentType");
            } catch (Throwable ignored) {
                // ignore
            }
            if (contentClass != null) {
                Object ct;
                try {
                    String fieldName = isError ? "ERROR_OUTPUT" : "NORMAL_OUTPUT";
                    ct = contentClass.getField(fieldName).get(null);
                } catch (Throwable e) {
                    ct = Arrays.stream(contentClass.getFields()).findFirst().map(f -> {
                        try { return f.get(null); } catch (Throwable ignored) { return null; }
                    }).orElse(null);
                }
                Method print = Arrays.stream(consoleView.getClass().getMethods())
                        .filter(m -> m.getName().equals("print") && m.getParameterCount() >= 2
                                && m.getParameterTypes()[0] == String.class)
                        .findFirst().orElse(null);
                if (ct != null && print != null) {
                    print.invoke(consoleView, text, ct);
                    LOG.info("[M36] writeConsoleOutput: ConsoleView.print ok");
                    return;
                } else {
                    LOG.info("[M36] writeConsoleOutput: ct=" + (ct != null) + " print=" + (print != null));
                }
            } else {
                LOG.info("[M36] writeConsoleOutput: ConsoleViewContentType class not found");
            }
        } catch (Throwable e) {
            LOG.info("[M36] writeConsoleOutput FAILED: " + e.getMessage());
        }
    }

    /** M36.5.8/M36.5.9/M36.5.13：执行后结果区刷新——限时多次重取。 */
    private static void refreshResultsComponent(Object ui, Logger LOG, boolean forceSwapIn, int generation, @Nullable Consumer<String> onError) {
        Consumer<JComponent> callback = pendingOnConsole;
        if (callback == null) return;
        JComponent top = sharedTopBlock != null ? sharedTopBlock : preparedTopBlock;
        if (top == null) return;
        Object shared = sharedConsole;
        final int[] ticks = {0};
        final boolean[] swapped = {false};
        Timer timer = new Timer(600, null);
        timer.addActionListener(e -> {
            ticks[0]++;
            if (generation != swapGeneration) {
                timer.stop();
                LOG.info("[M36] results refresh cancelled (generation " + generation + " != " + swapGeneration + ")");
                return;
            }
            String err = extractConsoleError(shared != null ? shared : preparedConsole, LOG);
            if (err != null && !err.equals(lastReportedError)) {
                timer.stop();
                lastReportedError = err;
                LOG.info("[M36] refresh console error detected (tick=" + ticks[0] + "): " + (err.length() > 120 ? err.substring(0, 120) : err));
                final String fErr = err;
                ApplicationManager.getApplication().invokeLater(() -> {
                    writeConsoleOutput(shared != null ? shared : preparedConsole, "执行失败: " + fErr + "\n", true);
                    if (onError != null) onError.accept(fErr);
                }, ModalityState.any());
                return;
            }
            if (shared != null) clearConsolePrompt(shared, LOG);
            JComponent live = null;
            try {
                Object c = ui.getClass().getMethod("getComponent").invoke(ui);
                live = c instanceof JComponent ? (JComponent) c : null;
            } catch (Throwable ignored) {
                // ignore
            }
            boolean shouldSwap = live != null && (forceSwapIn && !swapped[0] || live != lastLiveResultSwapped);
            if (shouldSwap && live != null) {
                JComponent full = swapResultIntoPersistentConsole(sharedFullComponent, live, top, LOG);
                boolean changed = live != lastLiveResultSwapped;
                swapped[0] = true;
                lastLiveResultSwapped = live;
                sharedFullComponent = full;
                preparedFullComponent = full;
                Consumer<JComponent> cb = pendingOnConsole;
                if (cb != null) cb.accept(full);
                LOG.info("[M36] results refreshed (tick=" + ticks[0] + ", forced=" + forceSwapIn + ", instanceChanged=" + changed + ", gen=" + generation + ")");
            }
            if (ticks[0] >= 8) {
                timer.stop();
                LOG.info("[M36] results refresh watch finished (ticks=" + ticks[0] + ")");
            }
        });
        timer.start();
    }

    /** M36.5.6：结果区换入监听。 */
    private static void attachResultsHook(
            Object ui,
            JComponent topBlock,
            JComponent resultComponent,
            Object console,
            String sql,
            Logger LOG) {
        if (ui == null || resultComponent == null) return;
        try {
            Object cm = ui.getClass().getMethod("getContentManager").invoke(ui);
            if (!(cm instanceof ContentManager)) return;
            ContentManager contentManager = (ContentManager) cm;
            contentManager.addContentManagerListener(new ContentManagerListener() {
                @Override
                public void contentAdded(@NotNull ContentManagerEvent event) {
                    Content content = event.getContent();
                    String name = content != null ? content.getDisplayName() : null;
                    if (name == null) return;
                    LOG.info("[M36] contentAdded: " + name);
                    if (name.equalsIgnoreCase("Output") || name.equalsIgnoreCase("输出")) return;
                    Consumer<JComponent> callback = pendingOnConsole;
                    if (callback == null) {
                        LOG.info("[M36] contentAdded swap-in SKIPPED (pendingOnConsole=null)");
                        return;
                    }
                    JComponent liveResult = null;
                    try {
                        Object c = ui.getClass().getMethod("getComponent").invoke(ui);
                        liveResult = c instanceof JComponent ? (JComponent) c : null;
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (liveResult == null) liveResult = resultComponent;
                    lastLiveResultSwapped = liveResult;
                    JComponent full = swapResultIntoPersistentConsole(
                            sharedFullComponent != null ? sharedFullComponent : preparedFullComponent,
                            liveResult, topBlock, LOG);
                    preparedFullComponent = full;
                    sharedFullComponent = full;
                    sharedTopBlock = topBlock;
                    LOG.info("[M36] contentAdded swap-in dispatched (name=" + name + ", rebuilt=true)");
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            callback.accept(full);
                        } catch (Throwable ex) {
                            LOG.warn("[M36] contentAdded swap-in FAILED: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
                        }
                        try {
                            Editor ed = (sharedEditor != null ? sharedEditor : preparedEditor);
                            String currentSql = (ed != null && ed.getDocument().getText() != null && !ed.getDocument().getText().isBlank())
                                    ? ed.getDocument().getText() : sql;
                            Object consoleDoc = console.getClass().getMethod("getDocument").invoke(console);
                            if (consoleDoc instanceof Document) {
                                Document cd = (Document) consoleDoc;
                                if (!cd.getText().equals(currentSql)) {
                                    Project proj = null;
                                    try {
                                        Object p = console.getClass().getMethod("getProject").invoke(console);
                                        proj = p instanceof Project ? (Project) p : null;
                                    } catch (Throwable ignored) {
                                        // ignore
                                    }
                                    if (proj == null) return;
                                    final Project fProj = proj;
                                    final String fSql = currentSql;
                                    WriteCommandAction.runWriteCommandAction(fProj, () -> cd.setText(fSql));
                                    try {
                                        Editor e2 = Arrays.stream(EditorFactory.getInstance().getEditors(cd)).findFirst().orElse(null);
                                        if (e2 != null) {
                                            e2.getSelectionModel().removeSelection();
                                            e2.getCaretModel().moveToOffset(0);
                                        }
                                    } catch (Throwable ignored) {
                                        // ignore
                                    }
                                    LOG.info("[M36] contentAdded: console doc rewritten (len=" + cd.getTextLength() + ")");
                                }
                            }
                        } catch (Throwable e) {
                            LOG.warn("[M36] contentAdded rewrite FAILED: " + e.getMessage());
                        }
                    }, ModalityState.any());
                }
            });
            LOG.info("[M36] results hook attached (contentAdded -> embed results)");
        } catch (Throwable e) {
            LOG.warn("[M36] results hook attach FAILED: " + e.getMessage());
        }
    }

    /** M36.5.6：嵌入后自检数据上下文。 */
    private static void logDataContextCheck(
            JComponent editorBlock,
            Object console,
            Editor editor,
            Object execOption,
            Logger LOG) {
        ApplicationManager.getApplication().invokeLater(() ->
                ReadAction.compute(() -> {
                    try {
                        DataContext ctx = com.intellij.ide.DataManager.getInstance().getDataContext(editorBlock);
                        boolean projectOk = CommonDataKeys.PROJECT.getData(ctx) != null;
                        boolean editorOk = CommonDataKeys.EDITOR.getData(ctx) != null;
                        DataKey<?> clientKey = null;
                        try {
                            Field f = Class.forName("com.intellij.database.console.client.SessionClientHolder")
                                    .getField("CLIENT_KEY");
                            clientKey = (DataKey<?>) f.get(null);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        boolean consoleOk = clientKey != null && clientKey.getData(ctx) != null;
                        PsiFile psiFile = CommonDataKeys.PSI_FILE.getData(ctx);
                        boolean psiOk = psiFile != null;
                        Object sqlAtPlace = null;
                        try {
                            sqlAtPlace = Class.forName("com.intellij.database.console.JdbcConsoleProvider")
                                    .getMethod("isSqlAtPlace", PsiFile.class, Editor.class)
                                    .invoke(null, psiFile, editor);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        Object statementsRange = null;
                        try {
                            Class<?> clientClass = Class.forName("com.intellij.database.console.client.DatabaseScriptingSessionClient");
                            statementsRange = Class.forName("com.intellij.database.script.ScriptModelUtil")
                                    .getMethod("getSelectedStatementsRange", clientClass, Editor.class, execOption.getClass())
                                    .invoke(null, console, editor, execOption);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        String modelDiag = null;
                        try {
                            Object model = Class.forName(CONSOLE_CLASS).getMethod("getScriptModel").invoke(console);
                            Object isActual = model != null ? model.getClass().getMethod("isActual").invoke(model) : null;
                            Iterable<?> stmts = null;
                            if (model != null) {
                                Object s = model.getClass().getMethod("statements").invoke(model);
                                stmts = s instanceof Iterable ? (Iterable<?>) s : null;
                            }
                            Object tr = model != null ? model.getClass().getMethod("getTextRange").invoke(model) : null;
                            Language lang = null;
                            try {
                                lang = model != null ? (Language) model.getClass().getMethod("getLanguage").invoke(model) : null;
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            List<?> stmtsList = stmts != null ? new ArrayList<Object>((java.util.Collection<?>) toList(stmts)) : List.of();
                            StringBuilder stmtDetail = new StringBuilder();
                            int si = 0;
                            for (Object it : stmtsList) {
                                if (si++ >= 3) break;
                                String cls = it != null ? it.getClass().getSimpleName() : "null";
                                Object r = null;
                                try {
                                    r = it.getClass().getMethod("getTextRange").invoke(it);
                                } catch (Throwable ignored) {
                                    try { r = it.getClass().getMethod("range").invoke(it); } catch (Throwable ignored2) { /* ignore */ }
                                }
                                stmtDetail.append(cls).append("(").append(r).append("),");
                            }
                            int modelIdentity = System.identityHashCode(model);
                            String subDiag;
                            try {
                                Class<?> posRangeClass = Class.forName("com.intellij.database.script.ScriptModel$PositionRange");
                                Object posRange = posRangeClass.getConstructor(TextRange.class)
                                        .newInstance(TextRange.create(0, editor.getDocument().getTextLength()));
                                Object sub = model != null ? model.getClass().getMethod("subModel", TextRange.class).invoke(model, posRange) : null;
                                List<?> subStmts = null;
                                if (sub != null) {
                                    Object ss = sub.getClass().getMethod("statements").invoke(sub);
                                    subStmts = ss instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) ss)) : null;
                                }
                                Object first = subStmts != null && !subStmts.isEmpty() ? subStmts.get(0) : null;
                                Object api = first != null ? first.getClass().getMethod("api").invoke(first) : null;
                                LanguageExtension<?> ep = null;
                                try {
                                    Field epf = Class.forName("com.intellij.sql.dialects.EvaluationHelper").getField("EP");
                                    ep = (LanguageExtension<?>) epf.get(null);
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                Object helperInstance = lang != null && ep != null ? ep.forLanguage(lang) : null;
                                Object isStmt = null;
                                try {
                                    isStmt = helperInstance != null ? helperInstance.getClass()
                                            .getMethod("isStatement", api != null ? api.getClass() : Object.class)
                                            .invoke(helperInstance, api) : null;
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                Object v = null;
                                try {
                                    if (isStmt instanceof com.intellij.openapi.util.Condition) {
                                        v = ((com.intellij.openapi.util.Condition<Object>) isStmt).value(first);
                                    }
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                Object firstRange = null;
                                try {
                                    Class<?> pr2 = Class.forName("com.intellij.database.script.ScriptModel$PositionRange");
                                    Object pr = pr2.getConstructor(TextRange.class).newInstance(TextRange.create(0, 1));
                                    Object sub2 = model != null ? model.getClass().getMethod("subModel", TextRange.class).invoke(model, pr) : null;
                                    Object ss2 = sub2 != null ? sub2.getClass().getMethod("statements").invoke(sub2) : null;
                                    List<?> st2 = ss2 instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) ss2)) : null;
                                    firstRange = st2 != null && !st2.isEmpty() ? (run(() -> {
                                        try { return st2.get(0).getClass().getMethod("range").invoke(st2.get(0)); }
                                        catch (Throwable ignored) { return null; }
                                    })) : null;
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                subDiag = "firstStmt=" + (first != null ? first.getClass().getSimpleName() : "null") + "(" + run(() -> {
                                    try { return first != null ? first.getClass().getMethod("range").invoke(first) : null; }
                                    catch (Throwable ignored) { return null; }
                                }) + ") api=" + (api != null ? api.getClass().getSimpleName() : "null")
                                        + " helper=" + (helperInstance != null ? helperInstance.getClass().getName() : "null")
                                        + " isStatement=" + v + " probe01=" + (st2size(firstRange) + ":" + firstRange);
                            } catch (Throwable t) {
                                subDiag = "filter FAILED:" + t.getMessage();
                            }
                            String docRel;
                            try {
                                Object consoleDoc = Class.forName(CONSOLE_CLASS).getMethod("getDocument").invoke(console);
                                Object consoleVf = Class.forName(CONSOLE_CLASS).getMethod("getVirtualFile").invoke(console);
                                Document fileDoc = consoleVf != null ? FileDocumentManager.getInstance().getDocument((VirtualFile) consoleVf) : null;
                                PsiFile file = null;
                                try {
                                    file = (PsiFile) Class.forName(CONSOLE_CLASS).getMethod("getFile").invoke(console);
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                docRel = "consoleDoc(" + System.identityHashCode(consoleDoc) + ":"
                                        + (consoleDoc instanceof Document ? ((Document) consoleDoc).getTextLength() : "null") + ") "
                                        + "editorDoc(" + System.identityHashCode(editor.getDocument()) + ":" + editor.getDocument().getTextLength() + ") "
                                        + "same=" + (consoleDoc == editor.getDocument()) + " "
                                        + "vfDoc(" + System.identityHashCode(fileDoc) + ":"
                                        + (fileDoc instanceof Document ? ((Document) fileDoc).getTextLength() : "null") + ") "
                                        + "vfDocSameEditorDoc=" + (fileDoc == editor.getDocument()) + " "
                                        + "consoleFile=" + (file != null ? file.getClass().getSimpleName() : "null") + ":"
                                        + (file != null ? file.getTextLength() : "null") + ":"
                                        + (file != null && file.getVirtualFile() != null ? file.getVirtualFile().getName() : "null");
                            } catch (Throwable t) {
                                docRel = "docRel FAILED:" + t.getMessage();
                            }
                            String clientCheck;
                            try {
                                Class<?> clientClass = Class.forName("com.intellij.database.console.client.DatabaseScriptingSessionClient");
                                boolean isClient = clientClass.isInstance(console);
                                Object clientModel = isClient ? clientClass.getMethod("getScriptModel").invoke(console) : null;
                                Object consoleModel = Class.forName(CONSOLE_CLASS).getMethod("getScriptModel").invoke(console);
                                clientCheck = "consoleIsClient=" + isClient
                                        + " clientModelId=" + (clientModel != null ? System.identityHashCode(clientModel) : "null")
                                        + " consoleModelId=" + System.identityHashCode(consoleModel)
                                        + " sameModel=" + (clientModel == consoleModel);
                            } catch (Throwable t) {
                                clientCheck = "clientCheck FAILED:" + t.getMessage();
                            }
                            modelDiag = "modelIsActual=" + isActual + " stmts=" + stmtsList.size() + " modelRange=" + tr
                                    + " lang=" + (lang != null ? lang.getID() : "null") + " stmtsDetail=[" + stmtDetail + "]"
                                    + " modelId=" + modelIdentity + " docLen=" + editor.getDocument().getTextLength()
                                    + " caret=" + editor.getCaretModel().getOffset() + " execSelection="
                                    + run(() -> {
                                        try { return execOption.getClass().getField("execSelection").getInt(execOption); }
                                        catch (Throwable ignored) { return null; }
                                    })
                                    + " " + docRel + " " + clientCheck + " | " + subDiag;
                        } catch (Throwable t) {
                            modelDiag = "subDiag FAILED:" + t.getMessage();
                        }
                        LOG.info("[M36] dataContext check: PROJECT=" + projectOk + ", EDITOR=" + editorOk
                                + ", CLIENT_KEY=" + consoleOk + ", PSI_FILE=" + psiOk
                                + ", isSqlAtPlace=" + sqlAtPlace + ", statementsRange=" + statementsRange + ", " + modelDiag);
                        return null;
                    } catch (Throwable e) {
                        LOG.warn("[M36] dataContext check FAILED: " + e.getMessage());
                        return null;
                    }
                }), ModalityState.any());
    }

    private static List<?> toList(Iterable<?> it) {
        List<Object> list = new ArrayList<>();
        for (Object o : it) list.add(o);
        return list;
    }

    private static Object run(RunWithResult r) {
        try { return r.run(); } catch (Throwable ignored) { return null; }
    }

    private interface RunWithResult { Object run() throws Throwable; }

    private static String st2size(Object firstRange) {
        return String.valueOf(firstRange);
    }

    /** 准备链续接（consumer 回调内调用）。 */
    private static void prepareInConsole(
            @NotNull Project project,
            Object session,
            @NotNull VirtualFile vf,
            boolean executeAfterPrepare,
            @Nullable String dataSourceName,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            Class<?> providerClass = Class.forName(CONSOLE_PROVIDER_CLASS);
            Class<?> sessionClass = Class.forName(SESSION_CLASS);

            Method attach = providerClass.getMethod("attachConsole", Project.class, sessionClass, VirtualFile.class);
            attach.setAccessible(true);
            Object console = attach.invoke(null, project, session, vf);
            LOG.info("[M36] attachConsole -> " + console);
            if (console == null) return;

            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);

            Object documentObj = consoleClass.getMethod("getDocument").invoke(console);
            Document document = documentObj instanceof Document ? (Document) documentObj : null;
            Editor editor = null;
            for (int i = 0; i < 20; i++) {
                if (editor != null) break;
                if (document != null) {
                    editor = Arrays.stream(EditorFactory.getInstance().getEditors(document)).findFirst().orElse(null);
                }
                if (editor == null) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
            LOG.info("[M36] editor=" + editor);
            if (editor == null) return;
            if (onEditor != null) onEditor.accept(editor);

            Object scriptModel = consoleClass.getMethod("getScriptModel").invoke(console);
            Class<?> execOptionClass = Class.forName("com.intellij.database.settings.DatabaseSettings$ExecOption");
            Object execOption;
            try {
                execOption = Class.forName("com.intellij.database.settings.DatabaseSettings")
                        .getMethod("getDefaultExecOption").invoke(null);
            } catch (Throwable ignored) {
                execOption = execOptionClass.getDeclaredConstructor().newInstance();
            }
            if (scriptModel == null) return;

            Object effectiveScriptModel = scriptModel;
            try {
                Object consoleVf = consoleClass.getMethod("getVirtualFile").invoke(console);
                if (consoleVf instanceof VirtualFile) {
                    VirtualFile cvf = (VirtualFile) consoleVf;
                    String consoleText = document != null ? document.getText() : "";
                    WriteAction.run(() -> {
                        Document fileDoc = FileDocumentManager.getInstance().getDocument(cvf);
                        if (fileDoc != null && !fileDoc.getText().equals(consoleText)) {
                            fileDoc.setText(consoleText);
                            PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                        } else if (fileDoc != null) {
                            PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                        } else {
                            try {
                                cvf.setBinaryContent(consoleText.getBytes(StandardCharsets.UTF_8));
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
                    final Object consoleForModel = console;
                    final VirtualFile vfForModel = cvf;
                    DumbService.getInstance(project).runWhenSmart(() ->
                            ApplicationManager.getApplication().invokeLater(() -> {
                                try {
                                    refreshConsoleModel(project, consoleForModel, vfForModel, LOG);
                                } catch (Throwable e) {
                                    LOG.warn("[M36] scriptModel refresh (smart) FAILED: " + e.getMessage());
                                }
                            }, ModalityState.any()));
                }
            } catch (Throwable e) {
                LOG.warn("[M36] scriptModel vf-sync FAILED (fallback to attach-time model): " + e.getMessage());
            }

            executionGeneration++;
            preparedConsole = console;
            preparedEditor = editor;
            preparedScriptModel = effectiveScriptModel;
            preparedExecOption = execOption;

            Object logView = null;
            try {
                logView = consoleClass.getMethod("getView").invoke(console);
            } catch (Throwable ignored) {
                // ignore
            }
            if (logView != null) {
                try {
                    logView.getClass().getMethod("ensureContentInitialized").invoke(logView);
                } catch (Throwable e) {
                    LOG.warn("[M36] ensureContentInitialized FAILED: " + e.getMessage());
                }
            }
            Object ui = null;
            if (logView != null) {
                try { ui = logView.getClass().getMethod("getUi").invoke(logView); } catch (Throwable ignored) { /* ignore */ }
            }
            if (ui == null) {
                try { ui = consoleClass.getMethod("getUi").invoke(console); } catch (Throwable ignored) { /* ignore */ }
            }
            preparedUi = ui;
            JComponent resultComponent = null;
            if (ui != null) {
                try {
                    Object rc = ui.getClass().getMethod("getComponent").invoke(ui);
                    resultComponent = rc instanceof JComponent ? (JComponent) rc : null;
                } catch (Throwable ignored) {
                    // ignore
                }
            }

            JComponent editorComponentRaw = editor.getComponent();
            if (editorComponentRaw == null) return;
            editorComponentRaw.setVisible(true);
            if (editorComponentRaw.getPreferredSize() == null || editorComponentRaw.getPreferredSize().height < 50) {
                editorComponentRaw.setPreferredSize(new Dimension(400, 200));
            }
            if (editorComponentRaw.getMinimumSize() == null || editorComponentRaw.getMinimumSize().height < 200) {
                editorComponentRaw.setMinimumSize(new Dimension(400, 200));
            }

            String clientKeyName = null;
            try {
                Field f = Class.forName("com.intellij.database.console.client.SessionClientHolder").getField("CLIENT_KEY");
                DataKey<?> dk = (DataKey<?>) f.get(null);
                clientKeyName = dk.getName();
            } catch (Throwable ignored) {
                // ignore
            }
            final Object consoleForData = console;
            final Editor editorForData = editor;
            final VirtualFile vfForData = vf;
            final PsiFile[] psiForData = {null};
            try {
                psiForData[0] = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
            } catch (Throwable ignored) {
                // ignore
            }
            JComponent editorComponent = new EditorDataPanel(project, editor, vf, console, clientKeyName, psiForData);
            editorComponent.setOpaque(false);
            editorComponent.setLayout(new GridLayout(1, 1));
            editorComponent.add(editorComponentRaw);
            editorComponentRaw.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            editorComponent.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    Dimension avail = editorComponent.getSize();
                    if (avail.width > 0 && avail.height > 0) {
                        editorComponentRaw.setSize(avail.width, avail.height);
                        editorComponentRaw.revalidate();
                    }
                }
            });

            JComponent topBlock = editorComponent;
            try {
                Method buildHeader = consoleClass.getMethod("buildHeaderComponent", Editor.class, consoleClass);
                buildHeader.invoke(null, editor, console);
                LOG.info("[M36] native console header built (Console.Jdbc.Left/Right toolbars)");
            } catch (Throwable headerError) {
                LOG.warn("[M36] buildHeaderComponent FAILED (" + headerError.getMessage() + "), fallback to console-actions toolbar");
                try {
                    Object consoleView = consoleClass.getMethod("getConsoleView").invoke(console);
                    if (consoleView == null) return;
                    com.intellij.openapi.actionSystem.DefaultActionGroup group = new com.intellij.openapi.actionSystem.DefaultActionGroup();
                    Class<?> util = Class.forName("com.intellij.database.console.JdbcConsoleUtil");
                    Method addActions = util.getMethod("addLeftToolbarConsoleActions",
                            com.intellij.openapi.actionSystem.DefaultActionGroup.class, Class.forName("com.intellij.execution.ui.ConsoleView"));
                    addActions.invoke(null, group, consoleView);
                    if (group.getChildrenCount() > 0) {
                        com.intellij.openapi.actionSystem.ActionToolbar toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                                .createActionToolbar("MpSqlNativeConsole", group, true);
                        toolbar.setTargetComponent(editorComponent);
                        JComponent tb = toolbar.getComponent();
                        topBlock = new JPanel(new BorderLayout()) {{
                            setOpaque(false);
                            setBorder(com.intellij.util.ui.JBUI.Borders.empty());
                            add(tb, BorderLayout.NORTH);
                            add(editorComponent, BorderLayout.CENTER);
                        }};
                        LOG.info("[M36] console-actions toolbar injected: " + group.getChildrenCount() + " actions");
                    }
                } catch (Throwable e) {
                    LOG.warn("[M36] toolbar injection skipped (fallback to bare editor): " + e.getMessage());
                }
            }

            JComponent fullComponent = resultComponent != null ? buildConsoleSplitPane(topBlock, resultComponent) : null;

            pendingOnConsole = onConsole;
            JComponent component = fullComponent != null ? fullComponent : topBlock;
            preparedTopBlock = topBlock;
            preparedResultComponent = resultComponent;
            preparedFullComponent = fullComponent;
            LOG.info("[M36] embed component=" + component + " (executeAfterPrepare=" + executeAfterPrepare + ")");

            if (!executeAfterPrepare) {
                attachResultsHook(ui, topBlock, resultComponent, console, editor.getDocument().getText(), LOG);
            }
            logDataContextCheck(editorComponent, console, editor, execOption, LOG);
            clearConsolePrompt(console, LOG);

            sharedConsole = console;
            sharedEditor = editor;
            sharedScriptModel = effectiveScriptModel;
            sharedExecOption = execOption;
            sharedUi = ui;
            sharedTopBlock = topBlock;
            sharedFullComponent = fullComponent;
            sharedDataSourceName = dataSourceName;
            if (dataSourceName != null) rememberRunnerName(project, dataSourceName);

            if (onConsole != null) onConsole.accept(component);

            if (!executeAfterPrepare) {
                if (component instanceof JSplitPane) {
                    final JComponent compX = component;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ((JSplitPane) compX).setDividerLocation(1.0);
                        ((JSplitPane) compX).revalidate();
                        ((JSplitPane) compX).repaint();
                    }, ModalityState.any());
                }
            }

            if (executeAfterPrepare) {
                executeOnConsole(project, console, editor, effectiveScriptModel, execOption, ui, null, onError);
            }
        } catch (Throwable e) {
            LOG.warn("[M36] prepareInConsole FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            if (onError != null) onError.accept(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        }
    }

    /** M36.5.24g：dumb 退出后重建 console 的 scriptModel。 */
    private static void refreshConsoleModel(
            @NotNull Project project,
            Object console,
            @NotNull VirtualFile consoleVf,
            Logger LOG) {
        try {
            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);
            Language lang = null;
            try {
                lang = (Language) Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
                        .getMethod("getMapping", Project.class, VirtualFile.class)
                        .invoke(null, project, consoleVf);
            } catch (Throwable ignored) {
                // ignore
            }
            if (lang == null) lang = LanguageUtil.getLanguageForPsi(project, consoleVf);
            Object fresh = Class.forName("com.intellij.sql.script.SqlScriptModel")
                    .getConstructor(Project.class, VirtualFile.class, Language.class)
                    .newInstance(project, consoleVf, lang);
            List<?> freshStmts = null;
            try {
                Object fs = fresh.getClass().getMethod("statements").invoke(fresh);
                freshStmts = fs instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) fs)) : null;
            } catch (Throwable ignored) {
                // ignore
            }
            Object freshLang = null;
            try { freshLang = fresh.getClass().getMethod("getLanguage").invoke(fresh); } catch (Throwable ignored) { /* ignore */ }
            if (freshStmts == null || freshStmts.isEmpty()) {
                LOG.info("[M36] refreshConsoleModel: model empty (lang=" + freshLang + ", stmts=" + (freshStmts != null ? freshStmts.size() : "null") + "), keep console model");
                return;
            }
            Field myScriptField = fresh.getClass().getDeclaredField("myScript");
            myScriptField.setAccessible(true);
            Object myScript = myScriptField.get(fresh);
            Object targetMode = null;
            try {
                targetMode = Class.forName("com.intellij.database.script.ScriptModelUtilCore")
                        .getMethod("getSplitScriptMode", Class.forName("com.intellij.sql.script.Script"))
                        .invoke(null, myScript);
            } catch (Throwable ignored) {
                // ignore
            }
            Object settings = Class.forName("com.intellij.database.settings.DatabaseSettings")
                    .getMethod("getSettings").invoke(null);
            Field splitField = settings.getClass().getField("splitScriptMode");
            Object oldSplit = splitField.get(settings);
            if (targetMode != null && !targetMode.equals(oldSplit)) {
                splitField.set(settings, targetMode);
            }
            Object freshIsActual = null;
            try { freshIsActual = fresh.getClass().getMethod("isActual").invoke(fresh); } catch (Throwable ignored) { /* ignore */ }
            Field field = consoleClass.getDeclaredField("myScriptModel");
            field.setAccessible(true);
            ((AtomicReference<Object>) field.get(console)).set(fresh);
            preparedScriptModel = fresh;
            if (sharedConsole == console) sharedScriptModel = fresh;
            Object afterSet = null;
            try { afterSet = consoleClass.getMethod("getScriptModel").invoke(console); } catch (Throwable ignored) { /* ignore */ }
            List<?> afterStmts = null;
            try {
                Object as = afterSet != null ? afterSet.getClass().getMethod("statements").invoke(afterSet) : null;
                afterStmts = as instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) as)) : null;
            } catch (Throwable ignored) {
                // ignore
            }
            LOG.info("[M36] refreshConsoleModel: replaced with explicit-language model: "
                    + "newLang=" + freshLang + ", stmts=" + freshStmts.size() + ", freshIsActual=" + freshIsActual
                    + ", splitMode=" + oldSplit + "->" + targetMode + ", setAfterGetIdentity=" + System.identityHashCode(afterSet)
                    + ", setAfterStmts=" + (afterStmts != null ? afterStmts.size() : "null"));
            final int[] stable = {0};
            final int[] retries = {0};
            final Language fLang = lang;
            final VirtualFile fVf = consoleVf;
            final Object fConsole = console;
            final Project fProject = project;
            final Logger fLog = LOG;
            Timer timer = new Timer(1000, null);
            timer.addActionListener(e -> {
                try {
                    Object cur = fConsole.getClass().getMethod("getScriptModel").invoke(fConsole);
                    List<?> curStmts = null;
                    try {
                        Object cs = cur != null ? cur.getClass().getMethod("statements").invoke(cur) : null;
                        curStmts = cs instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) cs)) : null;
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (curStmts != null && !curStmts.isEmpty()) {
                        stable[0]++;
                        if (stable[0] >= 2) {
                            timer.stop();
                            fLog.info("[M36] refreshConsoleModel: model stable (stmts=" + curStmts.size() + ")");
                            try {
                                Editor ed = preparedEditor;
                                Object ex = preparedExecOption;
                                JComponent tb = preparedTopBlock;
                                if (ed != null && ex != null && tb != null) logDataContextCheck(tb, fConsole, ed, ex, fLog);
                            } catch (Throwable ex) {
                                fLog.warn("[M36] refreshConsoleModel stable-probe FAILED: " + ex.getMessage());
                            }
                        }
                    } else {
                        stable[0] = 0;
                        retries[0]++;
                        if (retries[0] > 6) {
                            timer.stop();
                            fLog.warn("[M36] refreshConsoleModel: give up after " + retries[0] + " retries (stmts=" + (curStmts != null ? curStmts.size() : "null") + ")");
                            return;
                        }
                        Object freshN = Class.forName("com.intellij.sql.script.SqlScriptModel")
                                .getConstructor(Project.class, VirtualFile.class, Language.class)
                                .newInstance(fProject, fVf, fLang);
                        int nStmts = 0;
                        try {
                            Object ns = freshN.getClass().getMethod("statements").invoke(freshN);
                            List<?> nl = ns instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) ns)) : null;
                            nStmts = nl != null ? nl.size() : 0;
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        if (nStmts > 0) {
                            Field f2 = fConsole.getClass().getDeclaredField("myScriptModel");
                            f2.setAccessible(true);
                            ((AtomicReference<Object>) f2.get(fConsole)).set(freshN);
                            preparedScriptModel = freshN;
                            if (sharedConsole == fConsole) sharedScriptModel = freshN;
                            fLog.info("[M36] refreshConsoleModel: retry#" + retries[0] + " re-set fresh (stmts=" + nStmts + ")");
                        } else {
                            fLog.info("[M36] refreshConsoleModel: retry#" + retries[0] + " fresh empty (stmts=" + nStmts + "), keep current");
                        }
                    }
                } catch (Throwable ex) {
                    fLog.warn("[M36] refreshConsoleModel timer FAILED: " + ex.getMessage());
                    timer.stop();
                }
            });
            timer.setRepeats(true);
            timer.start();
            try {
                Editor ed = preparedEditor;
                Object ex = preparedExecOption;
                JComponent tb = preparedTopBlock;
                if (ed != null && ex != null && tb != null) logDataContextCheck(tb, console, ed, ex, LOG);
            } catch (Throwable ex) {
                LOG.warn("[M36] refreshConsoleModel post-probe FAILED: " + ex.getMessage());
            }
        } catch (Throwable e) {
            LOG.warn("[M36] refreshConsoleModel FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** M36.5.2：主题化完整控制台 SplitPane。 */
    private static JComponent buildConsoleSplitPane(JComponent topBlock, JComponent resultComponent) {
        Dimension min = topBlock.getMinimumSize();
        topBlock.setMinimumSize(new Dimension(min != null ? min.width : 400, 260));
        Dimension rmin = resultComponent.getMinimumSize();
        resultComponent.setMinimumSize(new Dimension(rmin != null ? rmin.width : 400, 160));
        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topBlock, resultComponent) {
            @Override
            public void paint(Graphics g) {
                super.paint(g);
                g.setColor(UIUtil.getPanelBackground());
                g.fillRect(0, getDividerLocation(), getWidth(), getDividerSize());
            }
        };
        sp.setResizeWeight(0.6);
        sp.setContinuousLayout(true);
        sp.setOneTouchExpandable(false);
        sp.setDividerSize(3);
        sp.setBorder(JBUI.Borders.empty());
        sp.setBackground(UIUtil.getPanelBackground());
        sp.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (sp.getHeight() < 200) return;
                sp.removeComponentListener(this);
                sp.setDividerLocation(0.6);
                sp.revalidate();
                sp.repaint();
            }
        });
        return sp;
    }

    /** M36.5.41-A4：持久整控台——结果刷新时原位换底。 */
    private static JComponent swapResultIntoPersistentConsole(
            JComponent full,
            JComponent liveResult,
            JComponent topBlock,
            Logger LOG) {
        JSplitPane sp = full instanceof JSplitPane ? (JSplitPane) full : null;
        if (sp == null) return buildConsoleSplitPane(topBlock, liveResult);
        Component current = sp.getBottomComponent();
        if (current == liveResult && liveResult.getParent() == sp) {
            Dimension rmin = liveResult.getMinimumSize();
            liveResult.setMinimumSize(new Dimension(rmin != null ? rmin.width : 400, 160));
            realignSplitDivider(sp, true);
            sp.revalidate();
            sp.repaint();
            return sp;
        }
        if (current != null) sp.remove(current);
        sp.setBottomComponent(liveResult);
        Dimension lrmin = liveResult.getMinimumSize();
        liveResult.setMinimumSize(new Dimension(lrmin != null ? lrmin.width : 400, 160));
        try {
            LOG.info("[M36] liveResult class=" + liveResult.getClass().getName() + ", hasSplit=" + (liveResult instanceof JSplitPane));
            if (liveResult instanceof JSplitPane) {
                JSplitPane lsp = (JSplitPane) liveResult;
                LOG.info("[M36] liveResult divider=" + lsp.getDividerLocation() + " orientation=" + lsp.getOrientation()
                        + " top=" + (lsp.getTopComponent() != null ? lsp.getTopComponent().getClass().getSimpleName() : "null")
                        + " bottom=" + (lsp.getBottomComponent() != null ? lsp.getBottomComponent().getClass().getSimpleName() : "null"));
            }
        } catch (Throwable ignored) {
            // ignore
        }
        realignSplitDivider(sp, true);
        sp.revalidate();
        sp.repaint();
        LOG.info("[M36] persistent console bottom swapped (result id=" + System.identityHashCode(liveResult)
                + ", prevParent=" + (liveResult.getParent() != null ? liveResult.getParent().getClass().getSimpleName() : "null") + ")");
        return sp;
    }

    /** M36.5.52/53：校准整控台 SplitPane 分隔。 */
    private static void realignSplitDivider(JSplitPane sp, boolean force) {
        ApplicationManager.getApplication().invokeLater(() -> realignDividerOnce(sp, force), ModalityState.any());
        sp.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                realignDividerOnce(sp, false);
            }
        });
    }

    private static void realignDividerOnce(JSplitPane sp, boolean force) {
        if (sp.getHeight() < 100) return;
        int topH = sp.getTopComponent() != null ? sp.getTopComponent().getHeight() : 0;
        int total = sp.getHeight();
        Logger log = Logger.getInstance(DataSourceSelector.class);
        boolean tooAuto = force || topH < total * 0.35 || topH > total * 0.68;
        if (tooAuto) {
            log.info("[M36] divider realign(force=" + force + "): topH=" + topH + " total=" + total + " → 0.6");
            sp.setDividerLocation(0.6);
            sp.revalidate();
            sp.repaint();
            dumpSplitBounds(sp, log);
        } else {
            log.info("[M36] divider OK: topH=" + topH + " total=" + total + " (no change)");
        }
        ApplicationManager.getApplication().invokeLater(() -> installOutputHistoryFillLayout(sp, log), ModalityState.any());
    }

    /** M36.5.75：Output 控制台布局替换修复。 */
    public static void installOutputHistoryFillLayout(Component root, Logger log) {
        try {
            JSplitPane sp = root instanceof JSplitPane ? (JSplitPane) root : null;
            Component searchRoot = sp != null ? sp.getBottomComponent() : root;
            if (!(searchRoot instanceof Container)) return;
            Container sr = (Container) searchRoot;
            List<Component> targets = new ArrayList<>();
            findAllOutputs(sr, 0, targets);
            if (targets.isEmpty()) {
                log.info("[M36][OUTFILL] no output editor (EditorImpl$PanelWithFloatingToolbar) in result area; skip");
                return;
            }
            boolean installedAny = false;
            for (Component t : targets) {
                if (t.getParent() != null && t.getParent().getLayout() instanceof FillTargetLayout) continue;
                List<Container> chain = new ArrayList<>();
                boolean anchored = false;
                Container cur = t.getParent();
                while (cur != null) {
                    chain.add(cur);
                    if (cur.getLayout() != null && cur.getLayout().getClass().getName().contains("EditorMergedHorizontalScrollBarLayout")) {
                        anchored = true;
                        break;
                    }
                    if (cur == searchRoot) break;
                    cur = cur.getParent();
                }
                if (!anchored) {
                    long now = System.currentTimeMillis();
                    if (now - lastOutFillDiagAt > 2000) {
                        lastOutFillDiagAt = now;
                        StringBuilder chainDesc = new StringBuilder();
                        Container p = t.getParent();
                        int lvl = 0;
                        while (p != null && lvl < 10) {
                            chainDesc.append("\n  ↑").append(lvl).append(" ").append(p.getClass().getName())
                                    .append(" layout=").append(p.getLayout() != null ? p.getLayout().getClass().getName() : "null")
                                    .append(" bounds=").append(p.getBounds());
                            if (p == searchRoot) break;
                            p = p.getParent();
                            lvl++;
                        }
                        log.info("[M36][OUTFILL] EMHSBL panel not found above output editor; ancestors=" + chainDesc);
                    }
                    continue;
                }
                Component child = t;
                for (Container c : chain) {
                    if (c instanceof JScrollPane) {
                        log.info("[M36][OUTFILL] chain node is JScrollPane (" + c.getClass().getName() + "), keep its layout");
                    } else if (!(c.getLayout() instanceof FillTargetLayout)) {
                        String oldLayout = c.getLayout() != null ? c.getLayout().getClass().getName() : "null";
                        java.awt.Rectangle before = c.getBounds();
                        c.setLayout(new FillTargetLayout(child));
                        c.revalidate();
                        log.info("[M36][OUTFILL] installed on " + c.getClass().getName() + " (was " + oldLayout + ") target=" + child.getClass().getSimpleName() + " before=" + before);
                        installedAny = true;
                    }
                    child = c;
                }
            }
            if (installedAny) {
                sr.revalidate();
                sr.repaint();
            }
        } catch (Throwable e) {
            log.warn("[M36][OUTFILL] install failed: " + e.getMessage());
        }
    }

    private static void findAllOutputs(Component c, int depth, List<Component> targets) {
        if (targets.size() >= 4 || depth > 24) return;
        if (c.getClass().getName().contains("EditorImpl$PanelWithFloatingToolbar")) {
            targets.add(c);
            return;
        }
        if (c instanceof Container) {
            for (Component d : ((Container) c).getComponents()) findAllOutputs(d, depth + 1, targets);
        }
    }

    /** M36.5.75：目标子组件填充布局。 */
    private static class FillTargetLayout implements LayoutManager {
        private final Component target;
        private int logged = 0;

        FillTargetLayout(Component target) { this.target = target; }

        @Override public void addLayoutComponent(String name, Component comp) {}
        @Override public void removeLayoutComponent(Component comp) {}
        @Override
        public Dimension preferredLayoutSize(Container parent) {
            if (parent == null) return new Dimension(0, 0);
            if (parent.getHeight() > 0) return new Dimension(parent.getWidth(), parent.getHeight());
            int sbH = 0;
            for (Component cc : parent.getComponents()) {
                if (cc instanceof JScrollBar && cc.isVisible()) sbH = cc.getPreferredSize().height;
            }
            return new Dimension(parent.getWidth(), target.getPreferredSize().height + sbH);
        }
        @Override
        public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, 0); }
        @Override
        public void layoutContainer(Container parent) {
            Component sb = null;
            for (Component cc : parent.getComponents()) {
                if (cc instanceof JScrollBar && cc.isVisible()) sb = cc;
            }
            int reserved = sb != null ? Math.max(sb.getPreferredSize().height, 0) : 0;
            int avail = Math.max(parent.getHeight() - reserved, 0);
            for (Component c : parent.getComponents()) {
                if (c == target) c.setBounds(0, 0, parent.getWidth(), avail);
                else if (c == sb) c.setBounds(0, avail, parent.getWidth(), reserved);
                else c.setBounds(0, avail, parent.getWidth(), 0);
            }
            if (logged < 3 && avail > 0 && target.getHeight() != avail) {
                logged++;
                Logger.getInstance(DataSourceSelector.class).info("[M36][OUTFILL] layout: parent=" + parent.getClass().getSimpleName()
                        + " h=" + parent.getHeight() + " target(" + target.getClass().getSimpleName() + ") → " + avail);
            }
        }
    }

    /** M36.5.60：运行时组件边界取证。 */
    private static void dumpSplitBounds(JSplitPane sp, Logger log) {
        try {
            java.util.function.Function<Component, String> b = c -> {
                if (c == null) return "null";
                return c.getClass().getSimpleName() + "@" + System.identityHashCode(c) + " bounds=" + c.getBounds()
                        + " pref=" + c.getPreferredSize() + " min=" + c.getMinimumSize() + " max=" + c.getMaximumSize();
            };
            log.info("[M36][BOUNDS] sp=" + b.apply(sp) + " divider=" + sp.getDividerLocation());
            log.info("[M36][BOUNDS]   top=" + b.apply(sp.getTopComponent()));
            Container top = sp.getTopComponent() instanceof Container ? (Container) sp.getTopComponent() : null;
            if (top != null) {
                Component[] tc = top.getComponents();
                for (int i = 0; i < tc.length; i++) {
                    log.info("[M36][BOUNDS]   top.child[" + i + "]=" + b.apply(tc[i]));
                    if (tc[i] instanceof Container) {
                        Component[] dc = ((Container) tc[i]).getComponents();
                        for (int j = 0; j < dc.length; j++) {
                            log.info("[M36][BOUNDS]   top.child[" + i + "].child[" + j + "]=" + b.apply(dc[j]));
                            if (dc[j] instanceof Container) {
                                Component[] ec = ((Container) dc[j]).getComponents();
                                for (int k = 0; k < ec.length; k++) {
                                    log.info("[M36][BOUNDS]   top.child[" + i + "].child[" + j + "].child[" + k + "]=" + b.apply(ec[k]));
                                    if (ec[k] instanceof JScrollPane) {
                                        JScrollPane sp2 = (JScrollPane) ec[k];
                                        log.info("[M36][BOUNDS]     scrollpane viewport=" + (sp2.getViewport() != null
                                                ? b.apply(sp2.getViewport()) + " vsbV=" + (sp2.getVerticalScrollBar() != null && sp2.getVerticalScrollBar().isVisible())
                                                : "null"));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            log.info("[M36][BOUNDS]   bottom=" + b.apply(sp.getBottomComponent()));
            Container bottom = sp.getBottomComponent() instanceof Container ? (Container) sp.getBottomComponent() : null;
            if (bottom != null) {
                Component[] bc = bottom.getComponents();
                for (int i = 0; i < bc.length; i++) {
                    log.info("[M36][BOUNDS]   bottom.child[" + i + "]=" + b.apply(bc[i]));
                    if (bc[i] instanceof Container) {
                        Component[] dc = ((Container) bc[i]).getComponents();
                        for (int j = 0; j < dc.length; j++) dumpDeep(dc[j], 2, "bottom.child[" + i + "].child[" + j + "]", log);
                    }
                }
            }
            if (bottom != null) scanTextViews(bottom, "bottom", log);
        } catch (Throwable e) {
            log.info("[M36][BOUNDS] dump failed: " + e.getMessage());
        }
    }

    private static void dumpDeep(Component c, int depth, String path, Logger log) {
        if (c == null || depth > 12) return;
        String extra = c instanceof JSplitPane ? " div=" + ((JSplitPane) c).getDividerLocation() + " split(" + ((JSplitPane) c).getOrientation() + ")" : "";
        log.info("[M36][BOUNDS]   " + path + "=" + c.getClass().getSimpleName() + "@" + System.identityHashCode(c) + " bounds=" + c.getBounds() + extra);
        if (c instanceof Container) {
            int idx = 0;
            for (Component d : ((Container) c).getComponents()) {
                dumpDeep(d, depth + 1, path + ".child[" + idx + "]", log);
                idx++;
            }
        }
    }

    private static void scanTextViews(Component c, String path, Logger log) {
        if (c == null) return;
        String cls = c.getClass().getName();
        if (c instanceof JScrollPane || cls.contains("ConsoleView") || cls.contains("EditorImpl") || c instanceof javax.swing.text.JTextComponent) {
            log.info("[M36][BOUNDS][TEXT] " + path + " " + cls.substring(cls.lastIndexOf('.') + 1) + " bounds=" + c.getBounds() + " pref=" + c.getPreferredSize());
            if (c instanceof JScrollPane) {
                JScrollPane jsp = (JScrollPane) c;
                log.info("[M36][BOUNDS][TEXT]   viewport=" + (jsp.getViewport() != null ? jsp.getViewport().getBounds().toString() : "null")
                        + " vsbV=" + (jsp.getVerticalScrollBar() != null && jsp.getVerticalScrollBar().isVisible()));
                if (jsp.getViewport() != null) scanTextViews(jsp.getViewport().getView(), path + ".view", log);
            }
        }
        if (c instanceof Container) {
            int idx = 0;
            for (Component d : ((Container) c).getComponents()) {
                scanTextViews(d, path + ".child[" + idx + "]", log);
                idx++;
            }
        }
    }

    /** M36.5：在指定控制台上执行 SQL。 */
    private static void executeOnConsole(
            @NotNull Project project,
            Object console,
            @NotNull Editor editor,
            Object scriptModel,
            Object execOption,
            Object ui,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        lastReportedError = null;
        consoleErrorBaseline = consoleHistoryLength(console);
        try {
            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);
            Method execute = consoleClass.getMethod("executeQueries", Editor.class, Class.forName("com.intellij.database.script.ScriptModel"), execOption.getClass());

            final int generation = executionGeneration;
            LOG.info("[M36] executeOnConsole begin (build=" + BUILD_STAMP + ")");
            DumbService.getInstance(project).runWhenSmart(() -> {
                if (generation != executionGeneration) {
                    LOG.warn("[M36] execution submit cancelled (generation " + generation + " != " + executionGeneration + ")");
                    return;
                }
                try {
                    Object consoleVf = consoleClass.getMethod("getVirtualFile").invoke(console);
                    if (consoleVf instanceof VirtualFile) {
                        VirtualFile cvf = (VirtualFile) consoleVf;
                        String consoleText = editor.getDocument().getText();
                        WriteAction.run(() -> {
                            Document fileDoc = FileDocumentManager.getInstance().getDocument(cvf);
                            if (fileDoc != null && !fileDoc.getText().equals(consoleText)) {
                                fileDoc.setText(consoleText);
                                PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                            } else if (fileDoc != null) {
                                PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                            } else {
                                try {
                                    cvf.setBinaryContent(consoleText.getBytes(StandardCharsets.UTF_8));
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                            }
                        });
                        LOG.info("[M36] console doc synced+committed to vf before execution (" + cvf.getName() + ")");
                    }
                } catch (Throwable e) {
                    LOG.warn("[M36] sync console doc to vf FAILED: " + e.getMessage());
                }
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    final boolean[] submittedRef = {false};
                    try {
                        ReadAction.compute(() -> {
                            VirtualFile modelVf = null;
                            try {
                                modelVf = FileDocumentManager.getInstance().getFile(editor.getDocument());
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            if (modelVf == null) {
                                try {
                                    Object cv = consoleClass.getMethod("getVirtualFile").invoke(console);
                                    modelVf = cv instanceof VirtualFile ? (VirtualFile) cv : null;
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                            }
                            PsiFile vfPsi = modelVf != null ? PsiManager.getInstance(project).findFile(modelVf) : null;
                            java.util.function.Function<Object, String> statementsInfo = m -> {
                                try {
                                    Object st = m.getClass().getMethod("statements").invoke(m);
                                    List<?> l = st instanceof Iterable ? new ArrayList<Object>((java.util.Collection<?>) toList((Iterable<?>) st)) : null;
                                    return "count=" + (l != null ? l.size() : "null");
                                } catch (Throwable ex) {
                                    return "FAILED:" + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                                }
                            };
                            Object freshModel = null;
                            if (vfPsi != null) {
                                try {
                                    Object facade = Class.forName("com.intellij.sql.psi.SqlPsiFacade")
                                            .getMethod("getInstance", Project.class).invoke(null, project);
                                    freshModel = facade.getClass().getMethod("createScriptModel", PsiFile.class).invoke(facade, vfPsi);
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                            }
                            if (freshModel != null && statementsInfo.apply(freshModel).startsWith("count=0")) {
                                LOG.warn("[M36] vf PSI model has 0 statements, fallback to console scriptModel");
                                freshModel = null;
                            }
                            if (freshModel == null) {
                                try {
                                    freshModel = consoleClass.getMethod("getScriptModel").invoke(console);
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                if (freshModel == null) freshModel = scriptModel;
                            }
                            if (freshModel != scriptModel) {
                                preparedScriptModel = freshModel;
                                if (sharedConsole == console) sharedScriptModel = freshModel;
                            }
                            LOG.info("[M36] executing model: class=" + freshModel.getClass().getSimpleName()
                                    + ", stmts=" + statementsInfo.apply(freshModel)
                                    + ", vfPsiText tail=..." + (vfPsi != null && vfPsi.getText() != null
                                    ? (vfPsi.getText().replace("\n", " ").length() > 30 ? vfPsi.getText().replace("\n", " ").substring(vfPsi.getText().replace("\n", " ").length() - 30) : vfPsi.getText().replace("\n", " "))
                                    : ""));
                            final PsiFile vfPsiFinal = vfPsi;
                            final Editor editorFinal = editor;
                            final Object execOptionFinal = execOption;
                            final boolean[] nativeRunRef = {false};
                            if (vfPsi != null) {
                                Class<?> providerClass = Class.forName(CONSOLE_PROVIDER_CLASS);
                                Method findScriptModel = providerClass.getMethod("findScriptModel", PsiFile.class, com.intellij.psi.PsiElement.class, Editor.class, execOption.getClass());
                                final Method[] doRunMethodRef = {null};
                                try {
                                    doRunMethodRef[0] = providerClass.getMethod("doRunQueryInConsole", consoleClass, Class.forName("com.intellij.database.console.JdbcConsoleProvider$Info"));
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                if (doRunMethodRef[0] == null) {
                                    LOG.warn("[M36] native doRunQueryInConsole method not found, fallback");
                                } else {
                                    ApplicationManager.getApplication().invokeLater(() -> {
                                        if (generation != executionGeneration) {
                                            LOG.warn("[M36] execution submit cancelled at EDT (generation " + generation + " != " + executionGeneration + ")");
                                            return;
                                        }
                                        com.intellij.openapi.Disposable disposable = (console instanceof com.intellij.openapi.Disposable)
                                                ? (com.intellij.openapi.Disposable) console : (com.intellij.openapi.Disposable) () -> {};
                                        try {
                                            TransactionGuard.getInstance().submitTransactionLater(disposable, () -> {
                                                try {
                                                    Object info = findScriptModel.invoke(null, vfPsiFinal, vfPsiFinal, editorFinal, execOptionFinal);
                                                    if (info == null) {
                                                        LOG.warn("[M36] native findScriptModel returned null (nothing to run?)");
                                                        ApplicationManager.getApplication().invokeLater(() -> {
                                                            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.noStatement"));
                                                        }, ModalityState.any());
                                                        return;
                                                    }
                                                    LOG.info("[M36] native findScriptModel OK, doRunQueryInConsole");
                                                    doRunMethodRef[0].invoke(null, console, info);
                                                    LOG.info("[M36] native query SUBMITTED (starting result watch)");
                                                    afterSubmitted(project, ui, console, onResultsReady, onError, LOG);
                                                } catch (Throwable t) {
                                                    Throwable cause = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
                                                    LOG.warn("[M36] native run chain FAILED: " + cause.getClass().getName() + ": " + cause.getMessage()
                                                            + "\n    " + stackTrace(cause), cause);
                                                    ApplicationManager.getApplication().invokeLater(() -> {
                                                        if (onError != null) onError.accept(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                                                    }, ModalityState.any());
                                                }
                                            });
                                        } catch (Throwable t) {
                                            LOG.warn("[M36] submitTransactionLater FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                                        }
                                    }, ModalityState.any());
                                    nativeRunRef[0] = true;
                                }
                            }
                            if (!nativeRunRef[0]) {
                                execute.invoke(console, editor, freshModel, execOption);
                                submittedRef[0] = true;
                            }
                            return null;
                        });
                        if (submittedRef[0]) {
                            LOG.info("[M36] executeQueries invoked OK (fallback path, submitted synchronously)");
                        } else {
                            LOG.info("[M36] native transaction posted (NOT submitted yet - watch starts at submit)");
                        }
                    } catch (Exception e) {
                        LOG.warn("[M36] executeQueries FAILED: " + e.getMessage(), e);
                        Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
                        String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                        final String fMsg = msg;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (onError != null) onError.accept(fMsg);
                        }, ModalityState.any());
                        return;
                    }
                    if (submittedRef[0]) {
                        afterSubmitted(project, ui, console, onResultsReady, onError, LOG);
                    }
                });
            });
        } catch (Throwable e) {
            LOG.warn("[M36] executeOnConsole FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            if (onError != null) onError.accept(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        }
    }

    private static String stackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = t.getStackTrace();
        int n = Math.min(st.length, 8);
        for (int i = 0; i < n; i++) sb.append(st[i].toString()).append("\n    at ");
        return sb.toString();
    }

    /** M36.5.22：真实提交后的统一收尾。 */
    private static void afterSubmitted(
            @NotNull Project project,
            Object ui,
            Object console,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            Logger LOG) {
        swapGeneration++;
        ApplicationManager.getApplication().invokeLater(() -> {
            List<Content> pre = resultContents(ui);
            Set<String> preExistingNames = pre.stream().map(Content::getDisplayName).collect(Collectors.toSet());
            LOG.info("[M36] result watch started (pre-existing-names=" + preExistingNames + ", ids=" + pre.stream().map(System::identityHashCode).toList() + ")");
            if (ui != null && onError != null) {
                final Object uiForWatch = ui;
                final Consumer<String> hintOnError = onError;
                final Object consoleForWatch = console;
                Timer watch = new Timer(8000, null);
                watch.addActionListener(e -> {
                    watch.stop();
                    boolean newTab = resultContents(uiForWatch).stream().anyMatch(c -> !preExistingNames.contains(c.getDisplayName()));
                    boolean namespaceOk;
                    try {
                        Object ns = consoleForWatch.getClass().getMethod("getCurrentNamespace").invoke(consoleForWatch);
                        namespaceOk = ns != null && !ns.toString().isBlank();
                    } catch (Throwable ignored) {
                        namespaceOk = true;
                    }
                    if (!newTab && !namespaceOk) {
                        LOG.info("[M36] no NEW result tab AND no namespace within 8s - credential/driver hint");
                        final String hint = MpSqlVisualizerBundle.message("execute.credential.hint");
                        hintOnError.accept(hint);
                    } else {
                        LOG.info("[M36] result/layout OK within 8s (newTab=" + newTab + ", namespaceOk=" + namespaceOk + ") - no hint");
                    }
                });
                watch.setRepeats(false);
                watch.start();
            }
            if (onResultsReady != null) {
                JComponent top = preparedTopBlock;
                JComponent liveResult = null;
                try {
                    Object c = ui != null ? ui.getClass().getMethod("getComponent").invoke(ui) : null;
                    liveResult = c instanceof JComponent ? (JComponent) c : null;
                } catch (Throwable ignored) {
                    // ignore
                }
                if (liveResult == null) liveResult = preparedResultComponent;
                if (top != null && liveResult != null) {
                    JComponent full = swapResultIntoPersistentConsole(preparedFullComponent, liveResult, top, LOG);
                    onResultsReady.accept(full);
                    clearConsolePrompt(console, LOG);
                }
            }
            if (ui != null) {
                Set<String> names = preExistingNames;
                startResultTabRetry(project, ui, onError, names);
            }
        }, ModalityState.any());
    }

    /** 读取 RunnerLayoutUi 当前全部结果内容（排除 Output/输出）。 */
    public static List<Content> resultContents(Object ui) {
        if (ui == null) return List.of();
        Object all;
        try {
            all = ui.getClass().getMethod("getContents").invoke(ui);
        } catch (Throwable ignored) {
            return List.of();
        }
        if (!(all instanceof Content[])) return List.of();
        Content[] arr = (Content[]) all;
        List<Content> result = new ArrayList<>();
        for (Content c : arr) if (!isOutputContent(c)) result.add(c);
        return result;
    }

    private static boolean isOutputContent(Content c) {
        return c.getDisplayName().equalsIgnoreCase("Output") || c.getDisplayName().equalsIgnoreCase("输出");
    }

    /** M36.5.22b：结果 Tab 选择判定（纯函数）。 */
    public static Content pickResultTab(Content[] contents, Set<String> preExistingNames, int attempts) {
        Content newOne = null;
        for (Content c : contents) {
            if (!isOutputContent(c) && !preExistingNames.contains(c.getDisplayName())) { newOne = c; break; }
        }
        if (newOne != null) return newOne;
        Content staleOne = null;
        for (Content c : contents) {
            if (!isOutputContent(c)) { staleOne = c; break; }
        }
        return (staleOne != null && attempts >= REUSE_ACCEPT_TICKS) ? staleOne : null;
    }

    /** M36.5.44：旧 Tab 复用接受的 tick 数。 */
    public static final int REUSE_ACCEPT_TICKS = 4;

    /** 结果 Tab 激活重试。 */
    private static void startResultTabRetry(
            @NotNull Project project,
            Object ui,
            @Nullable Consumer<String> onError,
            Set<String> preExistingNames,
            int generation) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        final javax.swing.Timer[] timerHolder = {null};
        final int[] attempts = {0};
        ActionListener listener = e -> {
            attempts[0]++;
            if (generation != swapGeneration) {
                timerHolder[0].stop();
                LOG.info("[M36] result tab retry cancelled (generation " + generation + " != " + swapGeneration + ")");
                return;
            }
            String err = extractConsoleError(sharedConsole != null ? sharedConsole : preparedConsole, LOG);
            if (err != null && !err.equals(lastReportedError)) {
                timerHolder[0].stop();
                lastReportedError = err;
                LOG.info("[M36] early console error detected (attempts=" + attempts[0] + "): " + (err.length() > 120 ? err.substring(0, 120) : err));
                final String fErr = err;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (onError != null) onError.accept(fErr);
                }, ModalityState.any());
                return;
            }
            Content[] contents;
            try {
                Object c = ui.getClass().getMethod("getContents").invoke(ui);
                contents = c instanceof Content[] ? (Content[]) c : new Content[0];
            } catch (Throwable ignored) {
                contents = new Content[0];
            }
            List<String> all = Arrays.stream(contents).map(Content::getDisplayName).collect(Collectors.toList());
            List<Integer> ids = Arrays.stream(contents).map(System::identityHashCode).collect(Collectors.toList());
            Content result = pickResultTab(contents, preExistingNames, attempts[0]);
            if (result != null && preExistingNames.contains(result.getDisplayName())) {
                LOG.info("[M36] result tab reused (name=" + result.getDisplayName() + ") - accepting existing tab");
            }
            LOG.info("[M36] retry#" + attempts[0] + " contents=" + all + ", ids=" + ids + ", select=" + (result != null ? result.getDisplayName() : "null") + ", preExisting=" + preExistingNames);
            if (result != null) {
                timerHolder[0].stop();
                try {
                    Method select = ui.getClass().getMethod("selectAndFocus", Content.class, Boolean.TYPE, Boolean.TYPE);
                    select.invoke(ui, result, false, true);
                } catch (Throwable ex) {
                    LOG.warn("[M36] select result tab FAILED: " + ex.getMessage());
                }
                refreshResultsComponent(ui, LOG, true, generation, onError);
            } else {
                boolean dumb = false;
                try { dumb = DumbService.isDumb(project); } catch (Throwable ignored) { /* ignore */ }
                int limit = dumb ? 100 : 40;
                if (attempts[0] >= limit) {
                    timerHolder[0].stop();
                    LOG.info("[M36] result tab not appeared after " + attempts[0] + " attempts (dumb=" + dumb + ")");
                    String realError = extractConsoleError(sharedConsole != null ? sharedConsole : preparedConsole, LOG);
                    String hint = realError != null ? realError : MpSqlVisualizerBundle.message(dumb ? "execute.indexing.hint" : "execute.credential.hint");
                    final String fHint = hint;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (onError != null) onError.accept(fHint);
                    }, ModalityState.any());
                }
            }
        };
        Timer timer = new Timer(400, listener);
        timerHolder[0] = timer;
        timer.start();
    }

    /** 结果 Tab 激活重试（使用当前 swapGeneration 作为监听代际）。 */
    private static void startResultTabRetry(
            @NotNull Project project,
            Object ui,
            @Nullable Consumer<String> onError,
            Set<String> preExistingNames) {
        startResultTabRetry(project, ui, onError, preExistingNames, swapGeneration);
    }

    /** M36.5：读取 last-used 数据源名。 */
    private static String lastRunnerName(Project project) {
        try {
            return com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue(LAST_RUNNER_KEY);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** M36.5：记录 last-used 数据源名。 */
    private static void rememberRunnerName(Project project, String name) {
        try {
            com.intellij.ide.util.PropertiesComponent.getInstance(project).setValue(LAST_RUNNER_KEY, name);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 反射调用 DatabaseRunners.getAttachDataSourceRunners。 */
    private static List<Object> getAttachDataSourceRunners(PsiFile psiFile, String sql, com.intellij.util.Consumer<Object> sessionConsumer) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            Class<?> clazz = Class.forName(RUNNERS_CLASS);
            Method method = clazz.getMethod("getAttachDataSourceRunners", PsiFile.class, String.class, com.intellij.util.Consumer.class);
            Object result = method.invoke(null, psiFile, sql, sessionConsumer);
            if (result instanceof List) {
                List<Object> list = new ArrayList<>();
                for (Object o : (List<?>) result) if (o != null) list.add(o);
                return list;
            }
            return List.of();
        } catch (Throwable e) {
            LOG.warn("[M36] getAttachDataSourceRunners reflection FAILED: " + e, e);
            return List.of();
        }
    }

    /** 反射获取 Runner 显示名称。 */
    private static String getRunnerName(Object runner) {
        try {
            Method method = runner.getClass().getMethod("getDisplayName");
            method.setAccessible(true);
            Object r = method.invoke(runner);
            return r instanceof String ? (String) r : "Unknown";
        } catch (Throwable ignored) {
            return "Unknown";
        }
    }

    /** 反射获取 Runner 图标。 */
    private static javax.swing.Icon getRunnerIcon(Object runner) {
        try {
            Method method = runner.getClass().getMethod("getIcon");
            method.setAccessible(true);
            Object r = method.invoke(runner);
            return r instanceof javax.swing.Icon ? (javax.swing.Icon) r : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射获取 Runner.shouldBeChosenIfAlone。 */
    private static boolean shouldBeChosenIfAlone(Object runner) {
        try {
            Method method = runner.getClass().getMethod("shouldBeChosenIfAlone");
            method.setAccessible(true);
            Object r = method.invoke(runner);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 反射调用 Runner.run。 */
    private static void runRunner(Object runner) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            Method method = runner.getClass().getMethod("run");
            method.setAccessible(true);
            method.invoke(runner);
            LOG.info("[M36] runRunner invoked OK");
        } catch (Throwable e) {
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            LOG.warn("[M36] runRunner FAILED: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
            Project project = ProjectManager.getInstance().getOpenProjects().length > 0 ? ProjectManager.getInstance().getOpenProjects()[0] : null;
            if (project == null) return;
            Messages.showErrorDialog(project, MpSqlVisualizerBundle.message("execute.datasource.failed", cause.getMessage() != null ? cause.getMessage() : cause.toString()), MpSqlVisualizerBundle.message("execute.title"));
        }
    }

    /** 弹窗展示 Runner 列表。 */
    private static void showRunnerPopup(Project project, List<Object> runners, Consumer<Object> callback) {
        List<RunnerItem> items = new ArrayList<>();
        for (Object r : runners) items.add(new RunnerItem(r, getRunnerName(r), getRunnerIcon(r)));
        com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem> step =
                new com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem>(
                        MpSqlVisualizerBundle.message("execute.datasource.select"), items) {
                    @Override
                    public String getTextFor(RunnerItem value) { return value.name; }
                    @Override
                    public javax.swing.Icon getIconFor(RunnerItem value) { return value.icon; }
                    @Override
                    public com.intellij.openapi.ui.popup.PopupStep<?> onChosen(RunnerItem selectedValue, boolean finalChoice) {
                        Logger.getInstance(DataSourceSelector.class).info("[M36] popup onChosen: " + (selectedValue != null ? selectedValue.name : "null"));
                        callback.accept(selectedValue != null ? selectedValue.runner : null);
                        return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE;
                    }
                };
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step).showInFocusCenter();
    }

    /** 弹窗列表项（Runner 包装）。 */
    private static final class RunnerItem {
        final Object runner;
        final String name;
        final javax.swing.Icon icon;
        RunnerItem(Object runner, String name, javax.swing.Icon icon) {
            this.runner = runner;
            this.name = name;
            this.icon = icon;
        }
    }

    /** 临时 PsiFile 持有。 */
    private static final class TempPsiFile {
        final VirtualFile vf;
        final PsiFile psi;
        TempPsiFile(VirtualFile vf, PsiFile psi) { this.vf = vf; this.psi = psi; }
    }

    /** M36.5.7：编辑器块补 UiDataProvider（原生 ▶ 执行动作数据上下文）。 */
    private static final class EditorDataPanel extends JBPanel<EditorDataPanel> implements UiDataProvider {
        private final Project project;
        private final Editor editorForData;
        private final VirtualFile vfForData;
        private final Object consoleForData;
        private final String clientKeyName;
        private final PsiFile[] psiForData;

        EditorDataPanel(Project project, Editor editorForData, VirtualFile vfForData, Object consoleForData, String clientKeyName, PsiFile[] psiForData) {
            super(new BorderLayout());
            this.project = project;
            this.editorForData = editorForData;
            this.vfForData = vfForData;
            this.consoleForData = consoleForData;
            this.clientKeyName = clientKeyName;
            this.psiForData = psiForData;
        }

        @Override
        public void uiDataSnapshot(@NotNull DataSink sink) {
            sink.set(CommonDataKeys.PROJECT, project);
            sink.set(CommonDataKeys.EDITOR, editorForData);
            sink.set(CommonDataKeys.VIRTUAL_FILE, vfForData);
            sink.lazy(CommonDataKeys.PSI_FILE, () -> {
                if (psiForData[0] != null && psiForData[0].isValid()) return psiForData[0];
                try {
                    return ReadAction.compute(() -> {
                        PsiFile f = PsiManager.getInstance(project).findFile(vfForData);
                        psiForData[0] = f;
                        return f;
                    });
                } catch (Throwable ignored) {
                    return null;
                }
            });
            if (clientKeyName != null) {
                try {
                    DataKey<Object> ck = (DataKey<Object>) Class.forName(
                            "com.intellij.database.console.client.SessionClientHolder")
                            .getField("CLIENT_KEY").get(null);
                    sink.set(ck, (Object) consoleForData);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** 创建临时 .sql 文件。 */
    private static TempPsiFile createTempPsiFile(Project project, String sql) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            File ioFile = projectExecFile(project, sql);
            if (ioFile == null) return null;
            try (AccessToken ignored = SlowOperations.allowSlowOperations(SlowOperations.GENERIC)) {
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
                if (vf == null) return null;
                mapDialectBeforePsi(project, vf, LOG);
                PsiFile psi = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
                if (psi == null) return null;
                return new TempPsiFile(vf, psi);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** M36.5.12：项目内执行文件路径。 */
    private static File projectExecFile(Project project, String sql) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        String base = project.getBasePath();
        if (base == null) return null;
        File dir = new File(base, ".idea" + File.separator + SqlLogAutoSaver.DATA_DIR_NAME + File.separator + "exec");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "mpsql-exec.sql");
        VirtualFile existing = LocalFileSystem.getInstance().findFileByIoFile(f);
        if (existing != null) {
            boolean updated = false;
            try {
                WriteAction.run(() -> {
                    Document fileDoc = FileDocumentManager.getInstance().getDocument(existing);
                    if (fileDoc != null && !fileDoc.getText().equals(sql)) {
                        fileDoc.setText(sql);
                        PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                    } else if (fileDoc != null) {
                        PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                    } else {
                        try {
                            existing.setBinaryContent(sql.getBytes(StandardCharsets.UTF_8));
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                });
                updated = true;
            } catch (Throwable ignored) {
                updated = false;
            }
            if (updated) {
                LOG.info("[M36] exec file updated via VFS (no cache conflict): " + f.getName());
                return f;
            }
        }
        try {
            com.intellij.openapi.util.io.FileUtil.writeToFile(f, sql);
        } catch (Throwable ignored) {
            // ignore
        }
        return f;
    }

    /** M36.5.3：临时文件方言预映射（MySQL）。 */
    private static void mapDialectBeforePsi(Project project, VirtualFile vf, Logger LOG) {
        try {
            Class<?> mappingsClass = Class.forName("com.intellij.sql.dialects.SqlDialectMappings");
            Object mappings = mappingsClass.getMethod("getInstance", Project.class).invoke(null, project);
            List<?> values = null;
            try {
                Object v = mappingsClass.getMethod("getAvailableValues").invoke(mappings);
                values = v instanceof List ? (List<?>) v : null;
            } catch (Throwable ignored) {
                // ignore
            }
            Object mysql = null;
            if (values != null) {
                for (Object it : values) {
                    if (it instanceof Language && ((Language) it).getID().equalsIgnoreCase("MySQL")) { mysql = it; break; }
                }
            }
            if (mysql != null) {
                mappingsClass.getMethod("setMapping", VirtualFile.class, Object.class).invoke(mappings, vf, mysql);
                LOG.info("[M36] dialect pre-mapped to MySQL for " + vf.getName());
            } else {
                LOG.warn("[M36] MySQL language dialect not found in SqlDialectMappings (fallback to pusher)");
            }
        } catch (Throwable e) {
            LOG.warn("[M36] dialect pre-mapping skipped: " + e.getMessage());
        }
    }

    /** 打开 Database 工具窗口。 */
    private static void openDatabaseToolWindow(Project project) {
        try {
            ToolWindowManager.getInstance(project).getToolWindow("Database").show();
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
