package cn.song.mybatisplus.visualizer.support;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * JDK 21 中，SecurityManager 存在期间首次使用 ForkJoinPool.commonPool() 时，common pool 会改用
 * InnocuousForkJoinWorkerThread（JDK 内部设计：setContextClassLoader 无条件抛 SecurityException）。
 * IDEA 2024.3 启动早期（vmoptions 的 -Djava.security.manager=CoreBootstrapSecurityManager）恰好
 * 覆盖 common pool 首次初始化窗口 → RegionUrlMapper 等异步加载在 setContextClassLoader 处硬抛，
 * 启动日志刷大量 WARN 堆栈。
 *
 * 通过系统属性 java.util.concurrent.ForkJoinPool.common.threadFactory 指向本工厂（JDK 21 的
 * common pool 构造会用 ClassLoader.getSystemClassLoader() 加载它），强制 common pool 使用普通
 * 线程，绕过 innocuous 限制。仅在 runIde 沙盒启动 classpath 中提供，不进入插件产物。
 */
public final class PlainForkJoinWorkerThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {

    @Override
    public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
        return new ForkJoinWorkerThread(pool) {
        };
    }
}
