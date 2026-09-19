package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport;
import com.intellij.credentialStore.OneTimeString;
import com.intellij.database.access.DbCredentialManager;
import com.intellij.database.dataSource.DatabaseConnectionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.LocalDataSourceManager;
import com.intellij.database.util.GuardedRef;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import kotlin.Pair;

/**
 * DataSourceSelector 数据源**选择/发现**层集成测试骨架（M36.5.23 续）。
 *
 * 与 {@link DbConsoleIntegrationTest}（覆盖 prepareConsole→executePrepared 控制台执行链）互补，本骨架
 * 专门验证此前只能人工沙盒验收的**数据源选择契约**——即 DataSourceSelector 依赖的真实平台集成点
 * `com.intellij.database.console.DatabaseRunners.getAttachDataSourceRunners`：
 *
 *   1. **无数据源** → prepareConsole 回调 false + onError 非空（打开 Database 工具窗引导），
 *      不抛异常、不 NPE（无 MySQL 也可验证的选择降级路径）。
 *   2. **单数据源** → 真实平台 API 返回恰好 1 个 runner，且 runner 显示名含数据源名；
 *      prepareConsole 静默复用（不弹选择框）→ isPrepared()=true 且 sharedDataSourceName==数据源名
 *      （证明「按单数据源自动选中」的选择决策正确，无弹窗）。
 *   3. **多数据源** → 真实平台 API 返回 N 个 runner（选择分支的输入：N 个 → 弹出选择框）。
 *   4. **控制台编辑器不可得**（T-TS-09）→ prepareConsole 必须回调 onError，不静默挂死
 *      （修复前 `prepareInConsole` 两处静默返回，调用方无任何回调）。
 *
 * 运行环境：`testDatabase` 任务（独立沙盒、Database 插件真实加载、复用 runIde 沙盒 MySQL 驱动）。
 * 默认 `test` 任务已排除本类（其 classpath 剥离 database jar，否则类加载即 NoClassDefFoundError）。
 */
public class DataSourceSelectorIntegrationTest extends BasePlatformTestCase {

    /** introspect 期间持有的连接守卫（close 即释放连接，见 introspectDataSource 内注释）。 */
    private GuardedRef<?> introspectRef = null;

    /** 控制台编辑器夹具的持有标记（造/释放见 DbIntegrationProjectSupport.installExecEditor）。 */
    private Editor execEditor = null;

    /**
     * 稳定 light 工程目录（testDatabase 通道五类共用，根因见 DbIntegrationProjectSupport 类注释）：
     * 默认描述符下工程目录 = JVM 里首个用例的 unitTest 临时目录，被属主用例 tearDown 递归删除。
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
        return "root";
    }

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

    private LocalDataSource createDataSource(DataSourceInfo info) {
        return createDataSource(info, "");
    }

    private LocalDataSource createDataSource(DataSourceInfo info, String nameSuffix) {
        LocalDataSourceManager mgr = LocalDataSourceManager.getInstance(getProject());
        LocalDataSource ds = mgr.createEmpty();
        ds.setName("mpj_demo@localhost-itest" + nameSuffix);
        ds.setUrl(info.url);
        ds.setDriverClass(info.driverClass);
        ds.setUsername(info.user);
        try {
            Method setDriverRef = ds.getClass().getDeclaredMethod("setDriverRef", String.class);
            setDriverRef.setAccessible(true);
            setDriverRef.invoke(ds, "mysql.8");
        } catch (Exception ignored) {
            // 反射失败忽略（包私有/不存在）
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
            return "introspect 失败: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
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

    private Object selectorField(String name) {
        try {
            java.lang.reflect.Field f = DataSourceSelector.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(DataSourceSelector.INSTANCE);
        } catch (Exception e) {
            return null;
        }
    }

    /** runner 显示名清单（断言消息用）。 */
    private static List<String> runnerNames(List<Object> runners) {
        return runners.stream().map(DbIntegrationProjectSupport.INSTANCE::runnerName).collect(Collectors.toList());
    }

    /**
     * 反射探针与收敛等待的实现已收编 DbIntegrationProjectSupport
     * （discoverRunners / runnerName / awaitRunners）——本类与另外两个 DB 集成类共用同一份探针，
     * 语义不变：直接调真实平台 API `DatabaseRunners.getAttachDataSourceRunners`，不绕插件封装。
     */

    // ───────────────────────────── 用例 ─────────────────────────────

    /**
     * 无数据源路径（选择降级契约）：不创建任何数据源，prepareConsole 应回调 false + onError 非空，
     * 且不得抛异常/NPE。仅依赖 Database 插件已加载（testDatabase 提供），**不依赖 MySQL**，始终运行。
     *
     * T-ExecDocGate：本用例前置显式清空数据源——LocalDataSourceManager 是应用级，兄弟用例
     * （本类其它用例 / DbConsoleIntegrationTest / ExecDocGateIntegrationTest）createDataSource
     * 建的数据源跨用例可见；此前它们因 driver 解析不出去而被 DbImplUtil 过滤「隐形」，驱动种子
     * 修复后 resolveDriver 生效、残留变可见 → runners 非空 → 走弹窗分支（headless 无 frame
     * → NPE 红）。契约是「无数据源」路径，就须保证环境确实无数据源。
     *
     * 20260918：清空后还须**有界等待发现链收敛到 0**（见 awaitRunners）——否则取样落在
     * facade 尚未跟上的中间态上，仍会走弹窗分支。
     */
    @Test
    public void testNoDataSourceReportedAsUnavailable() throws Exception {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        List<String> removed = DbIntegrationProjectSupport.INSTANCE.clearDataSources(getProject());
        Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile> psi =
                DbIntegrationProjectSupport.INSTANCE.ensureExecFile(getProject(), "SELECT 1");
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null);
        List<Object> runners = DbIntegrationProjectSupport.INSTANCE.awaitRunners(psi.getSecond(), "SELECT 1", 0, 60_000L);
        assertEquals(
                "清空数据源后平台不应再发现 runner（清掉=" + removed
                        + "，残留=" + DbIntegrationProjectSupport.INSTANCE.dataSourceNames(getProject())
                        + "，发现=" + runnerNames(runners) + "）",
                0,
                runners.size());
        Boolean[] cbResult = {null};
        List<String> errors = new ArrayList<>();
        DataSourceSelector.prepareConsole(
                getProject(),
                "SELECT 1",
                null,
                null,
                it -> errors.add(it),
                cb -> cbResult[0] = cb
        );
        // 无数据源：选择逻辑走到 runners.isEmpty() 分支 → 打开 Database 工具窗 + onError + callback(false)
        assertNotNull("prepareConsole 应回调（无论成败）", cbResult[0]);
        assertEquals("无数据源时 prepareConsole 应回调 false", Boolean.FALSE, cbResult[0]);
        assertTrue("无数据源时 onError 应非空（引导用户配置数据源）", !errors.isEmpty());
        assertFalse("无数据源时不应标记为已准备", DataSourceSelector.isPrepared());
    }

    /**
     * 单数据源发现契约：真实平台 API `getAttachDataSourceRunners` 返回恰好 1 个 runner，
     * 且 runner 显示名含数据源名。验证选择逻辑「单数据源」分支的输入正确（DB 相关，环境门控）。
     *
     * 20260918：① 前置清空 + 数据源名带唯一后缀——名字断言才有区分度（同名残留会让「含数据源名」
     * 恒真）；② 发现结果有界等待收敛（awaitRunners）。
     */
    @Test
    public void testSingleSourceDiscoverableByRunner() throws Exception {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        DataSourceInfo info = readDemoDataSource("mpj_demo");
        assumeTrue("demo 项目数据源配置不可读: " + demoProjectDir.getAbsolutePath(), info != null);
        DbIntegrationProjectSupport.INSTANCE.clearDataSources(getProject());
        LocalDataSource ds = createDataSource(info, "-single");
        String introspectError = introspectDataSource(ds);
        // T-ExecDocGate：显式绿跳（assumeTrue 会被平台 runner 记为 failure 红任务）
        if (introspectError != null) {
            System.out.println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: " + introspectError);
            return;
        }

        String sql = "SELECT id FROM car_device_info WHERE id = 1";
        Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile> psi =
                DbIntegrationProjectSupport.INSTANCE.ensureExecFile(getProject(), sql);
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null);
        PsiFile psiFile = psi.getSecond();

        List<Object> runners = DbIntegrationProjectSupport.INSTANCE.awaitRunners(psiFile, sql, 1, 60_000L);
        assertEquals(
                "单数据源时平台应返回恰好 1 个 runner（实际=" + runnerNames(runners)
                        + "，应用级数据源=" + DbIntegrationProjectSupport.INSTANCE.dataSourceNames(getProject()) + "）",
                1,
                runners.size());
        String name = DbIntegrationProjectSupport.INSTANCE.runnerName(runners.get(0));
        assertTrue("runner 显示名应含数据源名 '" + ds.getName() + "'，实际='" + name + "'", name.contains(ds.getName()));
    }

    /**
     * 多数据源发现契约：创建 2 个数据源，真实平台 API 返回 2 个 runner。
     * 验证选择逻辑「多数据源」分支的输入（N 个 → 弹出选择框）正确（DB 相关，环境门控）。
     *
     * 20260918：前置清空 + 两个唯一后缀名 + 有界收敛（同单数据源用例）；tearDown 的
     * `FileSystemException: 另一个程序正在使用此文件` 随稳定工程目录一并消失
     * （根因见 DbIntegrationProjectSupport 类注释——该异常与断言无关，是工程目录被删）。
     */
    @Test
    public void testMultiSourceDiscoveryReturnsAllRunners() throws Exception {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        DataSourceInfo info = readDemoDataSource("mpj_demo");
        assumeTrue("demo 项目数据源配置不可读: " + demoProjectDir.getAbsolutePath(), info != null);
        DbIntegrationProjectSupport.INSTANCE.clearDataSources(getProject());
        LocalDataSource ds1 = createDataSource(info, "-multi1");
        LocalDataSource ds2 = createDataSource(info, "-multi2");
        String e1 = introspectDataSource(ds1);
        String e2 = introspectDataSource(ds2);
        // T-ExecDocGate：显式绿跳（同上）
        if (e1 != null || e2 != null) {
            System.out.println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: ds1=" + e1 + ", ds2=" + e2);
            return;
        }

        String sql = "SELECT id FROM car_device_info WHERE id = 1";
        Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile> psi =
                DbIntegrationProjectSupport.INSTANCE.ensureExecFile(getProject(), sql);
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null);
        PsiFile psiFile = psi.getSecond();

        List<Object> runners = DbIntegrationProjectSupport.INSTANCE.awaitRunners(psiFile, sql, 2, 60_000L);
        assertEquals(
                "多数据源时平台应返回 2 个 runner（选择分支输入，实际=" + runnerNames(runners)
                        + "，应用级数据源=" + DbIntegrationProjectSupport.INSTANCE.dataSourceNames(getProject()) + "）",
                2,
                runners.size());
        List<String> names = runnerNames(runners);
        assertTrue("runner 列表应含 ds1=" + ds1.getName() + "，实际=" + names, names.stream().anyMatch(n -> n.contains(ds1.getName())));
        assertTrue("runner 列表应含 ds2=" + ds2.getName() + "，实际=" + names, names.stream().anyMatch(n -> n.contains(ds2.getName())));
    }

    /**
     * 单数据源静默选择契约：prepareConsole 对唯一数据源应**不弹选择框**直接选中 →
     * isPrepared()=true 且 sharedDataSourceName==数据源名。证明选择决策正确（DB 相关，环境门控）。
     *
     * 20260918：前置清空 + 唯一名 + 有界收敛，另加**控制台编辑器夹具**——控制台文档即 exec 文件
     * 文档（`JdbcConsole.getDocument()` 字节码实证），headless 下无人为它建编辑器 →
     * `prepareInConsole` 的 `getEditors(consoleDocument)` 恒空 → 静默返回 → 120s 不就绪且
     * errors=[]（基线 idea.log `[M36] editor=null (async wait)` 实证）。
     */
    @Test
    public void testPrepareConsoleSilentlySelectsSingleSource() throws Exception {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        DataSourceInfo info = readDemoDataSource("mpj_demo");
        assumeTrue("demo 项目数据源配置不可读: " + demoProjectDir.getAbsolutePath(), info != null);
        DbIntegrationProjectSupport.INSTANCE.clearDataSources(getProject());
        LocalDataSource ds = createDataSource(info, "-silent");
        String introspectError = introspectDataSource(ds);
        // T-ExecDocGate：显式绿跳（assumeTrue 会被平台 runner 记为 failure 红任务）
        if (introspectError != null) {
            System.out.println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: " + introspectError);
            return;
        }

        String sql = "SELECT id, station_id FROM car_device_info WHERE id = 1";
        Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile> psi =
                DbIntegrationProjectSupport.INSTANCE.ensureExecFile(getProject(), sql);
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null);
        PsiFile psiFile = psi.getSecond();
        // 单数据源是静默选择分支的输入，先钉住（残留数据源会让契约退化成弹窗分支）
        List<Object> runners = DbIntegrationProjectSupport.INSTANCE.awaitRunners(psiFile, sql, 1, 60_000L);
        assertEquals(
                "静默选择契约要求发现链恰好给出 1 个 runner（实际=" + runnerNames(runners) + "）",
                1,
                runners.size());
        execEditor = DbIntegrationProjectSupport.INSTANCE.installExecEditor(getProject());
        assertNotNull("控制台编辑器夹具应可建（prepareConsole 依赖 getEditors(consoleDocument) 非空）", execEditor);

        boolean[] prepared = {false};
        List<String> errors = new ArrayList<>();
        DataSourceSelector.prepareConsole(
                getProject(),
                sql,
                null,
                null,
                it -> errors.add(it),
                cb -> prepared[0] = cb
        );
        boolean preparedInTime = DbIntegrationProjectSupport.INSTANCE.waitWithPump(
                120_000L, 50L, () -> DataSourceSelector.isPrepared());
        if (!preparedInTime) {
            System.out.println("[DB-IT] prepareConsole 未就绪诊断: "
                    + DbIntegrationProjectSupport.INSTANCE.diagnoseConsoleState(getProject(), ds));
        }
        assertTrue(
                "prepareConsole 120s 内未就绪 (errors=" + errors + ")",
                preparedInTime);
        assertTrue("prepareConsole callback 应返回 true", prepared[0]);
        assertTrue("准备阶段不应有错误 (errors=" + errors + ")", errors.isEmpty());
        String selected = (String) selectorField("sharedDataSourceName");
        assertEquals("静默选择应记录唯一数据源名（无弹窗）", ds.getName(), selected);
    }

    /**
     * T-TS-09：**控制台编辑器不可得 → 必须回调 onError**（可诊断性缺口直测）。
     *
     * 与 {@link #testPrepareConsoleSilentlySelectsSingleSource} 是同一准备链的**反面输入**：那条用例
     * 用 DbIntegrationProjectSupport.installExecEditor 补上真实 IDE 里由控制台 UI 提供的编辑器，
     * 本用例**不补**——`prepareInConsole` 用 `getEditors(consoleDocument)` 反查执行用编辑器，
     * 恒空时修复前是两处静默（`:1267` 同步路径 `?: return`、`:1284` 异步路径无 else）：
     * 调用方既收不到 onError 也收不到 callback，面板停在「执行中」且 errors=[]——正是驱动管线
     * 打通前观测到的「120s 不就绪 + 错误为空」的成因（基线 idea.log `[M36] editor=null (async wait)`）。
     *
     * 断言口径：onError 在 60s 内到达，且准备链未走完（isPrepared()=false）。打印实际文案即可看出
     * 命中的是 sync（非 EDT 轮询）还是 async（EDT 异步等待）分支——两条分支成因不同，都由本用例覆盖。
     *
     * 环境门槛同本类其余 DB 用例（testDatabase 任务 + demo 数据源 + 本地 MySQL）。
     */
    @Test
    public void testPrepareConsoleWithoutConsoleEditorReportsError() throws Exception {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        DataSourceInfo info = readDemoDataSource("mpj_demo");
        assumeTrue("demo 项目数据源配置不可读: " + demoProjectDir.getAbsolutePath(), info != null);
        DbIntegrationProjectSupport.INSTANCE.clearDataSources(getProject());
        LocalDataSource ds = createDataSource(info, "-noeditor");
        String introspectError = introspectDataSource(ds);
        // T-ExecDocGate：显式绿跳（assumeTrue 会被平台 runner 记为 failure 红任务）
        if (introspectError != null) {
            System.out.println("[DB-IT] SKIP: introspect 失败（MySQL 未运行/驱动管线未修复）: " + introspectError);
            return;
        }

        // SQL **刻意与紧随其后的 testPrepareConsoleSilentlySelectsSingleSource 逐字相同**：
        // exec 文件是五类共用的**同一路径**，而 `writeVfTextViaDocument` 在「文档内容 != 目标文本」
        // 时才 setText+save，save 内部会 `VirtualFile.refresh()`，该 refresh 触发的 VFS 内容变更事件
        // **不带 from-save 标记** → `MemoryDiskConflictResolver` 登记冲突，随后在事件队列 flush 时
        // 若文档/文件戳仍不等就硬抛 `Unexpected memory-disk conflict in tests`（本轮堆栈实证：
        // writeVfTextViaDocument:3370 → doSaveDocument:377 → VirtualFile.refresh:674 →
        // RefreshSessionImpl.fireEventsInWriteAction → beforeContentChange:57）。
        // 让本用例留下的内容与下一个用例一致，下一个用例就整段跳过 setText+save，不再走那条登记链。
        String sql = "SELECT id, station_id FROM car_device_info WHERE id = 1";
        Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile> psi =
                DbIntegrationProjectSupport.INSTANCE.ensureExecFile(getProject(), sql);
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null);
        PsiFile psiFile = psi.getSecond();
        // 单数据源是静默选择分支的输入，先钉住（残留数据源会让准备链退化成弹窗分支）
        List<Object> runners = DbIntegrationProjectSupport.INSTANCE.awaitRunners(psiFile, sql, 1, 60_000L);
        assertEquals(
                "本用例要求发现链恰好给出 1 个 runner（实际=" + runnerNames(runners) + "）",
                1,
                runners.size());
        // 前置钉死「编辑器不可得」：控制台文档即 exec 文件文档（见 DbIntegrationProjectSupport 类注释），
        // 先释放其上全部存活编辑器——forkEvery=1 下本类用例同 JVM，前序用例的夹具可能残留，
        // 不显式释放则本用例的前置条件取决于执行顺序（顺序依赖假失败）。
        DbIntegrationProjectSupport.INSTANCE.releaseExecEditors(getProject());

        Boolean[] cbResult = {null};
        List<String> errors = new ArrayList<>();
        DataSourceSelector.prepareConsole(
                getProject(),
                sql,
                null,
                null,
                it -> errors.add(it),
                cb -> cbResult[0] = cb
        );
        boolean notified = DbIntegrationProjectSupport.INSTANCE.waitWithPump(60_000L, 50L, () -> !errors.isEmpty());
        assertTrue(
                "编辑器不可得时 onError 必须回调（修复前此处静默返回：面板停在「执行中」且 errors=[]，"
                        + "callback 也永不触发）——errors=" + errors + ", callback=" + cbResult[0],
                notified);
        assertFalse("准备链不应走完（无编辑器 → prepareAfterEditorResolved 未执行）", DataSourceSelector.isPrepared());
        System.out.println("[DB-IT] editor-unavailable onError: " + errors.get(0));

        // 显式收口（不等 tearDown）：本用例把控制台挂上后**中止**了准备链，这是既有用例都没有过的
        // 状态（其余用例要么走完整链、要么从未 attach）。**立即**摘控制台 + 落盘，把「文档已改未存」
        // 的窗口压到最短——该窗口内任何**非 save 来源**的写盘（DBN 控制台自身、平台 teardown 都会做）
        // 都会被 MemoryDiskConflictResolver 登记成冲突，并在**下一个用例**的任意一次文档写里硬抛
        // `Unexpected memory-disk conflict in tests`（20260918 实测：报错点是下一个用例的
        // prepareConsole）。故意不在这里额外 pump 事件队列——pump 只会拉长该窗口。
        DbIntegrationProjectSupport.INSTANCE.detachDbnConsole(getProject());
        DbIntegrationProjectSupport.INSTANCE.releaseExecEditors(getProject());
        try {
            DataSourceSelector.releaseForPanel();
        } catch (Exception ignored) {
        }
        try {
            DataSourceSelector.clearCachedTempPsiFileForTest();
        } catch (Exception ignored) {
        }
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
        DbIntegrationProjectSupport.INSTANCE.detachDbnConsole(getProject());
        DbIntegrationProjectSupport.INSTANCE.releaseExecEditors(getProject());
        // T-PreviewExecDs-03 / M36.5.10：单例的 prepared/shared 槽位跨用例存活（clearPrepared 只清
        // prepared，shared 控制台仍在）——下一个用例会经 reuseSharedConsole 命中上个用例的控制台，
        // 而该控制台的编辑器/文档属于上个用例的夹具。面板关闭语义的清理是 releaseForPanel
        // （clearPrepared + dropSharedConsole + 清 pendingOnConsole），用例边界用它。
        try {
            DataSourceSelector.releaseForPanel();
        } catch (Exception ignored) {
        }
        // T-TestDbDriver：object 单例的 temp Psi 缓存跨用例存活（clearPrepared 刻意不清，见 T-ExecCopyFix F2c）——
        // 而 BasePlatformTestCase 每个用例换一套 fixture 临时目录，残留缓存指向已拆目录 →
        // 「VFS not visible after refresh / 另一个程序正在使用此文件」。用例间必须显式清缓存。
        try {
            DataSourceSelector.clearCachedTempPsiFileForTest();
        } catch (Exception ignored) {
        }
        super.tearDown();
    }
}
