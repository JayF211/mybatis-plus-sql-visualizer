// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpExpectedBridge.kt
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.coroutines.CoroutineContext;

/**
 * 平台 MCP 错误/项目上下文的<b>全反射桥</b>（T-McpDynamicToolset：jar 字节码零
 * {@code com.intellij.mcpserver.*} 类型引用——平台类一律 Class.forName 字符串运行时解析）。
 *
 * <p><b>为什么存在</b>：McpToolsetSupport 原形态直接引用 McpExpectedError/McpCallInfoKt.projectOrNull，
 * 在 243/251（mcpServer 旧形态 1.0.x 无新 API）被 Marketplace verifier 判未解析类（compatibility
 * problems，20260926 审核拒绝主因）。本桥把两类调用点反射化，签名经 262 mcpserver.jar 字节码
 * 实证（getProjectOrNull(CoroutineContext) / 构造器 (String, JsonObject)，构造器第二参按
 * 「参数两个且首参 String」宽松匹配，规避对 kotlinx JsonObject 类的解析）。</p>
 *
 * <p><b>降级语义（两处一致）</b>：平台类不可达（243/251/无 mcpServer 环境——此时 MCP 功能本就不加载）
 * 或反射失败时，wrapExpected 原样返回 {@link SqlvExpectedToolException}、currentProjectOrNull 返回
 * null（调用方按「无项目绑定」报 PROJECT_NOT_READY）——不崩 IDE，功能面最坏不可用。
 * 探测结果各缓存一次（成功与失败都缓存，避免每次工具调用重复 Class.forName）。</p>
 *
 * <p>移植注记：master 的 {@code internal object} 按本仓惯例落为 final class + {@link #INSTANCE}
 * + 全静态成员，{@code internal} 升 public。</p>
 */
public final class McpExpectedBridge {

    public static final McpExpectedBridge INSTANCE = new McpExpectedBridge();

    private McpExpectedBridge() {
    }

    private static final Logger LOG = Logger.getInstance(McpExpectedBridge.class);

    private static final String EXPECTED_ERROR_FQN = "com.intellij.mcpserver.McpExpectedError";
    private static final String CALL_INFO_KT_FQN = "com.intellij.mcpserver.McpCallInfoKt";

    // ---- 平台 McpExpectedError 构造器 ----

    private static final AtomicReference<Constructor<?>> expectedErrorCtor = new AtomicReference<>();
    private static final AtomicBoolean expectedErrorResolved = new AtomicBoolean(false);

    /**
     * 把自有异常转译为平台 McpExpectedError（平台按「agent 可读错误」处理而非 Internal error）。
     * 转译失败原样返回入参（降级：平台按未预期异常记 Internal error，工具调用本身仍失败收场）。
     */
    public static Throwable wrapExpected(SqlvExpectedToolException e) {
        Constructor<?> ctor = resolveExpectedErrorCtor();
        if (ctor == null) {
            return e;
        }
        try {
            // 第二参 mcpErrorStructureContent 恒 null（与改造前所有 throwExpected 调用点一致）
            return (Throwable) ctor.newInstance(e.getMcpErrorText(), (Object) null);
        } catch (Throwable t) {
            LOG.warn("McpExpectedBridge: 平台 McpExpectedError 构造失败（降级为自有异常）", t);
            return e;
        }
    }

    private static Constructor<?> resolveExpectedErrorCtor() {
        if (expectedErrorResolved.get()) {
            return expectedErrorCtor.get();
        }
        synchronized (McpExpectedBridge.class) {
            if (expectedErrorResolved.get()) {
                return expectedErrorCtor.get();
            }
            Constructor<?> ctor = null;
            try {
                for (Constructor<?> candidate : Class.forName(EXPECTED_ERROR_FQN).getConstructors()) {
                    if (candidate.getParameterTypes().length == 2
                            && candidate.getParameterTypes()[0] == String.class) {
                        ctor = candidate;
                        break;
                    }
                }
            } catch (Throwable t) {
                LOG.warn("McpExpectedBridge: McpExpectedError 不可达（无 mcpServer 新形态环境，预期内）", t);
            }
            expectedErrorCtor.set(ctor);
            expectedErrorResolved.set(true);
            return ctor;
        }
    }

    // ---- McpCallInfoKt.projectOrNull ----

    private static final AtomicReference<Method> projectOrNullMethod = new AtomicReference<>();
    private static final AtomicBoolean projectOrNullResolved = new AtomicBoolean(false);

    /** 当前调用绑定的项目（平台从 coroutineContext 解析）；不可达/失败返回 null。 */
    public static Project currentProjectOrNull(CoroutineContext context) {
        Method method = resolveProjectOrNull();
        if (method == null) {
            return null;
        }
        try {
            Object result = method.invoke(null, context);
            // Kotlin `as? Project` 安全转译语义：非 Project 返回 null 且不告警
            return result instanceof Project ? (Project) result : null;
        } catch (Throwable t) {
            LOG.warn("McpExpectedBridge: projectOrNull 反射调用失败（按无项目处理）", t);
            return null;
        }
    }

    private static Method resolveProjectOrNull() {
        if (projectOrNullResolved.get()) {
            return projectOrNullMethod.get();
        }
        synchronized (McpExpectedBridge.class) {
            if (projectOrNullResolved.get()) {
                return projectOrNullMethod.get();
            }
            Method method = null;
            try {
                for (Method candidate : Class.forName(CALL_INFO_KT_FQN).getMethods()) {
                    if (candidate.getName().equals("getProjectOrNull")
                            && candidate.getParameterTypes().length == 1
                            && candidate.getParameterTypes()[0] == CoroutineContext.class) {
                        method = candidate;
                        break;
                    }
                }
            } catch (Throwable t) {
                LOG.warn("McpExpectedBridge: McpCallInfoKt 不可达（无 mcpServer 新形态环境，预期内）", t);
            }
            projectOrNullMethod.set(method);
            projectOrNullResolved.set(true);
            return method;
        }
    }
}
