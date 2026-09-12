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

        if (missing > 0) {
            System.out.println("RESULT MISSING " + missing);
            System.exit(1);
        }
        System.out.println("RESULT OK");
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
