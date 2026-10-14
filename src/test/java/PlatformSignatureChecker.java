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

        // 7. F48（T-PerfQuick5）：SqlMapperParserDefinition 编程式注册的卸载摘除路径——
        //    KeyedExtensionCollector 三参 addExplicitExtension（内部 Disposer.register(parent,
        //    removeExplicitExtension)，243 源码 :80-82）与两参 removeExplicitExtension。
        //    生产摘除挂插件应用级宿主（SqlMapperInjectionBarrierHost），动态卸载容器级联即摘。
        Class<?> collector = Class.forName("com.intellij.openapi.util.KeyedExtensionCollector", true, loader);
        missing += check(byNameAndCount(collector, "addExplicitExtension", 3),
                "addExplicitExtension(3 params)");
        missing += check(byNameAndCount(collector, "removeExplicitExtension", 2),
                "removeExplicitExtension(2 params)");

        // 8. T-XmlSynHi D68：mapper XML 注入门「平台 per-host 探测」的运行面——
        //    MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME（ProjectExtensionPointName，EP 名串
        //    com.intellij.multiHostInjector；243 lib/util-8.jar / 262 lib/intellij.platform.core.jar
        //    双版 javap 实证同形态）+ getExtensions(AreaInstance) + MultiHostRegistrar stub 三方法
        //    （startInjecting 二参默认实现委托一参）。均为运行期按名解析的平台签名，漂移即本项红。
        //    注：initialize=false——仅核签名不触发静态初始化（EP 常量构造依赖平台引导态，裸
        //    URLClassLoader 不具备，true 形态会 NoClassDefFoundError 假红）。
        missing += checkPerHostProbeApis(loader);
        missing += checkEditorColorsTopic(loader);

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

    /**
     * #8（T-XmlSynHi D68）：per-host 注入探针运行面核验。
     *
     * 顺带打印三代 SQL 注入器在本发行版的在场实况（INFO 不计缺失）——
     *  - `com.intellij.sql.injector.SqlLanguageInjector`（历史让位门槛的判定对象）在 243/262
     *    发行物已不存在（本仓 599/1809 jar 全扫实证），类在场判定废止的直接证据；
     *  - SqlTypeBasedInjector 按代际不同名（243=com.intellij.sql.injection，database-plugin.jar
     *    META-INF/intellilang-sql-support.xml 注册；262=com.intellij.database.sql.intelliLang.injection，
     *    META-INF/plugin.xml 注册）——per-host 探测经 EP 枚举对改名免疫，故只作 INFO 不判缺失。
     */
    private static int checkPerHostProbeApis(ClassLoader loader) {
        int missing = 0;
        try {
            Class<?> mhi = Class.forName("com.intellij.lang.injection.MultiHostInjector", false, loader);
            try {
                java.lang.reflect.Field epField = mhi.getField("MULTIHOST_INJECTOR_EP_NAME");
                System.out.println("INFO MULTIHOST_INJECTOR_EP_NAME type=" + epField.getType().getName());
                missing += check(byNameAndCount(epField.getType(), "getExtensions", 1),
                        "ProjectExtensionPointName.getExtensions(1 param)");
            } catch (NoSuchFieldException e) {
                System.out.println("MISS MULTIHOST_INJECTOR_EP_NAME field: " + e);
                missing++;
            }
            missing += check(byNameAndCount(mhi, "getLanguagesToInject", 2),
                    "MultiHostInjector.getLanguagesToInject(2 params)");
            missing += check(byNameAndCount(mhi, "elementsToInjectIn", 0),
                    "MultiHostInjector.elementsToInjectIn()");

            Class<?> registrar = Class.forName("com.intellij.lang.injection.MultiHostRegistrar", false, loader);
            missing += check(byNameAndCount(registrar, "startInjecting", 1),
                    "MultiHostRegistrar.startInjecting(1 param)");
            missing += check(byNameAndCount(registrar, "addPlace", 4),
                    "MultiHostRegistrar.addPlace(4 params)");
            missing += check(byNameAndCount(registrar, "doneInjecting", 0),
                    "MultiHostRegistrar.doneInjecting()");
        } catch (ClassNotFoundException e) {
            System.out.println("MISS per-host probe platform types: " + e);
            return 1;
        }

        try {
            Class.forName("com.intellij.sql.injector.SqlLanguageInjector", false, loader);
            System.out.println("INFO legacy SqlLanguageInjector present (pre-243 代际；类在场判定已废止)");
        } catch (ClassNotFoundException absent) {
            System.out.println("INFO legacy SqlLanguageInjector absent（243+ 已移除——类在场让位门槛废止的实证）");
        }
        boolean anyTypeBased = false;
        String[] generations = {
                "com.intellij.sql.injection.SqlTypeBasedInjector",
                "com.intellij.database.sql.intelliLang.injection.SqlTypeBasedInjector",
        };
        for (String fqn : generations) {
            try {
                Class<?> c = Class.forName(fqn, false, loader);
                Class<?>[] ifaces = c.getInterfaces();
                boolean multiHost = false;
                for (Class<?> i : ifaces) {
                    if ("com.intellij.lang.injection.MultiHostInjector".equals(i.getName())) {
                        multiHost = true;
                        break;
                    }
                }
                System.out.println("INFO SqlTypeBasedInjector present: " + fqn
                        + " implementsMultiHostInjector=" + multiHost);
                anyTypeBased = true;
            } catch (ClassNotFoundException otherGeneration) {
                // 代际缺席正常（243 无 database.* 代、262 无 sql.injection 注册代）
            }
        }
        if (!anyTypeBased) {
            System.out.println("INFO SqlTypeBasedInjector absent in this distro（Community/裁剪包形态，探针按 EP 空清单判不让位）");
        }
        return missing;
    }

    private static int checkEditorColorsTopic(ClassLoader loader) {
        try {
            Class<?> manager = Class.forName("com.intellij.openapi.editor.colors.EditorColorsManager", false, loader);
            Class<?> listener = Class.forName("com.intellij.openapi.editor.colors.EditorColorsListener", false, loader);
            Class<?> scheme = Class.forName("com.intellij.openapi.editor.colors.EditorColorsScheme", false, loader);
            java.lang.reflect.Field topic = manager.getField("TOPIC");
            if (!java.lang.reflect.Modifier.isStatic(topic.getModifiers()) ||
                    !"com.intellij.util.messages.Topic".equals(topic.getType().getName())) {
                System.out.println("MISS EditorColorsManager.TOPIC type or modifiers");
                return 1;
            }
            return check(listener.getMethod("globalSchemeChange", scheme),
                    "EditorColorsListener.globalSchemeChange(EditorColorsScheme)");
        } catch (Throwable t) {
            System.out.println("MISS editor color scheme change topic: " + t);
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
