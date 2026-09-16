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
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
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
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
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
    private static final String BUILD_STAMP = "M36.8-exec-silent-noop";

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

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

    /** T-PreviewFlash：mpsql-exec.sql 的 vf+psi 配对缓存（路径稳定，vf 有效即复用，吸收每次预览的 VFS refresh + findFile 慢操作）。
     *  T-ExecSlowOps F3：{@link #prewarmExecTempFile} 后台线程成为第二写方后须 volatile 跨线程可见。 */
    private static volatile TempPsiFile cachedTempPsiFile = null;
    /** T-PreviewExecDs-02 F1：缓存归属项目名（跨项目拒绝诊断用，与 cachedTempPsiFile 同生命周期）。 */
    private static volatile String cachedTempPsiProjectName = null;

    /**
     * T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（stage 标识或异常摘要），
     * 成功时清空——供 chooseAndPrepare 的 onError 文案携带，用户截图反馈的笼统
     * "Failed to create temporary SQL file" 自此可归因。
     */
    private static volatile String lastTempFileFailure = null;

    /** T-PreviewFlash：已 setMapping 的 vf（同文件幂等短路，避免每次预览重设方言触发 push all dumb）。
     * T-ExecSlowOps F3：BGT 预热链也会读写，volatile 保证跨线程可见。 */
    private static volatile VirtualFile dialectMappedVf = null;

    /** T-ExecSlowOps F2：exec 预热进行中标记（并发预览只跑一条真实链，其余直接返回）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean execPrewarmInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** M36.5.6：结果区换入回调。 */
    private static Consumer<JComponent> pendingOnConsole = null;

    /** M36.5.9：最近一次换入宿主的结果区组件实例。 */
    private static Component lastLiveResultSwapped = null;

    /** M36.5.52：最近一次已上报的 SQL 错误文本——早检去重用。 */
    private static String lastReportedError = null;

    /** M36.5.57：本次执行开始时 console history 的长度基线——早检只扫<b>本次执行之后</b>新增的
     * 文本，忽略 history 里的旧错误残留（否则正常 SQL 会把上个失败的错误"带过来"，用户实测）。
     * T-PerfSweep2-01：internal 供 ConsoleErrorExtractTest 注入基线（生产路径仅 executeOnConsole 赋值）。 */
    public static int consoleErrorBaseline = 0;

    /** T-ExecSilentNoop：本次执行的提示已发出标记——8s 连接监控与 40 拍超时窗口共用，
     * 保证同一次执行只报一次提示（此前二者各按同一 key 报一次，用户见两次红字）。 */
    private static volatile boolean executionHintReported = false;

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
        // T-ExecCopyFix F2c：不清 cachedTempPsiFile——vf 失效守卫已兜底（下次命中前校验
        // isValid），内容覆盖写走 document 驱动不破坏 vf↔psi 配对；原实现每次预览切换清
        // 缓存，执行必落冷路径，击穿 T-PreviewFlash「跨点击吸收」的设计意图。
    }

    /**
     * T-Perf-16：面板关闭/项目关闭时释放单例滞留引用——单例的 prepared、shared、
     * pendingOnConsole 槽位强引用已关闭面板（pendingOnConsole 闭包捕获 SqlPreviewPanel 整棵
     * Swing 树）与原生控制台/编辑器/文档（持旧 Project 引用）。此前只有面板「清空」按钮走
     * clearPrepared，弹窗关闭/项目关闭路径无清理挂点 → 堆中滞留整棵面板树。
     */
    public static void releaseForPanel() {
        clearPrepared();
        dropSharedConsole();
        pendingOnConsole = null;
    }

    /**
     * M36.5：准备控制台（不执行）。
     * 数据源选择策略：唯一数据源/last-used 命中 → 静默；多数据源 → 原生弹窗（记住选择）；
     * 无数据源 → 预览路径静默 onError（不抢侧边栏），显式执行路径打开 Database 工具窗口 + onError。
     */
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
            // T-ExecCopyFix F2a：携带阶段原因（stage 标识/异常摘要），本地化文案——
            // 用户真机曾只见笼统英文 "Failed to create temporary SQL file"，无从归因
            LOG.warn("[M36] createTempPsiFile failed (reason=" + lastTempFileFailure + "), abort");
            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.tempFile.failed",
                    lastTempFileFailure != null ? lastTempFileFailure : "unknown"));
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
                    runRunner(project, matched, true, onError);
                    if (callback != null) callback.accept(true);
                    return;
                }
            }
        }

        if (runners.isEmpty()) {
            LOG.warn("[M36] runners empty (execute=" + executeAfterPrepare + ")");
            // T-DsCover：仅显式执行引导打开 Database 工具窗；预览默认准备静默——
            // 补全 M36.5 预览路径（onError=null）设计意图，避免每次双击预览抢占侧边栏。
            if (executeAfterPrepare) openDatabaseToolWindow(project);
            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.datasource.empty"));
            if (callback != null) callback.accept(false);
        } else if (runners.size() == 1 && shouldBeChosenIfAlone(runners.get(0))) {
            LOG.info("[M36] single runner, run directly");
            chosenRunnerName[0] = getRunnerName(runners.get(0));
            runRunner(project, runners.get(0), !executeAfterPrepare, onError);
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
                // 弹窗中用户刚做了主动选择，失败仍需模态引导（silent=false）
                if (runner != null) runRunner(project, runner, false, onError);
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

    /**
     * T-ExecSilentNoop F1a 探针：提交前的一次性可执行性预检 + 现场埋点。
     *
     * DBN 侧存在两条<b>全静默出口</b>（javap 实证，database-plugin.jar）：{@code chooseStatements}
     * 空选集且 {@code execOutside==1} 时既不消费 model 也不提示、直接 return；{@code beforeExecuteQueries}
     * 遇 {@code model.statements().isEmpty()} 或 {@code console.isValid()==false} 返回 false → 整段跳过。
     * 两者都无输出、无结果 Tab、无异常。此探针把这两个出口对应的现场量提前照出来，
     * 归因"查询没提交"具体撞的是哪一条，并据此决定是否走 {@code executeQueries(freshModel)} 兜底。
     * 纯反射 + try/catch，任何一步失败降级为 null，绝不阻断执行。
     *
     * @return null 表示"DBN 侧可执行（或探不到），按 native 链走"；
     *         非 null 表示"预检判定 DBN 侧会静默拒绝"，返回真实原因描述。
     */
    private static String execSilentProbe(Object console, Object freshModel, boolean demoted, String destDsName, Logger LOG) {
        Object isValid = null;
        try {
            isValid = console.getClass().getMethod("isValid").invoke(console);
        } catch (Throwable ignored) {
            // 探不到按 null 呈现
        }
        String stmts = "null";
        try {
            Object result = freshModel != null ? freshModel.getClass().getMethod("statements").invoke(freshModel) : null;
            if (result instanceof Iterable) {
                int n = 0;
                for (Object ignored : (Iterable<?>) result) n++;
                stmts = String.valueOf(n);
            }
        } catch (Throwable ignored) {
            // 探不到按 null 呈现
        }
        LOG.info("[M36][EXEC] probe start (console isValid=" + isValid + ", freshModel stmts=" + stmts + ", ds=" + destDsName + ")");
        if (demoted) {
            String reason = "freshModel demoted (vf PSI resolved 0 statements, fell back to console model)";
            LOG.info("[M36][EXEC] probe demoted -> " + reason);
            return reason;
        }
        return null;
    }

    /** M36.5.48：从 console 历史文档提取真实错误文本。
     * T-PerfSweep2-01：getCharsSequence + subSequence 片段读取——本函数被 600ms×8 拍与
     * 400ms×40/100 拍两个定时器<b>逐拍</b>调用，此前 doc.getText() 每拍整串拷贝全史
     * （history 随长会话涨到 MB 级后是 EDT 上唯一可感知的 O(全史) 重复分配）。
     * 只物化 baseline 之后的切片；getCharsSequence 与 getText 同属 Document 接口，
     * 反射可达性一致，且免去 BadLocationException 面。 */
    @Nullable
    public static String extractConsoleError(Object console, Logger LOG) {
        if (console == null) return null;
        try {
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return null;
            Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
            if (historyViewer == null) return null;
            Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
            if (doc == null) return null;
            Object seqObj = doc.getClass().getMethod("getCharsSequence").invoke(doc);
            if (!(seqObj instanceof CharSequence)) return null;
            CharSequence seq = (CharSequence) seqObj;
            // M36.5.57：只扫本次执行之后新增的文本（基线截断）——忽略 history 残留的旧错误。
            // baseline 可能因 DBN 写入历史而增长；若文本长度 <= 基线说明无新增内容，return null。
            int baseline = consoleErrorBaseline;
            String text;
            if (baseline <= 0) {
                text = seq.toString();
            } else if (seq.length() > baseline) {
                text = seq.subSequence(baseline, seq.length()).toString();
            } else {
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
        // T-ExplainFirstNative：结果 Tab 名称集合逐拍快照——集合变化（空名占位改名/新 Tab 出现）
        // 说明结果内容更新，即使组件实例未变也要补一次换入重绘（首点网格晚于换入构建的兜底）
        @SuppressWarnings("unchecked")
        final Set<String>[] lastResultNames = new Set[1];
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
            Set<String> names = resultContents(ui).stream().map(Content::getDisplayName).collect(Collectors.toSet());
            boolean namesChanged = lastResultNames[0] != null && !names.equals(lastResultNames[0]);
            lastResultNames[0] = names;
            JComponent live = null;
            try {
                Object c = ui.getClass().getMethod("getComponent").invoke(ui);
                live = c instanceof JComponent ? (JComponent) c : null;
            } catch (Throwable ignored) {
                // ignore
            }
            boolean shouldSwap = live != null && (forceSwapIn && !swapped[0] || live != lastLiveResultSwapped || namesChanged);
            if (shouldSwap && live != null) {
                JComponent full = swapResultIntoPersistentConsole(sharedFullComponent, live, top, LOG);
                boolean changed = live != lastLiveResultSwapped;
                swapped[0] = true;
                lastLiveResultSwapped = live;
                sharedFullComponent = full;
                preparedFullComponent = full;
                Consumer<JComponent> cb = pendingOnConsole;
                if (cb != null) cb.accept(full);
                LOG.info("[M36] results refreshed (tick=" + ticks[0] + ", forced=" + forceSwapIn + ", instanceChanged=" + changed + ", namesChanged=" + namesChanged + ", gen=" + generation + ")");
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
                    // T-ExplainFirstNative：空名占位（DBN 先 add 后改名）换入必然过早——网格未构建，
                    // 交由 startResultTabRetry 在改名后 select+换入
                    if (name.isBlank()) return;
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
        // T-Perf-13：诊断探针降 debug——非 debug 模式零开销（此前每次执行一次 DataManager 解析
        // + 数十条反射 + info 刷屏；需要诊断时开 debug 恢复全部输出）
        if (!LOG.isDebugEnabled()) return;
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
                        LOG.debug("[M36] dataContext check: PROJECT=" + projectOk + ", EDITOR=" + editorOk
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
            // T-ReverseParenWira：attachConsole 包 WriteIntentReadAction（既非 plain ReadAction，
            // 亦非裸 EDT）——三轮踩坑收口：
            //   · T-PreviewDocLock2 F1 包 plain ReadAction 压读权限断言 → DBN attachConsole 底层
            //     new EditorImpl 的 setHighlighter 走 WriteIntentReadAction，平台锁层明文禁
            //     「plain ReadAction 内再开 WIRA」硬抛（IllegalStateException <- setHighlighter:1832
            //     <- ConsoleExecutionEditor.<init>），被 runCatching 吞成 InvocationTargetException:null
            //     -> 控制台从未挂载。
            //   · T-PreviewSlow F2 整段拆包还原裸 EDT → 修了 setHighlighter 嵌套，但 262 同一条构造链
            //     上 ConsoleExecutionEditor.<init>:54 → FileDocumentManager.getDocument 要求调用方已
            //     持读权限，裸 EDT 直调硬抛 RuntimeExceptionWithAttachments「Read access is allowed
            //     from inside read-action only」（真机堆栈实证），又被外层 runCatching 吞掉静默降级。
            //   · 本轮包 WIRA：隐含读权限满足 getDocument 断言；内部 setHighlighter 的 WIRA 属
            //     WIRA-in-WIRA 重入——平台只禁 plain ReadAction→WIRA，重入合法。同文件
            //     T-ExecCopyFix F2b createTempPsiFile 冷路径同款先例（Computable 单类型实参，
            //     243~262 交集——T-Wia262）。V-2 留 262 真机 idea.log 验证无新嵌套硬抛。
            Object console = WriteIntentReadAction.compute(
                (com.intellij.openapi.util.Computable<Object>) () -> {
                    try {
                        return attach.invoke(null, project, session, vf);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            LOG.info("[M36] attachConsole -> " + console);
            if (console == null) return;

            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);

            // 2. 执行用编辑器：按 console 文档反查（先于内嵌——编辑器随 console 创建即初始化）。
            // T-ReverseParenWira：单独为 getDocument 包 plain ReadAction（T-PreviewSlow F2 注释
            // 预先授权方案）——此句只取 attachConsole 已构建好的 console 文档，不 new EditorImpl、
            // 不触发 WIRA，plain ReadAction 即满足 262 读权限断言，无嵌套风险。
            Document document = ReadAction.compute(
                (com.intellij.openapi.util.ThrowableComputable<Document, Exception>) () -> {
                    Object documentObj = consoleClass.getMethod("getDocument").invoke(console);
                    return documentObj instanceof Document ? (Document) documentObj : null;
                });
            // T-Perf-13：EDT 上不再 repeat(20){Thread.sleep(20)} 忙等编辑器实例（最多 400ms 冻结）——
            // 首查即中（绝大多数：编辑器随 attachConsole 同步构造）或后台线程调用：同步短轮询（原语义）；
            // EDT 首查未命中：后台轮询线程 20ms 一查，命中后回 EDT 继续余下准备链（prepareAfterEditorResolved）。
            final EditorFactory editorFactory = EditorFactory.getInstance();
            final Document documentRef = document;
            java.util.function.Supplier<Editor> findEditor = () ->
                    documentRef == null ? null
                            : Arrays.stream(editorFactory.getEditors(documentRef)).findFirst().orElse(null);
            Editor firstEditor = findEditor.get();
            if (firstEditor != null || !ApplicationManager.getApplication().isDispatchThread()) {
                Editor editor = firstEditor;
                if (editor == null) {
                    for (int i = 0; i < 20 && editor == null; i++) {
                        editor = findEditor.get();
                        if (editor == null) {
                            try { Thread.sleep(20); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        }
                    }
                }
                LOG.info("[M36] editor=" + editor);
                if (editor == null) return;
                // M36.2：编辑器就绪回调（调用方注入插件补全上下文，与原生补全叠加）
                if (onEditor != null) onEditor.accept(editor);
                prepareAfterEditorResolved(project, vf, executeAfterPrepare, dataSourceName,
                        onConsole, onError, console, document, editor);
            } else {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    Editor editor = null;
                    for (int i = 0; i < 20 && editor == null; i++) {
                        editor = findEditor.get();
                        if (editor == null) {
                            try { Thread.sleep(20); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        }
                    }
                    final Editor resolved = editor;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        LOG.info("[M36] editor=" + resolved + " (async wait)");
                        if (resolved != null) {
                            if (onEditor != null) onEditor.accept(resolved);
                            try {
                                prepareAfterEditorResolved(project, vf, executeAfterPrepare, dataSourceName,
                                        onConsole, onError, console, document, resolved);
                            } catch (Throwable e) {
                                LOG.warn("[M36] prepareInConsole(editor async wait) FAILED: "
                                        + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                                Throwable cause = (e instanceof InvocationTargetException)
                                        ? ((InvocationTargetException) e).getTargetException() : e;
                                if (onError != null) {
                                    onError.accept(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                                }
                            }
                        }
                    }, ModalityState.any());
                });
            }
        } catch (Throwable e) {
            LOG.warn("[M36] prepareInConsole FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            // T-PreviewExecDs-03 F3：console 被清扫 dispose 的处置（prepare 链后段撞上时同款
            // 特判，见 prepareAfterEditorResolved onFailure）
            if (cause instanceof com.intellij.util.IncorrectOperationException
                    && cause.getMessage() != null && cause.getMessage().contains("already been disposed")) {
                LOG.warn("[M36] console disposed during prepareInConsole (swept), clear caches for retry: "
                        + cause.getMessage().substring(0, Math.min(120, cause.getMessage().length())));
                clearPrepared();
                dropSharedConsole();
                if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.console.disposed"));
            } else {
                if (onError != null) onError.accept(cause.getMessage() != null ? cause.getMessage() : cause.toString());
            }
        }
    }

    /**
     * M36.5：控制台准备链后段（T-Perf-13 从 prepareInConsole 抽出——编辑器实例就绪后执行：
     * scriptModel 装配 / 执行上下文缓存 / 原生控制台 UI 拼合内嵌 / 可选立即执行）。
     * EDT 编辑器异步等待路径会经此继续（异常处理与同步路径一致：warn + onError 面板内显示）。
     */
    private static void prepareAfterEditorResolved(
            @NotNull Project project,
            @NotNull VirtualFile vf,
            boolean executeAfterPrepare,
            @Nullable String dataSourceName,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<String> onError,
            Object console,
            Document document,
            Editor editor) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);

            // T-PreviewExecDs-03 F2：getScriptModel 前 isValid 预检 + 三分量诊断——attach 后
            // console 可能已被 getActiveConsoles 清扫（isValid=false，依赖 facade 数据源加载状态，
            // 见开发计划 §2.2 反编译实锤）。disposed 直接快速失败（等待无意义，F3 自愈清缓存）；
            // 未 disposed 但分量失效时，非 EDT 上有界重查等 facade 异步窗口，EDT 不阻塞直走
            //（getScriptModel 若撞上清扫由 F3 特判接管）。
            ConsoleValidity initialValidity = probeConsoleValidity(console, project, LOG);
            if (initialValidity != null && initialValidity.disposed) {
                LOG.warn("[M36] console DISPOSED right after attach: " + initialValidity);
                throw new IllegalStateException(MpSqlVisualizerBundle.message("execute.console.disposed"));
            }
            if (initialValidity != null && !initialValidity.valid()
                    && !ApplicationManager.getApplication().isDispatchThread()) {
                try {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        Thread.sleep(300);
                        ConsoleValidity v = probeConsoleValidity(console, project, LOG);
                        if (v != null && v.valid()) {
                            LOG.info("[M36] console valid after retry (attempt=" + (attempt + 1) + ")");
                        }
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
                LOG.warn("[M36] console still invalid after bounded retry: " + probeConsoleValidity(console, project, LOG));
            }

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
            // T-PreviewExecDs-03 F3：console 已被 getActiveConsoles 清扫（Disposer.register 撞
            // 已 dispose 父节点抛 IncorrectOperationException）→ 清单例缓存（否则下轮执行复用
            // dead 缓存继续失败）+ 可读文案，不再透平台异常长串。
            if (cause instanceof com.intellij.util.IncorrectOperationException
                    && cause.getMessage() != null && cause.getMessage().contains("already been disposed")) {
                LOG.warn("[M36] console disposed during prepare (swept by getActiveConsoles), clear caches for retry: "
                        + cause.getMessage().substring(0, Math.min(120, cause.getMessage().length())));
                clearPrepared();
                dropSharedConsole();
                if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.console.disposed"));
            } else {
                if (onError != null) onError.accept(cause.getMessage() != null ? cause.getMessage() : cause.toString());
            }
        }
    }

    /**
     * T-PreviewExecDs-03 F2：console 有效性的三分量快照（反编译实锤 {@code isValid} =
     * {@code !isDisposed && isViewValid && facade.findDataSource(dataSource.uniqueId) != null}）。
     * 任何一环为 false 即可能被 {@code getActiveConsoles} 清扫 dispose——日志/自愈判定共用。
     */
    public static final class ConsoleValidity {
        public final boolean disposed;
        public final Boolean viewValid;
        public final Boolean dsInFacade;

        ConsoleValidity(boolean disposed, Boolean viewValid, Boolean dsInFacade) {
            this.disposed = disposed;
            this.viewValid = viewValid;
            this.dsInFacade = dsInFacade;
        }

        public boolean valid() {
            return !disposed && !Boolean.FALSE.equals(viewValid) && !Boolean.FALSE.equals(dsInFacade);
        }

        @Override
        public String toString() {
            return "disposed=" + disposed + ", viewValid=" + viewValid + ", dsInFacade=" + dsInFacade + ", valid=" + valid();
        }
    }

    /** T-PreviewExecDs-03 F2：反射探测 console 三分量有效性（任一环节失败降级 null，绝不阻断）。 */
    @Nullable
    private static ConsoleValidity probeConsoleValidity(Object console, Project project, Logger LOG) {
        try {
            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);
            boolean disposed;
            try {
                // 243 平台仅 Disposable 版本（标 deprecated），无 Object 重载——cast + suppress
                disposed = (console instanceof com.intellij.openapi.Disposable)
                        ? Disposer.isDisposed((com.intellij.openapi.Disposable) console) : false;
            } catch (Throwable t) {
                disposed = false;
            }
            Boolean viewValid = null;
            try {
                Object v = consoleClass.getMethod("isViewValid").invoke(console);
                viewValid = v instanceof Boolean ? (Boolean) v : null;
            } catch (Throwable ignored) {
                // 探不到按 null 呈现
            }
            Boolean dsInFacade = null;
            try {
                Object ds = consoleClass.getMethod("getDataSource").invoke(console);
                if (ds != null) {
                    Object id = ds.getClass().getMethod("getUniqueId").invoke(ds);
                    if (id instanceof String) {
                        Object facade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                                .getMethod("getInstance", Project.class).invoke(null, project);
                        dsInFacade = facade.getClass().getMethod("findDataSource", String.class)
                                .invoke(facade, id) != null;
                    }
                }
            } catch (Throwable ignored) {
                // 探不到按 null 呈现
            }
            return new ConsoleValidity(disposed, viewValid, dsInFacade);
        } catch (Throwable e) {
            LOG.warn("[M36] probeConsoleValidity FAILED: " + e.getMessage());
            return null;
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
            log.debug("[M36] divider realign(force=" + force + "): topH=" + topH + " total=" + total + " → 0.6");
            sp.setDividerLocation(0.6);
            sp.revalidate();
            sp.repaint();
            dumpSplitBounds(sp, log);
        } else {
            log.debug("[M36] divider OK: topH=" + topH + " total=" + total + " (no change)");
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
                log.debug("[M36][OUTFILL] no output editor (EditorImpl$PanelWithFloatingToolbar) in result area; skip");
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
                        log.debug("[M36][OUTFILL] EMHSBL panel not found above output editor; ancestors=" + chainDesc);
                    }
                    continue;
                }
                Component child = t;
                for (Container c : chain) {
                    if (c instanceof JScrollPane) {
                        log.debug("[M36][OUTFILL] chain node is JScrollPane (" + c.getClass().getName() + "), keep its layout");
                    } else if (!(c.getLayout() instanceof FillTargetLayout)) {
                        String oldLayout = c.getLayout() != null ? c.getLayout().getClass().getName() : "null";
                        java.awt.Rectangle before = c.getBounds();
                        c.setLayout(new FillTargetLayout(child));
                        c.revalidate();
                        log.debug("[M36][OUTFILL] installed on " + c.getClass().getName() + " (was " + oldLayout + ") target=" + child.getClass().getSimpleName() + " before=" + before);
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
                Logger.getInstance(DataSourceSelector.class).debug("[M36][OUTFILL] layout: parent=" + parent.getClass().getSimpleName()
                        + " h=" + parent.getHeight() + " target(" + target.getClass().getSimpleName() + ") → " + avail);
            }
        }
    }

    /** M36.5.60：运行时组件边界取证。 */
    private static void dumpSplitBounds(JSplitPane sp, Logger log) {
        // T-Perf-13：取证 dump 降 debug 门控（非 debug 模式零开销）
        if (!log.isDebugEnabled()) return;
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
        // T-ExecSilentNoop：新执行亦重置提示去重标记——同一次执行只报一次提示。
        executionHintReported = false;
        // T-ExecSilentNoop：notSubmitted 提示携带本次目标数据源名（Kotlin 侧默认参 dsName = sharedDataSourceName）
        final String dsName = sharedDataSourceName;
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
                            // T-ExecDocSync T-EC-01：document 驱动写 + 同临界区落盘（saveDocument）——
                            // 执行前 console 手改未保存内容与磁盘收口，不留「已修改未保存」窗口
                            //（M36.5.20 的 commitDocument/PSI 对齐语义保留在 writeVfTextViaDocument 内）
                            writeVfTextViaDocument(project, cvf, consoleText);
                        });
                        LOG.info("[M36] console doc synced+committed+saved to vf before execution (" + cvf.getName() + ")");
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
                            boolean freshModelDemoted = false;
                            if (freshModel != null && statementsInfo.apply(freshModel).startsWith("count=0")) {
                                LOG.warn("[M36] vf PSI model has 0 statements, fallback to console scriptModel");
                                freshModel = null;
                                freshModelDemoted = true;
                            }
                            if (freshModel == null) {
                                try {
                                    freshModel = consoleClass.getMethod("getScriptModel").invoke(console);
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                if (freshModel == null) freshModel = scriptModel;
                                freshModelDemoted = true;
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
                            // T-ExecSilentNoop F1a：提交前预检——探测 DBN 会静默拒绝的现场。
                            // demoted 时（vf PSI 解析 0 语句回落 console model）说明准备窗口不稳
                            //（方言 push 未完成 / PSI 未对齐），优先走 executeQueries(freshModel) 兜底
                            // 而非原生链，把"静默不执行"降级为"明确执行/明确报错"。
                            // 保守起见只在 demoted 时强制降级；非 demoted 保持 native 链原行为，
                            // 交由提交后的回执判定（afterSubmitted/startResultTabRetry）兜底。
                            boolean demoteToFallback = freshModelDemoted;
                            execSilentProbe(console, freshModel, freshModelDemoted, dsName, LOG);
                            final PsiFile vfPsiFinal = vfPsi;
                            final Editor editorFinal = editor;
                            final Object execOptionFinal = execOption;
                            final boolean[] nativeRunRef = {false};
                            if (vfPsi != null && !demoteToFallback) {
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
                                                    afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, false);
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
                                LOG.info("[M36][EXEC] fallback path (native not used: " + (demoteToFallback ? "demoted" : "no vf psi or doRun missing") + "), executeQueries(freshModel)");
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
                        afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, false);
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

    /** M36.5.22：真实提交后的统一收尾。（T-ExecSilentNoop：dsName/demotedOverride 供提示三分流与去重） */
    private static void afterSubmitted(
            @NotNull Project project,
            Object ui,
            Object console,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            Logger LOG,
            String dsName,
            boolean demotedOverride) {
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
                    boolean newTab = resultContents(uiForWatch).stream().anyMatch(c ->
                            c.getDisplayName() != null && !c.getDisplayName().isBlank()
                                    && !preExistingNames.contains(c.getDisplayName()));
                    boolean namespaceOk;
                    try {
                        Object ns = consoleForWatch.getClass().getMethod("getCurrentNamespace").invoke(consoleForWatch);
                        namespaceOk = ns != null && !ns.toString().isBlank();
                    } catch (Throwable ignored) {
                        namespaceOk = true;
                    }
                    if (!newTab && !namespaceOk) {
                        // T-ExecSilentNoop：提交后 8s 回执——namespace 都出来了说明连接<b>已建立</b>、
                        // 探针显示未提交（freshModel 回落）则归因"未提交"而非凭据；否则才落到
                        // 凭据/驱动。与 40 拍窗口共用 executionHintReported 去重，同次执行只报一次。
                        if (!executionHintReported) {
                            executionHintReported = true;
                            String hint = demotedOverride
                                    ? MpSqlVisualizerBundle.message("execute.notSubmitted.hint", dsName != null ? dsName : "")
                                    : MpSqlVisualizerBundle.message("execute.credential.hint");
                            LOG.info("[M36] no NEW result tab AND no namespace within 8s (newTab=" + newTab + ", namespaceOk=" + namespaceOk
                                    + ", demotedOverride=" + demotedOverride + ") - hint=" + (demotedOverride ? "notSubmitted" : "credential"));
                            hintOnError.accept(hint);
                        }
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
                startResultTabRetry(project, ui, onError, names, dsName, demotedOverride);
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

    /**
     * M36.5.22b：结果 Tab 选择判定（纯函数）。
     *
     * <p>T-ExplainFirstNative：<b>空名占位不算结果</b>——DBN 首次执行先 add 空名 Content 再异步
     * 改名/建网格（262 真机 21:06:45 实证 {@code contentAdded: }），空名 ∉ 快照集合恒真会把占位当
     * 新结果第 1 拍选中并强制换入（网格尚未构建），事后无人补换入 → 结果滞留原生控制台。</p>
     */
    public static Content pickResultTab(Content[] contents, Set<String> preExistingNames, int attempts) {
        Content newOne = null;
        for (Content c : contents) {
            if (!isOutputContent(c) && !c.getDisplayName().isBlank()
                    && !preExistingNames.contains(c.getDisplayName())) { newOne = c; break; }
        }
        if (newOne != null) return newOne;
        Content staleOne = null;
        for (Content c : contents) {
            if (!isOutputContent(c) && !c.getDisplayName().isBlank()) { staleOne = c; break; }
        }
        return (staleOne != null && attempts >= REUSE_ACCEPT_TICKS) ? staleOne : null;
    }

    /** M36.5.44：旧 Tab 复用接受的 tick 数。 */
    public static final int REUSE_ACCEPT_TICKS = 4;

    /** 结果 Tab 激活重试。（T-ExecSilentNoop：dsName/demotedOverride 供 40 拍超时窗口提示三分流） */
    private static void startResultTabRetry(
            @NotNull Project project,
            Object ui,
            @Nullable Consumer<String> onError,
            Set<String> preExistingNames,
            int generation,
            String dsName,
            boolean demotedOverride) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        final javax.swing.Timer[] timerHolder = {null};
        final int[] attempts = {0};
        ActionListener listener = e -> {
            attempts[0]++;
            if (generation != swapGeneration) {
                timerHolder[0].stop();
                LOG.debug("[M36] result tab retry cancelled (generation " + generation + " != " + swapGeneration + ")");
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
            Content result = pickResultTab(contents, preExistingNames, attempts[0]);
            if (result != null && preExistingNames.contains(result.getDisplayName())) {
                LOG.info("[M36] result tab reused (name=" + result.getDisplayName() + ") - accepting existing tab");
            }
            // T-PerfSweep2-01：列表构建仅喂 LOG.debug——debug 关闭时不再逐拍白建两份列表
            //（400ms 定时器最长 100 拍，此前每拍 2 次 map 空转）。
            if (LOG.isDebugEnabled()) {
                List<String> all = Arrays.stream(contents).map(Content::getDisplayName).collect(Collectors.toList());
                List<Integer> ids = Arrays.stream(contents).map(System::identityHashCode).collect(Collectors.toList());
                LOG.debug("[M36] retry#" + attempts[0] + " contents=" + all + ", ids=" + ids + ", select="
                        + (result != null ? result.getDisplayName() : "null") + ", preExisting=" + preExistingNames);
            }
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
                    // T-ExecSilentNoop：40 拍超时窗口与 8s 监控共用 executionHintReported 去重。
                    // 真实错误优先（DBN 已把失败写进 history 才叫真失败/真超时）；无错误痕迹时
                    // 不再一律归因凭据——先看本次是否探到"未提交"（freshModel 回落，DBN 侧静默
                    // 拒绝），按情况给 notSubmitted / timeout / indexing 三种真实提示。
                    if (realError == null && !executionHintReported) {
                        executionHintReported = true;
                        String fallbackHint = dumb
                                ? MpSqlVisualizerBundle.message("execute.indexing.hint")
                                : (demotedOverride
                                        ? MpSqlVisualizerBundle.message("execute.notSubmitted.hint", dsName != null ? dsName : "")
                                        : MpSqlVisualizerBundle.message("execute.timeout.hint"));
                        LOG.info("[M36] result tab not appeared, no console error (dumb=" + dumb + ", demoted=" + demotedOverride + ") - hint via 40-tick window");
                        final String fHint = fallbackHint;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (onError != null) onError.accept(fHint);
                        }, ModalityState.any());
                    } else if (realError != null) {
                        final String fErr = realError;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (onError != null) onError.accept(fErr);
                        }, ModalityState.any());
                    }
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
            Set<String> preExistingNames,
            String dsName,
            boolean demotedOverride) {
        startResultTabRetry(project, ui, onError, preExistingNames, swapGeneration, dsName, demotedOverride);
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

    /** 反射调用 Runner.run：建 session → consumer 回调续接执行。失败（常见：驱动未下载）→ 面板行内回调 + 按 silent 决定是否模态引导。
     * T-PreviewExecDs-02 F4：显式传 project——失败路径曾用 openProjects.firstOrNull() 清 last-used
     * /弹错误框，多项目同开时清的是「第一个打开项目」的记忆（错项目 bug，见开发计划 §2.2）。 */
    private static void runRunner(Project project, Object runner, boolean silent, Consumer<String> onError) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        try {
            Method method = runner.getClass().getMethod("run");
            method.setAccessible(true);
            method.invoke(runner);
            LOG.info("[M36] runRunner invoked OK");
        } catch (Throwable e) {
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            LOG.warn("[M36] runRunner FAILED: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
            // T-DsCover：预览默认准备路径静默失败（被动展示不应弹模态打断）；
            // 并清 last-used 记忆，避免驱动损坏的数据源每次预览都白失败一次。
            // T-Perf-01：失败同时回调 onError（面板行内红字 + 「执行中」态恢复），静默与非静默路径都回调。
            String failMsg = MpSqlVisualizerBundle.message("execute.datasource.failed",
                    cause.getMessage() != null ? cause.getMessage() : cause.toString());
            if (onError != null) onError.accept(failMsg);
            if (silent) {
                try {
                    com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue(LAST_RUNNER_KEY);
                } catch (Throwable ignored) {
                }
                return;
            }
            // 驱动缺失/连接失败等：提示用户到数据源设置解决（原生 Download Driver 弹窗由 Database 插件触发）
            // T-UiNative T-UN-02：改 ERROR 轻通知（原模态错误框——失败仍醒目，但不打断）；
            // 测试模式跳过 + runCatching 兜底（对齐 CopyExecutableSqlAction.showToast 约定）
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                try {
                    new com.intellij.notification.Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("execute.title"),
                            failMsg,
                            com.intellij.notification.NotificationType.ERROR
                    ).notify(project);
                } catch (Throwable ignored) {
                    // headless 无通知系统依赖，与原 runCatching 等价
                }
            }
        }
    }

    /** 弹窗展示 Runner 列表。 */
    private static void showRunnerPopup(Project project, List<Object> runners, Consumer<Object> callback) {
        List<RunnerItem> items = new ArrayList<>();
        for (Object r : runners) items.add(new RunnerItem(r, getRunnerName(r), getRunnerIcon(r)));
        // T-PreviewExecDs-02 F5：多项目同开时标题带当前项目名——runner 枚举严格按当前项目
        // facade（F1 修复后），标题注明归属避免「这是哪来的数据源」困惑；单项目不加噪音。
        boolean multiProject = ProjectManager.getInstance().getOpenProjects().length > 1;
        String title = multiProject
                ? MpSqlVisualizerBundle.message("execute.datasource.select.in", project.getName())
                : MpSqlVisualizerBundle.message("execute.datasource.select");
        com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem> step =
                new com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem>(
                        title, items) {
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

    /**
     * 创建临时 .sql 文件。
     *
     * <p>T-ExecCopyFix：失败可见化——原实现整段 runCatching{}.getOrNull() 吞异常零日志，
     * 用户真机 "Failed to create temporary SQL file" 无法归因。现分级记录每个 return null
     * 站点（stage 标识 + ioFile 路径/exists 状态），异常级 warn 带堆栈，并向调用方回传
     * 阶段原因（{@link #lastTempFileFailure}）供错误文案携带。</p>
     *
     * <p>T-ExecCopyFix F2b：冷路径 refresh 段换 WriteIntentReadAction.compute（Computable 单类型
     * 实参，243~262 交集——T-Wia262）——修 262 真机最可能根因：refreshAndFindFileByIoFile 同步
     * VFS refresh 要求 write-safe 语境，262 平台 RefreshSessionImpl.launch 对 EDT-ReadAction
     * 硬抛 "Access is allowed from write thread only"（T-PreviewWiLock 同 API 先例）。WIRA 隐含
     * 读权限，refresh 后续链不受影响。</p>
     *
     * <p>T-ExecSlowOps F2：<b>本方法自此退化为兜底路径</b>——预览链成功后
     * {@link #prewarmExecTempFile} 已在后台线程完成 写盘→refresh→方言映射→findFile→填缓存槽，
     * 弹窗打开后这里 {@link #consumeCachedTempPsiFile} 必命中，EDT 上不再执行 refresh/findFile
     *（262 真机 "Slow operations are prohibited on EDT" SEVERE 本体，WorkspaceFileIndex
     * .ensureIsUpToDate）。冷路径仅存于无预览链触达的显式执行首跑等场景。原"后台化经评估
     * 否决"（BGT 无法安全做 EDT-only 的 setMapping）已被 {@link DialectPreMapping#ensureMapped}
     * 两段式（BGT 读探测 + invokeLater 派 EDT + 有界 latch 等待，绝不 invokeAndWait）解决。</p>
     */
    private static TempPsiFile createTempPsiFile(Project project, String sql) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        // T-ExecCopyFix F2a：失败阶段记录（onError 文案携带，见 chooseAndPrepare 错误文案）
        lastTempFileFailure = null;
        final File ioFile;
        try {
            ioFile = projectExecFile(project, sql);
        } catch (Throwable e) {
            // projectExecFile 尾段 FileUtil.writeToFile 可抛 IOException——原实现靠外层
            // runCatching 吞掉，拆除后这里必须自接，否则异常直冲 EDT
            return failTempFile("writeFile", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (ioFile == null) {
            return failTempFile("writeFile", "projectExecFile null (basePath=" + project.getBasePath() + ")");
        }
        // T-PreviewFlash：EDT 同步 VFS refresh + PsiManager.findFile 已被跨点击缓存吸收——
        // mpsql-exec.sql 路径稳定，vf 仍有效时直接复用配对缓存，把
        // WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言移出每次预览的热路径。
        TempPsiFile cached = consumeCachedTempPsiFile(project);
        if (cached != null) return cached;
        // T-ExecCopyFix F2b：refresh 段包 WriteIntentReadAction（详见方法 Javadoc）；
        // mapDialect 与 findFile 维持原线程原语义（setMapping 须 EDT；findFile 沿用
        // ReadAction.compute 既有包装，与 T-M36.6-03 前行为一致）。
        VirtualFile vf;
        try {
            vf = WriteIntentReadAction.compute(
                    (com.intellij.openapi.util.Computable<VirtualFile>) () ->
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile));
        } catch (Throwable e) {
            return failTempFile("refreshVfs", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (vf == null) {
            return failTempFile("refreshVfs",
                    "VFS not visible after refresh (" + ioFile.getPath() + ", exists=" + ioFile.exists() + ")");
        }
        // T-EC-04：VFS 缺条目裸写路径的防御收口——正常恒 no-op，异常残留在此收敛（含落盘）
        try {
            syncExecVfContentIfNeeded(project, vf, sql);
        } catch (Throwable e) {
            return failTempFile("syncVfContent", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        mapDialectBeforePsi(project, vf, LOG);
        PsiFile psi;
        try {
            psi = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
        } catch (Throwable e) {
            return failTempFile("findPsi", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (psi == null) {
            return failTempFile("findPsi", "PsiManager.findFile null (" + vf.getName() + ")");
        }
        // T-PerfSweep2-04：写点统一走 markCachedTempPsiFile（挂一次性项目关闭钩子）
        markCachedTempPsiFile(project, vf, psi);
        return cachedTempPsiFile;
    }

    /** T-ExecCopyFix F2a：失败阶段记录与日志（stage 标识 + 详情），恒返 null 供上层透传。 */
    private static TempPsiFile failTempFile(String stage, String detail) {
        lastTempFileFailure = (detail == null || detail.isEmpty()) ? stage : stage + ": " + detail;
        Logger.getInstance(DataSourceSelector.class)
                .warn("[M36] createTempPsiFile FAILED at " + stage
                        + ((detail == null || detail.isEmpty()) ? "" : " (" + detail + ")"));
        return null;
    }

    /**
     * T-PreviewExecDs-02 F1：消费 exec 临时文件 vf+psi 缓存——可用（归属当前项目且 vf 有效）
     * 返回配对，否则清缓存返回 null（走冷路径重建）。供跨项目隔离测试直测。
     * 跨项目泄漏实锤见开发计划 §2.2：旧实现只查 vf.isValid，在 B 项目执行时返回 A 项目的
     * vf+psi → runner 按 A 项目 facade 枚举数据源 → 弹窗显示另一项目数据源 → console 因
     * facade 找不到该数据源被 getActiveConsoles 清扫 → "parent has already been disposed"。
     */
    @Nullable
    public static TempPsiFile consumeCachedTempPsiFile(Project project) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        TempPsiFile cached = cachedTempPsiFile;
        if (cached == null) return null;
        if (isCacheUsable(project, cached.vf)) {
            LOG.info("[M36] exec psi cache hit (no VFS refresh): " + cached.vf.getName());
            return cached;
        }
        // 跨项目/失效命中——弃缓存，禁止把别项目的 vf+psi 当当前项目用
        if (cachedTempPsiProjectName != null && !cachedTempPsiProjectName.equals(project.getName())) {
            LOG.warn("[M36] exec psi cache REJECTED across project (cached=" + cachedTempPsiProjectName
                    + ", current=" + project.getName() + "), rebuild");
        }
        cachedTempPsiFile = null;
        cachedTempPsiProjectName = null;
        return null;
    }

    /**
     * T-PreviewExecDs-02 F1：exec 临时文件 vf+psi 缓存是否对当前项目可用——既要 vf 有效，
     * 也要 vf 归属当前项目（basePath 前缀）。跨项目泄漏根因修复的判定纯函数（表驱动单测）。
     */
    public static boolean isCacheUsable(Project project, VirtualFile vf) {
        if (!vf.isValid()) return false;
        String base = project.getBasePath();
        if (base == null) return false;
        String nBase = com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(base).replaceAll("/+$", "");
        String nPath = com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(vf.getPath());
        return nPath.startsWith(nBase + "/");
    }

    /**
     * T-EC-03：执行文件单写方锁——BGT 预热（prewarmExecTempFileInner）与 EDT 冷路径
     *（createTempPsiFile）两条链并发写同一 mpsql-exec.sql 的串行化。临界区毫秒级；
     * 两个调用点均在普通线程语境（非 WriteAction 内），持本锁再取平台写锁无环。
     */
    private static final Object execFileWriteLock = new Object();

    /**
     * T-EC-02：裸写兜底守卫——仅当该文件当前<b>无已加载 document</b>时才允许绕过 document
     * 直接写盘。文件有已加载 document（编辑器持有/近期读写）时裸写盘 = 绕过 document 的
     * 外部改写，叠加 document「已修改未保存」态即触发平台 MemoryDiskConflictResolver
     * 「File Cache Conflict」模态弹窗（T-ExecDocSync 真机截图实证）。供表驱动测试。
     */
    public static boolean canFallBackToRawWrite(VirtualFile vf) {
        return FileDocumentManager.getInstance().getCachedDocument(vf) == null;
    }

    /**
     * T-EC-01：document 驱动写 + <b>同临界区落盘</b>——{@code setText → commitDocument → saveDocument}，
     * 磁盘=VFS=内存三方在同一写临界区内收口，不留「已修改未保存」窗口（该窗口内任何绕过
     * document 的磁盘改写都触发 File Cache Conflict 弹窗，弹窗期间 invokeLater 链冻结 →
     * 执行链卡死）；落盘不再依赖 IDE 自动保存追赶。
     *
     * <p><b>仅 EDT 调用</b>（BGT 统一经 {@link #dispatchDocWriteOnEdt} 派发）——T-ExecDocSync 关键发现
     *（javap + 沙盒堆栈双实证）：262 平台文件 document（非 FREE_THREADED）的 setText 包
     * CommandProcessor.executeCommand，BGT 直调硬抛 EDT-only 断言（RuntimeExceptionWithAttachments）。
     * 旧实现 BGT 预热走 document 分支<b>每次必炸</b>，runCatching 吞掉后静默掉进裸写兜底——
     * 这正是真机 File Cache Conflict 链的本体（非低概率竞态）。</p>
     *
     * <p>document 未加载（fileDoc == null，即无冲突面）走 setBinaryContent（VFS+磁盘同步）；
     * text 相同但 document 仍 modified（用户改后还原等）时同样 commit+save 收敛。</p>
     */
    public static void writeVfTextViaDocument(Project project, VirtualFile vf, String text) {
        Document fileDoc = FileDocumentManager.getInstance().getDocument(vf);
        if (fileDoc == null) {
            try {
                vf.setBinaryContent(text.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
                // ignore
            }
            return;
        }
        if (!fileDoc.getText().equals(text)) {
            fileDoc.setText(text);
        }
        // setText 的 write command 结束时自动 commit；显式 commitDocument 双保险
        //（同步后同 tick 内 findFile 的 PSI 立即对齐，M36.5.20 语义保留）
        PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
        // T-EC-01 核心：同临界区落盘——磁盘即时与内存一致，document 不滞留 modified 态
        FileDocumentManager.getInstance().saveDocument(fileDoc);
    }

    /** BGT 派 EDT document 写的有界等待预算（毫秒）——DialectPreMapping 同款取值；EDT 病态冻结时超时放行走守卫降级。 */
    private static final long EDT_EXEC_DOC_SYNC_WAIT_MS = 2000L;

    /**
     * T-EC-01/T-EC-03 编排：document 写统一派发 EDT——EDT 调用方同步直执行；BGT 两段式
     * invokeLater + 有界 latch 等待（DialectPreMapping.ensureMapped 同款：BGT 等 EDT，
     * EDT 永不反向阻塞，超时自愈不互锁）。失败注入缝 {@link #execWriteActionFailureInjection}
     * 在 {@link #docWriteOnce} 内统一生效（EDT/BGT 两形态等价）。
     */
    private static boolean dispatchDocWriteOnEdt(Project project, VirtualFile vf, String sql) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) return docWriteOnce(project, vf, sql);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        app.invokeLater(() -> {
            try {
                ok[0] = docWriteOnce(project, vf, sql);
            } finally {
                done.countDown();
            }
        });
        boolean signaled;
        try {
            signaled = done.await(EDT_EXEC_DOC_SYNC_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException t) {
            signaled = false;
            Thread.currentThread().interrupt();
        }
        if (!signaled) {
            LOG.warn("[M36] exec doc write EDT dispatch timeout after " + EDT_EXEC_DOC_SYNC_WAIT_MS + "ms (EDT busy?)");
        }
        return signaled && ok[0];
    }

    /** document 写单次尝试：WriteAction 内 {@link #writeVfTextViaDocument}；失败原因必达日志（旧 getOrDefault 吞 Throwable 的教训）。 */
    private static boolean docWriteOnce(Project project, VirtualFile vf, String sql) {
        try {
            if (execWriteActionFailureInjection) {
                throw new IllegalStateException("injected exec WriteAction failure (T-ExecDocSync test seam)");
            }
            WriteAction.run(() -> writeVfTextViaDocument(project, vf, sql));
            return true;
        } catch (Throwable e) {
            Logger.getInstance(DataSourceSelector.class)
                    .warn("[M36] exec doc write FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * T-EC-04：VFS 缺条目裸写路径的防御性收口——raw write 后由调用方 refresh 得到 vf，
     * 此处探测 document（getCachedDocument，不主动创建）或 VFS 内容与 sql 的分叉，
     * 命中则经 {@link #dispatchDocWriteOnEdt} 补一次 document 驱动同步（T-EC-01 同款落盘收口，
     * BGT 调用安全——写侧统一派 EDT）。正常流程恒为 no-op（磁盘刚写入 == sql）；防御
     * VFS 陈旧条目/裸写与 refresh 之间的外部改写残留。探测包 ReadAction（BGT 无隐式读）。
     *
     * @return true = 执行了修复同步（正常流程 false）
     */
    public static boolean syncExecVfContentIfNeeded(Project project, VirtualFile vf, String sql) throws Throwable {
        boolean diverged = ReadAction.compute(
                (com.intellij.openapi.util.ThrowableComputable<Boolean, Throwable>) () -> {
                    Document doc = FileDocumentManager.getInstance().getCachedDocument(vf);
                    String disk = new String(vf.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return (doc != null && !doc.getText().equals(sql)) || !disk.equals(sql);
                });
        if (!diverged) return false;
        return dispatchDocWriteOnEdt(project, vf, sql);
    }

    /**
     * M36.5.12：项目内执行文件路径——{@code <projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql}
     *（F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再使用）。
     * 目录不存在则创建；文件写入 SQL（覆盖）。与 sql-history 同根（.idea/sql-visualizer/），
     * 但<b>独立子目录</b> exec（sql-history 读取器只扫 sql-history 子目录，互不污染）。
     *
     * <p>M36.5.13/M36.5.43：VFS 已知文件走 document 驱动写（历史：裸 FileUtil.writeToFile 覆盖
     * 磁盘触发 "File Cache Conflict" 模态弹窗，MemoryDiskConflictResolver，沙盒 12:08:06/
     * 16:05:39 两次实证）。T-ExecDocSync 三点收口（同一问题族第三轮）：</p>
     * <ul>
     *  <li><b>① 同临界区落盘</b>（T-EC-01，{@link #writeVfTextViaDocument}）——补 saveDocument，磁盘即时
     *      与内存一致，不再留「已修改未保存」窗口（M36.5.43 只改内存、磁盘靠自动保存追赶，
     *      窗口期内任何外部改盘即弹冲突窗，easy-code-demo 真机截图实证）；</li>
     *  <li><b>② document 写统一派 EDT</b>（{@link #dispatchDocWriteOnEdt}，DialectPreMapping 两段式同款）——
     *      262 平台文件 document 的 setText 包 CommandProcessor BGT 硬抛 EDT-only（javap+沙盒
     *      堆栈双实证）：旧实现 BGT 预热的 document 分支每次必炸、静默掉进裸写兜底 = 真机
     *      File Cache Conflict 链本体。BGT 预热自此不再降级；</li>
     *  <li><b>③ 裸写兜底守卫</b>（T-EC-02，{@link #canFallBackToRawWrite}）——document 写失败（含注入缝）
     *      时只有无已加载 document 才允许裸写盘；有 document 一律抛 writeConflictGuard 拒写
     *     （宁内容旧一拍，执行前 console doc 同步链会重写并落盘，执行内容不受影响），
     *      失败原因 Throwable 必达日志（原 getOrDefault(false) 吞掉）；</li>
     *  <li><b>④ 单写方锁</b>（T-EC-03，{@link #execFileWriteLock}）——串行化 BGT 预热与 EDT 冷路径。</li>
     * </ul>
     * <p>VFS 缺条目路径维持裸写 + 调用方 refresh + {@link #syncExecVfContentIfNeeded} 防御收口（T-EC-04）。</p>
     */
    public static File projectExecFile(Project project, String sql) throws java.io.IOException {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        String base = project.getBasePath();
        if (base == null) {
            // T-ExecCopyFix F2a：basePath null 是「无异常的 null」，原来连日志都没有
            LOG.warn("[M36] projectExecFile FAILED: project.basePath null (project=" + project.getName() + ")");
            return null;
        }
        File dir = new File(base, ".idea" + File.separator + SqlLogAutoSaver.DATA_DIR_NAME + File.separator + "exec");
        if (!dir.exists() && !dir.mkdirs()) {
            // T-ExecCopyFix F2a：mkdirs 返回值原来被忽略，目录建不起来后写盘抛异常再被吞
            LOG.warn("[M36] projectExecFile FAILED: mkdirs false (" + dir.getPath() + ")");
            return null;
        }
        File f = new File(dir, "mpsql-exec.sql");
        synchronized (execFileWriteLock) {
            VirtualFile existing = LocalFileSystem.getInstance().findFileByIoFile(f);
            if (existing != null) {
                // T-EC-01/T-EC-03：document 写统一派 EDT（EDT 直调同步；BGT 派发 + 有界等待）。
                // 262 实证 document setText BGT 硬抛（见 writeVfTextViaDocument Javadoc）——旧实现
                // 的 BGT document 分支每次必炸、静默掉进裸写兜底，即真机冲突链本体。
                boolean updated = dispatchDocWriteOnEdt(project, existing, sql);
                if (updated) {
                    LOG.info("[M36] exec file updated via document+save (disk synced in-critical-section): " + f.getName());
                    return f;
                }
                // T-EC-02：裸写兜底守卫——有已加载 document 时禁止绕过 document 裸写盘
                //（裸写叠加「已修改未保存」态 = File Cache Conflict 弹窗 + invokeLater 链冻结）。
                // 宁内容旧一拍：执行前 console doc 同步（executeOnConsole，T-EC-01 同款落盘）
                // 会重写并落盘，执行内容不受影响。
                if (!canFallBackToRawWrite(existing)) {
                    throw new IllegalStateException(
                            "exec file writeConflictGuard: raw write refused, document loaded (" + f.getName() + ")");
                }
                LOG.warn("[M36] exec file doc-write FAILED, fallback to raw write (no loaded document): " + f.getName());
            }
            com.intellij.openapi.util.io.FileUtil.writeToFile(f, sql);
            return f;
        }
    }

    /**
     * M36.5.3：临时文件方言预映射（MySQL）——PSI 创建时即按映射解析为 MySQL（否则走
     * SqlDialectPusher，dumb mode 下被推迟）。任何环节失败静默跳过（保持原行为，仅记录日志）。
     *
     * <p>T-ExecSlowOps F1：反射段收编 {@link DialectPreMapping}（与预览路径 mapPreviewDialectBeforePsi
     * 共用），本方法保留 exec 侧「进程内同 vf 幂等短路」语义 + 成功回写 {@link #dialectMappedVf}；
     * {@code ensureMapped} 线程自适应——EDT 直调安全（本方法现调用点全在 EDT），BGT 调用自动走两段式派发。</p>
     */
    private static void mapDialectBeforePsi(Project project, VirtualFile vf, Logger LOG) {
        // T-PreviewFlash：插件侧幂等短路——mpsql-exec.sql 路径稳定，同 vf 已映射过 MySQL 直接跳过，
        // 消除每次预览的点 setMapping（同值 setMapping 仍触发 SqlDialectPusher push all dumb 任务，见沙盒日志）。
        boolean inProcessMapped = dialectMappedVf != null && dialectMappedVf.isValid() && dialectMappedVf == vf;
        if (DialectPreMapping.ensureMapped(project, vf, inProcessMapped)) {
            dialectMappedVf = vf;
            LOG.info("[M36] dialect pre-mapped to MySQL for " + vf.getName());
        }
    }

    /** 打开 Database 工具窗口（无配置数据源时引导用户，仅显式执行路径调用；预览路径静默）。 */
    private static void openDatabaseToolWindow(Project project) {
        try {
            ToolWindowManager.getInstance(project).getToolWindow("Database").show();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    /** T-ExecCopyFix F2c：exec 临时文件 vf+psi 缓存是否仍持有（clearPrepared 不清缓存断言用）。 */
    public static boolean cachedTempPsiFileForTest() {
        return cachedTempPsiFile != null;
    }

    /**
     * T-ExecSlowOps F2：后台预热 exec 临时文件（写盘 → VFS refresh → 方言映射 → findFile →
     * 填 {@link #cachedTempPsiFile}）——<b>只允许后台线程调用</b>（EDT 直调拒绝并 warn：本链含
     * refresh/findFile 慢操作，EDT 执行正是 262 真机堆栈①的 SEVERE 本体）。
     *
     * <p>由 {@code SqlPreviewService.previewAsync} 在池线程内、{@code invokeLater} 派发弹窗之前调用：弹窗打开后
     * {@code prepareDefaultConsole → chooseAndPrepare → consumeCachedTempPsiFile} 必命中缓存，EDT 冷路径
     *（{@link #createTempPsiFile} 的 refresh+findFile）退化为兜底（无预览链触达的显式执行路径首跑等）。</p>
     *
     * <p>SQL 一致性：预热写入的是 {@code PreviewResult.sql} 原文，与
     * 面板渲染文本（格式化/参数回填后）可能不同——与 T-PreviewFlash 缓存命中路径同款语义（缓存命中
     * 时本就不重写文件，文档内容差异由下游 console doc 同步链吸收：reuseSharedConsole setText /
     * prepareAfterEditorResolved model 同步 / executeOnConsole 执行前同步）。</p>
     *
     * <p>幂等：缓存已对当前项目可用即直接返回（同项目连续预览仅一次真实链）。
     * Community/无 Database 插件场景安全：本方法不触任何 Database 类，纯 VFS/PSI。</p>
     */
    public static void prewarmExecTempFile(Project project, String sql) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            LOG.warn("[M36] prewarmExecTempFile called on EDT — refused (slow ops, T-ExecSlowOps F2)");
            return;
        }
        if (project.isDisposed() || sql.isBlank()) return;
        TempPsiFile cachedVf = cachedTempPsiFile;
        if (cachedVf != null && isCacheUsable(project, cachedVf.vf)) {
            // 缓存命中≠内容新鲜：连续预览 SQL 会变，而缓存命中路径不重写文件——只刷新文件
            // 内容（projectExecFile doc 驱动三方同步，vf/psi 身份不变），保证 attachConsole
            // 初始 document 与本条预览 SQL 一致；差量内容比较在 projectExecFile 内部短路。
            try {
                projectExecFile(project, sql);
            } catch (Throwable e) {
                LOG.warn("[M36] exec prewarm refresh-content failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            LOG.info("[M36] exec prewarm: cache hit, content refreshed only");
            return;
        }
        // 并发预览（gutter 连点/多面板）只允许一条真实预热链——其余直接返回，弹窗冷路径兜底
        if (!execPrewarmInFlight.compareAndSet(false, true)) {
            LOG.info("[M36] exec prewarm skipped (another prewarm in flight)");
            return;
        }
        try {
            prewarmExecTempFileInner(project, sql, LOG);
        } finally {
            execPrewarmInFlight.set(false);
        }
    }

    /** {@link #prewarmExecTempFile} 的真实链（已持有 in-flight 标记；仅后台线程调用）。 */
    private static void prewarmExecTempFileInner(Project project, String sql, Logger LOG) {
        lastTempFileFailure = null;
        // T-ExecCopyFix F2b：refresh 段 WriteIntentReadAction（BGT 上 WIRA 合法；与 EDT 冷路径同款包装）
        File ioFile;
        try {
            ioFile = projectExecFile(project, sql);
        } catch (Throwable e) {
            LOG.warn("[M36] exec prewarm writeFile failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (ioFile == null) return;
        VirtualFile vf;
        try {
            vf = WriteIntentReadAction.compute(
                    (com.intellij.openapi.util.Computable<VirtualFile>) () ->
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile));
        } catch (Throwable e) {
            LOG.warn("[M36] exec prewarm refreshVfs failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (vf == null) {
            LOG.warn("[M36] exec prewarm: VFS not visible after refresh (" + ioFile.getPath() + ", exists=" + ioFile.exists() + ")");
            return;
        }
        // T-EC-04：同冷路径——裸写残留的防御收口（正常恒 no-op）
        try {
            syncExecVfContentIfNeeded(project, vf, sql);
        } catch (Throwable e) {
            LOG.warn("[M36] exec prewarm syncVfContent failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        // T-ExecSlowOps F1：BGT 两段式映射（读探测稳态跳过 / 首见派 EDT + 有界等待）
        boolean inProcessMapped = dialectMappedVf != null && dialectMappedVf.isValid() && dialectMappedVf == vf;
        if (DialectPreMapping.ensureMapped(project, vf, inProcessMapped)) {
            dialectMappedVf = vf;
        }
        PsiFile psi;
        try {
            psi = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
        } catch (Throwable e) {
            LOG.warn("[M36] exec prewarm findPsi failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (psi == null) {
            LOG.warn("[M36] exec prewarm: findFile null (" + vf.getName() + ")");
            return;
        }
        if (project.isDisposed()) return;
        markCachedTempPsiFile(project, vf, psi);
        LOG.info("[M36] exec prewarm DONE (cache filled off EDT): " + vf.getName());
    }

    /** T-PerfSweep2-04：已挂项目关闭钩子的项目集——防对同一项目重复 Disposer.register。 */
    private static final Set<Project> hookProjectsClose =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * T-ExecSlowOps F2：填 exec 临时文件 vf+psi 缓存槽（项目绑定；BGT/EDT 均可调用——volatile 写）。
     * T-PerfSweep2-04：同时挂一次性项目关闭钩子——项目关闭即清槽，不再等下次消费顶掉
     *（此前项目关闭后槽里残留该项目的 vf+psi 强引用至下次写入；isCacheUsable 惰性校验
     * 保留为双保险）。parentDisposable 默认项目自身，测试注入自有 Disposable 手动触发。
     */
    public static void markCachedTempPsiFile(Project project, VirtualFile vf, PsiFile psi) {
        markCachedTempPsiFile(project, vf, psi, project);
    }

    /** parentDisposable 显式重载（Kotlin 默认参数 {@code parentDisposable = project} 的显式形态）。 */
    public static void markCachedTempPsiFile(Project project, VirtualFile vf, PsiFile psi,
                                             com.intellij.openapi.Disposable parentDisposable) {
        cachedTempPsiFile = new TempPsiFile(vf, psi);
        cachedTempPsiProjectName = project.getName();
        hookProjectClose(project, parentDisposable);
    }

    private static void hookProjectClose(Project project, com.intellij.openapi.Disposable parentDisposable) {
        if (!hookProjectsClose.add(project)) return;
        Disposer.register(parentDisposable, () -> {
            hookProjectsClose.remove(project);
            TempPsiFile cached = cachedTempPsiFile;
            if (cached != null && cached.psi.getProject() == project) {
                cachedTempPsiFile = null;
                cachedTempPsiProjectName = null;
            }
        });
    }

    /** T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（成功时 null）。 */
    public static String lastTempFileFailureForTest() {
        return lastTempFileFailure;
    }

    /** T-ExecCopyFix F2c 测试缝：等价链路置缓存槽（psi 经 PsiManager.findFile 同生产路径配对）。 */
    public static void markCachedTempPsiFileForTest(Project project, VirtualFile vf) {
        PsiFile psi = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
        if (psi == null) {
            throw new IllegalStateException("markCachedTempPsiFileForTest: findFile null for " + vf.getName());
        }
        markCachedTempPsiFile(project, vf, psi);
    }

    /** T-ExecCopyFix F2c 测试缝：清缓存槽（测试隔离收尾）。 */
    public static void clearCachedTempPsiFileForTest() {
        cachedTempPsiFile = null;
        cachedTempPsiProjectName = null;
    }

    /** T-ExecDocSync 测试缝：置 true 时 {@link #docWriteOnce} 注入失败（测 T-EC-02 守卫拒绝路径；EDT/BGT 两形态统一生效）。 */
    public static boolean execWriteActionFailureInjection = false;
}
