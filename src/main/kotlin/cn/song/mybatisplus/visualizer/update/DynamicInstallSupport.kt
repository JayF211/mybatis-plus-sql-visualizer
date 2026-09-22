package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.BuildNumber
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.swing.JComponent

/**
 * 动态安装门面（T-DynUpdate）：下载包先尝试平台动态热替换——卸载旧版 → 解压新版 → 动态装载，
 * 任一步不满足或失败都回退 [PlatformPluginInstaller.installRestartScript] 启动脚本路径
 * （重启生效，M28 原行为），全程无中间死态。
 *
 * 平台 API 全走反射探测（F0 javap 243 + 反编译 262 双端实证，签名与语义一致）：
 * - `PluginDescriptorLoader.loadDescriptorFromArtifact(Path, BuildNumber)`：从发行 zip 读新版描述（两平台 public）；
 * - `DynamicPlugins.allowLoadUnloadWithoutRestart(descriptor)`：卸载可行性预检——EP dynamic 属性
 *   （本插件 9/9 合规）、`ide.dynamicPluginVetoer`、依赖插件图（两平台 public）；
 * - `DynamicPlugins.unloadPlugin(descriptor)`：运行期卸载旧版（两平台 public，内部走 EDT write action）；
 * - `PluginInstaller.installAndLoadDynamicPlugin(Path, JComponent?, descriptor)`：解包 + 动态装载新版
 *   （两平台 public；双端反编译实证 descriptor 参数仅用于进度标题/日志/fireState，解包布局由 zip
 *   顶层目录名决定，传新版描述即可）；
 * - `PluginManagerCore.findPlugin(PluginId)`：取当前已装描述（两平台 public）。
 *
 * installFromDisk 复用路线 F0 否决：243 包私有 + `File` 参数 + 6 参，262 public + `Path` + 7 参，
 * 且两条平台流程都不卸载已加载旧版（`installAndLoadDynamicPlugin` 在旧版仍加载时直接返回 false），
 * 热更新必失败——故手工编排 unload → install 序列。
 *
 * 自更新的特殊处境：[install] 的调用栈运行在将被卸载的旧 classloader 上——卸载/装载回调内只触达
 * 平台 API 与纯函数，返回后由调用方（UI 层）经平台通知报告结果，不再触达自身服务。
 *
 * 派发模态（T-DynUpdateWriteSafe，2026-09-22）：卸载/装载一律以 [dispatchModalityForUnload] 解析出的
 * 写安全模态派发，禁用 `ModalityState.any()`——平台卸载会压入 `Unloading of <plugin>` ad-hoc 模态，
 * 其写安全资格取「派发时刻」快照，`any()` 下恒 false，卸载窗口内平台自身的模型改动会记
 * `Write-unsafe context!`（真机日志实锤）。根因链见 [dispatchModalityForUnload] KDoc。
 */
object DynamicInstallSupport {

    private val LOG = Logger.getInstance(DynamicInstallSupport::class.java)

    /**
     * 安装入口：zip 前置校验 → 开发态守卫 → 平台能力探测 → 卸载预检 → EDT 卸载旧版 + 装载新版；
     * 任何失败回退启动脚本路径。全链 runCatching 兜底——崩溃也不劣化 M28 行为。
     */
    fun install(zip: Path): PluginUpdateRunner.InstallResult = runCatching { doInstall(zip) }.getOrElse { error ->
        LOG.warn("dynamic install crashed — falling back to restart script: ${error.message}", error)
        fallbackToRestartScript(zip, "dynamic install crashed: ${error.message}")
    }

    private fun doInstall(zip: Path): PluginUpdateRunner.InstallResult {
        // 1. zip 前置校验（纯函数）：防半截包/错包进入热替换（快速失败，不触平台）
        val artifact = validateArtifact(zip)
        if (artifact is ArtifactCheck.Invalid) {
            return fallbackToRestartScript(zip, "artifact check failed: ${artifact.reason}")
        }
        val version = (artifact as ArtifactCheck.Ok).version

        // 2. 开发态守卫（runIde/测试从 build/classes 加载，卸载/替换会误删构建产物）
        PlatformPluginInstaller.devGuardOrFailure()?.let { return it }

        // 3. 平台能力探测：五个反射入口任一缺失（未来版本签名漂移/裁剪发行版）即回退
        val api = PlatformApis.probe()
        if (api == null) {
            return fallbackToRestartScript(zip, "platform dynamic-install APIs unavailable")
        }

        // 4. 当前描述缺失（未安装/已禁用态）→ 无法 unload 旧版，走脚本路径原样覆盖
        val current = api.findPlugin(UpdateSource.PLUGIN_ID)
        if (current == null) {
            return fallbackToRestartScript(zip, "current plugin descriptor not found")
        }

        // 5. 卸载可行性预检：本插件 EP 已全 dynamic（9/9 实证），此处主要防 vetoer/依赖图否决
        if (!api.allowLoadUnload(current)) {
            return fallbackToRestartScript(zip, "dynamic unload rejected by platform (vetoer/EP/dependency)")
        }

        // 6. 新版描述（仅作 installAndLoadDynamicPlugin 的日志/状态广播参数，见类 KDoc）
        val newDescriptor = api.loadDescriptorFromArtifact(zip)

        // 7. EDT 串行执行：卸载旧版 → 解压并动态装载新版
        val (unloaded, installed) = api.unloadAndInstallOnEdt(current, zip, newDescriptor)
        return if (installed) {
            LOG.info("dynamic install succeeded — plugin hot-reloaded to $version (zip=$zip)")
            PluginUpdateRunner.InstallResult.InstalledDynamic(version)
        } else {
            val reason = if (unloaded) "old version unloaded but dynamic load failed" else "dynamic unload failed"
            fallbackToRestartScript(zip, reason)
        }
    }

    /** 兜底：调度启动脚本替换（M28 路径），失败原因记日志供 idea.log 排查。 */
    private fun fallbackToRestartScript(zip: Path, reason: String): PluginUpdateRunner.InstallResult {
        LOG.info("dynamic install not applicable — falling back to restart script: $reason")
        return PlatformPluginInstaller.installRestartScript(zip)
    }

    // ------------------------------------------------------------------
    // zip 前置校验（纯 java.util.zip，单测直接覆盖）
    // ------------------------------------------------------------------

    /** 校验结果：[Ok] 携带从 plugin.xml 读出的 id/version；[Invalid] 携带面向日志的原因。 */
    internal sealed interface ArtifactCheck {
        data class Ok(val id: String, val version: String) : ArtifactCheck
        data class Invalid(val reason: String) : ArtifactCheck
    }

    /**
     * 校验下载包：zip 可打开、能定位 plugin.xml、`<id>` 与 [UpdateSource.PLUGIN_ID] 一致、
     * `<version>` 非空。plugin.xml 查找顺序：zip 内直属 `META-INF/plugin.xml`（解包态布局）
     * → 内层 lib 目录下插件 jar 的 `META-INF/plugin.xml`（IPGP buildPlugin 布局）。
     *
     * T-UpdateZipBounds：plugin.xml 读取有界（[MAX_DESCRIPTOR_XML_BYTES]，实测 KB 级 ×100
     * 余量）——谎报头的巨量条目不进字符串。
     */
    internal fun validateArtifact(zip: Path): ArtifactCheck = runCatching {
        ZipFile(zip.toFile()).use { zf ->
            val direct = zf.getEntry("META-INF/plugin.xml")
            if (direct != null) {
                return@runCatching checkDescriptorXml(readDescriptorBounded(zf.getInputStream(direct)))
            }
            val libJars = zf.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".jar") && it.name.contains("/lib/") }
                .toList()
            for (entry in libJars) {
                ZipInputStream(zf.getInputStream(entry).buffered()).use { inner ->
                    var next = inner.nextEntry
                    var scanned = 0
                    while (next != null && scanned < MAX_INNER_ENTRIES) {
                        if (next.name.equals("META-INF/plugin.xml", ignoreCase = true)) {
                            return@runCatching checkDescriptorXml(readDescriptorBounded(inner))
                        }
                        next = inner.nextEntry
                        scanned++
                    }
                }
            }
            ArtifactCheck.Invalid("META-INF/plugin.xml not found in artifact (direct or lib/*.jar)")
        }
    }.getOrElse { ArtifactCheck.Invalid("artifact not readable: ${it.message}") }

    /** plugin.xml 有界读取（超限判 Invalid——KB 级文件读出 MB 级即异常包）。 */
    private fun readDescriptorBounded(stream: java.io.InputStream): String {
        val buf = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var read = 0L
        stream.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                read += n
                if (read > MAX_DESCRIPTOR_XML_BYTES) error("plugin.xml implausibly large (> $MAX_DESCRIPTOR_XML_BYTES bytes)")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray().decodeToString()
    }

    /** plugin.xml 文本 → id/version 校验（regex 提取，无 XML 依赖）。 */
    private fun checkDescriptorXml(xml: String): ArtifactCheck {
        val idValue = extractTag(xml, "id")?.trim()
        val versionValue = extractTag(xml, "version")?.trim()
        return when {
            idValue == null -> ArtifactCheck.Invalid("plugin.xml has no <id>")
            idValue != UpdateSource.PLUGIN_ID ->
                ArtifactCheck.Invalid("plugin.xml id mismatch: expected ${UpdateSource.PLUGIN_ID}, got $idValue")
            versionValue.isNullOrEmpty() -> ArtifactCheck.Invalid("plugin.xml has no <version>")
            else -> ArtifactCheck.Ok(idValue, versionValue)
        }
    }

    /** 提取首个 `<tag>…</tag>` 文本（DOTALL，容忍跨行）。 */
    private fun extractTag(xml: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)

    private const val MAX_INNER_ENTRIES = 2000

    /** plugin.xml 读取上限（实测 KB 级，×100 余量；超限即异常包）。 */
    internal const val MAX_DESCRIPTOR_XML_BYTES = 1024 * 1024

    // ------------------------------------------------------------------
    // 平台反射门面（F0 实证的五入口；探测一次缓存，缺失整体降级）
    // ------------------------------------------------------------------

    /** 五入口齐备才可用；任一缺失 = 平台签名漂移/裁剪发行版 → 整体回退脚本路径。 */
    internal class PlatformApi internal constructor(
        private val dynamicPlugins: Any,
        private val allowLoadUnloadMethod: Method,
        private val unloadPluginMethod: Method,
        /** 双参 unloadPlugin 形态时的默认 UnloadPluginOptions 实例（单参形态为 null）。 */
        private val unloadPluginOptions: Any?,
        private val installAndLoadMethod: Method,
        private val loadFromArtifactMethod: Method,
        private val findPluginMethod: Method,
    ) {
        /** 当前已装插件描述（未安装/描述缺失返回 null）。 */
        fun findPlugin(pluginId: String): Any? = runCatching {
            invoke(findPluginMethod, null, PluginId.getId(pluginId))
        }.getOrElse {
            LOG.warn("findPlugin reflective call failed: ${it.message}")
            null
        }

        /** 卸载可行性预检（平台否决返回 false）。 */
        fun allowLoadUnload(currentDescriptor: Any): Boolean = runCatching {
            invoke(allowLoadUnloadMethod, dynamicPlugins, currentDescriptor) as Boolean
        }.getOrElse {
            LOG.warn("allowLoadUnloadWithoutRestart reflective call failed: ${it.message}")
            false
        }

        /** 从发行 zip 读新版描述（失败返回 null——不阻断流程，装载仍可进行）。 */
        fun loadDescriptorFromArtifact(zip: Path): Any? = runCatching {
            invoke(loadFromArtifactMethod, null, zip, null as BuildNumber?)
        }.getOrElse {
            LOG.warn("loadDescriptorFromArtifact reflective call failed: ${it.message}")
            null
        }

        /**
         * EDT 串行：卸载旧版 → 解压并动态装载新版。返回 (旧版是否卸载成功, 新版是否装载成功)。
         * 卸载失败则不尝试装载（插件保持运行原版）；卸载成功而装载失败由调用方回退脚本路径修复。
         *
         * T-DynUpdateWriteSafe：派发模态走 [dispatchModalityForUnload]（写安全），不再用 `any()`
         * ——平台卸载会在该模态下压入 `Unloading of <plugin>` 实体，窗口内平台自身的 commit/落盘/
         * dispose 都按这个模态的写安全资格判定（根因与源码链见 [dispatchModalityForUnload] KDoc）。
         */
        fun unloadAndInstallOnEdt(currentDescriptor: Any, zip: Path, newDescriptor: Any?): Pair<Boolean, Boolean> {
            var unloaded = false
            var installed = false
            val modality = dispatchModalityForUnload()
            LOG.info(
                "dynamic update: dispatch modality=$modality writeSafe=${isWriteSafeDispatch(modality)}" +
                    " (T-DynUpdateWriteSafe)",
            )
            edtDispatcher(modality) {
                unloaded = runCatching {
                    val args = if (unloadPluginOptions != null) {
                        arrayOf(currentDescriptor, unloadPluginOptions)
                    } else {
                        arrayOf(currentDescriptor)
                    }
                    invoke(unloadPluginMethod, dynamicPlugins, *args) as Boolean
                }.getOrElse {
                    LOG.warn("unloadPlugin reflective call failed: ${it.message}")
                    false
                }
                LOG.info("dynamic update: old version unloaded=$unloaded")
                if (unloaded && newDescriptor != null) {
                    installed = runCatching {
                        invoke(installAndLoadMethod, null, zip, null as JComponent?, newDescriptor) as Boolean
                    }.getOrElse {
                        LOG.warn("installAndLoadDynamicPlugin reflective call failed: ${it.message}")
                        false
                    }
                    LOG.info("dynamic update: new version installed=$installed")
                }
            }
            return unloaded to installed
        }

        /** @JvmStatic 双形态统一调用：静态方法 null receiver，实例方法传对象单例。 */
        private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? =
            if (java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                method.invoke(null, *args)
            } else {
                method.invoke(receiver, *args)
            }
    }

    // ------------------------------------------------------------------
    // 卸载/装载的派发模态（T-DynUpdateWriteSafe）
    // ------------------------------------------------------------------

    /**
     * 卸载/装载派发模态：在 EDT 上读**当前模态实例**并交给 [chooseDispatchModality] 决策。
     *
     * 为什么不能再用 `ModalityState.any()`（T-DynUpdateWriteSafe 根因，262 源码实证）：
     * 写模型/落盘/PSI commit 的合法性由「这次 EDT 活动是否 write-safe」决定，判定读的是
     * `TransactionGuardImpl.myWriteSafeModalities[模态实例]`，该表在**模态进入时快照**
     * `myWritingAllowed`（`LaterInvocator.enterModal:204-207` → `enteredModality:199-201`）。
     * 平台卸载会压入一个 ad-hoc 模态（`DynamicPluginsLegacyImpl:509` 的
     * `inModalContext(sentinel("Unloading of <plugin>"))`），其资格就是**派发时刻**的快照；
     * `any()` 排出的是 posted runnable，一律走 `performActivity(false, …)`
     * （`IdeEventQueue:1106-1122` 只把输入/Item/Focus 事件算用户活动），`wrapLaterInvocation`
     * 对非写安全模态也不套 `runWithWritingAllowed`（`TransactionGuardImpl:203-220`）→ 快照恒 false
     * → 卸载窗口内平台自身的 commit/落盘/dispose 全部记 `Write-unsafe context!`（真机日志模态串
     * `[设置对话框, Unloading of SQL Visualizer]` 即此形态）。
     *
     * `ModalityState.current()` 是 `@RequiresEdt`，而本函数从后台任务线程调用，故先用一次只读跳板
     * 把它取回 EDT（回调体只读模态，无任何模型改动）。
     */
    internal fun dispatchModalityForUnload(): ModalityState {
        val app = ApplicationManager.getApplication() ?: return ModalityState.nonModal()
        val captured = AtomicReference<ModalityState?>()
        // write-safe: UI-only（只读当前模态，无文档/模型写；any() 保证模态弹窗存续期跳板照常执行）
        runCatching { app.invokeAndWait({ captured.set(ModalityState.current()) }, ModalityState.any()) }
        return chooseDispatchModality(captured.get())
    }

    /**
     * 派发模态决策（纯函数，单测直覆盖）：写安全 → 原样用；取不到或非写安全 → `nonModal`。
     *
     * 取「当前模态实例」而非别的形态的理由：`ModalityStateEx.accepts` 的 trivial 分支
     * （`requested == this`）保证它**立即执行**，不会被模态弹窗推迟——设置页点安装时正是这个场景。
     * 非写安全时退 `nonModal` 而不是硬上：`nonModal` 恒 write-safe，是平台文档（
     * `TransactionGuard` 类注释「When in doubt, use ModalityState#nonModal()」）与仓内
     * `DocWriteContext` 的既有口径（延迟到弹窗关闭是正确语义，非退化）。
     */
    internal fun chooseDispatchModality(captured: ModalityState?): ModalityState =
        if (captured != null && isWriteSafeDispatch(captured)) captured else ModalityState.nonModal()

    /** 模态写安全判定（平台谓词直读，供 [chooseDispatchModality] 与派发日志取证；异常即 false）。 */
    internal fun isWriteSafeDispatch(modality: ModalityState): Boolean =
        runCatching { TransactionGuard.getInstance().isWriteSafeModality(modality) }.getOrDefault(false)

    /** 测试缝：EDT 派发器（默认平台 `invokeAndWait`；测试注入以观察实际派发模态）。 */
    internal var edtDispatcher: (ModalityState, Runnable) -> Unit = { modality, body ->
        ApplicationManager.getApplication().invokeAndWait(body, modality)
    }

    internal object PlatformApis {

        @Volatile
        private var cached: PlatformApi? = null

        @Volatile
        private var probed = false

        /** 探测五入口（首次后缓存）；任一缺失返回 null。 */
        fun probe(): PlatformApi? {
            if (probed) return cached
            synchronized(this) {
                if (!probed) {
                    cached = runCatching { buildApi() }.onFailure {
                        LOG.info("dynamic-install platform APIs unavailable: ${it.message}")
                    }.getOrNull()
                    probed = true
                }
                return cached
            }
        }

        private fun buildApi(): PlatformApi {
            val loader = DynamicInstallSupport::class.java.classLoader
            val dpClass = Class.forName("com.intellij.ide.plugins.DynamicPlugins", true, loader)
            val dynamicPlugins = dpClass.getField("INSTANCE").get(null)
            val descriptorClass = Class.forName("com.intellij.ide.plugins.IdeaPluginDescriptorImpl", true, loader)
            val installerClass = Class.forName("com.intellij.ide.plugins.PluginInstaller", true, loader)
            val descriptorLoaderClass = Class.forName("com.intellij.ide.plugins.PluginDescriptorLoader", true, loader)
            val coreClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore", true, loader)

            fun method(clazz: Class<*>, name: String, vararg params: Class<*>): Method =
                clazz.methods.filter { it.name == name && it.parameterTypes.contentEquals(params) }
                    .minByOrNull { if (java.lang.reflect.Modifier.isStatic(it.modifiers)) 0 else 1 }
                    ?.also { it.isAccessible = true }
                    ?: error("$name(${params.joinToString { it.simpleName }}) not found on ${clazz.name}")

            // unloadPlugin 首参类型跨版本漂移：243=IdeaPluginDescriptorImpl、262=其子类
            // PluginMainDescriptor（262 反编译实证）——精确匹配会探测失败回退重启（实测）。
            // 弹性匹配：首参与描述类互为可赋值即可，优先最少参数；双参形态构造 options 无参实例。
            val unload = resolveMethodByFirstParam(dpClass, "unloadPlugin", descriptorClass)
                ?: error("unloadPlugin(<descriptor>) not found on ${dpClass.name}")
            val unloadOptions = if (unload.parameterTypes.size >= 2) {
                runCatching {
                    unload.parameterTypes[1].getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                }.getOrElse { error("unloadPlugin options no-arg constructor unavailable: ${it.message}") }
            } else {
                null
            }

            return PlatformApi(
                dynamicPlugins = dynamicPlugins,
                // allowLoadUnload 与 unloadPlugin 同样存在首参漂移风险（262 实证），一并弹性匹配；
                // 最少参数优先自动命中 1 参重载（262/243 均为 1/2/3 参并存）
                allowLoadUnloadMethod = resolveMethodByFirstParam(dpClass, "allowLoadUnloadWithoutRestart", descriptorClass)
                    ?.also { it.isAccessible = true }
                    ?: error("allowLoadUnloadWithoutRestart(<descriptor>) not found on ${dpClass.name}"),
                unloadPluginMethod = unload.also { it.isAccessible = true },
                unloadPluginOptions = unloadOptions,
                installAndLoadMethod = method(
                    installerClass,
                    "installAndLoadDynamicPlugin",
                    Path::class.java,
                    JComponent::class.java,
                    descriptorClass,
                ),
                loadFromArtifactMethod = method(
                    descriptorLoaderClass,
                    "loadDescriptorFromArtifact",
                    Path::class.java,
                    BuildNumber::class.java,
                ),
                findPluginMethod = method(coreClass, "findPlugin", PluginId::class.java),
            )
        }
    }

    /**
     * 按方法名 + 首参兼容性解析（纯函数，单测直接覆盖）：首参与 [firstParamBase]
     * 互为可赋值（任一方向）即命中，优先参数最少者。unloadPlugin 的参数类型在
     * 243/262 间漂移（IdeaPluginDescriptorImpl → PluginMainDescriptor 子类），
     * 精确签名匹配在 262 实测探测失败。
     */
    internal fun resolveMethodByFirstParam(clazz: Class<*>, name: String, firstParamBase: Class<*>): Method? =
        clazz.methods
            .filter {
                it.name == name && it.parameterTypes.isNotEmpty() &&
                    (firstParamBase.isAssignableFrom(it.parameterTypes[0]) ||
                        it.parameterTypes[0].isAssignableFrom(firstParamBase))
            }
            .minByOrNull { it.parameterTypes.size }
}
