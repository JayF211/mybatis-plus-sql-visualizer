package cn.song.mybatisplus.sqlvis.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * connect 织入 transformer（§4.2 #1）：只改方法体（返回前插钩子调用），不增删方法/字段——
 * retransformClasses 合规（agentmain 已加载驱动路径的前提约束）。
 * §4.2 #6 classloader 契约在织入期收口：定义类 loader 解析不到回调类 → 整类不织入
 * （安全降级为无钩子）；可解析 → 注入零分支直线 invokestatic（回调自吞一切 Throwable）。
 */
public class SqlHookTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        // instrument API 传内部名（斜杠）；登记表为点分名——归一化
        final String dotted = className.replace('/', '.');
        if (!DriverRegistry.isWeavable(dotted)) {
            return null;
        }
        try {
            return ConnectWeaver.weave(loader, classfileBuffer);
        } catch (Throwable t) {
            // 织入失败 = 该驱动不增强，绝不影响宿主应用启动/运行
            return null;
        }
    }
}
