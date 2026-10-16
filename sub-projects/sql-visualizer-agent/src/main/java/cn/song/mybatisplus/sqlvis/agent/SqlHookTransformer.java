package cn.song.mybatisplus.sqlvis.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 织入 transformer（§4.2 #1）：只改方法体（钩子调用插桩），不增删方法/字段——
 * retransformClasses 合规（agentmain 已加载驱动路径的前提约束）。
 * §4.2 #6 classloader 契约在织入期收口：定义类 loader 解析不到回调类 → 整类不织入
 * （安全降级为无钩子）；可解析 → 注入零分支直线 invokestatic（回调自吞一切 Throwable）。
 *
 * T-StatementWeave：三面路由——驱动 connect 面（ConnectWeaver，原有）／语句 execute 面／
 * prepare 登记面（StatementWeaver）。登记表三面不相交，同一类只落一面。
 */
public class SqlHookTransformer implements ClassFileTransformer {

    // ---------- attach 取证计数（T-AttachZeroEvent，2026-10-09） ----------
    //
    // 现场复盘：用户对运行中应用 attach 后「面板零新条目」，而 transform 层两种静默形态
    // （织入异常吞掉返回 null / 名单命中但织入器产出原字节）此前**零观测出口**——retransform
    // 「成功」与「真的织上了」无法区分，排障只能靠行为反推。四个计数均进程累计：
    // matched = 名单命中进入织入分派的类数；woven = 产出与原字节不同的类数（真织上）；
    // noChange = 名单命中但产出原字节（方法形态不匹配/可见性闸门降级）；failed = 织入抛异常
    // （返回 null，类按原字节装载 = 无钩子）。判读：matched>0 且 woven==0 即「attach 无钩子」，
    // 指向织入器/可见性闸门；failed>0 指向具体异常形态。
    private static final java.util.concurrent.atomic.AtomicLong COUNT_WEAVABLE =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong COUNT_WOVEN =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong COUNT_NO_CHANGE =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong COUNT_FAILED =
            new java.util.concurrent.atomic.AtomicLong();

    /** 名单命中进入织入分派的累计类数（attach 取证读数）。 */
    public static long weavableCount() {
        return COUNT_WEAVABLE.get();
    }

    /** 产出与原字节不同（真织入落地）的累计类数。 */
    public static long wovenCount() {
        return COUNT_WOVEN.get();
    }

    /** 名单命中但产出原字节（无钩子）的累计类数。 */
    public static long noChangeCount() {
        return COUNT_NO_CHANGE.get();
    }

    /** 织入抛异常（返回 null = 无钩子）的累计类数。 */
    public static long failedCount() {
        return COUNT_FAILED.get();
    }

    /** 仅测试用：四计数清零（用例间隔离）。 */
    static void resetCountersForTest() {
        COUNT_WEAVABLE.set(0L);
        COUNT_WOVEN.set(0L);
        COUNT_NO_CHANGE.set(0L);
        COUNT_FAILED.set(0L);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        // instrument API 传内部名（斜杠）；登记表为点分名——归一化
        final String dotted = className.replace('/', '.');
        try {
            if (ServletWeaver.matches(dotted)) {
                COUNT_WEAVABLE.incrementAndGet();
                final byte[] out = ServletWeaver.weave(loader, dotted, classfileBuffer);
                noteOutcome(out, classfileBuffer);
                return out;
            }
            if (DriverRegistry.isWeavable(dotted)) {
                COUNT_WEAVABLE.incrementAndGet();
                final byte[] out = ConnectWeaver.weave(loader, classfileBuffer);
                noteOutcome(out, classfileBuffer);
                return out;
            }
            if (DriverRegistry.isStatementWeavable(dotted) || DriverRegistry.isPrepareWeavable(dotted)) {
                COUNT_WEAVABLE.incrementAndGet();
                final byte[] out = StatementWeaver.weave(loader, dotted, classfileBuffer);
                noteOutcome(out, classfileBuffer);
                return out;
            }
            return null;
        } catch (Throwable t) {
            // 织入失败 = 该驱动不增强，绝不影响宿主应用启动/运行——不再无声：计数可观测
            // （T-AttachZeroEvent：单类失败此前完全静默，retransform「成功」掩盖「零钩子」）
            COUNT_FAILED.incrementAndGet();
            return null;
        }
    }

    /** 织入产出分类记账：与原字节不同 = 真织上；同字节/null = 无钩子（noChange）。 */
    private static void noteOutcome(byte[] out, byte[] original) {
        if (out != null && out != original && !java.util.Arrays.equals(out, original)) {
            COUNT_WOVEN.incrementAndGet();
        } else {
            COUNT_NO_CHANGE.incrementAndGet();
        }
    }
}
