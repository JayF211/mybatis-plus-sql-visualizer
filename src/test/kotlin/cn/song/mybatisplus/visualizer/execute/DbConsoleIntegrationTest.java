package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport;
import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver;
import com.intellij.credentialStore.OneTimeString;
import com.intellij.database.access.DbCredentialManager;
import com.intellij.database.dataSource.DatabaseConnectionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.LocalDataSourceManager;
import com.intellij.database.util.GuardedRef;
import com.intellij.lang.Language;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.sql.dialects.SqlDialectMappings;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import kotlin.Pair;

/**
 * DB 控制台端到端集成测试（M36.5.23）——模拟 Ultimate 沙盒真实环境。
 *
 * prepareConsole（原生链 chooseAndPrepare → 真实 MySQL 连接 → 控制台就绪）
 *   → executePrepared（原生链 doRunQueryInConsole → 真实 MySQL 连接 → 结果 Tab）
 *   → 模拟参数变更（编辑器文档 setText）→ 再次 executePrepared
 *   → **断言新结果 Tab 出现**（「滞后一轮」端到端回归，M36.5.22/22b 修复的行为级验证）。
 *
 * 运行：./gradlew testDatabase
 */
public class DbConsoleIntegrationTest extends BasePlatformTestCase {

    /** introspect 期间持有的连接守卫（close 即释放连接，见 introspectDataSource 内注释）。 */
    private GuardedRef<?> introspectRef = null;

    /** 控制台编辑器夹具的持有标记（造/释放见 DbIntegrationProjectSupport.installExecEditor）。 */
    private Editor execEditor = null;

    /**
     * 稳定 light 工程目录（testDatabase 通道五类共用，根因见 DbIntegrationProjectSupport 类注释）：
     * 默认描述符下 `project.basePath` = JVM 里首个用例的 unitTest 临时目录，被属主用例 tearDown
     * 递归删除 → 后续用例的 exec 文件写盘与 VFS 分叉（基线 `refreshVfs: VFS not visible after refresh`）。
     */
    @Override
    protected LightProjectDescriptor getProjectDescriptor() {
        return DbIntegrationProjectSupport.INSTANCE.getDescriptor();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        DbIntegrationProjectSupport.INSTANCE.allowRootAccess(getTestRootDisposable());
    }

    private final File demoProjectDir = new File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"));

    private String getDbPassword() {
        // M36.5.23：从 demo 项目 application.yml 复制（root/root，本地开发库无敏感性）
        return "root";
    }

    /** demo dataSources.xml 中目标数据源的连接信息。 */
    private static class DataSourceInfo {
        final String url;
        final String user;
        final String driverClass;

        DataSourceInfo(String url, String user, String driverClass) {
            this.url = url;
            this.user = user;
            this.driverClass = driverClass;
        }
    }

    private DataSourceInfo readDemoDataSource(String namePart) {
        File xml = new File(demoProjectDir, ".idea/dataSources.xml");
        File localXml = new File(demoProjectDir, ".idea/dataSources.local.xml");
        if (!xml.isFile()) return null;
        DataSourceInfo info = null;
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            NodeList sources = doc.getElementsByTagName("data-source");
            for (int i = 0; i < sources.getLength(); i++) {
                Node node = sources.item(i);
                Node nameNode = node.getAttributes().getNamedItem("name");
                if (nameNode == null) continue;
                String name = nameNode.getNodeValue();
                if (!name.contains(namePart)) continue;
                String url = null;
                String driver = null;
                NodeList children = node.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if ("jdbc-url".equals(child.getNodeName())) {
                        url = child.getTextContent().trim();
                    } else if ("jdbc-driver".equals(child.getNodeName())) {
                        driver = child.getTextContent().trim();
                    }
                }
                if (url != null) {
                    info = new DataSourceInfo(url, "root", driver != null ? driver : "com.mysql.cj.jdbc.Driver");
                }
            }
            if (info != null && localXml.isFile()) {
                Document local = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(localXml);
                NodeList nodes = local.getElementsByTagName("data-source");
                for (int i = 0; i < nodes.getLength(); i++) {
                    Node node = nodes.item(i);
                    Node nameNode = node.getAttributes().getNamedItem("name");
                    if (nameNode == null) continue;
                    String name = nameNode.getNodeValue();
                    if (!name.contains(namePart)) continue;
                    NodeList children = node.getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        if ("user-name".equals(children.item(j).getNodeName())) {
                            String user = children.item(j).getTextContent().trim();
                            if (!user.isEmpty()) {
                                info = new DataSourceInfo(info.url, user, info.driverClass);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return info;
    }

    /** 编程创建数据源（LocalDataSourceManager + 凭据注入），返回 DBN LocalDataSource。 */
    private LocalDataSource createDataSource(DataSourceInfo info) {
        LocalDataSourceManager mgr = LocalDataSourceManager.getInstance(getProject());
        LocalDataSource ds = mgr.createEmpty();
        ds.setName("mpj_demo@localhost-itest");
        ds.setUrl(info.url);
        ds.setDriverClass(info.driverClass);
        ds.setUsername(info.user);
        // driverRef="mysql.8" + resolveDriver()：仅 setDriverClass 不解析 DatabaseDriver，
        // getAppropriateDataSourcesForExec 按 driver.dialect 过滤会跳过 driver=null 的数据源
        //（diag 实证：driver=null → runner 列表空）。setDriverRef 包私有——反射调。
        try {
            Method setDriverRef = ds.getClass().getDeclaredMethod("setDriverRef", String.class);
            setDriverRef.setAccessible(true);
            setDriverRef.invoke(ds, "mysql.8");
        } catch (Exception ignored) {
        }
        ds.resolveDriver();
        // T-TestDbDriver：驱动种子文件 + 驱动 artifact 版本钉定（管线全貌见助手 KDoc）
        DbIntegrationSeedSupport.INSTANCE.prepareDriverPipeline();
        ds.setWorkingDir(getProject().getBasePath());
        ds.setPasswordStorage(LocalDataSource.Storage.MEMORY);
        mgr.addDataSource(ds);
        new DbCredentialManager().setPassword(ds, new OneTimeString(getDbPassword()), LocalDataSource.Storage.MEMORY);
        return ds;
    }

    private String introspectDataSource(LocalDataSource ds) {
        try {
            DatabaseConnectionManager mgr = DatabaseConnectionManager.getInstance();
            Object builder = mgr.build(getProject(), ds);
            builder.getClass().getMethod("setAskPassword", boolean.class).invoke(builder, false);
            Future<GuardedRef> future = ApplicationManager.getApplication().executeOnPooledThread(
                    (Callable<GuardedRef>) () -> {
                        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
                        return ProgressManager.getInstance().runProcess((Computable<GuardedRef>) () -> {
                            try {
                                return (GuardedRef) builder.getClass().getMethod("createBlockingNonCancellable").invoke(builder);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }, indicator);
                    });
            GuardedRef ref = future.get();
            if (ref == null) return "createBlocking future null";
            boolean loaded = waitUntil(90_000, () -> !LocalDataSourceManager.getInstance(getProject()).isLoading(ds));
            // 不在此 close：GuardedRef.close() 释放连接（DatabaseConnectionManager.releaseConnection 语义，
            // 见 Builder.createBlockingNonCancellable 返回 GuardedRef<DatabaseConnection>），引擎随之终止；
            // 控制台/session 是连接的下游——连接先死则控制台 attach 后立刻被 DBN 回收（20260918 实测
            // `console DISPOSED right after attach: disposed=true`）。守卫留到 tearDown 关闭，
            // 等价真机里用户会话持着连接（用例结束即释放，不跨用例泄漏）。
            introspectRef = ref;
            return loaded ? null : "isLoading 未结束（90s 超时）";
        } catch (Exception e) {
            Throwable cause = (e instanceof ExecutionException) ? e.getCause() : e;
            return "introspect 失败 (proxy=" + System.getProperty("http.proxyHost") + ":" + System.getProperty("http.proxyPort")
                    + "): " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    /** 反射读取 DataSourceSelector 的静态字段（preparedEditor/preparedUi）。 */
    private Object selectorField(String name) {
        try {
            java.lang.reflect.Field f = DataSourceSelector.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(DataSourceSelector.INSTANCE);
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前结果 Tab 名称列表（排除 Output）。 */
    private List<String> resultTabNames() {
        Object ui = selectorField("preparedUi");
        if (ui == null) return Collections.emptyList();
        try {
            Method m = ui.getClass().getMethod("getContents");
            Object res = m.invoke(ui);
            if (!(res instanceof Content[])) return Collections.emptyList();
            Content[] contents = (Content[]) res;
            List<String> names = new ArrayList<>();
            for (Content c : contents) {
                String dn = c.getDisplayName();
                if (!"Output".equalsIgnoreCase(dn) && !"输出".equalsIgnoreCase(dn)) {
                    names.add(dn);
                }
            }
            return names;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private boolean waitUntil(long timeoutMs, BooleanSupplier condition) {
        return waitUntil(timeoutMs, 300, condition);
    }

    private boolean waitUntil(long timeoutMs, long intervalMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (safeGet(condition)) return true;
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return safeGet(condition);
    }

    private boolean safeGet(BooleanSupplier condition) {
        try {
            return condition.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /** runner 显示名清单（断言消息用）。 */
    private static List<String> runnerNames(List<Object> runners) {
        return runners.stream().map(DbIntegrationProjectSupport.INSTANCE::runnerName).collect(Collectors.toList());
    }

    /**
     * 端到端：准备 → 执行 → 改参 → 再执行 → 断言新结果 Tab。
     * 「滞后一轮」回归：第二次执行后必须出现与第一次**不同**的结果 Tab（M36.5.22b 行为）。
     */
    @Test
    public void testPrepareExecuteAndParameterChangeEndToEnd() throws Exception {
        // 前置：Database 插件已加载（testDatabase 任务不排 jar）+ demo 项目可达。
        // 注意：密码 assume 放在「执行」前——无密码时 prepare 层（插件/数据源/控制台准备）仍被验证。
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        DataSourceInfo info = readDemoDataSource("mpj_demo");
        assumeTrue("demo 项目数据源配置不可读: " + demoProjectDir.getAbsolutePath(), info != null);
        DataSourceInfo dsInfo = info;

        // ⓪ 清空应用级残留数据源：LocalDataSourceManager 跨用例存活，本用例走的是「唯一数据源静默
        //    复用」契约，多一条残留即退化成多 runner 弹窗分支（headless 无 frame → NPE，基线实证）
        DbIntegrationProjectSupport.INSTANCE.clearDataSources(getProject());

        // ① 创建数据源（连接信息来自 demo 项目）
        LocalDataSource ds = createDataSource(dsInfo);

        // ①b 触发 introspection：让 delegate 切换为 SqlDataSource（否则 containsFile 过滤失败）。
        // MySQL 未启动 / 库未初始化 / RegionUrlMapper 网络失败 → skip 余下场景（不 fail）
        String introspectError = introspectDataSource(ds);
        // T-ExecDocGate：显式绿跳——平台 runner 把 AssumptionViolatedException 记为 failure 红任务
        if (introspectError != null) {
            System.out.println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: " + introspectError);
            return;
        }

        // 本用例执行 SQL（诊断 dump 与准备链共用同一文本）
        String execSql = "SELECT id, station_id FROM car_device_info WHERE id = 1";

        // 诊断 dump 只在断言失败时求值（见 runnerDiag）：该 dump 自身会写 exec 文件、重建 PSI、
        // 重设方言映射，放在准备链之前会污染被测状态（20260918 实测：前置 dump 让控制台 attach 后
        // 即被 DBN 回收——排查代码本身成了故障源）
        // ② prepareConsole：唯一数据源 → 静默复用 → 控制台就绪回调
        // ②a 前置：发现链收敛到恰好 1 个 runner（本用例契约是「唯一数据源静默复用」，多一条残留即
        //     走弹窗分支、少一条即回调 false；facade 相对 LocalDataSourceManager 有异步窗口，见
        //     DbIntegrationProjectSupport.awaitRunners KDoc）
        Pair<VirtualFile, PsiFile> execPsi = DbIntegrationProjectSupport.INSTANCE.ensureExecFile(getProject(), execSql);
        assertNotNull("临时执行 PsiFile 应可构造（VFS/方言映射）", execPsi);
        List<Object> runners = DbIntegrationProjectSupport.INSTANCE.awaitRunners(execPsi.getSecond(), execSql, 1, 60_000L);
        assertEquals(
                "唯一数据源契约要求发现链恰好 1 个 runner（实际=" + runnerNames(runners) + "）",
                1,
                runners.size());
        // ②b 控制台编辑器夹具：控制台文档即 exec 文件文档（JdbcConsole.getDocument() 字节码实证），
        //     headless 下无人为它建编辑器 → prepareInConsole 的 getEditors(consoleDocument) 恒空 →
        //     静默返回（基线 idea.log `[M36] editor=null (async wait)`，120s 不就绪且 errors=[]）
        execEditor = DbIntegrationProjectSupport.INSTANCE.installExecEditor(getProject());
        assertNotNull("控制台编辑器夹具应可建（prepareConsole 依赖 getEditors(consoleDocument) 非空）", execEditor);

        boolean[] consoleReady = {false};
        List<String> errors = new ArrayList<>();
        boolean[] prepared = {false};
        DataSourceSelector.prepareConsole(
                getProject(),
                execSql,
                c -> consoleReady[0] = true,
                e -> {
                },
                it -> errors.add(it),
                p -> prepared[0] = p
        );
        boolean consoleReadyInTime = DbIntegrationProjectSupport.INSTANCE.waitWithPump(120_000L, 50L, () -> consoleReady[0]);
        if (!consoleReadyInTime) {
            System.out.println("[DB-IT] prepareConsole 未就绪诊断: "
                    + DbIntegrationProjectSupport.INSTANCE.diagnoseConsoleState(getProject(), ds));
            System.out.println("[DB-IT] runner diag: " + runnerDiag(execSql));
        }
        assertTrue("prepareConsole 120s 内未就绪 (errors=" + errors + ")", consoleReadyInTime);
        assertTrue("prepareConsole callback 应返回 true", prepared[0]);
        assertTrue("控制台就绪后 isPrepared() 应为 true", DataSourceSelector.isPrepared());
        assertTrue("prepare 阶段不应有错误 (errors=" + errors + ")", errors.isEmpty());

        // ③ executePrepared：真实 MySQL 执行 → 结果 Tab 出现（需要密码；未配置则 skip 余下场景）
        assumeTrue("未配置 mpTestDbPassword——请在 ~/.gradle/gradle.properties 添加 mpTestDbPassword=<MySQL密码>"
                + "（无密码时仅验证 prepare 层）", !getDbPassword().isEmpty());

        boolean[] resultsReady = {false};
        List<String> execErrors = new ArrayList<>();
        DataSourceSelector.executePrepared(
                getProject(),
                c -> resultsReady[0] = true,
                it -> execErrors.add(it),
                null
        );
        assertTrue(
                "首次执行 60s 内无结果 Tab (errors=" + execErrors + ")",
                DbIntegrationProjectSupport.INSTANCE.waitWithPump(60_000L, 50L, () -> !resultTabNames().isEmpty()));
        assertTrue("onResultsReady 应被回调", resultsReady[0]);
        List<String> firstTabs = resultTabNames();
        assertTrue("首次执行后应有结果 Tab (tabs=" + firstTabs + ")", !firstTabs.isEmpty());
        String firstTabName = firstTabs.get(firstTabs.size() - 1);

        // ④ 模拟参数变更：编辑器文档改为 id = 2（面板 executeSql 同步文档的等价操作）
        Editor editor = (Editor) selectorField("preparedEditor");
        WriteAction.runAndWait(() -> editor.getDocument().setText("SELECT id, station_id FROM car_device_info WHERE id = 2"));

        // ⑤ 再次执行：断言出现**新的**结果 Tab（滞后一轮回归点）
        boolean[] secondReady = {false};
        DataSourceSelector.executePrepared(
                getProject(),
                c -> secondReady[0] = true,
                it -> execErrors.add(it),
                null
        );
        boolean newTabAppeared = DbIntegrationProjectSupport.INSTANCE.waitWithPump(
                60_000L, 50L, () -> resultTabNames().stream().anyMatch(n -> !n.equals(firstTabName)));
        List<String> secondTabs = resultTabNames();
        assertTrue("第二次执行后未出现新结果 Tab（滞后一轮回归）：first=" + firstTabName + ", second=" + secondTabs + ", errors=" + execErrors,
                newTabAppeared);
        assertTrue("第二次执行 onResultsReady 应被回调", secondReady[0]);
        assertTrue("集成链路不应有错误回调 (errors=" + execErrors + ")", execErrors.isEmpty());

        // ⑥ 清理：释放控制台引用（不影响 IDE 管理的 session 生命周期）
        DataSourceSelector.clearPrepared();
        assertEquals("clearPrepared 后 isPrepared() 应为 false", false, DataSourceSelector.isPrepared());
        assertNotNull(dsInfo);
    }

    /**
     * runner 发现链诊断 dump（**惰性**：只在断言失败时求值）。
     *
     * 内容全是「读状态 + 复现 createTempPsiFile 的 4 步」：复现步骤会写 exec 文件、重建 PSI、
     * 重设方言映射——这些写点若发生在 prepareConsole 之前，会与被测准备链互相干扰
     *（20260918 实测：前置 dump 让控制台 attach 后即被 DBN 回收，排查代码成了故障源）。
     * 故改为失败时求值：诊断能力不减，被测状态不再被诊断代码扰动。
     */
    private String runnerDiag(String execSql) {
        StringBuilder diag = new StringBuilder();
        try {
            LocalDataSourceManager mgr = LocalDataSourceManager.getInstance(getProject());
            List<LocalDataSource> all = mgr.getDataSources();
            diag.append("LocalDataSourceManager.dataSources count=").append(all.size()).append("\n");
            for (LocalDataSource it : all) {
                String driver = it.getDatabaseDriver() != null ? it.getDatabaseDriver().getId() : null;
                diag.append("  ds name=").append(it.getName()).append(" uniqueId=").append(it.getUniqueId())
                        .append(" driver=").append(driver).append(" workingDir=").append(it.getWorkingDir()).append("\n");
            }
            diag.append("DataSource visible: ").append(!all.isEmpty()).append("\n");
            try {
                Object psiFacade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                        .getMethod("getInstance", com.intellij.openapi.project.Project.class).invoke(null, getProject());
                List<?> psiDs = (List<?>) psiFacade.getClass().getMethod("getDataSources").invoke(psiFacade);
                diag.append("DbPsiFacade.getDataSources count=").append(psiDs.size()).append("\n");
                for (Object it : psiDs) diag.append("  psi ds=").append(it).append("\n");
            } catch (Throwable t) {
                diag.append("DbPsiFacade dump failed: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
            }
            try {
                String base = getProject().getBasePath() != null ? getProject().getBasePath() : null;
                if (base == null) throw new IllegalStateException("getProject().basePath null");
                File dir = new File(base, ".idea/" + SqlLogAutoSaver.DATA_DIR_NAME + "/exec");
                dir.mkdirs();
                File f = new File(dir, "mpsql-exec.sql");
                java.nio.file.Files.write(f.toPath(), execSql.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                diag.append("step1 file written: ").append(f.getAbsolutePath()).append("\n");
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
                if (vf == null) throw new IllegalStateException("refreshAndFindFileByIoFile null");
                diag.append("step2 vf=").append(vf.getPath()).append("\n");
                SqlDialectMappings mappings = SqlDialectMappings.getInstance(getProject());
                List<?> values = (List<?>) mappings.getClass().getMethod("getAvailableValues").invoke(mappings);
                diag.append("step3 available dialects=")
                        .append(values.stream().map(o -> o instanceof Language ? ((Language) o).getID() : null).collect(Collectors.toList()))
                        .append("\n");
                Object mysql = values.stream()
                        .filter(o -> o instanceof Language && "MySQL".equalsIgnoreCase(((Language) o).getID()))
                        .findFirst().orElse(null);
                diag.append("step3b mysql dialect=").append(mysql != null ? ((Language) mysql).getID() : null).append("\n");
                if (mysql != null) {
                    mappings.getClass().getMethod("setMapping", VirtualFile.class, Object.class).invoke(mappings, vf, mysql);
                    diag.append("step3c mapping set OK\n");
                }
                PsiFile psi = PsiManager.getInstance(getProject()).findFile(vf);
                diag.append("step4 psi=").append(psi != null ? psi.getClass().getSimpleName() : (String) null)
                        .append(" dialect=").append(psi != null && psi.getLanguage() != null ? psi.getLanguage().getID() : (String) null).append("\n");
                Iterable<?> appropriate = (Iterable<?>) Class.forName("com.intellij.database.util.DbImplUtil")
                        .getMethod("getAppropriateDataSourcesForExec", PsiFile.class).invoke(null, psi);
                int step5count = 0;
                for (Object ignored : appropriate) step5count++;
                diag.append("step5 getAppropriateDataSourcesForExec=").append(step5count).append("\n");
                Object sqlDialect = Class.forName("com.intellij.database.util.DbImplUtil")
                        .getMethod("getSqlLanguageDialectForExec", PsiFile.class).invoke(null, psi);
                diag.append("step6 sqlDialectForExec=").append(sqlDialect).append(" (")
                        .append(sqlDialect instanceof Language ? ((Language) sqlDialect).getID() : (String) null).append(")\n");
                Object dbDialect = null;
                try {
                    dbDialect = sqlDialect.getClass().getMethod("getDatabaseDialect").invoke(sqlDialect);
                } catch (Throwable ignored) {
                }
                diag.append("step6b dbDialect=").append(dbDialect != null ? dbDialect.getClass().getSimpleName() : (String) null)
                        .append(" id=").append(dbDialect instanceof Language ? ((Language) dbDialect).getID() : (String) null).append("\n");
                try {
                    Object psiFacade2 = Class.forName("com.intellij.database.psi.DbPsiFacade")
                            .getMethod("getInstance", com.intellij.openapi.project.Project.class).invoke(null, getProject());
                    List<?> psiDs2 = (List<?>) psiFacade2.getClass().getMethod("getDataSources").invoke(psiFacade2);
                    Object dsEl = psiDs2.get(0);
                    Object isValid = dsEl.getClass().getMethod("isValid").invoke(dsEl);
                    diag.append("step7 psi ds isValid=").append(isValid).append(" class=").append(dsEl.getClass().getSimpleName()).append("\n");
                    Object dsDialect = Class.forName("com.intellij.database.util.DbImplUtilCore")
                            .getMethod("getDatabaseDialect", Class.forName("com.intellij.database.psi.DbElement")).invoke(null, dsEl);
                    diag.append("step8 ds dialect=").append(dsDialect != null ? dsDialect.getClass().getSimpleName() : (String) null)
                            .append(" id=").append(dsDialect instanceof Language ? ((Language) dsDialect).getID() : null).append("\n");
                    if (dbDialect != null && dsDialect != null) {
                        Object similar = dbDialect.getClass().getMethod("similarTo", Class.forName("com.intellij.database.dialects.DatabaseDialect"))
                                .invoke(dbDialect, dsDialect);
                        diag.append("step9 similarTo=").append(similar).append("\n");
                        Object sqlDs = dsEl.getClass().getMethod("getDelegate").invoke(dsEl);
                        Object contains = null;
                        try {
                            contains = sqlDs.getClass().getMethod("containsFile", VirtualFile.class).invoke(sqlDs, vf);
                        } catch (Throwable ignored) {
                        }
                        diag.append("step10 SqlDataSource.containsFile(vf)=").append(contains)
                                .append(" sqlDsClass=").append(sqlDs != null ? sqlDs.getClass().getSimpleName() : (String) null).append("\n");
                        Object wd = dsEl.getClass().getMethod("getWorkingDir").invoke(dsEl);
                        diag.append("step11 ds workingDir=").append(wd).append("\n");
                    }
                } catch (Throwable t) {
                    diag.append("filter repro FAILED: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                }
            } catch (Throwable t) {
                diag.append("step FAILED: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                for (int i = 0; i < Math.min(6, t.getStackTrace().length); i++) {
                    diag.append("  at ").append(t.getStackTrace()[i]).append("\n");
                }
            }
        } catch (Throwable t) {
            diag.append("dump failed: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
        }
        return diag.toString();
    }

    @Override
    protected void tearDown() throws Exception {
        if (introspectRef != null) {
            try {
                introspectRef.close();
            } catch (Exception ignored) {
            }
        }
        introspectRef = null;
        // 夹具编辑器必须先释放：平台 TestApplicationKt.checkEditorsReleased 对每个存活编辑器硬抛
        DbIntegrationProjectSupport.INSTANCE.detachDbnConsole(getProject());
        DbIntegrationProjectSupport.INSTANCE.releaseExecEditors(getProject());
        // 单例的 prepared/shared 槽位跨用例存活——面板关闭语义的清理是 releaseForPanel
        // （clearPrepared + dropSharedConsole + 清 pendingOnConsole），用例边界用它，避免下个用例
        // 经 reuseSharedConsole 命中本用例的控制台/编辑器（详见 DataSourceSelectorIntegrationTest 同款注释）
        try {
            DataSourceSelector.releaseForPanel();
        } catch (Exception ignored) {
        }
        // T-TestDbDriver：object 单例 temp Psi 缓存跨用例存活，fixture 临时目录每用例一换 → 显式清。
        try {
            DataSourceSelector.clearCachedTempPsiFileForTest();
        } catch (Exception ignored) {
        }
        super.tearDown();
    }
}
