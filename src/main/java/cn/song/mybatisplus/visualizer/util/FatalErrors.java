package cn.song.mybatisplus.visualizer.util;

/**
 * JVM 级致命错误判据（T-FatalPassthrough T-FP-01）。
 *
 * <p>本仓 74 处 {@code catch (…: Throwable)} 里，绝大多数是<b>设计意图内的降级</b>（反射探测、平台 API 兼容、
 * 可选能力探测——对齐 {@code runCatching} 的既有惯例），本类不改变这些语义。它只解决一个缺口：
 * <b>捕获面覆盖了 JVM 级致命错误</b>——被吞成一条 warn 之后，用户看到的是「功能没反应」，
 * 真正的故障现场（栈、类加载失败链）只剩一行日志，且流程继续，往往在下游以更不可解的形式再失败。</p>
 *
 * <p>白名单三类（对齐 zMyBatis {@code XmlMapperPreparationEngine.kt:380-392} 的 fatal 放行，第八轮报告 §7.2 Z-21）：
 * <ul>
 *   <li>{@link VirtualMachineError}（含 {@code StackOverflowError} / {@code OutOfMemoryError} / {@code InternalError}）：
 *       JVM 自身无法保证继续正常运转的信号。吞掉后进程处于不确定状态——内存已耗尽时继续分配、
 *       栈已溢出时继续递归，只会把「一次可定位的崩溃」拖成随机形态的连环故障；</li>
 *   <li>{@link LinkageError}（含 {@code NoClassDefFoundError} / {@code ExceptionInInitializerError} /
 *       {@code IncompatibleClassChangeError}）：类加载/链接失败是<b>环境或构建损坏</b>，不是「可选能力缺失」——
 *       后者由 {@code ClassNotFoundException}（普通异常，仍走既有降级）表达。吞掉 {@code NoClassDefFoundError}
 *       等于把「插件/依赖装坏了」伪装成「该功能不支持」，用户无从下手；</li>
 *   <li>{@link ThreadDeath}：{@code Thread.stop()} 遗留的异步终止信号，吞掉会破坏线程终止契约。</li>
 * </ul>
 *
 * <p><b>其余 {@code Error} 子类（如 {@code AssertionError}）刻意不算 fatal</b>：断言失败是编程错误的显式信号，
 * 既有降级路径（测试链路、平台断言包装）需要接住它，与 JDK 惯例一致。</p>
 *
 * <p>用法（形态 A，捕获块首行）：
 * <pre>
 * } catch (Throwable t) {
 *     if (FatalErrors.isFatal(t)) throw t;
 *     LOG.warn("…降级…", t);
 * }
 * </pre>
 * 反射链须先解包：{@code Method.invoke} 把目标异常（含 {@code Error}）包进
 * {@code InvocationTargetException}（普通异常），直接判外层会漏判——见
 * {@code execute/DataSourceSelector} 原生执行链的 {@code cause} 解包形态。
 *
 * <p><b>与错误通道的关系（四通道各司其职，不做统一封装）</b>：本仓错误呈现走四条通道——① 日志
 * （{@code Logger.warn}/{@code error}，排查锚点）；② 平台通知（用户可见的轻提示）；③ 执行链回调
 * （{@code onError} / {@code onErrorRich}，控制台错误面板的结构化位置）；④ {@code com.intellij.errorHandler} EP
 * （{@code feedback/IdeErrorReportSubmitter}，进程级错误的平台上报）。四者职责不同，强行统一风险大于收益
 * （第八轮报告锚点核实结论）。本类只补一层<b>判据</b>：fatal 跨捕获边界时透传，交给平台/进程级通道，
 * 而不是被通道 ① 降级成一行日志。
 *
 * <p><b>防回潮</b>：{@code convention/BannedApiSourceScanTest} 的「catch (…: Throwable) 块必须判 fatal 或登记
 * 豁免」规则扫 src/main 全部 {@code Throwable} 捕获块——新增捕获点要么调用本类，要么在守卫的豁免表里
 * 登记文件 + 站点数 + 理由。
 *
 * <p>master 的 {@code object FatalErrors} 的 Java 承载（全 static）。</p>
 */
public final class FatalErrors {

    private FatalErrors() {
    }

    /** {@code t} 是否 JVM 级致命错误（白名单三类及其子类）。纯函数、无副作用、无平台依赖。 */
    public static boolean isFatal(Throwable t) {
        return t instanceof VirtualMachineError || t instanceof LinkageError || t instanceof ThreadDeath;
    }
}
