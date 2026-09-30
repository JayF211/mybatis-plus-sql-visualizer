package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
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
import com.intellij.openapi.components.Service;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * T-SI-02：last-used 记忆的<b>身份键</b>（数据源 UUID，LocalDataSource.getUniqueId）。
     * 旧键（显示名）继续读写，兼容一个版本后废弃（见 CHANGELOG [Unreleased]）；命中判定以
     * UUID 为主、显示名为回退——显示名同名多条时旧实现静默取首条，属「猜」，已弃。
     */
    private static final String LAST_RUNNER_ID_KEY = "mpsql.visualizer.last.runner.id";

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

    /** T-CEC-01：最近一次已记入日志的「未命中判据的控制台尾部」——取证日志去重用（每次执行至多
     * 记一条，避免 400ms 拍刷屏）。 */
    private static volatile String lastUnrecognizedTail = null;

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
        chooseAndPrepare(project, sql, false, onConsole, onEditor, onError, callback, null, false);
    }

    /** M36.5：在已准备的控制台上执行（Kotlin 默认参 {@code wholeScriptExec = false} 形态）。 */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
        return executePrepared(project, onResultsReady, onError, onErrorRich, false);
    }

    /**
     * M36.5：在已准备的控制台上执行。
     *
     * @param wholeScriptExec O-64：identity 包装产物整批直跑（ExecOption 覆写为
     *                        EXECUTE_INSIDE_WHOLE_SCRIPT/EXECUTE_OUTSIDE_WHOLE_SCRIPT，
     *                        原生 Statements 选择弹层不再弹出、整批全部执行）；普通执行传默认 false
     */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        Object console = preparedConsole;
        Editor editor = preparedEditor;
        Object scriptModel = preparedScriptModel;
        Object execOption = preparedExecOption;
        if (console == null || editor == null || scriptModel == null || execOption == null) return false;
        executeOnConsole(project, console, editor, scriptModel, execOption, preparedUi, onResultsReady, onError, onErrorRich, wholeScriptExec);
        return true;
    }

    /** 选择数据源并执行 SQL（准备 + 执行合一，Kotlin 默认参 {@code wholeScriptExec = false} 形态）。 */
    public static void selectAndExecute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
        selectAndExecute(project, sql, onConsole, onEditor, onError, callback, onErrorRich, false);
    }

    /** 选择数据源并执行 SQL（准备 + 执行合一，O-64 wholeScriptExec 重载）。 */
    public static void selectAndExecute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        chooseAndPrepare(project, sql, true, onConsole, onEditor, onError, callback, onErrorRich, wholeScriptExec);
    }

    private static void chooseAndPrepare(
            @NotNull Project project,
            @NotNull String sql,
            boolean executeAfterPrepare,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        if (!ExecuteSqlSupport.isAvailable()) {
            LOG.warn("[M36] database plugin NOT available, abort");
            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.noDatabase"));
            if (callback != null) callback.accept(false);
            return;
        }

        // T-MysqlGStrip T-MG-02：提交链总口——SQL 文本进入 exec 文件/控制台文档之前剥离 MySQL CLI
        // 的 `\G` 后缀（DBX #35）。此点数据源尚未选定（runner 弹窗在下方），方言不可得 → 按计划
        // §三 定案默认剥离（`\G` 非任何方言合法语法，剥离无害）。剥后文本写入 exec 文件，
        // 控制台编辑器所见即所执行；历史链在预览侧记录原文，不受此处影响（两态分离）。
        String submitSql = stripGSuffixForSubmit(sql, null).submitText();

        // M36.5.5：共享控制台命中（last-used 数据源一致且控制台仍有效）→ 直接复用——
        // 不同步 session/连接/临时文件/方言映射，规避"第二个会话连接永不发起"挂死（见字段注释）。
        // 显式执行路径同样复用（结果落在已嵌入面板）；用户切换数据源走弹窗 → 新建并替换共享缓存。
        if (reuseSharedConsole(project, submitSql, executeAfterPrepare, onConsole, onEditor, onError, callback, onErrorRich, wholeScriptExec)) return;

        // 临时文件 + VF + PsiFile：Runner 上下文 + 控制台挂载点（VF 须保持引用）
        TempPsiFile temp = createTempPsiFile(project, submitSql);
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
            prepareInConsole(project, session, vf, executeAfterPrepare, chosenRunnerName[0], onConsole, onEditor, onError, onErrorRich, wholeScriptExec);
        };

        List<Object> runners = getAttachDataSourceRunners(psiFile, submitSql, sessionConsumer);
        LOG.info("[M36] runners=" + runners.size());

        // M36.5：last-used 数据源静默命中（仅 prepareConsole 路径；显式执行保持弹窗可选）
        // T-SI-02：命中判定升级为「UUID 精确命中 → 名字唯一命中」，同名多条判歧义弃记忆走弹窗
        //（旧实现 firstOrNull 静默取首条，选错数据源时用户无从察觉）。
        if (!executeAfterPrepare) {
            LastUsedMemory memory = lastUsedRunner(project);
            LastUsedResolution resolution = resolveLastUsedRunnerIndex(memory,
                    runners.stream().map(DataSourceSelector::getRunnerName).collect(java.util.stream.Collectors.toList()),
                    id -> {
                        try {
                            Object ds = DataSourceSettings.getDataSourceById(project, id);
                            return ds != null ? DataSourceSettings.getName(ds) : null;
                        } catch (Throwable ignored) {
                            return null;
                        }
                    });
            Object matched = (resolution.index() != null && resolution.index() >= 0 && resolution.index() < runners.size())
                    ? runners.get(resolution.index()) : null;
            if (matched != null) {
                String matchedName = getRunnerName(matched);
                LOG.info("[M36] silent reuse last runner: " + matchedName + " (memory id=" + memory.dataSourceId() + ")");
                chosenRunnerName[0] = matchedName;
                runRunner(project, matched, true, onError);
                if (callback != null) callback.accept(true);
                return;
            }
            if (resolution.ambiguous()) {
                LOG.info("[M36] last-used memory ambiguous (id=" + memory.dataSourceId() + ", name=" + memory.displayName()
                        + ") - drop memory, show popup");
                forgetLastRunner(project);
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
            @Nullable Consumer<Boolean> callback,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
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
        // T-SI-01：复用身份 fail-closed——复用判定从「显示名相等 + isValid」升级为「显示名相等 +
        // isValid + 三分量探测的 dsInFacade 分量」（复用 attach 路径既有探测，不重造）。复用是
        // 低频路径，探测是内存级反查（DbPsiFacade.findDataSource），成本可接受。
        // 身份被拒时弃共享会话并清 last-used 记忆——旧实现只丢会话不清记忆，下一轮仍会按显示名
        // 静默命中（同名数据源场景直接钉错库），与 attach 路径同规：身份不可信即弃，不猜。
        ConsoleValidity identity = probeConsoleValidity(console, project, LOG);
        if (!valid || !reuseIdentityAccepted(identity)) {
            LOG.warn("[M36] shared console identity rejected (ds=" + dsName + ", isValid=" + valid + ", " + identity + "), drop session");
            dropSharedConsole();
            if (identity != null && Boolean.FALSE.equals(identity.dsInFacade)) forgetLastRunner(project);
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
                    // T-ExecDocSync2 F1：同步收口为 writeVfTextViaDocument（T-EC-01 语义超集：
                    // setText-if-differ + commit + saveDocument；fileDoc==null 走 setBinaryContent）。
                    // 原实现只 commit 不落盘——console document 即 exec 文件 document（attachConsole
                    // 挂载点同文件），滞留「已修改未保存」+ 窗口期任意外部改盘 + VFS refresh =
                    // File Cache Conflict 弹窗（第四轮根因 G2，开发计划 §1.2）
                    writeVfTextViaDocument(project, cvf, sql);
                });
                // T-ConsoleDbContext B：复用链同样对齐库上下文（控制台可能来自别的库记忆）
                LOG.info("[M36] shared console db-context: " + ExecConsoleContext.alignToDataSourceDatabase(console));
                final Object consoleForModel = console;
                final VirtualFile vfForModel = cvf;
                DumbService.getInstance(project).runWhenSmart(() ->
                        // write-safe: 无 document 写（反射替换 DBN script model）
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
            // write-safe: UI-only（纯 Swing/只读，无文档写）
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
            executeOnConsole(project, console, editor, refreshedModel != null ? refreshedModel : scriptModel, execOption, sharedUi, null, onError, onErrorRich, wholeScriptExec);
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

    /**
     * M36.5.48：从 console 历史文档提取真实错误文本——仅当<b>超时无结果</b>（真失败/真超时）时
     * 调用。DBN 查询执行失败（SQL 语法错/表不存在等）会把错误消息写入 console history。
     *
     * <p>提取规则（防误判）：只认<b>明确的 SQL 错误判据</b>（{@link #isErrorSignatureLine}），且错误行不得是
     * 纯 SQL 语句（含 SELECT/FROM/WHERE/JOIN 等 SQL 词），避免把正常 SQL 历史误判为错误
     * （日志实证 {@code extracted console error: WHERE t.id = 1}——A 并无真失败，正常 SQL 片段被误报）。</p>
     *
     * <p>M36.5.57：只扫 <b>consoleErrorBaseline 之后</b>的文本——历史残留错误（上个失败 SQL 写入的
     * Unknown column 等）不在本次范围，正常 SQL 执行后不会"带过来"旧错误。</p>
     *
     * <p>T-CEC-02（用户真机 P15）：判据由「短语词表」升级为「结构性判据 + 短语族」——原词表对
     * {@code [42000][1064] You have an error in your SQL syntax}（MySQL 语法错）一条都不含，面板因此
     * 全程无红字、无复制按钮，用户只能在 Output 页自己找。同批漏检的还有 Oracle {@code ORA-00933}、
     * MySQL 1062/1451 等；历史真机验证过的错误恰好全是 {@code Unknown column} 系，故缺口长期未现。</p>
     */
    @Nullable
    public static String extractConsoleError(Object console, Logger LOG) {
        if (console == null) return null;
        try {
            String text = newConsoleText(console);
            if (text == null) return null;
            if (text.isBlank()) return null;
            List<String> lines = Arrays.stream(text.split("\n", -1))
                    .filter(s -> !s.isBlank()).collect(Collectors.toList());
            // 从末尾往前找"命中错误判据"且"非纯 SQL / 非本插件自写回显"的行
            String errorLine = null;
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (isErrorSignatureLine(line) && !isSqlLine(line) && !isOwnEchoLine(line)) {
                    errorLine = line;
                    break;
                }
            }
            if (errorLine == null) {
                // T-CEC-01：未命中即取证——记下本次新增文本的尾部（有界 + 每次执行只记一次），
                // 下次扩判据时以真机报文为准，不照用户截图猜形态。
                logUnrecognizedConsoleTail(lines, LOG);
                return null;
            }
            // 取错误行及其后非空行构成可读上下文——错误消息常跨多行（MySQL 语法错把 "near …" 的
            // SQL 片段跟在其后）。T-CEC-02：4 行放宽到 8 行，并剥掉逐行的 `[时间戳] ` 与提示符。
            String prompt = consolePrompt(console);
            int idx = lines.indexOf(errorLine);
            int end = Math.min(idx + EXCERPT_MAX_LINES, lines.size());
            StringBuilder excerptBuilder = new StringBuilder();
            for (int i = idx; i < end; i++) {
                if (i > idx) excerptBuilder.append('\n');
                excerptBuilder.append(stripConsolePrefix(lines.get(i), prompt));
            }
            String excerpt = excerptBuilder.toString();
            // T-ConsoleDbContext A：附「当前库 / 数据源 / 数据源默认库」上下文——真实事故中 SQL 本身
            // 没问题，是执行落在了错的库（mpj_demo 的 user 表没有 username → 1054），而裸 MySQL 报文
            // 看不出这一层；带上上下文后一眼可归因
            String ctxLine = ExecConsoleContext.describeContext(console);
            String enriched = ctxLine != null ? excerpt + "\n" + ctxLine : excerpt;
            LOG.info("[M36] extracted console error: " + (enriched.length() > 200 ? enriched.substring(0, 200) : enriched));
            return enriched;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * T-CEC-03：兜底呈现——判据认不出的失败，把控制台<b>新增文本去掉本次 SQL 回显后的残余</b>交回
     * 面板，而不是给「SQL 已提交执行，但等待结果超时……检查密码输入框/测试连接」这类笼统提示
     * （真机 P15 实证：把一条 MySQL 语法错归因成密码/驱动问题，归因方向是错的）。
     *
     * <p>只做「减去回显」不做任何语义判断：残余是什么就报什么，面板侧附一句如实说明
     * （{@code execute.error.unknownShape}）——不声称它是 SQL 错误，也不猜原因。</p>
     *
     * <p>剔除规则（宁少勿错）：空行；DBN 回显行（剥掉时间戳/提示符后是本次执行 SQL 的子串）；
     * DBN 状态行（Connected / completed in … 等）。全部被剔完返 null（调用方落回既有提示）。</p>
     */
    @Nullable
    public static String extractConsoleResidue(Object console, @Nullable String sqlText, Logger LOG) {
        if (console == null) return null;
        try {
            String text = newConsoleText(console);
            if (text == null) return null;
            if (text.isBlank()) return null;
            String sqlNorm = normalizeForEcho(sqlText != null ? sqlText : "");
            String prompt = consolePrompt(console);
            List<String> kept = new ArrayList<>();
            for (String rawLine : text.split("\n", -1)) {
                String line = rawLine.trim();
                if (line.isBlank()) continue;
                if (isConsoleStatusLine(line)) continue;
                if (isSqlEchoLine(line, sqlNorm, prompt)) continue;
                kept.add(line);
            }
            if (kept.isEmpty()) return null;
            int from = Math.max(0, kept.size() - RESIDUE_MAX_LINES);
            StringBuilder joinedBuilder = new StringBuilder();
            for (int i = from; i < kept.size(); i++) {
                if (i > from) joinedBuilder.append('\n');
                joinedBuilder.append(kept.get(i));
            }
            String joined = joinedBuilder.toString();
            String out = joined.length() > RESIDUE_MAX_CHARS ? joined.substring(0, RESIDUE_MAX_CHARS) : joined;
            String logTail = out.replace("\n", "⏎");
            LOG.info("[T-CEC] console residue (no known signature): "
                    + (logTail.length() > 200 ? logTail.substring(0, 200) : logTail));
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * T-CEC：读取 console history 文档中<b>基线之后</b>的新增文本（读不到 / 无新增返 null）。
     *
     * <p>T-PerfSweep2-01 的片段物化契约在此保持（ConsoleErrorExtractTest 逐条断言）：baseline&lt;=0
     * 走整串一次、&gt;0 只切一刀、无增长零物化——本函数是两个定时器逐拍调用的共用读取面，
     * 抽出来只为让兜底呈现复用同一份切片语义。</p>
     */
    @Nullable
    private static String newConsoleText(Object console) throws Exception {
        if (console == null) return null;
        Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
        if (consoleView == null) return null;
        Object historyViewer = consoleView.getClass().getMethod("getHistoryViewer").invoke(consoleView);
        if (historyViewer == null) return null;
        Object doc = historyViewer.getClass().getMethod("getDocument").invoke(historyViewer);
        if (doc == null) return null;
        // T-PerfSweep2-01：getCharsSequence + subSequence 片段读取——本函数被 600ms×8 拍与
        // 400ms×40/100 拍两个定时器<b>逐拍</b>调用，此前 doc.getText() 每拍整串拷贝全史
        // （history 随长会话涨到 MB 级后是 EDT 上唯一可感知的 O(全史) 重复分配）。
        // 只物化 baseline 之后的切片；getCharsSequence 与 getText 同属 Document 接口，
        // 反射可达性一致，且免去 BadLocationException 面。
        Object seqObj = doc.getClass().getMethod("getCharsSequence").invoke(doc);
        if (!(seqObj instanceof CharSequence)) return null;
        CharSequence seq = (CharSequence) seqObj;
        // M36.5.57：只扫本次执行之后新增的文本（基线截断）——忽略 history 残留的旧错误。
        // baseline 可能因 DBN 写入历史而增长；若文本长度 <= 基线说明无新增内容，返回 null。
        int baseline = consoleErrorBaseline;
        if (baseline <= 0) {
            return seq.toString();
        } else if (seq.length() > baseline) {
            return seq.subSequence(baseline, seq.length()).toString();
        } else {
            return null;
        }
    }

    /**
     * T-CEC-01：未命中判据的控制台新增文本尾部（有界 + 同一段只记一次）——取证用，供下次扩判据
     * 时核对 DBN 真实写入 history 的报文形态（是否带 {@code [时间戳]} 前缀、是否跨行、SQLSTATE 前缀
     * 是否在场）。行数与字符数双上限，避免长会话刷屏。
     */
    private static void logUnrecognizedConsoleTail(List<String> lines, Logger LOG) {
        int from = Math.max(0, lines.size() - UNRECOGNIZED_TAIL_LINES);
        StringBuilder tailBuilder = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            if (i > from) tailBuilder.append('\n');
            tailBuilder.append(lines.get(i));
        }
        String joined = tailBuilder.toString();
        String tail = joined.length() > UNRECOGNIZED_TAIL_CHARS ? joined.substring(0, UNRECOGNIZED_TAIL_CHARS) : joined;
        if (tail.isBlank() || tail.equals(lastUnrecognizedTail)) return;
        lastUnrecognizedTail = tail;
        LOG.info("[T-CEC] console tail (no error signature matched): " + tail.replace("\n", "⏎"));
    }

    /** T-CEC-02：错误上下文摘录的最大行数（错误行起算）。 */
    private static final int EXCERPT_MAX_LINES = 8;

    /** T-CEC-01：取证日志的行数 / 字符数上限。 */
    private static final int UNRECOGNIZED_TAIL_LINES = 6;
    private static final int UNRECOGNIZED_TAIL_CHARS = 600;

    /** T-CEC-03：兜底呈现的行数 / 字符数上限。 */
    private static final int RESIDUE_MAX_LINES = 6;
    private static final int RESIDUE_MAX_CHARS = 600;

    /**
     * T-CEC-02：DBN 服务端错误前缀形态 {@code [SQLSTATE][errorCode]}——DBN 把驱动 SQLException 的
     * SQLState 与 vendor code 拼在报文最前（真机日志实证：{@code [42S22][1054] Unknown column …}、
     * {@code [42000][1064] You have an error in your SQL syntax …}）。跨方言同形，比短语词表硬：
     * 原词表认 {@code syntax error}，却认不出 {@code You have an error in your SQL syntax}。
     * 5 位定长是关键——{@code [2026-09-22 08:48:12]} 这类时间戳前缀（19 字符）不会被误命中。
     */
    private static final Pattern SQLSTATE_PREFIX = Pattern.compile("\\[[0-9A-Za-z]{5}]\\[\\d+]");

    /** T-CEC-02：Oracle 错误码形态（{@code ORA-00933} 等）——Oracle 报文常不带 SQLSTATE 前缀。 */
    private static final Pattern ORACLE_CODE = Pattern.compile("\\bORA-\\d{5}\\b", Pattern.CASE_INSENSITIVE);

    /** T-CEC-02：错误签名短语族（结构性判据覆盖不到的方言 / 驱动惯用报文）。 */
    private static final List<String> ERROR_SIGNATURES = List.of(
            // M36.5.48 原有 14 条（历史命中系，行为保持不动）
            "sql exception", "syntax error", "sqlstate", "unknown column",
            "unknown table", "doesn't exist", "does not exist", "access denied",
            "deadlock", "error code", "broken pipe", "connection refused",
            "[日本語エラー]", "sql（", "exception in thread",
            // T-CEC-02 新增：MySQL 语法错（用户 2026-09-22 真机 Output 报文逐字）
            "you have an error in your sql syntax",
            // T-CEC-02 新增：厂商固定报文（MySQL 1062 / 1451、Oracle ORA-00933）
            "duplicate entry", "foreign key constraint",
            "cannot add or update a child row", "cannot delete or update a parent row",
            "sql command not properly ended"
    );

    /** T-CEC-02：一行是否命中错误判据（结构性判据 + 短语族）。 */
    private static boolean isErrorSignatureLine(String line) {
        if (SQLSTATE_PREFIX.matcher(line).find()) return true;
        if (ORACLE_CODE.matcher(line).find()) return true;
        for (String s : ERROR_SIGNATURES) {
            if (line.toLowerCase().contains(s.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * T-CEC-02：本插件自己写进 Output 的回显行前缀（{@link #writeConsoleOutput} 的「执行失败: 」）——
     * 提取时排除，避免「已上报的错误被自己写回控制台后又当成新错误」的自反馈。
     */
    private static final String EXEC_FAILURE_ECHO_PREFIX = "执行失败:";

    private static boolean isOwnEchoLine(String line) {
        return stripConsolePrefix(line, null).startsWith(EXEC_FAILURE_ECHO_PREFIX);
    }

    /** T-CEC-02：DBN 输出行的 {@code [时间戳] } 前缀（用户真机报文逐行带此前缀）。 */
    private static final Pattern TIMESTAMP_PREFIX =
            Pattern.compile("^\\[\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?]\\s*");

    private static String stripTimestamp(String line) {
        String s = line.trim();
        Matcher m = TIMESTAMP_PREFIX.matcher(s);
        if (!m.find()) return s;
        return s.substring(m.end()).trim();
    }

    /** T-CEC-02：剥掉时间戳与（DBN 实读的）提示符前缀——呈现与复制都更干净。 */
    private static String stripConsolePrefix(String line, @Nullable String prompt) {
        String s = stripTimestamp(line);
        if (prompt != null && !prompt.isBlank() && s.startsWith(prompt)) {
            s = s.substring(prompt.length()).trim();
        }
        return s;
    }

    /**
     * T-CEC-02：读 DBN 控制台提示符（{@code mpj_demo>} / {@code ds>>}，即 {@code LanguageConsoleView.prompt}——
     * 243 源码 jar 实证为 Kotlin {@code var prompt: String?}，插件既有 {@link #clearConsolePrompt} 已用 setPrompt）。
     * 取不到返 null：调用方退化为只剥时间戳，不猜提示符形态。
     */
    @Nullable
    private static String consolePrompt(Object console) {
        try {
            if (console == null) return null;
            Object consoleView = console.getClass().getMethod("getConsoleView").invoke(console);
            if (consoleView == null) return null;
            Object promptObj = consoleView.getClass().getMethod("getPrompt").invoke(consoleView);
            if (!(promptObj instanceof String)) return null;
            String prompt = (String) promptObj;
            return prompt.isBlank() ? null : prompt;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** T-CEC-03：回显比对用的归一化（空白折叠 + 小写）。 */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static String normalizeForEcho(String text) {
        return WHITESPACE_RUN.matcher(text).replaceAll(" ").trim().toLowerCase();
    }

    /** T-CEC-03：兜底剥离提示符的候选形态（仅当剩余部分是本次 SQL 子串时才据此判为回显）。 */
    private static final Pattern PROMPT_CANDIDATE = Pattern.compile("^\\S{1,64}>+\\s+");

    /**
     * T-CEC-03：该行是否本次执行 SQL 的回显——剥掉时间戳后（必要时再剥提示符）是本次 SQL 文本的
     * 子串即认（多行 SQL 的每一行都是 SQL 的子串）。判据宽松方向是「多剔」：宁可残余变空退回
     * 既有提示，也不把回显当错误报给用户。
     */
    private static boolean isSqlEchoLine(String line, String sqlNorm, @Nullable String prompt) {
        if (sqlNorm.isEmpty()) return false;
        String body = stripTimestamp(line);
        List<String> candidates = new ArrayList<>();
        candidates.add(body);
        if (prompt != null && !prompt.isBlank() && body.startsWith(prompt)) {
            candidates.add(body.substring(prompt.length()).trim());
        }
        Matcher promptMatcher = PROMPT_CANDIDATE.matcher(body);
        if (promptMatcher.find()) {
            candidates.add(body.substring(promptMatcher.end()).trim());
        }
        for (String c : candidates) {
            String n = normalizeForEcho(c);
            if (n.length() >= 4 && sqlNorm.contains(n)) return true;
        }
        return false;
    }

    /**
     * T-CEC-03：DBN 状态 / 结果摘要行——不是错误内容，兜底呈现时剔除。
     *
     * <p>判据刻意偏「多剔」：语句<b>成功</b>但 DBN 不建结果 Tab 的场景同样会走满 40 拍窗口（DML 与
     * {@code use} 这类工具语句的影响行数/完成回显就写在 Output 里），把 {@code 1 row affected} 当残余报出来
     * 等于把成功说成失败；剔多了只是落回既有提示，属可接受方向（宁少勿错）。</p>
     */
    private static final List<String> CONSOLE_STATUS_LINES = List.of(
            "connected", "completed in", "executing", "fetching", "fetched",
            "cancelled", "canceled", "query ok", "affected", "took ", "elapsed"
    );

    /** T-CEC-03：结果摘要词（{@code 1 row affected} / {@code N rows} / {@code updated} 等）——同上，偏多剔。 */
    private static final Pattern CONSOLE_STATUS_WORDS =
            Pattern.compile("\\b(rows?|records?|updated|deleted|inserted)\\b", Pattern.CASE_INSENSITIVE);

    private static boolean isConsoleStatusLine(String line) {
        String n = stripTimestamp(line).toLowerCase();
        for (String s : CONSOLE_STATUS_LINES) {
            if (n.contains(s)) return true;
        }
        return CONSOLE_STATUS_WORDS.matcher(n).find();
    }

    /** M36.5.48：判断一行是否纯 SQL 语句（避免把「WHERE t.id=1」等当错误）。 */
    private static boolean isSqlLine(String line) {
        String lower = line.trim().toLowerCase();
        // 含 SQL 关键字且无错误判据命中 → 大概率是 SQL 文本而非错误
        boolean starts = lower.startsWith("select") || lower.startsWith("from")
                || lower.startsWith("where") || lower.startsWith("join")
                || lower.startsWith("insert") || lower.startsWith("update")
                || lower.startsWith("delete") || lower.startsWith("create")
                || lower.startsWith("alter") || lower.startsWith("drop");
        return starts && !isErrorSignatureLine(line);
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

    /**
     * M36.5.70：用 DBN ConsoleView 原生 print API 把文本写入 Output 控制台可视区。
     *
     * <p>T-FatalPassthrough T-FP-02b：{@code public} 供 {@code FatalPassthroughTest} 直测本函数的<b>外层
     * runCatching 边界</b>（同 {@link #execFileContentMatches} / {@link #isExecDocOpenInEditor} 先例）
     * ——私有则行为用例无处注入。</p>
     */
    public static void writeConsoleOutput(Object console, String text, boolean isError) {
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
                        // 滚到末尾（新输出可见）
                        // write-safe: 无 document 写（反射替换 DBN script model）
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
                        // T-FatalPassthrough T-FP-02：JVM 级致命错误透传（见 util/FatalErrors.kt）——
                        // 本文件是执行链长驻路径，捕获面覆盖 Error：普通异常（含可选能力探测的
                        // ClassNotFoundException）仍走既有降级，VirtualMachineError/LinkageError 不得
                        // 被吞成一条日志后让执行链带病继续。
                        // Java 形态：isFatal 蕴含 Error，显式收窄后 throw（裸 `throw e` 在 try 含受检异常时
                        // 因精确重抛分析要求方法声明该受检异常而不可编译；语义与原对象逐字一致，不包装）。
                        if (FatalErrors.isFatal(e)) throw (Error) e;
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
                    if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
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
            // T-FatalPassthrough T-FP-02b（独立复核阻断 1 修正）：本函数的 runCatching 包住上面两处
            // fatal 透传（doc-append 捕获块 / ConsoleViewContentType 字段回退捕获块）——在此把 fatal
            // 吞成一行 INFO 等于透传无效（改前实测：fatal 被转成 "[M36] writeConsoleOutput FAILED: …"
            // 后执行链带病继续，正是上面捕获块注释声称的反面）。反射链先解包 InvocationTargetException
            // 再判（同本文件原生执行链 :2604-2607 形态；只判外层会漏判目标方法抛出的 fatal）。
            // 非 fatal 路径行为逐字节不变：仍只记原 INFO 行，函数返回 void、调用方（EDT 排出的
            // writeConsoleOutput + onError 回调）行为不变；fatal 则透传到平台 EDT 处理器记录现场。
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
            LOG.info("[M36] writeConsoleOutput FAILED: " + e.getMessage());
        }
    }

    /** isError 缺省重载（Kotlin 默认参数 {@code isError = false} 的缺省形态）。 */
    public static void writeConsoleOutput(Object console, String text) {
        writeConsoleOutput(console, text, false);
    }

    /** M36.5.8/M36.5.9/M36.5.13：执行后结果区刷新——限时多次重取。 */
    private static void refreshResultsComponent(Object ui, Logger LOG, boolean forceSwapIn, int generation,
                                                @Nullable Consumer<String> onError,
                                                @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
                                                @Nullable String sqlText) {
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
                // T-WSC-02：writeConsoleOutput 内部是文档写（runWriteCommandAction + insertString），
                // 且上游是 Swing Timer 拍（Timer 活动同样非用户活动）——必须 write-safe 模态投递，
                // any() 恒 write-unsafe（见 DocWriteContext KDoc）。
                final String fErr = err;
                ApplicationManager.getApplication().invokeLater(() -> {
                    writeConsoleOutput(shared != null ? shared : preparedConsole, EXEC_FAILURE_ECHO_PREFIX + " " + fErr + "\n", true);
                    // T-ELU-01/03：语句级错误同 startResultTabRetry 的早检路径——走富通道收口
                    deliverError(onErrorRich, onError, null, fErr, sqlText != null ? sqlText.length() : null);
                }, ModalityState.nonModal());
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
                                    WriteCommandAction.runWriteCommandAction(fProj, () -> {
                                        cd.setText(fSql);
                                        // T-ExecDocSync2 F3：同临界区落盘——console document 即 exec 文件
                                        // document（attachConsole 挂载点），只 setText 不落盘滞留
                                        // 「已修改未保存」窗口（第四轮根因 G4，开发计划 §1.2）。
                                        // 落盘失败不回滚改写本身（显示优先），仅记录
                                        try {
                                            Object p = console.getClass().getMethod("getProject").invoke(console);
                                            Project p2 = p instanceof Project ? (Project) p : null;
                                            if (p2 != null) {
                                                PsiDocumentManager.getInstance(p2).commitDocument(cd);
                                                FileDocumentManager.getInstance().saveDocument(cd);
                                            }
                                        } catch (Throwable e2) {
                                            LOG.warn("[M36] contentAdded post-rewrite save FAILED: " + e2.getMessage());
                                        }
                                    });
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
                        // T-WSC-02：本回调写 console 文档（setText + commitDocument + saveDocument）且用的是
                        // 快照文本、不幂等——必须「投递时即 write-safe」，不能用重投递自愈，故 any() 改
                        // nonModal（any() 恒 write-unsafe，见 DocWriteContext KDoc）。
                    }, ModalityState.nonModal());
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
        // write-safe: UI-only（纯 Swing/只读，无文档写）
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

    /**
     * T-TS-09：控制台编辑器不可得时的用户可读提示（{@link #prepareInConsole} 两条静默分支共用）。
     *
     * <p>复用既有键 {@code execute.failed}（"执行 SQL 失败：{0}"）＋技术性明细参数：明细是诊断串而非文案，
     * 与 {@code execute.tempFile.failed} 传 {@link #lastTempFileFailure} 同款（新增专用键要动双语 bundle，
     * 不在本任务写集内）。</p>
     *
     * @param path 触发的分支（sync = 非 EDT 同步轮询 / async = EDT 异步等待）——排障时据此定位，
     *             两条分支的成因不同（同步：调用方在非 EDT；异步：EDT 首查未命中后后台轮询）
     */
    private static String consoleEditorUnavailableMessage(String path) {
        return MpSqlVisualizerBundle.message(
                "execute.failed",
                "控制台编辑器不可得（" + path + "：400ms 内 getEditors(consoleDocument) 恒空，无一方为该文档建过编辑器）");
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
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
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
                if (editor == null) {
                    // T-TS-09：原为静默 `?: return`——非 EDT 调用方（本分支只在非 EDT 到达）在控制台
                    // 文档无人建编辑器时永久拿不到任何回调，UI 停在「执行中」且 errors=[]（驱动管线打通
                    // 前观测到的「120s 不就绪 + 错误为空」正是此处与下方异步分支两处静默共同造成）。
                    // 只补通知，不改准备链语义（onEditor/onConsole 仍只在成功路径触发）。
                    LOG.warn("[M36] console editor unavailable after 400ms wait (sync path), abort prepare");
                    // write-safe: UI-only（纯 Swing/只读，无文档写）——onError 由面板直接刷状态栏，
                    // 本分支在非 EDT 线程上，须归位 EDT（同下方异步分支语义）
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (onError != null) onError.accept(consoleEditorUnavailableMessage("sync"));
                    }, ModalityState.any());
                    return;
                }
                Editor ed = editor;
                // M36.2：编辑器就绪回调（调用方注入插件补全上下文，与原生补全叠加）
                if (onEditor != null) onEditor.accept(ed);
                prepareAfterEditorResolved(project, vf, executeAfterPrepare, dataSourceName,
                        onConsole, onError, console, document, ed, onErrorRich, wholeScriptExec);
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
                    // T-WSC-02：本块经 prepareAfterEditorResolved 写 console 文档（`:1354`
                    // WriteAction.compute { writeVfTextViaDocument }）——文档写回调一律 write-safe
                    // 模态，any() 恒 write-unsafe（见 DocWriteContext KDoc）。代价：模态弹窗存续期
                    // 准备链延后到弹窗关闭，正是平台对「改模型」要求的语义。
                    ApplicationManager.getApplication().invokeLater(() -> {
                        LOG.info("[M36] editor=" + resolved + " (async wait)");
                        if (resolved != null) {
                            if (onEditor != null) onEditor.accept(resolved);
                            try {
                                prepareAfterEditorResolved(project, vf, executeAfterPrepare, dataSourceName,
                                        onConsole, onError, console, document, resolved, onErrorRich, wholeScriptExec);
                            } catch (Throwable e) {
                                LOG.warn("[M36] prepareInConsole(editor async wait) FAILED: "
                                        + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                                Throwable cause = (e instanceof InvocationTargetException)
                                        ? ((InvocationTargetException) e).getTargetException() : e;
                                // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，
                                // 只判外层会漏判目标方法抛出的 fatal——解包后判 fatal 并透传给平台
                                // EDT 处理器记录现场；非 fatal 路径行为不变。
                                if (FatalErrors.isFatal(cause)) throw (Error) cause;
                                if (onError != null) {
                                    onError.accept(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                                }
                            }
                        } else {
                            // T-TS-09：原为「无 else」——EDT 调用方（测试/面板准备链的常态）轮询 400ms
                            // 仍取不到编辑器时，本函数就此静默结束：调用方既无 onError 也无 callback，
                            // 面板停在「执行中」且 errors=[]，与上面的同步分支同款不可诊断（20260918
                            // 基线 idea.log `[M36] editor=null (async wait)` 实证）。只补通知，不改语义。
                            LOG.warn("[M36] console editor unavailable after 400ms wait (async path), abort prepare");
                            if (onError != null) onError.accept(consoleEditorUnavailableMessage("async"));
                        }
                    }, ModalityState.nonModal());
                });
            }
        } catch (Throwable e) {
            LOG.warn("[M36] prepareInConsole FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，只判外层会漏判——
            // 解包后判 fatal 并透传给调用方捕获边界；非 fatal 路径行为不变。
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
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
            Editor editor,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
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
                        // T-ExecDocSync2 F2：同 F1 收口——commit 后补 saveDocument，console document
                        // 不再滞留「已修改未保存」（第四轮根因 G3，开发计划 §1.2）
                        writeVfTextViaDocument(project, cvf, consoleText);
                    });
                    // T-ConsoleDbContext B：控制台就绪即把库上下文对齐到「数据源 URL 里的库」——
                    // DBN 的文件→数据源映射与库记忆会把会话钉在别的库上（真机事故：exec 文件被映射
                    // 到 mpj_demo@localhost，而 SQL 属于 jpa_demo → Unknown column 'u.username'）。
                    // 用户定案：预览默认以数据源 URL 的库为准；用户之后在控制台手调库，记忆照常保留
                    //（对齐只在「与数据源默认库不一致」时切一次，已一致即 no-op，不会覆盖用户后来的选择）。
                    LOG.info("[M36] console db-context: " + ExecConsoleContext.alignToDataSourceDatabase(console));
                    final Object consoleForModel = console;
                    final VirtualFile vfForModel = cvf;
                    DumbService.getInstance(project).runWhenSmart(() ->
                            // 延迟到 dialpct push 完全结束后（dumb 退出 + 一次 EDT 泵）再重建
                            // write-safe: UI-only（纯 Swing/只读，无文档写）
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
            // T-SI-02：控制台已建，数据源 UUID 从此可取（LocalDataSource.getUniqueId）——
            // 记忆升级为「UUID + 显示名」双字段，下次预览按身份命中而非猜名字
            if (dataSourceName != null) rememberRunner(project, dataSourceName, consoleDataSourceId(console));

            if (onConsole != null) onConsole.accept(component);

            if (!executeAfterPrepare) {
                // write-safe: UI-only（纯 Swing/只读，无文档写）
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
                // 2026-09-23 真机走查补：此处原**漏传** onErrorRich（默认 null）⇒ 这条路径上的错误
                // 投递收口只能回退旧通道，定位按钮永不出现。本函数签名里一直有该参数，只是没往下传
                // ——「实现有、接线漏」的又一例（同族见 ErrorPositionWiringGuardTest 的转发断言）。
                // （k2j 移植基线此处已传 onErrorRich，语义等价；本轮按 master 统一为显式多行形态并接 wholeScriptExec）
                executeOnConsole(
                        project, console, editor, effectiveScriptModel, execOption, ui,
                        null, onError, onErrorRich, wholeScriptExec);
            }
        } catch (Throwable e) {
            LOG.warn("[M36] prepareInConsole FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，只判外层会漏判——
            // 解包后判 fatal 并透传给调用方捕获边界；非 fatal 路径行为不变。
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
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

    /**
     * T-SI-01：共享会话复用身份判定（纯函数，默认 test 通道可覆盖）——在 {@code console.isValid()}
     * 与编辑器未释放之外，把 {@link ConsoleValidity} 的 {@code dsInFacade} 分量作为<b>独立判据</b>接进复用
     * 判定（复用 attach 路径既有探测，不重造）。{@code identity == null} 表示探测不可用（降级），
     * 按现状放行——探测失败不得把正常复用打成重建（宁可用旧会话，也不每次预览重建连接）。
     */
    public static boolean reuseIdentityAccepted(@Nullable ConsoleValidity identity) {
        return identity == null || identity.valid();
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

    /**
     * T-ErrorLocate T-ELU-01/03：错误投递<b>统一收口</b>——富通道优先，富通道缺席才回退旧
     * {@code onError(String)}；两者<b>互斥</b>，不再先后各投一次。
     *
     * <p><b>为什么必须互斥</b>（T-ELU-01 实测的真相，与计划 §一.1 的「按钮可见但点了没反应」不同）：
     * 消费方 {@code SqlPreviewPanel} 的 {@code onError} 恒为 {@code resultPanel.showError(errorMsg)}，而
     * {@code showError} 会清掉定位动作与定位按钮（{@code ui/SqlResultPanel.kt} 的
     * {@code locateHandler = null} + {@code errorLocateButton.isVisible = false}）。原实现先投富通道
     * （{@code showErrorRich} 置按钮可见）、紧接着无条件投旧通道——<b>同一 EDT 任务内</b>后者立刻把
     * 前者抹掉，端态恒为「按钮不可见」，T-ELU-01 的「先解析后显隐」因此形同 no-op。
     * 两者呈现的文本同源（富通道 {@code showErrorRich} 内部就是 {@code showError(info.message)}），
     * 故富通道成功时不再投旧通道，展示行为等价而定位态得以保留。</p>
     *
     * <p>富通道自身抛异常（{@code runCatching}）时<b>回退旧通道</b>——不让「结构化」这一增强把错误呈现吞掉。</p>
     *
     * <p><b>2026-09-23 真机走查补记（本函数曾丢失过，勿再删）</b>：本函数在分支上实现于 {@code 4186231}，
     * 但 {@code 914724c}（「O-48/O-49 采信 master 侧已合入实现」）把 O-49 整体改回 master 侧形态，
     * 该收口随之消失，端态退回「按钮永不出现」——真机走查实证：一条锚点可解析的失败语句
     * （{@code DELETE FROM no_such_table_xyz}）面板只出现红字 +「复制错误信息」，<b>没有定位按钮</b>。</p>
     *
     * @param cause        解包后的异常（取 SQLState/errorCode；控制台历史文本错误传 null）
     * @param message      错误文本（位置 marker 从它提取）
     * @param sqlMaxLength 提交/被执行文本长度（T-ELU-03 越界钳制上界）；null = 不钳制
     */
    public static void deliverError(
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            @Nullable Consumer<String> onError,
            @Nullable Throwable cause,
            @NotNull String message,
            @Nullable Integer sqlMaxLength) {
        if (onErrorRich != null) {
            try {
                onErrorRich.accept(ErrorPositionExtractor.buildInfo(cause, message, sqlMaxLength));
            } catch (Throwable t) {
                Logger.getInstance(DataSourceSelector.class)
                        .warn("[T-ErrorLocate] onErrorRich invoke failed → fallback to onError", t);
                if (onError != null) onError.accept(message);
            }
        } else {
            if (onError != null) onError.accept(message);
        }
    }

    /** Kotlin 默认参 {@code sqlMaxLength = null} 的缺省形态。 */
    public static void deliverError(
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            @Nullable Consumer<String> onError,
            @Nullable Throwable cause,
            @NotNull String message) {
        deliverError(onErrorRich, onError, cause, message, null);
    }

    /**
     * O-64/T-RollbackIdentityParse：identity 包装产物的「整批直跑」ExecOption 副本。
     *
     * <p>平台语义（javap 243 {@code DatabaseSettings$ExecOption} + 常量组实证）：多语句执行范围由
     * {@code execInside}（光标在语句内）/ {@code execOutside}（光标在语句间）两个 int 决定，默认
     * {@code EXECUTE_INSIDE_SHOW_CHOOSER} 弹 Statements 选择层且默认只选第一条——P19 的 identity
     * 产物首语句恰是无副作用的 {@code SET … ON}，按默认执行等于 INSERT 没跑。本副本把两键都覆写为
     * {@code *_WHOLE_SCRIPT}：不弹层、整批全部执行，即「默认全选」的唯一平台杠杆。</p>
     *
     * <p>只造副本：{@code copyFrom} 基础配置后覆写两键，preparedExecOption/sharedExecOption 缓存与
     * 用户全局设置（{@code getDefaultExecOption()} 活实例）一概不碰。反射不可用 → 原样返回基础
     * 实例（执行维持现态，不劣化）。测试直接覆盖本函数（DatabaseTools 在默认 test classpath）。</p>
     */
    public static Object wholeScriptExecOption(Object base) {
        try {
            Class<?> settingsClass = Class.forName("com.intellij.database.settings.DatabaseSettings");
            Object fresh = base.getClass().getDeclaredConstructor().newInstance();
            base.getClass().getMethod("copyFrom", base.getClass()).invoke(fresh, base);
            base.getClass().getField("execInside").setInt(fresh,
                    settingsClass.getField("EXECUTE_INSIDE_WHOLE_SCRIPT").getInt(null));
            try {
                base.getClass().getField("execOutside").setInt(fresh,
                        settingsClass.getField("EXECUTE_OUTSIDE_WHOLE_SCRIPT").getInt(null));
            } catch (Throwable ignored) {
                // 内层 runCatching：execOutside 缺席不致命，保留 execInside 覆写
            }
            return fresh;
        } catch (Throwable t) {
            return base;
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
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        // O-64/T-RollbackIdentityParse：identity 包装产物整批直跑——覆写本次执行的 ExecOption
        // （只造**副本**传下去，prepared/shared 缓存与用户全局设置一概不动），弹层不再出现、
        // 整批（SET…ON + INSERT + SET…OFF）全部执行，即「Statements 默认全选」的平台实现杠杆。
        // 反射不可用（Database 插件类缺席）时原样返回基础实例 = 维持现态，不劣化。
        Object effectiveExecOption = wholeScriptExec ? wholeScriptExecOption(execOption) : execOption;
        // T-MysqlGStrip T-MG-02：提交链总口（已备控制台路径）——提交文本源是控制台编辑器文档，
        // 执行前剥离 `\G` 后缀并把剥后文本**回写编辑器文档**（所见=所执行，避免显示/执行分叉）。
        // 回写复用既有 T-ExecDocSync 写纪律（dispatchDocWriteOnEdt → writeVfTextViaDocument：
        // setText→commit→saveDocument 同临界区，BGT 调用派 EDT），不新开写路径。
        // 方言按控制台数据源 JDBC URL 判定：非 MySQL 系方言不剥；URL 不可得 → 默认剥离（计划 §三定案）。
        VirtualFile consoleVfForG = null;
        try {
            Object g = console.getClass().getMethod("getVirtualFile").invoke(console);
            consoleVfForG = g instanceof VirtualFile ? (VirtualFile) g : null;
        } catch (Throwable ignored) {
            // getOrNull 语义
        }
        MysqlClientGSuffix.Result gStrip = stripGSuffixForSubmit(editor.getDocument().getText(),
                ExecConsoleContext.dataSourceUrl(console));
        if (gStrip.stripped()) {
            LOG.info("[T-MysqlGStrip] stripped \\G suffix before submit "
                    + "(len " + gStrip.original().length() + "->" + gStrip.submitText().length()
                    + ", vf=" + (consoleVfForG != null ? consoleVfForG.getName() : null) + ")");
            if (consoleVfForG != null) dispatchDocWriteOnEdt(project, consoleVfForG, gStrip.submitText());
        }
        // T-DdlCacheInvalidate（O-12）：DDL/事务提交语句在提交前失效插件自持 schema 缓存
        // （DBX object_cache 语义：失效须在结果返回前、失效失败只记日志不吞执行结果——
        // Oracle 系 DDL 隐式提交，DDL 报错不代表没提交）。全执行链唯一提交收口点；
        // SQL 文本取控制台编辑器文档（三调用路径共用同一文档，提交时刻即执行内容）。
        String executedSql = gStrip.stripped() ? gStrip.submitText() : editor.getDocument().getText();
        try {
            MetadataInvalidator.invalidateAfterExecution(executedSql);
        } catch (Throwable t) {
            LOG.warn("[T-DdlCacheInvalidate] invalidate hook failed (execution continues)", t);
        }
        lastReportedError = null;
        // T-ExecSilentNoop：新执行亦重置提示去重标记——同一次执行只报一次提示。
        executionHintReported = false;
        // T-ExecSilentNoop：notSubmitted 提示携带本次目标数据源名（Kotlin 侧默认参 dsName = sharedDataSourceName）
        final String dsName = sharedDataSourceName;
        consoleErrorBaseline = consoleHistoryLength(console);
        // T-CEC-01：取证日志去重基线同批重置——每次执行至多记一条未识别尾部。
        lastUnrecognizedTail = null;
        try {
            Class<?> consoleClass = Class.forName(CONSOLE_CLASS);
            Method execute = consoleClass.getMethod("executeQueries", Editor.class, Class.forName("com.intellij.database.script.ScriptModel"), effectiveExecOption.getClass());

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
                        // T-MysqlGStrip：剥离后的文本才是本次执行内容（回写失败时编辑器文档可能仍是
                        // 原文，此处以剥后文本为准收敛 vf，保证提交内容恒定无 `\G`）
                        String consoleText = gStrip.stripped() ? gStrip.submitText() : editor.getDocument().getText();
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
                            final Object execOptionFinal = effectiveExecOption;
                            final boolean[] nativeRunRef = {false};
                            if (vfPsi != null && !demoteToFallback) {
                                Class<?> providerClass = Class.forName(CONSOLE_PROVIDER_CLASS);
                                Method findScriptModel = providerClass.getMethod("findScriptModel", PsiFile.class, com.intellij.psi.PsiElement.class, Editor.class, effectiveExecOption.getClass());
                                final Method[] doRunMethodRef = {null};
                                try {
                                    doRunMethodRef[0] = providerClass.getMethod("doRunQueryInConsole", consoleClass, Class.forName("com.intellij.database.console.JdbcConsoleProvider$Info"));
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                                if (doRunMethodRef[0] == null) {
                                    LOG.warn("[M36] native doRunQueryInConsole method not found, fallback");
                                } else {
                                    // write-safe: UI-only（纯 Swing/只读，无文档写）
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
                                                        // M36.5.48：findScriptModel 空 = 无可执行语句——显示**真实原因**，
                                                        // 而非笼统凭据超时提示（此前误用 execute.credential.hint，误导用户查密码/驱动）。
                                                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                                                        ApplicationManager.getApplication().invokeLater(() -> {
                                                            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.noStatement"));
                                                        }, ModalityState.any());
                                                        return;
                                                    }
                                                    LOG.info("[M36] native findScriptModel OK, doRunQueryInConsole");
                                                    doRunMethodRef[0].invoke(null, console, info);
                                                    LOG.info("[M36] native query SUBMITTED (starting result watch)");
                                                    afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, false, executedSql, onErrorRich);
                                                } catch (Throwable t) {
                                                    Throwable cause = (t instanceof InvocationTargetException) ? ((InvocationTargetException) t).getTargetException() : t;
                                                    final Throwable fCause = cause;
                                                    // T-FP-02：反射包装层（InvocationTargetException）是普通异常，
                                                    // 直接判外层会漏判——须解包后再判 fatal 并透传（见 util/FatalErrors.kt）
                                                    if (FatalErrors.isFatal(cause)) throw (Error) cause;
                                                    LOG.warn("[M36] native run chain FAILED: " + cause.getClass().getName() + ": " + cause.getMessage()
                                                            + "\n    " + stackTrace(cause), cause);
                                                    // M36.5.45：native 执行链失败必须通知 UI（此前仅 LOG.warn，用户看不到任何错误）
                                                    // T-ErrorLocate T-EL-02：富通道并行投递（SQLState/errorCode/驱动位置结构化），
                                                    // 旧 onError(String) 消费方零改动
                                                    // T-ErrorLocateUsability T-ELU-03（O-49）：接上被执行文本长度——
                                                    // 越界钳制此前只在单测生效（调用方全都不传 sqlMaxLength）
                                                    // write-safe: UI-only（纯 Swing/只读，无文档写）
                                                    ApplicationManager.getApplication().invokeLater(() -> {
                                                        deliverError(
                                                                onErrorRich, onError, fCause,
                                                                fCause.getMessage() != null ? fCause.getMessage() : fCause.toString(),
                                                                executedSql.length());
                                                    }, ModalityState.any());
                                                }
                                            });
                                        } catch (Throwable t) {
                                            if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
                                            LOG.warn("[M36] submitTransactionLater FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                                        }
                                    }, ModalityState.any());
                                    nativeRunRef[0] = true;
                                }
                            }
                            if (!nativeRunRef[0]) {
                                LOG.info("[M36][EXEC] fallback path (native not used: " + (demoteToFallback ? "demoted" : "no vf psi or doRun missing") + "), executeQueries(freshModel)");
                                execute.invoke(console, editor, freshModel, execOptionFinal);
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
                        // T-FP-02 定例（守卫解包锚点尾款收口）：解包 ITE 后判 fatal 并透传；本块为
                        // catch(Exception) 形态、不入守卫解包锚点计数，收口语录见守卫落地集表注。
                        if (FatalErrors.isFatal(cause)) throw (Error) cause;
                        String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                        final String fMsg = msg;
                        final Throwable fCause = cause;
                        // 打开期间默认 modality 的 invokeLater 冻结（沙盒 12:10:02→12:10:50 实证
                        // 48s 挂起），换入/重试/提示全不跑 → "执行中"卡住。any() 使任务在模态
                        // pump 中照常执行（纯 Swing 更新，安全）。
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        ApplicationManager.getApplication().invokeLater(() -> {
                            // T-ELU-01/03：经 deliverError 收口——富通道优先、**不再**同时投旧通道
                            // （旧通道会抹掉定位按钮），并接上被执行文本长度作越界钳制上界
                            deliverError(onErrorRich, onError, fCause, fMsg, executedSql.length());
                        }, ModalityState.any());
                        return;
                    }
                    if (submittedRef[0]) {
                        // 兜底路径（executeQueries 同步提交）：监听在此启动；原生路径由事务内
                        // afterSubmitted 启动，此处不再重复（防双重监听互相顶掉结果）。
                        afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, false, executedSql, onErrorRich);
                    }
                });
            });
        } catch (Throwable e) {
            LOG.warn("[M36] executeOnConsole FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            Throwable cause = (e instanceof InvocationTargetException) ? ((InvocationTargetException) e).getTargetException() : e;
            // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，只判外层会漏判——
            // 解包后判 fatal 并透传（本链跑在池化线程，透传即线程终止、平台记录现场）；非 fatal 路径行为不变。
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
            final Throwable fCause = cause;
            // T-ELU-01/03：经 deliverError 收口（富通道优先 + 提交文本长度钳制上界）。
            // 本对回调在 executeOnPooledThread 的后台线程上（非 EDT）——deliverError 只做纯计算
            // 与回调转发，不含 Swing 操作，故不改变既有线程契约。
            deliverError(onErrorRich, onError, fCause,
                    fCause.getMessage() != null ? fCause.getMessage() : fCause.toString(),
                    executedSql.length());
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
            boolean demotedOverride,
            @Nullable String sqlText,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
        swapGeneration++;
        // write-safe: UI-only（纯 Swing/只读，无文档写）
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
                startResultTabRetry(project, ui, onError, names, swapGeneration, dsName, demotedOverride, sqlText, onErrorRich);
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

    /**
     * T-DdlPostInvalidate（O-17）：执行收尾兜底失效——{@link MetadataInvalidator#invalidateAfterExecution}
     * 内部先 mayChangeMetadata 预判（命中才动作）且动作幂等，入口+收尾双调全程失效次数有界（1+1）。
     * <b>成功与失败/超时收尾都调</b>（DBX：失败的 DDL 可能已隐式提交——Oracle），把「过程内 DDL/
     * 预判漏判/中途结构变化」三类窗口关死；动作异常只记日志不吞收尾结果。
     */
    private static void runPostExecutionInvalidate(@Nullable String sqlText, Logger LOG) {
        if (sqlText == null) return;
        try {
            MetadataInvalidator.invalidateAfterExecution(sqlText);
        } catch (Throwable t) {
            LOG.warn("[T-DdlPostInvalidate] post-execution invalidation failed", t);
        }
    }

    /** 结果 Tab 激活重试。（T-ExecSilentNoop：dsName/demotedOverride 供 40 拍超时窗口提示三分流） */
    private static void startResultTabRetry(
            @NotNull Project project,
            Object ui,
            @Nullable Consumer<String> onError,
            Set<String> preExistingNames,
            int generation,
            String dsName,
            boolean demotedOverride,
            @Nullable String sqlText,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
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
                runPostExecutionInvalidate(sqlText, LOG);
                lastReportedError = err;
                LOG.info("[M36] early console error detected (attempts=" + attempts[0] + "): " + (err.length() > 120 ? err.substring(0, 120) : err));
                // write-safe: UI-only（纯 Swing/只读，无文档写）
                // T-ELU-01/03：DBN 写进 console history 的**语句级**错误也走富通道——
                // 这是「正常 SQL 执行失败」的主路径（提交链不抛异常，错误只落在 history），
                // 原实现只投旧 onError ⇒ 定位按钮永不出现。cause 传 null（文本里已有
                // 位置 marker，SQLState/errorCode 由驱动器在 history 文本中给出）。
                final String fErr = err;
                ApplicationManager.getApplication().invokeLater(() -> {
                    deliverError(onErrorRich, onError, null, fErr, sqlText != null ? sqlText.length() : null);
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
                runPostExecutionInvalidate(sqlText, LOG);
                // M36.5.48 勘误：此处**不**调用 extractConsoleError——结果 Tab 复用成功时
                // console history 含正常 SQL 文本（如 "WHERE t.id = 1"），会被误判为错误并
                // onError（日志实证 `extracted console error: WHERE t.id = 1`，误报 + 一闪而过）。
                try {
                    Method select = ui.getClass().getMethod("selectAndFocus", Content.class, Boolean.TYPE, Boolean.TYPE);
                    select.invoke(ui, result, false, true);
                } catch (Throwable ex) {
                    LOG.warn("[M36] select result tab FAILED: " + ex.getMessage());
                }
                refreshResultsComponent(ui, LOG, true, generation, onError, onErrorRich, sqlText);
            } else {
                boolean dumb = false;
                try { dumb = DumbService.isDumb(project); } catch (Throwable ignored) { /* ignore */ }
                int limit = dumb ? 100 : 40;
                if (attempts[0] >= limit) {
                    timerHolder[0].stop();
                    runPostExecutionInvalidate(sqlText, LOG);
                    LOG.info("[M36] result tab not appeared after " + attempts[0] + " attempts (dumb=" + dumb + ")");
                    String realError = extractConsoleError(sharedConsole != null ? sharedConsole : preparedConsole, LOG);
                    // T-CEC-03：判据认不出的失败也要有下文——控制台确有新增输出（去掉本次 SQL
                    // 回显后的残余）时如实呈现，而不是把语法错归因成密码/驱动（真机 P15 实证）。
                    String residue = realError == null
                            ? extractConsoleResidue(sharedConsole != null ? sharedConsole : preparedConsole, sqlText, LOG)
                            : null;
                    // T-ExecSilentNoop：40 拍超时窗口与 8s 监控共用 executionHintReported 去重。
                    // 真实错误优先（DBN 已把失败写进 history 才叫真失败/真超时）；无错误痕迹时
                    // 不再一律归因凭据——先看本次是否探到"未提交"（freshModel 回落，DBN 侧静默
                    // 拒绝），按情况给 notSubmitted / timeout / indexing 三种真实提示。
                    if (realError == null && !executionHintReported) {
                        executionHintReported = true;
                        String message;
                        if (residue != null) {
                            // 如实标注：这是控制台新增输出，**不是**已识别的错误形态（不猜原因）
                            message = MpSqlVisualizerBundle.message("execute.error.unknownShape") + "\n" + residue;
                        } else {
                            message = dumb
                                    ? MpSqlVisualizerBundle.message("execute.indexing.hint")
                                    : (demotedOverride
                                            ? MpSqlVisualizerBundle.message("execute.notSubmitted.hint", dsName != null ? dsName : "")
                                            : MpSqlVisualizerBundle.message("execute.timeout.hint"));
                        }
                        LOG.info("[M36] result tab not appeared, no console error (dumb=" + dumb + ", "
                                + "demoted=" + demotedOverride + ", residue=" + (residue != null) + ") - hint via 40-tick window");
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        final String fHint = message;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (onError != null) onError.accept(fHint);
                        }, ModalityState.any());
                    } else if (realError != null) {
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        // T-ELU-01/03：语句级错误同早检路径，走富通道收口
                        final String fErr = realError;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            deliverError(onErrorRich, onError, null, fErr, sqlText != null ? sqlText.length() : null);
                        }, ModalityState.any());
                    }
                }
            }
        };
        Timer timer = new Timer(400, listener);
        timerHolder[0] = timer;
        timer.start();
    }

    /** 结果 Tab 激活重试（使用当前 swapGeneration 作为监听代际，Kotlin 默认参缺省形态）。 */
    private static void startResultTabRetry(
            @NotNull Project project,
            Object ui,
            @Nullable Consumer<String> onError,
            Set<String> preExistingNames,
            String dsName,
            boolean demotedOverride) {
        startResultTabRetry(project, ui, onError, preExistingNames, swapGeneration, dsName, demotedOverride, null, null);
    }

    /** M36.5：读取 last-used 数据源名（prepareConsole 静默复用；异常兜底 null）。 */
    private static String lastRunnerName(Project project) {
        return lastUsedRunner(project).displayName();
    }

    /** M36.5：记录 last-used 数据源名。 */
    private static void rememberRunnerName(Project project, String name) {
        rememberRunner(project, name, null);
    }

    /**
     * T-SI-02：last-used 记忆（UUID 身份 + 显示名，双字段）。
     * {@code dataSourceId} 来自 LocalDataSource.getUniqueId（{@link DataSourceSettings} 既有反射封装）。
     *
     * @param dataSourceId 数据源 UUID（取不到为 null）
     * @param displayName  显示名（取不到为 null）
     */
    public record LastUsedMemory(@Nullable String dataSourceId, @Nullable String displayName) {}

    /**
     * T-SI-02：记忆命中解析结果。{@code index} 命中 runner 下标；{@code ambiguous} 表示「记忆在、但同名
     * 多条无法唯一对应」——调用方据此清记忆走弹窗（不猜首条）。
     *
     * @param index     命中的 runner 下标（未命中 null）
     * @param ambiguous 同名多条歧义标记
     */
    public record LastUsedResolution(@Nullable Integer index, boolean ambiguous) {}

    /**
     * T-SI-02：last-used 记忆命中判定（纯函数，默认 test 通道可覆盖）。
     *
     * <p>命中顺序（UUID 优先，名字回退，皆不唯一即弃）：</p>
     * <ol>
     *  <li>UUID 精确命中——{@code dataSourceNameById} 取该 UUID 当前的数据源名（数据源被改名也认，
     *      这是 UUID 相对显示名的增益），再按该名在 runner 列表里唯一匹配；</li>
     *  <li>名字命中且唯一——旧键（显示名）兼容路径，升级用户无感；</li>
     *  <li>其余（未命中 / 同名多条）→ 弃记忆，调用方走弹窗重新选择。</li>
     * </ol>
     *
     * <p>{@code dataSourceNameById} 返回 null 表示该 UUID 的数据源已不存在——UUID 分量失效，退回名字判定。</p>
     */
    public static LastUsedResolution resolveLastUsedRunnerIndex(
            @NotNull LastUsedMemory memory,
            @NotNull List<String> runnerNames,
            @NotNull java.util.function.Function<String, String> dataSourceNameById) {
        String id = memory.dataSourceId() != null && !memory.dataSourceId().isBlank() ? memory.dataSourceId() : null;
        if (id != null) {
            String dsName = null;
            try {
                dsName = dataSourceNameById.apply(id);
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
            if (dsName != null && !dsName.isBlank()) {
                List<Integer> hits = new ArrayList<>();
                for (int i = 0; i < runnerNames.size(); i++) {
                    if (runnerNames.get(i).equals(dsName)) hits.add(i);
                }
                if (hits.size() == 1) return new LastUsedResolution(hits.get(0), false);
                if (hits.size() > 1) return new LastUsedResolution(null, true);
            }
        }
        String name = memory.displayName() != null && !memory.displayName().isBlank() ? memory.displayName() : null;
        if (name != null) {
            List<Integer> hits = new ArrayList<>();
            for (int i = 0; i < runnerNames.size(); i++) {
                if (runnerNames.get(i).equals(name)) hits.add(i);
            }
            if (hits.size() == 1) return new LastUsedResolution(hits.get(0), false);
            if (hits.size() > 1) return new LastUsedResolution(null, true);
        }
        return new LastUsedResolution(null, false);
    }

    /** T-SI-02：读 last-used 记忆双字段（异常兜底全空）。 */
    private static LastUsedMemory lastUsedRunner(Project project) {
        try {
            com.intellij.ide.util.PropertiesComponent pc = com.intellij.ide.util.PropertiesComponent.getInstance(project);
            return new LastUsedMemory(pc.getValue(LAST_RUNNER_ID_KEY), pc.getValue(LAST_RUNNER_KEY));
        } catch (Throwable ignored) {
            return new LastUsedMemory(null, null);
        }
    }

    /**
     * T-SI-02：写 last-used 记忆。{@code dataSourceId} 取不到（弹窗路径尚无控制台）时<b>清掉身份键</b>
     * ——留着上一轮的 UUID 会指认另一个数据源（身份说谎比没有身份更糟）。
     */
    private static void rememberRunner(Project project, String name, @Nullable String dataSourceId) {
        try {
            com.intellij.ide.util.PropertiesComponent pc = com.intellij.ide.util.PropertiesComponent.getInstance(project);
            pc.setValue(LAST_RUNNER_KEY, name);
            if (dataSourceId == null || dataSourceId.isBlank()) pc.unsetValue(LAST_RUNNER_ID_KEY);
            else pc.setValue(LAST_RUNNER_ID_KEY, dataSourceId);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** T-SI-02：清 last-used 记忆（两个键一起清，避免半截身份残留）。 */
    private static void forgetLastRunner(Project project) {
        try {
            com.intellij.ide.util.PropertiesComponent pc = com.intellij.ide.util.PropertiesComponent.getInstance(project);
            pc.unsetValue(LAST_RUNNER_KEY);
            pc.unsetValue(LAST_RUNNER_ID_KEY);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** T-SI-02：从已建控制台取数据源 UUID（复用 {@link DataSourceSettings} 既有反射封装；取不到 null）。 */
    @Nullable
    private static String consoleDataSourceId(@Nullable Object console) {
        try {
            if (console == null) return null;
            Object ds = console.getClass().getMethod("getDataSource").invoke(console);
            if (ds == null) return null;
            return DataSourceSettings.getUniqueId(ds);
        } catch (Throwable ignored) {
            return null;
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
            // T-FP-02 定例（守卫解包锚点尾款收口）：反射包装层是普通异常，只判外层会漏判——
            // 解包后判 fatal 并透传给调用方捕获边界；非 fatal 路径行为不变（仍走 onError 面板回调降级）。
            if (FatalErrors.isFatal(cause)) throw (Error) cause;
            LOG.warn("[M36] runRunner FAILED: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
            // T-DsCover：预览默认准备路径静默失败（被动展示不应弹模态打断）；
            // 并清 last-used 记忆，避免驱动损坏的数据源每次预览都白失败一次。
            // T-Perf-01：失败同时回调 onError（面板行内红字 + 「执行中」态恢复），静默与非静默路径都回调。
            String failMsg = MpSqlVisualizerBundle.message("execute.datasource.failed",
                    cause.getMessage() != null ? cause.getMessage() : cause.toString());
            if (onError != null) onError.accept(failMsg);
            if (silent) {
                // T-SI-02：两个键一起清（只清旧键会留下指认另一个数据源的 UUID 身份）
                forgetLastRunner(project);
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
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
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
        // T-ExecDocSync2 F4：refresh 前强制收敛——写点之后、refresh 之前若被用户键入/其他写方
        // 插入（doc modified-unsaved + 磁盘≠文档），refresh 恰会命中 MemoryDiskConflictResolver
        // 弹窗条件；findFileByIoFile（与 projectExecFile 内裸调同形态）命中即过一遍分叉探测 +
        // EDT 派发 doc 写收敛，正常恒 no-op。详见 prewarmExecTempFileInner 同款注释。
        try {
            VirtualFile vf0 = LocalFileSystem.getInstance().findFileByIoFile(ioFile);
            if (vf0 != null) {
                syncExecVfContentIfNeeded(project, vf0, sql);
            }
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            return failTempFile("preRefreshConverge", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // T-ExecCopyFix F2b：refresh 段包 WriteIntentReadAction（详见方法 Javadoc）；
        // mapDialect 与 findFile 维持原线程原语义（setMapping 须 EDT；findFile 沿用
        // ReadAction.compute 既有包装，与 T-M36.6-03 前行为一致）。
        VirtualFile vf;
        try {
            vf = WriteIntentReadAction.compute(
                    (com.intellij.openapi.util.Computable<VirtualFile>) () ->
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile));
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
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
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            return failTempFile("syncVfContent", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        mapDialectBeforePsi(project, vf, LOG);
        PsiFile psi;
        try {
            psi = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
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
     * T-EC-02 的裸写守卫（canFallBackToRawWrite）已由 T-ExecDocSync3 整体退役——真机日志实证其
     * 判据不可靠（EDT 忙时 getCachedDocument 恒 null，而排队中的写任务随后会建出 document），
     * 现策略见 {@link #projectExecFile} 内注释：VFS 已有该文件即绝不裸写。
     */

    /**
     * T-MysqlGStrip T-MG-02：提交链总口的 {@code \G} 剥离决策（{@link #chooseAndPrepare} 新建控制台链与
     * {@link #executeOnConsole} 已备控制台链共用同一入口）。
     *
     * @param sqlText       原始 SQL 文本
     * @param dataSourceUrl 控制台数据源 JDBC URL（方言判定输入）；null = 方言不可得 →
     *                      {@link MysqlClientGSuffix#stripForDialect} 按 MySQL 处理（默认剥离）
     * @return 两态结果——{@link MysqlClientGSuffix.Result#submitText} 进 exec 文件/控制台文档，
     *         {@link MysqlClientGSuffix.Result#original} 留给历史链（历史记原文、提交文本剥离）
     *
     * <p>public 供 headless 用例直测（沙盒默认 test 任务无 Database 插件，
     * chooseAndPrepare/executeOnConsole 在默认 test 任务不可达——ExecTempFileFixTest 同款口径）。</p>
     */
    public static MysqlClientGSuffix.Result stripGSuffixForSubmit(String sqlText, @Nullable String dataSourceUrl) {
        return MysqlClientGSuffix.stripForDialect(sqlText, MysqlClientGSuffix.dialectFromUrl(dataSourceUrl));
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
        // T-PreviewFast P4：程序性写入整段开闸——文档变更事件同步派发给自动落盘监听，
        // 闸门开着即跳过（内容由本函数自己落盘，无需 1.5s 后再写一遍）
        ExecDocAutosaveGate.suppressDuring(() -> {
            Document fileDoc = FileDocumentManager.getInstance().getDocument(vf);
            if (fileDoc == null) {
                try {
                    vf.setBinaryContent(text.getBytes(StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                    // ignore
                }
            } else {
                if (!fileDoc.getText().equals(text)) {
                    fileDoc.setText(text);
                }
                // setText 的 write command 结束时自动 commit；显式 commitDocument 双保险
                //（同步后同 tick 内 findFile 的 PSI 立即对齐，M36.5.20 语义保留）
                PsiDocumentManager.getInstance(project).commitDocument(fileDoc);
                // T-EC-01 核心：同临界区落盘——磁盘即时与内存一致，document 不滞留 modified 态
                // T-PreviewFast P5：落盘前先判「已修改未保存」——内容本就一致时（连续预览同一
                // SQL 的常见路径）跳过磁盘写，消除每次预览的无效落盘与随之而来的 VFS/索引噪音
                FileDocumentManager fdm = FileDocumentManager.getInstance();
                if (fdm.isDocumentUnsaved(fileDoc)) {
                    fdm.saveDocument(fileDoc);
                }
            }
            return null;
        });
    }

    /** BGT 派 EDT document 写的有界等待预算（毫秒）——DialectPreMapping 同款取值；EDT 病态冻结时超时放行走守卫降级。 */
    private static final long EDT_EXEC_DOC_SYNC_WAIT_MS = 2000L;

    /**
     * T-EC-01/T-EC-03 编排：document 写统一派发 EDT——EDT 调用方同步直执行；BGT 两段式
     * invokeLater + 有界 latch 等待（DialectPreMapping.ensureMapped 同款：BGT 等 EDT，
     * EDT 永不反向阻塞，超时自愈不互锁）。失败注入缝 {@link #execWriteActionFailureInjection}
     * 在 {@link #docWriteOnce} 内统一生效（EDT/BGT 两形态等价）。
     *
     * <p>T-PreviewFast P2：{@code waitBudgetMs <= 0} 时走 <b>fire-and-forget</b>（派发后立即返回 true）——
     * 预热是投机性写入，T-ExecDocSync3 取消裸写后超时已无副作用，没必要让 BGT 白等 2 秒。</p>
     *
     * <p>T-FixWriteSafe（T-WriteSafeCtx 留档补口）：两处投递原用 1 参 {@code invokeLater}，即隐式
     * {@code ModalityState.defaultModalityState()}。平台源码实证（{@code openapi/application/modality.kt}：
     * {@code defaultModalityImpl()} 依次取 {@code ProgressManager.currentProgressModality} → 线程/协程上下文
     * modality → NON_MODAL）：BGT 常态取到 NON_MODAL（{@code TransactionGuardImpl:37} 构造期即登记为
     * write-safe，与显式 {@code nonModal()} 等价），但<b>在模态进度/带 modality 上下文里会取到该模态</b>；
     * 若该模态本身是「写不安全进入」的（{@code enteredModality:188-190} 存的是进入那一刻
     * {@code myWritingAllowed} 的快照），排队的 runnable 就在该模态事件泵内以 write-unsafe 身份执行 →
     * 改文档即 {@code Write-unsafe context!}。故两处投递的<b>写体</b>统一过 {@link DocWriteContext} 闸门：</p>
     * <ul>
     *  <li>fire-and-forget：整拍改以 nonModal 投递（{@code invokeLaterWriteSafe}）——本路径无有界等待，
     *      模态存续期延后一拍正是「模态期间禁改文档」的正确语义，不会撞任何预算；</li>
     *  <li>有界等待：<b>保留默认模态投递</b>（保证能在该模态事件泵内执行，常态与改前逐字一致），
     *      只把写体过闸（{@code runWriteSafe}）：常态判为可写 → 就地写 + 同拍 countDown；仅当判为不安全
     *      才转 nonModal 投递并<b>立即</b> countDown（ok=false）——不让调用方白等 2 秒预算。延后那一拍
     *      重跑 {@link #docWriteOnce} 是幂等的（内容相同不 setText、已落盘不 saveDocument），且该超时/未完成
     *      路径已由 T-ExecDocSync3 定为无害（不裸写、内容旧一拍由后续同步链收敛）。</li>
     * </ul>
     */
    private static boolean dispatchDocWriteOnEdt(Project project, VirtualFile vf, String sql) {
        return dispatchDocWriteOnEdt(project, vf, sql, EDT_EXEC_DOC_SYNC_WAIT_MS);
    }

    /** waitBudgetMs 显式重载（Kotlin 默认参数 {@code waitBudgetMs = EDT_EXEC_DOC_SYNC_WAIT_MS} 的显式形态）。 */
    private static boolean dispatchDocWriteOnEdt(Project project, VirtualFile vf, String sql, long waitBudgetMs) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) return docWriteOnce(project, vf, sql);
        if (waitBudgetMs <= 0L) {
            // project 即 owner disposable：项目关闭后丢弃该拍（写目标就是项目内临时文件，重跑无意义）
            DocWriteContext.invokeLaterWriteSafe(project, "exec prewarm doc write (fire-and-forget)", () -> {
                try {
                    docWriteOnce(project, vf, sql);
                } catch (Throwable ignored) {
                    // runCatching 语义
                }
            });
            LOG.info("[M36] exec doc write dispatched fire-and-forget (T-PreviewFast P2)");
            return true;
        }
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        app.invokeLater(() -> {
            try {
                DocWriteContext.runWriteSafe(project, "exec doc sync (bounded)", () ->
                        ok[0] = docWriteOnce(project, vf, sql));
            } finally {
                done.countDown();
            }
        });
        boolean signaled;
        try {
            signaled = done.await(waitBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException t) {
            signaled = false;
            Thread.currentThread().interrupt();
        }
        if (!signaled) {
            LOG.warn("[M36] exec doc write EDT dispatch timeout after " + waitBudgetMs + "ms (EDT busy?)");
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

    // ------------------------------------------------------------------
    // T-ExecDocSync2 F5/F6：执行文档防抖自动落盘 + 冲突探针（第四轮）
    // ------------------------------------------------------------------

    /**
     * exec 文件 VFS 路径匹配（F5 自动落盘 / F6 探针共用过滤器；VFS 路径恒 '/' 分隔）。
     * public 供表驱动测试。
     */
    public static boolean isExecVfPath(String path) {
        return path.endsWith("/" + cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME + "/exec/mpsql-exec.sql");
    }

    /** 防抖窗（毫秒）——连击合并，窗内 modified-unsaved 暴露面上界。 */
    private static final long EXEC_DOC_AUTOSAVE_DELAY_MS = 1500L;

    /** F5 防抖调度器（daemon，不阻 IDE 退出；任务本身极轻——路径过滤后仅换句柄）。 */
    private static final java.util.concurrent.ScheduledExecutorService execDocAutosaveExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mpv-exec-doc-autosave");
                t.setDaemon(true);
                return t;
            });

    /** F5 待执行的防抖任务（新变更到达即顶掉旧任务）。 */
    private static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> execDocAutosavePending =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * T-PS4-11 快判缓存：上次命中 exec 路径的 document（<b>弱引用</b>——防强引用滞留已关闭文档，
     * GC 后 get()==null 自然退回原链探测）。companion 监听是应用级的，IDE 全局每次文档变更
     * 都会进 {@link #scheduleExecDocAutosave}；exec 文档连续变更（用户在控制台键入）是唯一需要跟进
     * 落盘的形态，其余文档每次都白付 getFile + endsWith。identity 比对（==）而非 equals，
     * document 换实例（文件关闭重开）即视为未命中。
     */
    private static final java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<Document>> lastExecDocRef =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * F6 探针已安装项目集（连接随项目 disposable 自动断开，此处只防重复安装）。
     *
     * <p>T-PS5-08：改<b>弱键集</b>（{@code WeakHashMap} 键弱引用：项目唯一强引用消失即可回收）——此前强引用
     * 集把项目对象钉到显式 {@code remove}（走 {@link #hookProjectClose} 的项目 Disposer，但该函数在
     * {@code hookProjectsClose} 已登记时会提前 return、不再登记第二次项目级 Disposer，留下不可回收窗口）。
     * 去重/判空语义不变（条目只用于「是否已安装」判定）。</p>
     */
    private static final Set<Project> conflictProbeProjects =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Project, Boolean>()));

    /** T-PS5-08 测试缝：探针项目集（弱引用可达性断言用；生产链路无调用点）。 */
    public static Set<Project> conflictProbeProjectsForTest() {
        return conflictProbeProjects;
    }

    /**
     * F5：应用级 document 变更监听（惰性安装一次）——exec 文档每次变更（用户在控制台手改/
     * 插件 setText）后 1.5s 防抖自动落盘。动机：console 编辑器文档即 exec 文件 document
     * （attachConsole 挂载点），用户手改滞留「已修改未保存」是第四轮冲突弹窗「内存中更改」
     * 一半的唯一插件拦不到的源头；帧失活自动保存最快也要切帧才触发，防抖落盘把暴露面压到
     * 防抖窗内。落盘方向恒 doc→disk（saveDocument），不回读不改写用户内容。
     */
    private static void installExecDocAutosaveListener() {
        // T-PS4-11：改两参重载 addDocumentListener(listener, disposable)，挂 [ExecDocAutosaveHost]
        // （应用级轻服务 = Disposable parent）——此前单参裸装、装后永不卸载，插件动态卸载
        // （DynamicInstallSupport → 平台 unloadPlugin）后监听残活并钉住旧 classloader，正是
        // 动态卸载屏障要杜绝的形态。挂靠定案依据见 [ExecDocAutosaveHost] KDoc。
        ExecDocAutosaveHost.getInstance().ensureListenerInstalled(
                new com.intellij.openapi.editor.event.DocumentListener() {
                    @Override
                    public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent e) {
                        scheduleExecDocAutosave(e.getDocument());
                    }
                });
    }

    private static void scheduleExecDocAutosave(Document doc) {
        // T-PreviewFast P4：程序性写入不排自动落盘——闸门开着说明本次变更来自插件写入
        //（写入方自己已落盘），防抖落盘只为用户手改兜底。保持最先判（闸门语义先于快判，
        // 程序性写入高频路径不因快判命中而漏抑制）
        if (ExecDocAutosaveGate.isSuppressed()) return;
        // T-PS4-11 快判：上次命中的 document 直接身份比对短路（免 getFile + endsWith）；
        // 未命中走原链探测回退，确认命中后更新缓存。弱引用被 GC 后 get()==null 视为未命中。
        java.lang.ref.WeakReference<Document> cachedRef = lastExecDocRef.get();
        if (cachedRef == null || cachedRef.get() != doc) {
            VirtualFile vf = null;
            try {
                vf = FileDocumentManager.getInstance().getFile(doc);
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
            if (vf == null || !isExecVfPath(vf.getPath())) return;
            lastExecDocRef.set(new java.lang.ref.WeakReference<>(doc));
        }
        java.util.concurrent.Future<?> prev = execDocAutosavePending.getAndSet(
                execDocAutosaveExecutor.schedule(() -> flushExecDocAutosave(doc),
                        EXEC_DOC_AUTOSAVE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS));
        if (prev != null) prev.cancel(false);
    }

    private static void flushExecDocAutosave(Document doc) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                if (!autosaveExecDocNow(doc)) return;
                Logger.getInstance(DataSourceSelector.class)
                        .info("[M36] exec doc autosaved (debounced " + EXEC_DOC_AUTOSAVE_DELAY_MS + "ms, T-ExecDocSync2 F5)");
            } catch (Throwable e) {
                Logger.getInstance(DataSourceSelector.class)
                        .warn("[M36] exec doc autosave FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    /**
     * F5 原语：EDT 上守卫落盘——doc 无主文件/已失效/未修改即 no-op（false）；确需落盘包
     * WriteAction saveDocument（T-EC-01 同款落盘语义）。public 供测试。
     */
    public static boolean autosaveExecDocNow(Document doc) throws java.io.IOException {
        FileDocumentManager fdm = FileDocumentManager.getInstance();
        VirtualFile vf = null;
        try {
            vf = fdm.getFile(doc);
        } catch (Throwable ignored) {
            // getOrNull 语义
        }
        if (vf == null || !vf.isValid()) return false;
        // 262：Document.isModified 已从接口移除——「已修改未保存」唯一查询是 isDocumentUnsaved
        if (!fdm.isDocumentUnsaved(doc)) return false;
        WriteAction.run((com.intellij.util.ThrowableRunnable<java.io.IOException>) () -> fdm.saveDocument(doc));
        return true;
    }

    /**
     * F6：冲突探针——「文件缓冲区冲突」弹窗（MemoryDiskConflictResolver）响应的是平台对修改中
     * document 发起的 reload，reload 前平台经 FileDocumentManagerListener.beforeFileContentReload
     * 广播（与弹窗实现同 topic，监听先后不保证，故仅取证不拦截）。exec 文件命中即落 warn 指纹
     * （doc/vf 长度与 stamp、unsaved 态）：F1-F5 全上后若真机仍复发，idea.log 直出现场数据，
     * 替代人工复现排查。经项目 messageBus 连接（随项目 disposable 自动断开）。
     */
    private static void installExecConflictProbe(Project project) {
        if (!conflictProbeProjects.add(project)) return;
        project.getMessageBus().connect(project).subscribe(
                com.intellij.openapi.fileEditor.FileDocumentManagerListener.TOPIC,
                new com.intellij.openapi.fileEditor.FileDocumentManagerListener() {
                    @Override
                    public void beforeFileContentReload(@NotNull VirtualFile vf, @NotNull Document document) {
                        if (!isExecVfPath(vf.getPath())) return;
                        FileDocumentManager fdm = FileDocumentManager.getInstance();
                        Boolean unsaved = null;
                        try {
                            unsaved = fdm.isDocumentUnsaved(document);
                        } catch (Throwable ignored) {
                            // getOrNull 语义
                        }
                        Logger.getInstance(DataSourceSelector.class)
                                .warn("[T-ExecDocSync2][conflict-probe] reload-with-unsaved-doc: " + vf.getPath()
                                        + ", docLen=" + document.getTextLength() + ", docStamp=" + document.getModificationStamp()
                                        + ", vfLen=" + vf.getLength() + ", vfStamp=" + vf.getModificationStamp()
                                        + ", unsaved=" + unsaved);
                    }
                });
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
     *  <li><b>③ 裸写兜底守卫</b>（T-EC-02，{@code canFallBackToRawWrite}）——document 写失败（含注入缝）
     *      时只有无已加载 document 才允许裸写盘；有 document 一律抛 writeConflictGuard 拒写
     *     （宁内容旧一拍，执行前 console doc 同步链会重写并落盘，执行内容不受影响），
     *      失败原因 Throwable 必达日志（原 getOrDefault(false) 吞掉）；
     *      → <b>T-ExecDocSync3 退役</b>（第四轮真机日志实证判据不可靠，见方法内注释）：改为
     *      「VFS 已有该文件即绝不裸写」，冷启动 EDT 忙场景彻底无冲突面；</li>
     *  <li><b>④ 单写方锁</b>（T-EC-03，{@link #execFileWriteLock}）——串行化 BGT 预热与 EDT 冷路径。</li>
     * </ul>
     * <p>VFS 缺条目路径维持裸写 + 调用方 refresh + {@link #syncExecVfContentIfNeeded} 防御收口（T-EC-04）。</p>
     * T-PreviewFast P1/P2：差量快路径（内容已一致即返回）+ 预热非阻塞派发（{@code nonBlocking=true}）。
     * public 供测试。
     *
     * @param nonBlocking true = 预热等投机性调用：BGT 派发不等 EDT（fire-and-forget，P2）
     */
    public static File projectExecFile(Project project, String sql) throws java.io.IOException {
        return projectExecFile(project, sql, false);
    }

    /** nonBlocking 显式重载（Kotlin 默认参数 {@code nonBlocking = false} 的显式形态）。 */
    public static File projectExecFile(Project project, String sql, boolean nonBlocking) throws java.io.IOException {
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
                // T-PreviewFast P1：**差量快路径**——派发前先判当前内容是否已等于目标 SQL。相同则
                // 直接返回，不派发、不落盘、不等 EDT（连续预览同一 SQL 是常见形态：真机日志 14:17
                // 五连预览每次都白付 2s 派发等待 + 一次无效落盘 + 一轮索引噪音）。
                // 判据双源：document 文本（有已加载文档时，UI 所见为准）或 vf 内容（无文档时）。
                if (execFileContentMatches(project, existing, sql)) {
                    LOG.info("[M36] exec file content already matches — write SKIPPED (T-PreviewFast P1): " + f.getName());
                    return f;
                }
                // T-EC-01/T-EC-03：document 写统一派 EDT（EDT 直调同步；BGT 派发 + 有界等待）。
                // 262 实证 document setText BGT 硬抛（见 writeVfTextViaDocument Javadoc）——旧实现
                // 的 BGT document 分支每次必炸、静默掉进裸写兜底，即真机冲突链本体。
                // T-PreviewFast P2：预热调用（unblocking=true）走 fire-and-forget，不再等满 2 秒。
                boolean updated = dispatchDocWriteOnEdt(
                        project, existing, sql,
                        nonBlocking ? 0L : EDT_EXEC_DOC_SYNC_WAIT_MS);
                if (updated) {
                    LOG.info("[M36] exec file updated via document+save (disk synced in-critical-section): " + f.getName());
                    return f;
                }
                // T-ExecDocSync3（第四轮真机复现的收口，20260917 真实 IDE 日志实证）：**VFS 已有
                // 该文件时一律不裸写盘**。原 T-EC-02 兜底守卫查 getCachedDocument==null 就放行裸写，
                // 实机证伪——EDT 忙（冷启动/索引，日志同刻可见 DumbService enter dumb mode）时
                // dispatch 有界等待 2s 超时返回 false，此刻「排队的 EDT 写任务」尚未执行、document
                // 也还没建，getCachedDocument 恒 null；随之而来的裸写先改盘（12,590），排队任务
                // 随后 ExecutionException 建出 document（resolver 日志 oldFileStamp:0 = 文档建于文件
                // 写入之前）并标脏，裸写引发的 VFS 变更落到这个刚建的文档上 →
                // MemoryDiskConflictResolver「重载 mpsql-exec.sql？」弹窗（12,609），23ms 后文档写入
                // 才自己落盘（12,613）。即**插件与自己的排队写赛跑**，而非外部改盘。
                // 宁内容旧一拍：排队任务/later 的 console doc 同步链（attachConsole 后的
                // prepareAfterEditorResolved、executeOnConsole 执行前 T-EC-01 同款同步）都会重写并
                // 落盘，执行内容不受影响。附带收益：冷启动 EDT 忙时不再有裸写 → 该场景彻底无冲突面。
                LOG.warn("[M36] exec doc-write not completed (EDT dispatch timeout/WriteAction failure); "
                        + "raw write SKIPPED to avoid File Cache Conflict (T-ExecDocSync3): " + f.getName());
                return f;
            }
            // VFS 无该条目（首次创建）：不可能存在 document（无 vf 可挂），裸写安全
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
            //
            // T-PreviewConsoleFmt F2 + T-PreviewFast P3：**该文件正被编辑器打开时跳过内容刷新**——
            // 有人正看着这份内容时预热不得改写（预热写的是「预览原文」裸单行，会盖掉面板渲染进
            // 控制台的格式化文本；真机 20260917 日志：showSql createEditor len=274/16 行 → prewarm
            // 原文 → attach 显示 1 行）。判据为「文档已加载且挂了编辑器」——原 `console.getVirtualFile
            // () === vf` 判据实机从未命中（SKIPPED 计数 0 / refreshed 10），已退役。
            if (isExecDocOpenInEditor(cachedVf.vf)) {
                LOG.info("[M36] exec prewarm: cache hit, exec doc open in editor — content refresh SKIPPED (T-PreviewFast P3)");
                return;
            }
            // T-PreviewFast P2：非阻塞派发——预热是投机性写入，没必要让 BGT 等满 2 秒
            try {
                projectExecFile(project, sql, true);
            } catch (Throwable e) {
                // T-FatalPassthrough T-FP-02b（独立复核阻断 2）：写盘链的 fatal 不得被本 runCatching
                // 吞成一条 warn（否则 prewarm 链 4 处既有透传全部作废）。非 fatal 仍原样 warn 降级。
                if (FatalErrors.isFatal(e)) throw (Error) e;
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

    /**
     * T-PreviewFast P1：exec 文件当前内容是否已等于目标 SQL——差量快路径判据（双源）。
     *
     * <p>有已加载 document 时以 <b>document 文本</b>为准（UI/控制台所见即权威，也是落盘会写入的内容）；
     * 无 document 时读 <b>vf 内容</b>。两者比对失败（异常/不可读）一律返回 false（保守走正常写入路径）。
     * public 供测试。</p>
     */
    public static boolean execFileContentMatches(Project project, VirtualFile vf, String sql) {
        try {
            Document doc = FileDocumentManager.getInstance().getCachedDocument(vf);
            if (doc != null) {
                return doc.getText().equals(sql);
            }
            String disk = new String(vf.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return disk.equals(sql);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * T-PreviewFast P3：exec 文件是否<b>正被编辑器打开</b>（= 有人正在看这份内容）——预热据此决定
     * 是否跳过内容刷新。
     *
     * <p>取代 T-PreviewConsoleFmt 的 {@code console.getVirtualFile() === vf} 判据：真机实测该等式从未成立
     * （{@code content refresh SKIPPED} 计数 0 而 {@code content refreshed only} 10 次），预热因此每次仍改写
     * 文件、与面板回填互相覆盖一轮。改判「该 vf 的文档已加载且挂了编辑器」——语义正是
     * 「别改用户眼前的内容」，且不依赖 DBN 内部方法。public 供测试。</p>
     */
    public static boolean isExecDocOpenInEditor(VirtualFile vf) {
        try {
            Document doc = FileDocumentManager.getInstance().getCachedDocument(vf);
            if (doc == null) return false;
            return EditorFactory.getInstance().getEditors(doc).length > 0;
        } catch (Throwable ignored) {
            return false;
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
            // T-FatalPassthrough T-FP-02b（独立复核阻断 2）：fatal 上浮（预热跑在 BGT，透传由平台
            // 记录现场）；非 fatal 仍返回（writeFile 失败 → 跳过预热，语义逐字不变）。
            if (FatalErrors.isFatal(e)) throw (Error) e;
            LOG.warn("[M36] exec prewarm writeFile failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (ioFile == null) return;
        // T-ExecDocSync2 F4：refresh 前强制收敛——projectExecFile 的 doc 写在 EDT 落盘后、本
        // refresh 之前，用户在控制台（document 即 exec 文件 document）键入可插入 modified-unsaved
        // 态；此时 refresh 命中「doc 已修改 && 磁盘≠文档」即弹 File Cache Conflict（第四轮残余竞态，
        // 开发计划 §1.3）。findFileByIoFile（与 projectExecFile 内裸调同形态，prewarm DONE 日志
        // 实证 BGT 可调）命中即过一遍分叉探测 + EDT 派发收敛，正常恒 no-op。
        try {
            VirtualFile vf0 = LocalFileSystem.getInstance().findFileByIoFile(ioFile);
            if (vf0 != null) {
                syncExecVfContentIfNeeded(project, vf0, sql);
            }
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
            LOG.warn("[M36] exec prewarm pre-refresh converge FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        VirtualFile vf;
        try {
            vf = WriteIntentReadAction.compute(
                    (com.intellij.openapi.util.Computable<VirtualFile>) () ->
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile));
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传（prewarm 跑在 BGT，透传由平台记录现场）
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
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
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
            if (FatalErrors.isFatal(e)) throw (Error) e; // T-FP-02：fatal 透传
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
        // T-ExecDocSync2 F5/F6 挂载点：exec 缓存槽建立即安装防抖自动落盘监听（应用级惰性一次）
        // 与冲突探针（项目级一次）；非 exec 路径 vf 进不来本方法，路径过滤双保险。
        // 安装段 runCatching 降级——探针 connect 在无 messageBus 的代理桩项目上 NPE
        // （CachedTempPsiFileTest 全代理桩实证）；两件均为增强件，失败仅失去防抖落盘
        // 与冲突取证，不得阻断缓存主链
        if (isExecVfPath(vf.getPath())) {
            try {
                installExecDocAutosaveListener();
                installExecConflictProbe(project);
            } catch (Throwable e) {
                Logger.getInstance(DataSourceSelector.class)
                        .warn("[M36] exec doc autosave/probe install skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private static void hookProjectClose(Project project, com.intellij.openapi.Disposable parentDisposable) {
        if (!hookProjectsClose.add(project)) return;
        Disposer.register(parentDisposable, () -> {
            hookProjectsClose.remove(project);
            conflictProbeProjects.remove(project);
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

    /** T-PS4-11 测试缝：防抖任务是否在途（未完成）——监听生效/卸载的行为断言点。 */
    public static boolean execDocAutosaveScheduledForTest() {
        java.util.concurrent.Future<?> pending = execDocAutosavePending.get();
        return pending != null && !pending.isDone();
    }

    /** T-PS4-11 测试缝：取消在途防抖任务并清快判缓存（测试隔离收尾，不触碰宿主挂载态）。 */
    public static void resetExecDocAutosaveForTest() {
        java.util.concurrent.Future<?> prev = execDocAutosavePending.getAndSet(null);
        if (prev != null) prev.cancel(false);
        lastExecDocRef.set(null);
    }
}
