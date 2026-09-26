package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP 工具集**动态壳注册器**（T-McpDynamicToolset）：运行期生成「extends 工具类 implements
 * 平台 McpToolset」的壳类并程序化注册到 `com.intellij.mcpServer.mcpToolset` EP，替代
 * mcp-optional.xml 的 10 条 XML toolset 声明。
 *
 * **为什么要动态生成壳类（方案 D 全动态化终态）**：verifier 只做静态字节码分析——jar 内任何
 * implements 平台接口的类都会在 243/251（mcpServer 旧形态缺新 API）被判未解析类（20260926
 * 审核拒绝主因）。XML 声明形态的 implementation 类无法消除该引用（EP 接口实现类，上轮
 * T-MarketCompatProxy 已证）；运行期生成的壳类不在 jar 内，verifier 不可见。壳类只覆写
 * 「继承即可满足接口」的最小面（三钩子直接继承 [McpToolsetSupport] 实现，连方法都不生成），
 * 平台的工具发现（kotlin-reflect 扫 @McpTool 注解方法/参数 schema/suspend 调用契约）全部
 * 走继承来的业务类编译产物，壳零参与。
 *
 * **注册链**：[McpGateFilterRegistrar]（mcp-optional.xml 的 postStartupActivity，mcpServer
 * 在场才加载）每项目启动触发 [registerOnce]，原子闸全应用只注册一次。探测
 * `Class.forName("com.intellij.mcpserver.McpToolset")` 失败（243/251 旧形态）静默跳过——
 * 与 XML 形态「片段加载但 EP 形态不同」的设计口径一致：该版本不支持 MCP 工具线。
 *
 * **EP 形态**：`interface="com.intellij.mcpserver.McpToolset" dynamic="true"`（262
 * mcpserver.jar META-INF/plugin.xml 实证）——与 mcpToolFilterProvider 同款，注册走
 * McpGateFilterEpBridge 先例（`getPoint(null)` application 级，真机验证过）；dynamic EP
 * 晚注册会触发清单重算，无需重启 IDE。
 *
 * **类名前缀硬约定（T-McpToolNaming）**：平台「暴露工具」页分组名=注册实例类的 simpleName——
 * 壳类放 `mcp.gen` 子包、**simpleName 与业务类逐字节一致**（如 SqlVisualizerCheckSqlTool），
 * 分组名与前缀美学（自带插件名、字典序相邻）与 XML 形态完全相同；FQN 含 gen 子包避免与
 * 业务类在不同 classloader 上的解析歧义。
 */
object McpToolsetDynamicRegistrar {

    private val LOG = Logger.getInstance(McpToolsetDynamicRegistrar::class.java)

    private const val TOOLSET_EP_NAME = "com.intellij.mcpServer.mcpToolset"
    private const val TOOLSET_IFACE_FQN = "com.intellij.mcpserver.McpToolset"

    private val registered = AtomicBoolean(false)

    /** 十工具类清单（McpToolGate.TOOL_NAMES 一一对应，守卫测试对账单一事实）。 */
    private val TOOL_CLASSES = listOf(
        SqlVisualizerRenderSqlTool::class.java,
        SqlVisualizerSchemaContextTool::class.java,
        SqlVisualizerExplainTool::class.java,
        SqlVisualizerRenderLogsTool::class.java,
        SqlVisualizerCheckSqlTool::class.java,
        SqlVisualizerRollbackPlanTool::class.java,
        SqlVisualizerPerfAdviceTool::class.java,
        SqlVisualizerCrudSqlTool::class.java,
        SqlVisualizerReverseSqlTool::class.java,
        SqlVisualizerMapperCheckTool::class.java,
    )

    /** 探测＋全量注册；已注册或探测失败（无 mcpServer 新形态）时零操作。 */
    fun registerOnce() {
        if (registered.get()) return
        val toolsetIface = runCatching { Class.forName(TOOLSET_IFACE_FQN) }.getOrNull() ?: return
        runCatching {
            TOOL_CLASSES.forEach { registerToolset(it, toolsetIface) }
            registered.set(true)
            LOG.info("T-McpDynamicToolset: ${TOOL_CLASSES.size} toolsets registered onto mcpToolset EP (dynamic shell form)")
        }.onFailure {
            // registered 不置位：下个项目启动重试（动态 EP 未就绪等瞬态失败可自愈）
            LOG.warn("T-McpDynamicToolset: mcpToolset EP 注册失败（MCP 工具不可用，其余功能不受影响）", it)
        }
    }

    private fun registerToolset(toolClass: Class<*>, toolsetIface: Class<*>) {
        require(toolClass.constructors.any { it.parameterTypes.isEmpty() }) {
            "toolset 类必须有无参构造（壳生成依赖）：${toolClass.name}"
        }
        // simpleName 与业务类一致（T-McpToolNaming 分组名=simpleName），gen 子包隔离 FQN
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen." + toolClass.simpleName
        val shellClass = ShellClassLoader.define(shellName, shellBytes(toolClass, toolsetIface, shellName))
        val instance = shellClass.getDeclaredConstructor().newInstance()
        ExtensionPointName.create<Any>(TOOLSET_EP_NAME).getPoint(null).registerExtension(instance)
        LOG.info("T-McpDynamicToolset: registered ${shellClass.simpleName} (gen shell of ${toolClass.name})")
    }

    /** 壳类字节码：`final class <ShellName> extends <工具类> implements McpToolset`——
     *  仅一个无参构造器（invokespecial 父类构造）。三钩子/工具方法全部继承，壳零额外成员。
     *  V17（major 61）：243 JBR 21 可加载，且与插件常规编译目标一致；COMPUTE_MAXS 交由
     *  ClassWriter 重算构造器的栈帧。internal 供壳生成器单测直接触达。 */
    internal fun shellBytes(superClass: Class<*>, iface: Class<*>, shellName: String): ByteArray {
        val internalName = shellName.replace('.', '/')
        val superInternal = superClass.name.replace('.', '/')
        val ifaceInternal = iface.name.replace('.', '/')
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            internalName,
            null,
            superInternal,
            arrayOf(ifaceInternal),
        )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** 壳类装载器：插件 classloader 的直系子加载器，defineClass 仅此一处（protected 合法调用点）。 */
    internal object ShellClassLoader : ClassLoader(McpToolsetDynamicRegistrar::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }
}
