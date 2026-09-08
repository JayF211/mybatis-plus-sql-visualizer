package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.Topic;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * 运行配置进程启动监听（20260907-2045 第二轮修复；22:53 会话修正反射细节）——
 * 「项目启动时自动开始监听」的进程级入口。
 *
 * 根因（沙盒日志 20:41 会话实证）：用户手动停止后 suppressed=true 持久化 + 桥轮询/contentListener
 * 全部拆除；此后再 Run 项目时 LogStartupCapture（postStartupActivity 只跑一次）、
 * SqlLogManager.onConsoleAppeared（suppressed && !userArmed 守卫拦截）、设置页边沿（设置未再变更）
 * 三条路径全部失效——自动监听永久沉没。项目真正启动是用户对「自动监听」的再次确认
 * （对齐踩坑第十一轮「设置优先」），须在此清 suppressed 并重启捕捉。
 *
 * 平台兼容：
 *  - ExecutionManager.EXECUTION_TOPIC / ExecutionListener 在 243 位于 app-client.jar
 *    （execution API 域）；插件 classloader 是平台 classloader 的子类，双亲委派下
 *    Class.forName 需显式传 platformClassLoader（pitfall-classloader-forname-execution-api）；
 *  - subscribe 必须走编译期 MessageBusConnection.subscribe(Topic, L) 直调——22:53 会话实证
 *    connection.javaClass.methods.first { name=="subscribe" } 拿到的是泛型擦除后的
 *    SimpleMessageBusConnection.subscribe(Topic, Object) 之外的某个桥方法序，invoke 抛
 *    IllegalArgumentException: argument type mismatch（Topic 类型形参与 Object 不匹配）。
 *    MessageBusConnection/SimpleMessageBusConnection 是 util.jar 全平台稳定接口，
 *    可编译期直引，无需反射；只有 execution 域（可能被 IPGP 裁剪/移位）才反射。
 *
 * 回调分工：processStarted → SqlLogManager.onProjectProcessStarted（清 suppressed + startCapture 幂等）；
 * processTerminated 不在此处理——项目停止语义由 SqlLogManager.onConsoleRemoved 承担，
 * 此处若停会对所有 RunProfile 触发（HTTP 请求/profiler 均是进程，踩坑第九轮 v2 误停教训）。
 *
 * 生命周期：project.messageBus.connect(manager)（manager 是 project 服务 Disposable），
 * 项目关闭自动断开。
 */
public final class RunConfigurationProcessListener {

    private static final Logger LOG = Logger.getInstance(RunConfigurationProcessListener.class);

    /** 平台 classloader（ExecutionListener 等平台 API 所在；插件 classloader 是其子类）。 */
    private static final ClassLoader PLATFORM_CLASS_LOADER = Project.class.getClassLoader();

    private RunConfigurationProcessListener() {
    }

    /**
     * 订阅项目级 EXECUTION_TOPIC；成功返回 true，execution API 缺失返回 false（降级）。
     * handler 只处理 processStarted（243：两参环境版/三参 ProcessHandler 版均在，
     * 任意重载触发一次即幂等），其余方法忽略。
     */
    public static boolean subscribe(Project project, SqlLogManager manager) {
        Class<?> listenerClass;
        try {
            listenerClass = Class.forName("com.intellij.execution.ExecutionListener", true, PLATFORM_CLASS_LOADER);
        } catch (Throwable t) {
            LOG.warn("RunConfigurationProcessListener: ExecutionListener 不可用——降级为 RCM 轮询兜底（project=" + project.getName() + "）", t);
            return false;
        }
        Object topic;
        try {
            Class<?> executionManagerClass =
                    Class.forName("com.intellij.execution.ExecutionManager", true, PLATFORM_CLASS_LOADER);
            topic = executionManagerClass.getField("EXECUTION_TOPIC").get(null);
        } catch (Throwable t) {
            LOG.warn("RunConfigurationProcessListener: EXECUTION_TOPIC 不可用——降级为 RCM 轮询兜底（project=" + project.getName() + "）", t);
            return false;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("processStarted")) {
                try {
                    manager.onProjectProcessStarted();
                } catch (Throwable t) {
                    LOG.warn("processStarted: 自动捕捉回调失败（project=" + project.getName() + "）", t);
                }
            }
            return null;
        };
        Object listener = Proxy.newProxyInstance(PLATFORM_CLASS_LOADER, new Class<?>[]{listenerClass}, handler);
        try {
            // 编译期直调 MessageBusConnection.subscribe(Topic, L)——243/253/262 全平台稳定接口
            @SuppressWarnings("unchecked")
            Topic<Object> typedTopic = (Topic<Object>) topic;
            MessageBusConnection connection = project.getMessageBus().connect(manager);
            connection.subscribe(typedTopic, listener);
            LOG.info("RunConfigurationProcessListener: 已订阅 EXECUTION_TOPIC（project=" + project.getName() + "）");
            return true;
        } catch (Throwable t) {
            LOG.warn("RunConfigurationProcessListener: messageBus 订阅失败——降级为 RCM 轮询兜底（project=" + project.getName() + "）", t);
            return false;
        }
    }
}
