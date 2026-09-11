package cn.song.mybatisplus.visualizer.update;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.BuildNumber;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.swing.JComponent;

/**
 * 动态安装门面（T-DynUpdate）：下载包先尝试平台动态热替换——卸载旧版 → 解压新版 → 动态装载，
 * 任一步不满足或失败都回退 [PlatformPluginInstaller.installRestartScript] 启动脚本路径
 * （重启生效，M28 原行为），全程无中间死态。
 *
 * <p>平台 API 全走反射探测（F0 javap 243 + 反编译 262 双端实证，签名与语义一致）：
 * <ul>
 * <li>{@code PluginDescriptorLoader.loadDescriptorFromArtifact(Path, BuildNumber)}：从发行 zip 读新版描述（两平台 public）；</li>
 * <li>{@code DynamicPlugins.allowLoadUnloadWithoutRestart(descriptor)}：卸载可行性预检——EP dynamic 属性
 * （本插件 9/9 合规）、{@code ide.dynamicPluginVetoer}、依赖插件图（两平台 public）；</li>
 * <li>{@code DynamicPlugins.unloadPlugin(descriptor)}：运行期卸载旧版（两平台 public，内部走 EDT write action）；</li>
 * <li>{@code PluginInstaller.installAndLoadDynamicPlugin(Path, JComponent?, descriptor)}：解包 + 动态装载新版
 * （两平台 public；双端反编译实证 descriptor 参数仅用于进度标题/日志/fireState，解包布局由 zip
 * 顶层目录名决定，传新版描述即可）；</li>
 * <li>{@code PluginManagerCore.findPlugin(PluginId)}：取当前已装描述（两平台 public）。</li>
 * </ul>
 *
 * <p>installFromDisk 复用路线 F0 否决：243 包私有 + {@code File} 参数 + 6 参，262 public + {@code Path} + 7 参，
 * 且两条平台流程都不卸载已加载旧版（{@code installAndLoadDynamicPlugin} 在旧版仍加载时直接返回 false），
 * 热更新必失败——故手工编排 unload → install 序列。
 *
 * <p>自更新的特殊处境：[install] 的调用栈运行在将被卸载的旧 classloader 上——卸载/装载回调内只触达
 * 平台 API 与纯函数，返回后由调用方（UI 层）经平台通知报告结果，不再触达自身服务。
 */
public final class DynamicInstallSupport {

    public static final DynamicInstallSupport INSTANCE = new DynamicInstallSupport();
    private DynamicInstallSupport() {}

    private static final Logger LOG = Logger.getInstance(DynamicInstallSupport.class);

    /**
     * 安装入口：zip 前置校验 → 开发态守卫 → 平台能力探测 → 卸载预检 → EDT 卸载旧版 + 装载新版；
     * 任何失败回退启动脚本路径。全链 runCatching 兜底——崩溃也不劣化 M28 行为。
     */
    public static PluginUpdateRunner.InstallResult install(Path zip) {
        try {
            return doInstall(zip);
        } catch (Throwable error) {
            LOG.warn("dynamic install crashed — falling back to restart script: " + error.getMessage(), error);
            return fallbackToRestartScript(zip, "dynamic install crashed: " + error.getMessage());
        }
    }

    private static PluginUpdateRunner.InstallResult doInstall(Path zip) {
        // 1. zip 前置校验（纯函数）：防半截包/错包进入热替换（快速失败，不触平台）
        ArtifactCheck artifact = validateArtifact(zip);
        if (artifact instanceof ArtifactCheck.Invalid invalid) {
            return fallbackToRestartScript(zip, "artifact check failed: " + invalid.reason());
        }
        String version = ((ArtifactCheck.Ok) artifact).version();

        // 2. 开发态守卫（runIde/测试从 build/classes 加载，卸载/替换会误删构建产物）
        PluginUpdateRunner.InstallResult.Failed guard = PlatformPluginInstaller.devGuardOrFailure();
        if (guard != null) return guard;

        // 3. 平台能力探测：五个反射入口任一缺失（未来版本签名漂移/裁剪发行版）即回退
        PlatformApi api = PlatformApis.probe();
        if (api == null) {
            return fallbackToRestartScript(zip, "platform dynamic-install APIs unavailable");
        }

        // 4. 当前描述缺失（未安装/已禁用态）→ 无法 unload 旧版，走脚本路径原样覆盖
        Object current = api.findPlugin(UpdateSource.PLUGIN_ID);
        if (current == null) {
            return fallbackToRestartScript(zip, "current plugin descriptor not found");
        }

        // 5. 卸载可行性预检：本插件 EP 已全 dynamic（9/9 实证），此处主要防 vetoer/依赖图否决
        if (!api.allowLoadUnload(current)) {
            return fallbackToRestartScript(zip, "dynamic unload rejected by platform (vetoer/EP/dependency)");
        }

        // 6. 新版描述（仅作 installAndLoadDynamicPlugin 的日志/状态广播参数，见类 KDoc）
        Object newDescriptor = api.loadDescriptorFromArtifact(zip);

        // 7. EDT 串行执行：卸载旧版 → 解压并动态装载新版
        UnloadInstallResult outcome = api.unloadAndInstallOnEdt(current, zip, newDescriptor);
        if (outcome.installed()) {
            LOG.info("dynamic install succeeded — plugin hot-reloaded to " + version + " (zip=" + zip + ")");
            return new PluginUpdateRunner.InstallResult.InstalledDynamic(version);
        }
        String reason = outcome.unloaded() ? "old version unloaded but dynamic load failed" : "dynamic unload failed";
        return fallbackToRestartScript(zip, reason);
    }

    /** 卸载/装载二态（Kotlin Pair&lt;Boolean, Boolean&gt; 等价，命名可读）。 */
    private record UnloadInstallResult(boolean unloaded, boolean installed) {}

    /** 兜底：调度启动脚本替换（M28 路径），失败原因记日志供 idea.log 排查。 */
    private static PluginUpdateRunner.InstallResult fallbackToRestartScript(Path zip, String reason) {
        LOG.info("dynamic install not applicable — falling back to restart script: " + reason);
        return PlatformPluginInstaller.installRestartScript(zip);
    }

    // ------------------------------------------------------------------
    // zip 前置校验（纯 java.util.zip，单测直接覆盖）
    // ------------------------------------------------------------------

    /** 校验结果：Ok 携带从 plugin.xml 读出的 id/version；Invalid 携带面向日志的原因。 */
    sealed interface ArtifactCheck {
        record Ok(String id, String version) implements ArtifactCheck {}

        record Invalid(String reason) implements ArtifactCheck {}
    }

    /**
     * 校验下载包：zip 可打开、能定位 plugin.xml、{@code <id>} 与 [UpdateSource.PLUGIN_ID] 一致、
     * {@code <version>} 非空。plugin.xml 查找顺序：zip 内直属 {@code META-INF/plugin.xml}（解包态布局）
     * → 内层 lib 目录下插件 jar 的 {@code META-INF/plugin.xml}（IPGP buildPlugin 布局）。
     */
    static ArtifactCheck validateArtifact(Path zip) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry direct = zf.getEntry("META-INF/plugin.xml");
            if (direct != null) {
                return checkDescriptorXml(new String(zf.getInputStream(direct).readAllBytes(), StandardCharsets.UTF_8));
            }
            List<ZipEntry> collected = new ArrayList<>();
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".jar") && e.getName().contains("/lib/")) {
                    collected.add(e);
                }
            }
            for (ZipEntry entry : collected) {
                try (ZipInputStream inner = new ZipInputStream(
                        new java.io.BufferedInputStream(zf.getInputStream(entry)))) {
                    ZipEntry next = inner.getNextEntry();
                    int scanned = 0;
                    while (next != null && scanned < MAX_INNER_ENTRIES) {
                        if (next.getName().equalsIgnoreCase("META-INF/plugin.xml")) {
                            return checkDescriptorXml(new String(inner.readAllBytes(), StandardCharsets.UTF_8));
                        }
                        next = inner.getNextEntry();
                        scanned++;
                    }
                }
            }
            return new ArtifactCheck.Invalid("META-INF/plugin.xml not found in artifact (direct or lib/*.jar)");
        } catch (Throwable t) {
            return new ArtifactCheck.Invalid("artifact not readable: " + t.getMessage());
        }
    }

    /** plugin.xml 文本 → id/version 校验（regex 提取，无 XML 依赖）。 */
    private static ArtifactCheck checkDescriptorXml(String xml) {
        String idValue = trimOrNull(extractTag(xml, "id"));
        String versionValue = trimOrNull(extractTag(xml, "version"));
        if (idValue == null) {
            return new ArtifactCheck.Invalid("plugin.xml has no <id>");
        }
        if (!idValue.equals(UpdateSource.PLUGIN_ID)) {
            return new ArtifactCheck.Invalid("plugin.xml id mismatch: expected " + UpdateSource.PLUGIN_ID + ", got " + idValue);
        }
        if (versionValue == null || versionValue.isEmpty()) {
            return new ArtifactCheck.Invalid("plugin.xml has no <version>");
        }
        return new ArtifactCheck.Ok(idValue, versionValue);
    }

    /** 提取首个 {@code <tag>…</tag>} 文本（DOTALL，容忍跨行）。 */
    private static String extractTag(String xml, String tag) {
        Pattern p = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        return s.trim();
    }

    private static final int MAX_INNER_ENTRIES = 2000;

    // ------------------------------------------------------------------
    // 平台反射门面（F0 实证的五入口；探测一次缓存，缺失整体降级）
    // ------------------------------------------------------------------

    /** 五入口齐备才可用；任一缺失 = 平台签名漂移/裁剪发行版 → 整体回退脚本路径。 */
    static final class PlatformApi {

        private final Object dynamicPlugins;
        private final Method allowLoadUnloadMethod;
        private final Method unloadPluginMethod;
        /** 双参 unloadPlugin 形态时的默认 UnloadPluginOptions 实例（单参形态为 null）。 */
        private final Object unloadPluginOptions;
        private final Method installAndLoadMethod;
        private final Method loadFromArtifactMethod;
        private final Method findPluginMethod;

        PlatformApi(
            Object dynamicPlugins,
            Method allowLoadUnloadMethod,
            Method unloadPluginMethod,
            Object unloadPluginOptions,
            Method installAndLoadMethod,
            Method loadFromArtifactMethod,
            Method findPluginMethod
        ) {
            this.dynamicPlugins = dynamicPlugins;
            this.allowLoadUnloadMethod = allowLoadUnloadMethod;
            this.unloadPluginMethod = unloadPluginMethod;
            this.unloadPluginOptions = unloadPluginOptions;
            this.installAndLoadMethod = installAndLoadMethod;
            this.loadFromArtifactMethod = loadFromArtifactMethod;
            this.findPluginMethod = findPluginMethod;
        }

        /** 当前已装插件描述（未安装/描述缺失返回 null）。 */
        Object findPlugin(String pluginId) {
            try {
                return invoke(findPluginMethod, null, PluginId.getId(pluginId));
            } catch (Throwable t) {
                LOG.warn("findPlugin reflective call failed: " + t.getMessage());
                return null;
            }
        }

        /** 卸载可行性预检（平台否决返回 false）。 */
        boolean allowLoadUnload(Object currentDescriptor) {
            try {
                return (Boolean) invoke(allowLoadUnloadMethod, dynamicPlugins, currentDescriptor);
            } catch (Throwable t) {
                LOG.warn("allowLoadUnloadWithoutRestart reflective call failed: " + t.getMessage());
                return false;
            }
        }

        /** 从发行 zip 读新版描述（失败返回 null——不阻断流程，装载仍可进行）。 */
        Object loadDescriptorFromArtifact(Path zip) {
            try {
                return invoke(loadFromArtifactMethod, null, zip, null);
            } catch (Throwable t) {
                LOG.warn("loadDescriptorFromArtifact reflective call failed: " + t.getMessage());
                return null;
            }
        }

        /**
         * EDT 串行：卸载旧版 → 解压并动态装载新版。返回 (旧版是否卸载成功, 新版是否装载成功)。
         * 卸载失败则不尝试装载（插件保持运行原版）；卸载成功而装载失败由调用方回退脚本路径修复。
         */
        UnloadInstallResult unloadAndInstallOnEdt(Object currentDescriptor, Path zip, Object newDescriptor) {
            boolean[] unloaded = {false};
            boolean[] installed = {false};
            ApplicationManager.getApplication().invokeAndWait(() -> {
                try {
                    Object[] args = unloadPluginOptions != null
                        ? new Object[]{currentDescriptor, unloadPluginOptions}
                        : new Object[]{currentDescriptor};
                    unloaded[0] = (Boolean) invoke(unloadPluginMethod, dynamicPlugins, args);
                } catch (Throwable t) {
                    LOG.warn("unloadPlugin reflective call failed: " + t.getMessage());
                    unloaded[0] = false;
                }
                LOG.info("dynamic update: old version unloaded=" + unloaded[0]);
                if (unloaded[0] && newDescriptor != null) {
                    try {
                        installed[0] = (Boolean) invoke(installAndLoadMethod, null, zip, null, newDescriptor);
                    } catch (Throwable t) {
                        LOG.warn("installAndLoadDynamicPlugin reflective call failed: " + t.getMessage());
                        installed[0] = false;
                    }
                    LOG.info("dynamic update: new version installed=" + installed[0]);
                }
            }, ModalityState.any());
            return new UnloadInstallResult(unloaded[0], installed[0]);
        }

        /** 静态/实例双形态统一调用：静态方法 null receiver，实例方法传对象单例。 */
        private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
            if (Modifier.isStatic(method.getModifiers())) {
                return method.invoke(null, args);
            }
            return method.invoke(receiver, args);
        }
    }

    /** 五入口反射探测与缓存（Kotlin internal object → 同包静态工具形态）。 */
    static final class PlatformApis {

        private PlatformApis() {}

        private static volatile PlatformApi cached;
        private static volatile boolean probed = false;

        /** 探测五入口（首次后缓存）；任一缺失返回 null。 */
        static PlatformApi probe() {
            if (probed) return cached;
            synchronized (PlatformApis.class) {
                if (!probed) {
                    try {
                        cached = buildApi();
                    } catch (Throwable t) {
                        LOG.info("dynamic-install platform APIs unavailable: " + t.getMessage());
                        cached = null;
                    }
                    probed = true;
                }
                return cached;
            }
        }

        private static PlatformApi buildApi() throws Exception {
            ClassLoader loader = DynamicInstallSupport.class.getClassLoader();
            Class<?> dpClass = Class.forName("com.intellij.ide.plugins.DynamicPlugins", true, loader);
            Object dynamicPlugins = dpClass.getField("INSTANCE").get(null);
            Class<?> descriptorClass = Class.forName("com.intellij.ide.plugins.IdeaPluginDescriptorImpl", true, loader);
            Class<?> installerClass = Class.forName("com.intellij.ide.plugins.PluginInstaller", true, loader);
            Class<?> descriptorLoaderClass = Class.forName("com.intellij.ide.plugins.PluginDescriptorLoader", true, loader);
            Class<?> coreClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore", true, loader);

            // unloadPlugin 首参类型跨版本漂移：243=IdeaPluginDescriptorImpl、262=其子类
            // PluginMainDescriptor（262 反编译实证）——精确匹配会探测失败回退重启（实测）。
            // 弹性匹配：首参与描述类互为可赋值即可，优先最少参数；双参形态构造 options 无参实例。
            Method unload = resolveMethodByFirstParam(dpClass, "unloadPlugin", descriptorClass);
            if (unload == null) {
                throw new IllegalStateException("unloadPlugin(<descriptor>) not found on " + dpClass.getName());
            }
            Object unloadOptions = null;
            if (unload.getParameterTypes().length >= 2) {
                try {
                    java.lang.reflect.Constructor<?> ctor = unload.getParameterTypes()[1].getDeclaredConstructor();
                    ctor.setAccessible(true);
                    unloadOptions = ctor.newInstance();
                } catch (Throwable t) {
                    throw new IllegalStateException("unloadPlugin options no-arg constructor unavailable: " + t.getMessage());
                }
            }

            // allowLoadUnload 与 unloadPlugin 同样存在首参漂移风险（262 实证），一并弹性匹配；
            // 最少参数优先自动命中 1 参重载（262/243 均为 1/2/3 参并存）
            Method allowLoadUnload = resolveMethodByFirstParam(dpClass, "allowLoadUnloadWithoutRestart", descriptorClass);
            if (allowLoadUnload == null) {
                throw new IllegalStateException("allowLoadUnloadWithoutRestart(<descriptor>) not found on " + dpClass.getName());
            }
            allowLoadUnload.setAccessible(true);

            return new PlatformApi(
                dynamicPlugins,
                allowLoadUnload,
                setAccessible(unload),
                unloadOptions,
                exactMethod(installerClass, "installAndLoadDynamicPlugin", Path.class, JComponent.class, descriptorClass),
                exactMethod(descriptorLoaderClass, "loadDescriptorFromArtifact", Path.class, BuildNumber.class),
                exactMethod(coreClass, "findPlugin", PluginId.class)
            );
        }

        private static Method setAccessible(Method m) {
            m.setAccessible(true);
            return m;
        }

        /** 精确签名解析（Kotlin 本地 method() 助手等价）：静态优先，命中即 setAccessible，缺失抛错。 */
        private static Method exactMethod(Class<?> clazz, String name, Class<?>... params) {
            Method best = null;
            for (Method m : clazz.getMethods()) {
                if (!m.getName().equals(name) || !java.util.Arrays.equals(m.getParameterTypes(), params)) continue;
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                if (best == null || (isStatic && !Modifier.isStatic(best.getModifiers()))) {
                    best = m;
                }
            }
            if (best == null) {
                throw new IllegalStateException(name + "(" + simpleNames(params) + ") not found on " + clazz.getName());
            }
            best.setAccessible(true);
            return best;
        }

        private static String simpleNames(Class<?>[] params) {
            StringBuilder sb = new StringBuilder();
            for (Class<?> p : params) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(p.getSimpleName());
            }
            return sb.toString();
        }
    }

    /**
     * 按方法名 + 首参兼容性解析（纯函数，单测直接覆盖）：首参与 firstParamBase
     * 互为可赋值（任一方向）即命中，优先参数最少者。unloadPlugin 的参数类型在
     * 243/262 间漂移（IdeaPluginDescriptorImpl → PluginMainDescriptor 子类），
     * 精确签名匹配在 262 实测探测失败。
     */
    static Method resolveMethodByFirstParam(Class<?> clazz, String name, Class<?> firstParamBase) {
        Method best = null;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length == 0) continue;
            Class<?> first = m.getParameterTypes()[0];
            boolean compatible = firstParamBase.isAssignableFrom(first) || first.isAssignableFrom(firstParamBase);
            if (!compatible) continue;
            if (best == null || m.getParameterTypes().length < best.getParameterTypes().length) {
                best = m;
            }
        }
        return best;
    }
}
