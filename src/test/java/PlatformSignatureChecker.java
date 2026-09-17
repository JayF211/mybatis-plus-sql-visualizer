import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨版本平台签名检查器（T-DynUpdate 262 签名漂移事故的回归护栏）。
 *
 * 由各发行版自带的 JBR 运行（字节码版本天然兼容），对本发行版 lib 目录下的平台 jar
 * 用与生产代码一致的弹性匹配逻辑解析动态安装五入口；任一入口缺失/形态不满足即
 * 非零退出。 stdout 末行输出 `RESULT OK` / `RESULT MISSING <n>`。
 *
 * 用法：java -cp <checker目录> PlatformSignatureChecker <发行版lib目录>
 */
public class PlatformSignatureChecker {

    public static void main(String[] args) throws Exception {
        File lib = new File(args[0]);
        List<URL> urls = new ArrayList<URL>();
        collectJars(lib, urls);
        File modules = new File(lib, "modules");
        collectJars(modules, urls);
        // T-DbSchemaCompletion：Database Tools 是 bundled 插件（plugins/DatabaseTools/lib），
        // 不在 lib 下——ObjectKind 检查（见 #6）需要它，且它依赖 lib 里的 DynamicBundle
        // （单 DBN jar 加载会 NoClassDefFoundError: com/intellij/DynamicBundle，实测）。
        File distroRoot = lib.getParentFile();
        File dbnLib = new File(distroRoot, "plugins/DatabaseTools/lib");
        collectJars(dbnLib, urls);
        collectJars(new File(dbnLib, "modules"), urls);
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null);

        int missing = 0;
        Class<?> dp = Class.forName("com.intellij.ide.plugins.DynamicPlugins", true, loader);
        Class<?> desc = Class.forName("com.intellij.ide.plugins.IdeaPluginDescriptorImpl", true, loader);

        // 1. allowLoadUnloadWithoutRestart：首参弹性（262/243 首参可能漂移），优先最少参数
        missing += check(byFirstParam(dp, "allowLoadUnloadWithoutRestart", desc),
                "allowLoadUnloadWithoutRestart(<descriptor>)");

        // 2. unloadPlugin：首参弹性（262=PluginMainDescriptor 子类，243=IdeaPluginDescriptorImpl）
        Method unload = byFirstParam(dp, "unloadPlugin", desc);
        missing += check(unload, "unloadPlugin(<descriptor>)");
        if (unload != null && unload.getParameterTypes().length >= 2) {
            try {
                unload.getParameterTypes()[1].getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                System.out.println("MISS unloadPlugin options no-arg ctor: " + t);
                missing++;
            }
        }

        // 3. installAndLoadDynamicPlugin(Path, JComponent, IdeaPluginDescriptorImpl)
        Class<?> installer = Class.forName("com.intellij.ide.plugins.PluginInstaller", true, loader);
        missing += check(byNameAndCount(installer, "installAndLoadDynamicPlugin", 3),
                "installAndLoadDynamicPlugin(3 params)");

        // 4. loadDescriptorFromArtifact(Path, BuildNumber)
        Class<?> pdl = Class.forName("com.intellij.ide.plugins.PluginDescriptorLoader", true, loader);
        missing += check(byNameAndCount(pdl, "loadDescriptorFromArtifact", 2),
                "loadDescriptorFromArtifact(2 params)");

        // 5. findPlugin(PluginId)
        Class<?> core = Class.forName("com.intellij.ide.plugins.PluginManagerCore", true, loader);
        missing += check(byNameAndCount(core, "findPlugin", 1), "findPlugin(1 param)");

        // 6. T-DbSchemaCompletion：DatabaseSchemaResolver 新增的反射入口——
        //    `ObjectKind.COLUMN` 必须是可读的 public static final 字段。DBN 的 ObjectKind 是
        //    手写 type-safe enum（非 java.lang.Enum，enumConstants 恒 null）：历史上按 enumConstants
        //    取，于是数据源 schema 列遍历一次都没执行过且静默无日志（真机潜伏数周）。
        //    Database Tools 未捆绑的发行版（Community/裁剪包）跳过本项，不计缺失。
        missing += checkObjectKindColumn(loader);

        if (missing > 0) {
            System.out.println("RESULT MISSING " + missing);
            System.exit(1);
        }
        System.out.println("RESULT OK");
    }

    /**
     * 校验 `ObjectKind.COLUMN` 可按静态字段读出；Database Tools 缺席时打印 SKIP 返回 0。
     *
     * 同时打印 `enumConstants` 实况——该值恒为 null 是「不能按枚举取」的直接证据，
     * 漂移时这一行就是判据。
     */
    private static int checkObjectKindColumn(ClassLoader loader) {
        try {
            Class<?> kind = Class.forName("com.intellij.database.model.ObjectKind", true, loader);
            Object[] constants = kind.getEnumConstants();
            Object column = kind.getField("COLUMN").get(null);
            System.out.println("INFO ObjectKind.COLUMN enumConstants=" + (constants == null ? "null" : "non-null")
                    + " isEnum=" + kind.isEnum() + " value=" + column);
            if (column == null) {
                System.out.println("MISS ObjectKind.COLUMN static field resolves to null");
                return 1;
            }
            return 0;
        } catch (ClassNotFoundException e) {
            System.out.println("SKIP DatabaseTools (Database Tools) not bundled: " + e.getMessage());
            return 0;
        } catch (Throwable t) {
            System.out.println("MISS ObjectKind.COLUMN resolve failed: " + t);
            return 1;
        }
    }

    private static int check(Method m, String label) {
        if (m == null) {
            System.out.println("MISS " + label);
            return 1;
        }
        System.out.println("OK   " + label + " -> " + m.getName() + m.toGenericString().substring(m.toGenericString().indexOf('(')));
        return 0;
    }

    private static void collectJars(File dir, List<URL> urls) throws java.net.MalformedURLException {
        File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars != null) {
            for (File j : jars) {
                urls.add(j.toURI().toURL());
            }
        }
    }

    /** 与生产 DynamicInstallSupport.resolveMethodByFirstParam 同款逻辑：首参互为可赋值，最少参数优先。 */
    private static Method byFirstParam(Class<?> clazz, String name, Class<?> firstParamBase) {
        Method best = null;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length == 0) {
                continue;
            }
            Class<?> first = m.getParameterTypes()[0];
            if (!firstParamBase.isAssignableFrom(first) && !first.isAssignableFrom(firstParamBase)) {
                continue;
            }
            if (best == null || m.getParameterTypes().length < best.getParameterTypes().length) {
                best = m;
            }
        }
        return best;
    }

    private static Method byNameAndCount(Class<?> clazz, String name, int count) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == count) {
                return m;
            }
        }
        return null;
    }
}
