import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * T-McpToolset MCP headless 执行链跨版本签名检查器（照 PlatformSignatureChecker 模式）。
 *
 * 由各发行版自带的 JBR 运行（字节码版本天然兼容），对本发行版 DatabaseTools jar 逐一核验
 * McpDbExecutor 反射链的全部入口（类 + 方法 + 首参形态）；任一缺失/形态不满足即非零退出，
 * stdout 末行输出 `RESULT OK` / `RESULT MISSING <n>`。
 *
 * 用法：java -cp <checker目录> McpDbSignatureChecker <发行版目录>
 * （发行版目录 = DatabaseTools 插件所在根，即 <distro>/plugins/DatabaseTools）
 */
public class McpDbSignatureChecker {

    public static void main(String[] args) throws Exception {
        File distro = new File(args[0]);
        List<URL> urls = new ArrayList<URL>();
        // 平台 jar 先收（DatabaseTools 类签名解析依赖平台类型如 AreaInstance；parent=null 的
        // URLClassLoader 必须自带完整依赖，PlatformSignatureChecker 同款 lib+modules 全收）。
        // DatabaseTools 伴随类（ErrorInfo/ErrorHandler 等 connection/throwable 与 util）跨版本
        // 拆分形态漂移大：243 在 grid-core-impl 插件、262 EAP 在 grid-core-plugin、262 装机在
        // connectivity 模块——按 jar 内容判定（含 com/intellij/database/ 类即收），形态无关。
        collectJars(new File(distro, "lib"), urls);
        collectJars(new File(distro, "lib/modules"), urls);
        collectDatabaseJars(new File(distro, "plugins"), urls);
        if (urls.isEmpty()) {
            System.out.println("MISS platform/DatabaseTools jars not found under " + distro);
            System.out.println("RESULT MISSING 1");
            System.exit(3);
        }
        // parent = 平台 classloader：发行版 jar 类 child-first 从 URL 加载，JDK 模块类型
        // （java.sql.SQLException 等）委托平台 loader 解析——parent=null 时 java.se 模块类
        // 不可见（JBR 21+ 模块化，bootstrap 只含 java.base）
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());

        int missing = 0;
        // 数据源发现链（McpDbExecutor.dataSources / listDataSourceNames / explain URL 读取）
        Class<?> dsm = Class.forName("com.intellij.database.psi.DataSourceManager", true, loader);
        missing += check(method(dsm, "getManagers", params("com.intellij.openapi.project.Project")), "DataSourceManager.getManagers(Project)");
        missing += check(method(dsm, "getDataSources"), "DataSourceManager.getDataSources()");
        Class<?> das = Class.forName("com.intellij.database.model.DasDataSource", true, loader);
        missing += check(method(das, "getName"), "DasDataSource.getName()");
        missing += check(method(das, "getConnectionConfig"), "DasDataSource.getConnectionConfig()");
        missing += check(method(Class.forName("com.intellij.database.model.RawConnectionConfig", true, loader), "getUrl"), "RawConnectionConfig.getUrl()");

        // 同步建连链（243/262 javap 同形实证；漂移即在此暴露）
        Class<?> dcm = Class.forName("com.intellij.database.dataSource.DatabaseConnectionManager", true, loader);
        missing += check(method(dcm, "getInstance"), "DatabaseConnectionManager.getInstance()");
        Class<?> point = Class.forName("com.intellij.database.dataSource.DatabaseConnectionPoint", true, loader);
        missing += check(method(dcm, "build", params("com.intellij.openapi.project.Project", "com.intellij.database.dataSource.DatabaseConnectionPoint")), "DatabaseConnectionManager.build(Project, ConnectionPoint)");
        Class<?> builder = Class.forName("com.intellij.database.dataSource.DatabaseConnectionManager$Builder", true, loader);
        missing += check(method(builder, "setAskPassword", params("boolean")), "Builder.setAskPassword(boolean)");
        missing += check(method(builder, "createBlocking"), "Builder.createBlocking()");
        Class<?> guardedRef = Class.forName("com.intellij.database.util.GuardedRef", true, loader);
        missing += check(method(guardedRef, "get"), "GuardedRef.get()");
        missing += check(method(guardedRef, "close"), "GuardedRef.close()");

        // remote JDBC 镜像链（getRemoteConnection 声明在 DatabaseConnectionCore）
        missing += check(method(Class.forName("com.intellij.database.dataSource.DatabaseConnectionCore", true, loader), "getRemoteConnection"), "DatabaseConnectionCore.getRemoteConnection()");
        Class<?> remoteConnection = Class.forName("com.intellij.database.remote.jdbc.RemoteConnection", true, loader);
        missing += check(method(remoteConnection, "createStatement"), "RemoteConnection.createStatement()");
        Class<?> remoteStatement = Class.forName("com.intellij.database.remote.jdbc.RemoteStatement", true, loader);
        missing += check(method(remoteStatement, "executeQuery", params("java.lang.String")), "RemoteStatement.executeQuery(String)");
        missing += check(method(remoteStatement, "close"), "RemoteStatement.close()");
        Class<?> rs = Class.forName("com.intellij.database.remote.jdbc.RemoteResultSet", true, loader);
        missing += check(method(rs, "next"), "RemoteResultSet.next()");
        missing += check(method(rs, "getString", params("int")), "RemoteResultSet.getString(int)");
        missing += check(method(rs, "getMetaData"), "RemoteResultSet.getMetaData()");
        missing += check(method(rs, "wasNull"), "RemoteResultSet.wasNull()");
        missing += check(method(rs, "close"), "RemoteResultSet.close()");
        Class<?> meta = Class.forName("com.intellij.database.remote.jdbc.RemoteResultSetMetaData", true, loader);
        missing += check(method(meta, "getColumnCount"), "RemoteResultSetMetaData.getColumnCount()");
        missing += check(method(meta, "getColumnLabel", params("int")), "RemoteResultSetMetaData.getColumnLabel(int)");

        System.out.println(missing == 0 ? "RESULT OK" : "RESULT MISSING " + missing);
        System.exit(missing == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------

    private static String[] params(String... names) {
        return names;
    }

    private static Method method(Class<?> declaring, String name, String... paramTypes) {
        // 在声明接口/类上找方法（McpDbExecutor 反射同语义）；找不到返回 null 交由 check 记账
        Class<?>[] types = new Class<?>[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            // 原始类型不能走 Class.forName
            if (paramTypes[i].equals("boolean")) { types[i] = boolean.class; continue; }
            if (paramTypes[i].equals("int")) { types[i] = int.class; continue; }
            if (paramTypes[i].equals("long")) { types[i] = long.class; continue; }
            try {
                types[i] = Class.forName(paramTypes[i], false, declaring.getClassLoader());
            } catch (Throwable t) {
                System.out.println("MISS param type " + paramTypes[i] + ": " + t);
                return null;
            }
        }
        try {
            return declaring.getMethod(name, types);
        } catch (Throwable t) {
            System.out.println("MISS " + declaring.getName() + "." + name + ": " + t);
            return null;
        }
    }

    private static int check(Method method, String label) {
        if (method == null) {
            System.out.println("MISS " + label);
            return 1;
        }
        return 0;
    }

    private static void collectJars(File dir, List<URL> urls) {
        File[] files = dir.listFiles();
        if (files == null) return;
        try {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".jar")) {
                    urls.add(f.toURI().toURL());
                }
            }
        } catch (Exception e) {
            System.out.println("MISS collect jars " + dir + ": " + e);
        }
    }

    /** 递归收集含 com/intellij/database/ 类的插件 jar（DatabaseTools 及其伴随插件拆分形态无关）。 */
    private static void collectDatabaseJars(File dir, List<URL> urls) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectDatabaseJars(f, urls);
            } else if (f.isFile() && f.getName().endsWith(".jar")) {
                try {
                    java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f);
                    try {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            if (entries.nextElement().getName().startsWith("com/intellij/database/")) {
                                urls.add(f.toURI().toURL());
                                break;
                            }
                        }
                    } finally {
                        zip.close();
                    }
                } catch (Exception e) {
                    // 非标准 jar 跳过
                }
            }
        }
    }
}
