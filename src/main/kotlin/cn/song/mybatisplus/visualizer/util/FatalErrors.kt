package cn.song.mybatisplus.visualizer.util

/**
 * JVM 级致命错误判据（T-FatalPassthrough T-FP-01）。
 *
 * 本仓 74 处 `catch (…: Throwable)` 里，绝大多数是**设计意图内的降级**（反射探测、平台 API 兼容、
 * 可选能力探测——对齐 `runCatching` 的既有惯例），本类不改变这些语义。它只解决一个缺口：
 * **捕获面覆盖了 JVM 级致命错误**——被吞成一条 warn 之后，用户看到的是「功能没反应」，
 * 真正的故障现场（栈、类加载失败链）只剩一行日志，且流程继续，往往在下游以更不可解的形式再失败。
 *
 * 白名单三类（对齐 zMyBatis `XmlMapperPreparationEngine.kt:380-392` 的 fatal 放行，第八轮报告 §7.2 Z-21）：
 *  - [VirtualMachineError]（含 `StackOverflowError` / `OutOfMemoryError` / `InternalError`）：JVM
 *    自身无法保证继续正常运转的信号。吞掉后进程处于不确定状态——内存已耗尽时继续分配、
 *    栈已溢出时继续递归，只会把「一次可定位的崩溃」拖成随机形态的连环故障；
 *  - [LinkageError]（含 `NoClassDefFoundError` / `ExceptionInInitializerError` / `IncompatibleClassChangeError`）：
 *    类加载/链接失败是**环境或构建损坏**，不是「可选能力缺失」——后者由 `ClassNotFoundException`
 *    （普通异常，仍走既有降级）表达。吞掉 `NoClassDefFoundError` 等于把「插件/依赖装坏了」
 *    伪装成「该功能不支持」，用户无从下手；
 *  - [ThreadDeath]：`Thread.stop()` 遗留的异步终止信号，吞掉会破坏线程终止契约。
 *
 * **其余 `Error` 子类（如 `AssertionError`）刻意不算 fatal**：断言失败是编程错误的显式信号，
 * 既有降级路径（测试链路、平台断言包装）需要接住它，与 JDK 惯例一致。
 *
 * 用法（形态 A，捕获块首行）：
 * ```
 * } catch (t: Throwable) {
 *     if (FatalErrors.isFatal(t)) throw t
 *     LOG.warn("…降级…", t)
 * }
 * ```
 * 反射链须先解包：`Method.invoke` 把目标异常（含 `Error`）包进
 * `InvocationTargetException`（普通异常），直接判外层会漏判——见
 * `execute/DataSourceSelector.kt` 原生执行链的 `cause` 解包形态。
 */
object FatalErrors {

    /**
     * [t] 是否 JVM 级致命错误（白名单三类及其子类）。纯函数、无副作用、无平台依赖。
     */
    fun isFatal(t: Throwable): Boolean =
        t is VirtualMachineError || t is LinkageError || t is ThreadDeath
}
