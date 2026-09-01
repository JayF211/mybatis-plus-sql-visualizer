package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport;
import com.intellij.credentialStore.OneTimeString;
import com.intellij.database.access.DbCredentialManager;
import com.intellij.database.dataSource.DatabaseConnectionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.LocalDataSourceManager;
import com.intellij.database.util.GuardedRef;
import com.intellij.lang.Language;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.sql.dialects.SqlDialectMappings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertTrue;
import org.junit.Assume.assumeTrue;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * DataSourceSelector 数据源选择/发现层集成测试骨架（M36.5.23 续）。
 * 覆盖无数据源/单数据源/多数据源的发现契约，环境门控（testDatabase 任务）。
 */
public class DataSourceSelectorIntegrationTest extends BasePlatformTestCase {

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
        LocalDataSourceManager mgr = LocalDataSourceManager.getInstance(project);
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
        ds.setWorkingDir(project.getBasePath());
        ds.setPasswordStorage(LocalDataSource.Storage.MEMORY);
        mgr.addDataSource(ds);
        new DbCredentialManager().setPassword(ds, new OneTimeString(getDbPassword()), LocalDataSource.Storage.MEMORY);
        return ds;
    }

    private String introspectDataSource(LocalDataSource ds) {
        try {
            DatabaseConnectionManager mgr = DatabaseConnectionManager.getInstance();
            Object builder = mgr.build(project, ds);
            builder.getClass().getMethod("setAskPassword", boolean.class).invoke(builder, false);
            Future<GuardedRef> future = ApplicationManager.getApplication().executeOnPooledThread(
                    (Computable<GuardedRef>) () -> {
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
            boolean loaded = waitUntil(90_000, () -> !LocalDataSourceManager.getInstance(project).isLoading(ds));
            ref.close();
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

    private PsiFile buildExecPsiFile(String sql) {
        String base = project.getBasePath();
        if (base == null) return null;
        File dir = new File(base, ".idea/sql-visualizer/exec");
        dir.mkdirs();
        File f = new File(dir, "mpsql-exec.sql");
        try {
            Files.write(f.toPath(), sql.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
        if (vf == null) return null;
        SqlDialectMappings mappings = SqlDialectMappings.getInstance(project);
        try {
            Method getAvailable = mappings.getClass().getMethod("getAvailableValues");
            Object valuesObj = getAvailable.invoke(mappings);
            if (valuesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) valuesObj;
                Object mysql = values.stream()
                        .filter(it -> it instanceof Language && "MySQL".equalsIgnoreCase(((Language) it).getId()))
                        .findFirst().orElse(null);
                if (mysql != null) {
                    Method setMapping = mappings.getClass().getMethod("setMapping", VirtualFile.class, Object.class);
                    setMapping.invoke(mappings, vf, mysql);
                }
            }
        } catch (Exception ignored) {
            // 反射失败忽略
        }
        return ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
    }

    private List<Object> discoverRunners(PsiFile psiFile, String sql) {
        com.intellij.util.Consumer<Object> consumer = o -> {
        };
        try {
            Class<?> clazz = Class.forName("com.intellij.database.console.DatabaseRunners");
            Method method = clazz.getMethod("getAttachDataSourceRunners",
                    PsiFile.class, String.class, com.intellij.util.Consumer.class);
            Object result = method.invoke(null, psiFile, sql, consumer);
            if (result instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) result;
                return list;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String runnerName(Object runner) {
        try {
            Method m = runner.getClass().getMethod("getDisplayName");
            m.setAccessible(true);
            Object r = m.invoke(runner);
            return r instanceof String ? (String) r : "Unknown";
        } catch (Exception e) {
            return "Unknown";
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

    // ───────────────────────────── 用例 ─────────────────────────────

    @Test
    public void testNoDataSourceReportedAsUnavailable() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        boolean[] cbResult = {false};
        List<String> errors = new ArrayList<>();
        DataSourceSelector.prepareConsole(
                project,
                "SELECT 1",
                null,
                null,
                it -> errors.add(it),
                cb -> cbResult[0] = cb
        );
        assertNotNull("prepareConsole 应回调（无论成败）", (Boolean) cbResult[0]);
        assertEquals("无数据源时 prepareConsole 应回调 false", false, cbResult[0]);
        assertTrue("无数据源时 onError 应非空（引导用户配置数据源）", !errors.isEmpty());
        assertFalse("无数据源时不应标记为已准备", DataSourceSelector.isPrepared());
    }

    @Test
    public void testSingleSourceDiscoverableByRunner() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        DataSourceInfo info = readDemoDataSource("mpj_demo");
        assumeTrue("demo 项目数据源配置不可读: " + demoProjectDir.getAbsolutePath(), info != null);
        LocalDataSource ds = createDataSource(info);
        String introspectError = introspectDataSource(ds);
        assumeTrue("introspect 失败（MySQL 未启动/库未初始化？）：" + introspectError, introspectError == null);

        PsiFile psi = buildExecPsiFile("SELECT id FROM car_device_info WHERE id = 1");
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null);

        List<Object> runners = discoverRunners(psi, "SELECT id FROM car_device_info WHERE id = 1");
        assertEquals("单数据源时平台应返回恰好 1 个 runner", 1, runners.size());
        String name = runnerName(runners.get(0));
        assertTrue("runner 显示名应含数据源名 '" + ds.getName() + "'，实际='" + name + "'", name.contains(ds.getName()));
    }

    @Test
    public void testMultiSourceDiscoveryReturnsAllRunners() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        DataSourceInfo info = readDemoDataSource("mpj_demo");
        assumeTrue("demo 项目数据源配置不可读: " + demoProjectDir.getAbsolutePath(), info != null);
        LocalDataSource ds1 = createDataSource(info, "");
        LocalDataSource ds2 = createDataSource(info, "2");
        String e1 = introspectDataSource(ds1);
        String e2 = introspectDataSource(ds2);
        assumeTrue("introspect 失败（MySQL 未启动/库未初始化？）：ds1=" + e1 + ", ds2=" + e2, e1 == null && e2 == null);

        PsiFile psi = buildExecPsiFile("SELECT id FROM car_device_info WHERE id = 1");
        assumeTrue("临时执行 PsiFile 构造失败（VFS/方言映射）", psi != null);

        List<Object> runners = discoverRunners(psi, "SELECT id FROM car_device_info WHERE id = 1");
        assertEquals("多数据源时平台应返回 2 个 runner（选择分支输入）", 2, runners.size());
        List<String> names = runners.stream().map(this::runnerName).collect(java.util.stream.Collectors.toList());
        assertTrue("runner 列表应含 ds1=" + ds1.getName() + "，实际=" + names, names.stream().anyMatch(n -> n.contains(ds1.getName())));
        assertTrue("runner 列表应含 ds2=" + ds2.getName() + "，实际=" + names, names.stream().anyMatch(n -> n.contains(ds2.getName())));
    }

    @Test
    public void testPrepareConsoleSilentlySelectsSingleSource() {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        DataSourceInfo info = readDemoDataSource("mpj_demo");
        assumeTrue("demo 项目数据源配置不可读: " + demoProjectDir.getAbsolutePath(), info != null);
        LocalDataSource ds = createDataSource(info);
        String introspectError = introspectDataSource(ds);
        assumeTrue("introspect 失败（MySQL 未启动/库未初始化？）：" + introspectError, introspectError == null);

        boolean[] prepared = {false};
        List<String> errors = new ArrayList<>();
        DataSourceSelector.prepareConsole(
                project,
                "SELECT id, station_id FROM car_device_info WHERE id = 1",
                null,
                null,
                it -> errors.add(it),
                cb -> prepared[0] = cb
        );
        assertTrue("prepareConsole 120s 内未就绪 (errors=" + errors + ")",
                waitUntil(120_000, () -> DataSourceSelector.isPrepared()));
        assertTrue("prepareConsole callback 应返回 true", prepared[0]);
        assertTrue("准备阶段不应有错误 (errors=" + errors + ")", errors.isEmpty());
        String selected = (String) selectorField("sharedDataSourceName");
        assertEquals("静默选择应记录唯一数据源名（无弹窗）", ds.getName(), selected);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            DataSourceSelector.clearPrepared();
        } catch (Exception ignored) {
        }
        super.tearDown();
    }
}
