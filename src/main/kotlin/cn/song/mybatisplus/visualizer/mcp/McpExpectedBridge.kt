package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * 平台 MCP 错误/项目上下文的**全反射桥**（T-McpDynamicToolset：jar 字节码零
 * `com.intellij.mcpserver.*` 类型引用——平台类一律 Class.forName 字符串运行时解析）。
 *
 * **为什么存在**：McpToolsetSupport 原形态直接引用 McpExpectedError/McpCallInfoKt.projectOrNull，
 * 在 243/251（mcpServer 旧形态 1.0.x 无新 API）被 Marketplace verifier 判未解析类（compatibility
 * problems，20260926 审核拒绝主因）。本桥把两类调用点反射化，签名经 262 mcpserver.jar 字节码
 * 实证（getProjectOrNull(CoroutineContext) / 构造器 (String, JsonObject)，构造器第二参按
 * 「参数两个且首参 String」宽松匹配，规避对 kotlinx JsonObject 类的解析）。
 *
 * **降级语义（两处一致）**：平台类不可达（243/251/无 mcpServer 环境——此时 MCP 功能本就不加载）
 * 或反射失败时，wrapExpected 原样返回 [SqlvExpectedToolException]、currentProjectOrNull 返回
 * null（调用方按「无项目绑定」报 PROJECT_NOT_READY）——不崩 IDE，功能面最坏不可用。
 * 探测结果各缓存一次（成功与失败都缓存，避免每次工具调用重复 Class.forName）。
 */
internal object McpExpectedBridge {

    private val LOG = Logger.getInstance(McpExpectedBridge::class.java)

    private const val EXPECTED_ERROR_FQN = "com.intellij.mcpserver.McpExpectedError"
    private const val CALL_INFO_KT_FQN = "com.intellij.mcpserver.McpCallInfoKt"

    // ---- 平台 McpExpectedError 构造器 ----

    private val expectedErrorCtor = AtomicReference<Constructor<*>?>(null)
    private val expectedErrorResolved = AtomicBoolean(false)

    /**
     * 把自有异常转译为平台 McpExpectedError（平台按「agent 可读错误」处理而非 Internal error）。
     * 转译失败原样返回入参（降级：平台按未预期异常记 Internal error，工具调用本身仍失败收场）。
     */
    fun wrapExpected(e: SqlvExpectedToolException): Throwable {
        val ctor = resolveExpectedErrorCtor() ?: return e
        return runCatching {
            // 第二参 mcpErrorStructureContent 恒 null（与改造前所有 throwExpected 调用点一致）
            @Suppress("UNCHECKED_CAST")
            ctor.newInstance(e.mcpErrorText, null as Any?) as Throwable
        }.onFailure {
            LOG.warn("McpExpectedBridge: 平台 McpExpectedError 构造失败（降级为自有异常）", it)
        }.getOrElse { e }
    }

    private fun resolveExpectedErrorCtor(): Constructor<*>? {
        if (expectedErrorResolved.get()) return expectedErrorCtor.get()
        synchronized(this) {
            if (expectedErrorResolved.get()) return expectedErrorCtor.get()
            val ctor = runCatching {
                Class.forName(EXPECTED_ERROR_FQN).constructors.firstOrNull {
                    it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java
                }
            }.onFailure {
                LOG.warn("McpExpectedBridge: McpExpectedError 不可达（无 mcpServer 新形态环境，预期内）", it)
            }.getOrNull()
            expectedErrorCtor.set(ctor)
            expectedErrorResolved.set(true)
            return ctor
        }
    }

    // ---- McpCallInfoKt.projectOrNull ----

    private val projectOrNullMethod = AtomicReference<Method?>(null)
    private val projectOrNullResolved = AtomicBoolean(false)

    /** 当前调用绑定的项目（平台从 coroutineContext 解析）；不可达/失败返回 null。 */
    fun currentProjectOrNull(context: CoroutineContext): Project? {
        val method = resolveProjectOrNull() ?: return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            method.invoke(null, context) as? Project
        }.onFailure {
            LOG.warn("McpExpectedBridge: projectOrNull 反射调用失败（按无项目处理）", it)
        }.getOrNull()
    }

    private fun resolveProjectOrNull(): Method? {
        if (projectOrNullResolved.get()) return projectOrNullMethod.get()
        synchronized(this) {
            if (projectOrNullResolved.get()) return projectOrNullMethod.get()
            val method = runCatching {
                Class.forName(CALL_INFO_KT_FQN).methods.firstOrNull {
                    it.name == "getProjectOrNull" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == CoroutineContext::class.java
                }
            }.onFailure {
                LOG.warn("McpExpectedBridge: McpCallInfoKt 不可达（无 mcpServer 新形态环境，预期内）", it)
            }.getOrNull()
            projectOrNullMethod.set(method)
            projectOrNullResolved.set(true)
            return method
        }
    }
}
