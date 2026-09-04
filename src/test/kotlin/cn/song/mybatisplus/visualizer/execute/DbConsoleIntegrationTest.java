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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * DB 控制台端到端集成测试（M36.5.23）——模拟 Ultimate 沙盒真实环境。
 * 运行：./gradlew testDatabase
 */
public class DbConsoleIntegrationTest extends BasePlatformTestCase {

    private final File demoProjectDir = new File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"));

    private String getDbPassword() {
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
        try {
            Method setDriverRef = ds.getClass().getDeclaredMethod("setDriverRef", String.class);
            setDriverRef.setAccessible(true);
            setDriverRef.invoke(ds, "mysql.8");
        } catch (Exception ignored) {
        }
        ds.resolveDriver();
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
            ref.close();
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

    /**
     * 端到端：准备 → 执行 → 改参 → 再执行 → 断言新结果 Tab。
     */
    @Test
    public void testPrepareExecuteAndParameterChangeEndToEnd() throws Exception {
        assumeTrue("Database 插件未加载（应在 testDatabase 任务下运行）", ExecuteSqlSupport.isAvailable());
        DataSourceInfo info = readDemoDataSource("mpj_demo");
        assumeTrue("demo 项目数据源配置不可读: " + demoProjectDir.getAbsolutePath(), info != null);
        DataSourceInfo dsInfo = info;

        LocalDataSource ds = createDataSource(dsInfo);

        String introspectError = introspectDataSource(ds);
        assumeTrue("introspect 失败：" + introspectError, introspectError == null);

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
                java.nio.file.Files.write(f.toPath(), "SELECT 1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                diag.append("step1 file written: ").append(f.getAbsolutePath()).append("\n");
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
                if (vf == null) throw new IllegalStateException("refreshAndFindFileByIoFile null");
                diag.append("step2 vf=").append(vf.getPath()).append("\n");
                SqlDialectMappings mappings = SqlDialectMappings.getInstance(getProject());
                List<?> values = (List<?>) mappings.getClass().getMethod("getAvailableValues").invoke(mappings);
                diag.append("step3 available dialects=")
                        .append(values.stream().map(o -> o instanceof Language ? ((Language) o).getID() : null).collect(java.util.stream.Collectors.toList()))
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
                diag.append("step4 psi=").append(psi != null ? psi.getClass().getSimpleName()  : (String) null)
                        .append(" dialect=").append(psi != null && psi.getLanguage() != null ? psi.getLanguage().getID()  : (String) null).append("\n");
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
                    diag.append("step8 ds dialect=").append(dsDialect != null ? dsDialect.getClass().getSimpleName()  : (String) null)
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
                                .append(" sqlDsClass=").append(sqlDs != null ? sqlDs.getClass().getSimpleName()  : (String) null).append("\n");
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

        boolean[] consoleReady = {false};
        List<String> errors = new ArrayList<>();
        boolean[] prepared = {false};
        DataSourceSelector.prepareConsole(
                getProject(),
                "SELECT id, station_id FROM car_device_info WHERE id = 1",
                c -> consoleReady[0] = true,
                e -> {
                },
                it -> errors.add(it),
                p -> prepared[0] = p
        );
        assertTrue("prepareConsole 120s 内未就绪 (errors=" + errors + ", diag=\n" + diag + ")",
                waitUntil(120_000, () -> consoleReady[0]));
        assertTrue("prepareConsole callback 应返回 true", prepared[0]);
        assertTrue("控制台就绪后 isPrepared() 应为 true", DataSourceSelector.isPrepared());
        assertTrue("prepare 阶段不应有错误 (errors=" + errors + ")", errors.isEmpty());

        assumeTrue("未配置 mpTestDbPassword——请在 ~/.gradle/gradle.properties 添加 mpTestDbPassword=<MySQL密码>（无密码时仅验证 prepare 层）",
                !getDbPassword().isEmpty());

        boolean[] resultsReady = {false};
        List<String> execErrors = new ArrayList<>();
        DataSourceSelector.executePrepared(
                getProject(),
                c -> resultsReady[0] = true,
                it -> execErrors.add(it)
        );
        assertTrue("首次执行 60s 内无结果 Tab (errors=" + execErrors + ")",
                waitUntil(60_000, () -> !resultTabNames().isEmpty()));
        assertTrue("onResultsReady 应被回调", resultsReady[0]);
        List<String> firstTabs = resultTabNames();
        assertTrue("首次执行后应有结果 Tab (tabs=" + firstTabs + ")", !firstTabs.isEmpty());
        String firstTabName = firstTabs.get(firstTabs.size() - 1);

        Editor editor = (Editor) selectorField("preparedEditor");
        WriteAction.runAndWait(() -> editor.getDocument().setText("SELECT id, station_id FROM car_device_info WHERE id = 2"));

        boolean[] secondReady = {false};
        DataSourceSelector.executePrepared(
                getProject(),
                c -> secondReady[0] = true,
                it -> execErrors.add(it)
        );
        boolean newTabAppeared = waitUntil(60_000, () -> resultTabNames().stream().anyMatch(n -> !n.equals(firstTabName)));
        List<String> secondTabs = resultTabNames();
        assertTrue("第二次执行后未出现新结果 Tab（滞后一轮回归）：first=" + firstTabName + ", second=" + secondTabs + ", errors=" + execErrors,
                newTabAppeared);
        assertTrue("第二次执行 onResultsReady 应被回调", secondReady[0]);
        assertTrue("集成链路不应有错误回调 (errors=" + execErrors + ")", execErrors.isEmpty());

        DataSourceSelector.clearPrepared();
        assertEquals("clearPrepared 后 isPrepared() 应为 false", false, DataSourceSelector.isPrepared());
        assertNotNull(dsInfo);
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
