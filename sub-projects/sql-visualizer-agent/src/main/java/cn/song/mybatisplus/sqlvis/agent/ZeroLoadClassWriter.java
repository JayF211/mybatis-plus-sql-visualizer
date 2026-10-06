package cn.song.mybatisplus.sqlvis.agent;

import org.objectweb.asm.ClassWriter;

/**
 * 零类加载 ClassWriter（20261006 dev 验收⑥ P0）：唯一职责是覆写
 * {@link #getCommonSuperClass(String, String)} 为零类加载保守实现——两参相同返回该值，
 * 否则一律返回 {@code java/lang/Object}。杜绝帧合并期的任何类解析。
 *
 * <p>根因（宿主启动 LinkageError 实录：selfdev-framework-demo / JDK8u181 /
 * mysql-connector 8.0.28 ConnectionImpl）：ASM 默认 getCommonSuperClass 按
 * {@code Class.forName(type, false, ClassWriter 实例所属 loader)} 解析两类型层级，
 * 而该调用发生在 SqlHookTransformer 织入回调内——运行于被织类自身 defineClass1
 * <b>进行中</b>（JVM ClassFileLoadHook 时点），同线程重入 loadClass(被织类) 时
 * findLoadedClass 对未注册完成的定义返回 null → findClass 二次 defineClass →
 * JVM 抛 {@code LinkageError: attempted duplicate class definition}。
 *
 * <p>①本覆写只是防未来重挂 COMPUTE_FRAMES 的兜底，正确形态是 COMPUTE_MAXS-only：
 * 两织入器（ConnectWeaver/StatementWeaver）的注入块为零分支直线（无 try/catch、无新帧
 * 合并点），原方法 StackMapTable 偏移随 Label 自动平移、内容不变——但 StatementWeaver
 * EXEC 族头插的 token 槽（long）在原帧点存活，须由织入器对原帧补 LONG
 * （patchFramesWithTokenSlot），COMPUTE_MAXS 才校验通过。
 * ②transform 期任何类加载都可能命中被织类自身进行中定义 → 宿主启动 LinkageError
 * （duplicate class definition 机制见上）——织入路径上禁止一切 Class.forName。
 * ③若 COMPUTE_FRAMES 被重新启用，本覆写产出的帧内容可能过粗（公共父类一律 Object），
 * 只保证不触发类加载，不保证帧精确——后续子类型方法调用可能 VerifyError，风险由启用方自担。
 */
public final class ZeroLoadClassWriter extends ClassWriter {

    public ZeroLoadClassWriter(int flags) {
        super(flags);
    }

    /** 两参相同返回原值（同类型合并无需解析）；否则保守取 Object——任何引用类型的最小公共上界。 */
    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        return type1.equals(type2) ? type1 : "java/lang/Object";
    }
}
