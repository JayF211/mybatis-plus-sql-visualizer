// T-M53-II D2-4：master 把 DataSourceSelector.kt 的结果区/临时文件族成员搬为 execute 包顶层扩展函数
// （DataSourceSelectorResults.kt）；本类是其 Java 承载——k2j 侧原为 DataSourceSelector.java 的成员，
// 按 master 拆分结果外提（同包同可见性，零行为改动）。
package cn.song.mybatisplus.visualizer.execute;

import static cn.song.mybatisplus.visualizer.execute.DataSourceSelector.*;

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
 * {@code DataSourceSelectorResults.kt} 顶层扩展函数（结果区换入 / 临时文件 / 执行冲突探针族）的 Java 承载类。
 *
 * <p>本类方法经 {@code import static ...DataSourceSelector.*} 直引原 object 成员（master 侧为扩展函数接收者）。</p>
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public final class DataSourceSelectorResultsKt {

    private DataSourceSelectorResultsKt() {
    }

    /** 结果 Tab 激活重试。（T-ExecSilentNoop：dsName/demotedOverride 供 40 拍超时窗口提示三分流） */
    public static void startResultTabRetry(
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
    public static void startResultTabRetry(
            @NotNull Project project,
            Object ui,
            @Nullable Consumer<String> onError,
            Set<String> preExistingNames,
            String dsName,
            boolean demotedOverride) {
        startResultTabRetry(project, ui, onError, preExistingNames, swapGeneration, dsName, demotedOverride, null, null);
    }


    public static void chooseAndPrepare(
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

        // T-FirstExecResultInvisible F1：准备链单飞 + 执行意图排队——打开预览的准备链（远程 PG
        // 会话需数秒）未落地时点执行，旧代码在下方无任何去重，会再建第二条临时文件/会话/控制台；
        // 两条链的 executionGeneration++ 与提交/取消判定交错 → 提交被取消或结果组件被换掉
        //（结果区空白、二击正常的 H1 根因）。单飞判定先于共享复用：冷启时 sharedConsole 尚未
        // 落地，复用必不命中，单飞是唯一闸门。
        QueuedExecuteIntent queuedIntent = executeAfterPrepare
                ? new QueuedExecuteIntent(onConsole, onEditor, onError, onErrorRich, wholeScriptExec)
                : null;
        PrepareGateDecision gateDecision = prepareGate.tryEnter(executeAfterPrepare, queuedIntent);
        if (gateDecision == PrepareGateDecision.QUEUE_ONLY) {
            LOG.info("[M36][T-FirstExec F1] prepare chain in flight: execute intent queued, no second session/console chain");
            if (callback != null) callback.accept(true);
            return;
        }
        final boolean ownsGate = gateDecision == PrepareGateDecision.PROCEED;
        if (gateDecision == PrepareGateDecision.PASS_THROUGH) {
            LOG.info("[M36][T-FirstExec F1] prepare chain in flight: preview prepare passes through (no gate ownership)");
        }

        // T-MysqlGStrip T-MG-02：提交链总口——SQL 文本进入 exec 文件/控制台文档之前剥离 MySQL CLI
        // 的 `\G` 后缀（DBX #35）。此点数据源尚未选定（runner 弹窗在下方），方言不可得 → 按计划
        // §三 定案默认剥离（`\G` 非任何方言合法语法，剥离无害）。剥后文本写入 exec 文件，
        // 控制台编辑器所见即所执行；历史链在预览侧记录原文，不受此处影响（两态分离）。
        String submitSql = stripGSuffixForSubmit(sql, null).submitText();

        // M36.5.5：共享控制台命中（last-used 数据源一致且控制台仍有效）→ 直接复用——
        // 不同步 session/连接/临时文件/方言映射，规避"第二个会话连接永不发起"挂死（见字段注释）。
        // 显式执行路径同样复用（结果落在已嵌入面板）；用户切换数据源走弹窗 → 新建并替换共享缓存。
        if (reuseSharedConsole(project, submitSql, executeAfterPrepare, onConsole, onEditor, onError, callback, onErrorRich, wholeScriptExec)) {
            // T-FirstExecResultInvisible F1：复用路径同步收敛（不建链、不经 prepareAfterEditorResolved）
            // ——门持有链就地终结复位；PASS_THROUGH 预览链不动门。
            if (ownsGate) prepareGate.finishPrepare();
            return;
        }

        // 临时文件 + VF + PsiFile：Runner 上下文 + 控制台挂载点（VF 须保持引用）
        TempPsiFile temp = createTempPsiFile(project, submitSql);
        if (temp == null) {
            // T-ExecCopyFix F2a：携带阶段原因（stage 标识/异常摘要），本地化文案——
            // 用户真机曾只见笼统英文 "Failed to create temporary SQL file"，无从归因
            LOG.warn("[M36] createTempPsiFile failed (reason=" + lastTempFileFailure + "), abort");
            if (onError != null) onError.accept(MpSqlVisualizerBundle.message("execute.tempFile.failed",
                    lastTempFileFailure != null ? lastTempFileFailure : "unknown"));
            // T-FirstExecResultInvisible F1：链就此终结，复位单飞位（否则后续执行被永久排队）
            if (ownsGate) prepareGate.finishPrepare();
            if (callback != null) callback.accept(false);
            return;
        }
        VirtualFile vf = temp.vf;
        PsiFile psiFile = temp.psi;

        final String[] chosenRunnerName = {null};
        com.intellij.util.Consumer<Object> sessionConsumer = session -> {
            LOG.info("[M36] session created: " + (session != null ? session.getClass().getName() : "null")
                    + ", attaching console (execute=" + executeAfterPrepare + ", ds=" + chosenRunnerName[0] + ")");
            DataSourceSelectorConsoleKt.prepareInConsole(project, session, vf, executeAfterPrepare, chosenRunnerName[0], onConsole, onEditor, onError, onErrorRich, wholeScriptExec, ownsGate);
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
            // T-FirstExecResultInvisible F1：链就此终结，复位单飞位（否则后续执行被永久排队）
            if (ownsGate) prepareGate.finishPrepare();
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
                // T-FirstExecResultInvisible F1：用户取消弹窗 = 链就此终结，复位单飞位
                if (runner == null && ownsGate) prepareGate.finishPrepare();
                // 弹窗中用户刚做了主动选择，失败仍需模态引导（silent=false）
                if (runner != null) runRunner(project, runner, false, onError);
            });
        }
    }


    public static boolean reuseSharedConsole(
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
                                DataSourceSelectorConsoleKt.refreshConsoleModel(project, consoleForModel, vfForModel, LOG);
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
                // T-FirstExecResultInvisible F3：折叠归口 embedMode——仅 PREVIEW 且结果区无内容才折叠
                //（上方清空失败/竞态下已有内容则保持展开，不吞用户可见的结果）。
                boolean collapse = ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(resultAreaHasContent(sharedUi));
                if (collapse) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ((JSplitPane) fullX).setDividerLocation(1.0);
                        ((JSplitPane) fullX).revalidate();
                        ((JSplitPane) fullX).repaint();
                    }, ModalityState.any());
                }
            }
        }
        pendingOnConsole = onConsole;
        DataSourceSelectorConsoleKt.logDataContextCheck(top, console, editor, execOption, LOG);
        clearConsolePrompt(console, LOG);
        LOG.info("[M36] shared console reused (ds=" + dsName + ", execute=" + executeAfterPrepare + ", liveResult=" + (liveResult != null) + ")");
        if (executeAfterPrepare) {
            DataSourceSelectorConsoleKt.executeOnConsole(project, console, editor, refreshedModel != null ? refreshedModel : scriptModel, execOption, sharedUi, null, onError, onErrorRich, wholeScriptExec);
        }
        if (callback != null) callback.accept(true);
        return true;
    }


    /** M36.5.6：结果区换入监听。 */
    public static void attachResultsHook(
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


    /** M36.5.60：运行时组件边界取证。 */
    public static void dumpSplitBounds(JSplitPane sp, Logger log) {
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

    public static void dumpDeep(Component c, int depth, String path, Logger log) {
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

    public static void scanTextViews(Component c, String path, Logger log) {
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


    /** M36.5.22：真实提交后的统一收尾。（T-ExecSilentNoop：dsName/demotedOverride 供提示三分流与去重） */
    public static void afterSubmitted(
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


    /** M36.5.8/M36.5.9/M36.5.13：执行后结果区刷新——限时多次重取。 */
    public static void refreshResultsComponent(Object ui, Logger LOG, boolean forceSwapIn, int generation,
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

    public static void findAllOutputs(Component c, int depth, List<Component> targets) {
        if (targets.size() >= 4 || depth > 24) return;
        if (c.getClass().getName().contains("EditorImpl$PanelWithFloatingToolbar")) {
            targets.add(c);
            return;
        }
        if (c instanceof Container) {
            for (Component d : ((Container) c).getComponents()) findAllOutputs(d, depth + 1, targets);
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
    public static TempPsiFile createTempPsiFile(Project project, String sql) {
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
        return new TempPsiFile(vf, psi);
    }

    /** T-ExecCopyFix F2a：失败阶段记录与日志（stage 标识 + 详情），恒返 null 供上层透传。 */
    public static TempPsiFile failTempFile(String stage, String detail) {
        lastTempFileFailure = (detail == null || detail.isEmpty()) ? stage : stage + ": " + detail;
        Logger.getInstance(DataSourceSelector.class)
                .warn("[M36] createTempPsiFile FAILED at " + stage
                        + ((detail == null || detail.isEmpty()) ? "" : " (" + detail + ")"));
        return null;
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


    /** {@link #prewarmExecTempFile} 的真实链（已持有 in-flight 标记；仅后台线程调用）。 */
    public static void prewarmExecTempFileInner(Project project, String sql, Logger LOG) {
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


    /** M36.5.2：主题化完整控制台 SplitPane。 */
    public static JComponent buildConsoleSplitPane(JComponent topBlock, JComponent resultComponent) {
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
    public static boolean dispatchDocWriteOnEdt(Project project, VirtualFile vf, String sql) {
        return dispatchDocWriteOnEdt(project, vf, sql, EDT_EXEC_DOC_SYNC_WAIT_MS);
    }

    /** waitBudgetMs 显式重载（Kotlin 默认参数 {@code waitBudgetMs = EDT_EXEC_DOC_SYNC_WAIT_MS} 的显式形态）。 */
    public static boolean dispatchDocWriteOnEdt(Project project, VirtualFile vf, String sql, long waitBudgetMs) {
        Logger LOG = Logger.getInstance(DataSourceSelector.class);
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            final boolean[] ok = {false};
            DocWriteContext.runWriteSafe(project, "exec doc sync (edt direct)", () -> ok[0] = docWriteOnce(project, vf, sql));
            return ok[0];
        }
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


    /** M36.5.41-A4：持久整控台——结果刷新时原位换底。 */
    public static JComponent swapResultIntoPersistentConsole(
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


    /**
     * F6：冲突探针——「文件缓冲区冲突」弹窗（MemoryDiskConflictResolver）响应的是平台对修改中
     * document 发起的 reload，reload 前平台经 FileDocumentManagerListener.beforeFileContentReload
     * 广播（与弹窗实现同 topic，监听先后不保证，故仅取证不拦截）。exec 文件命中即落 warn 指纹
     * （doc/vf 长度与 stamp、unsaved 态）：F1-F5 全上后若真机仍复发，idea.log 直出现场数据，
     * 替代人工复现排查。经项目 messageBus 连接（随项目 disposable 自动断开）。
     */
    public static void installExecConflictProbe(Project project) {
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


    public static void realignDividerOnce(JSplitPane sp, boolean force) {
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


    /** 反射调用 Runner.run：建 session → consumer 回调续接执行。失败（常见：驱动未下载）→ 面板行内回调 + 按 silent 决定是否模态引导。
     * T-PreviewExecDs-02 F4：显式传 project——失败路径曾用 openProjects.firstOrNull() 清 last-used
     * /弹错误框，多项目同开时清的是「第一个打开项目」的记忆（错项目 bug，见开发计划 §2.2）。 */
    public static void runRunner(Project project, Object runner, boolean silent, Consumer<String> onError) {
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
    public static String execSilentProbe(Object console, Object freshModel, boolean demoted, String destDsName, Logger LOG) {
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


}
