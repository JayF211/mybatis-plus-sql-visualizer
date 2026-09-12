package cn.song.mybatisplus.sqlvis.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * connect 方法出口织入（ASM Tree API，§4.2 #1/#2/#6）：在
 * {@code connect(String, Properties)} 的每个 ARETURN 前插入回调调用。
 *
 * classloader 契约（§4.2 #6）的实现方式：**织入期判定可见性**——transform 回调里先以
 * 定义类 loader 试解析回调类，不可见（驱动 loader 隐藏 agent 类 / bootstrap 定义）则
 * 整类不织入（安全降级为无钩子），可见则注入「零分支直线」的 invokestatic。
 * 因此注入块不含 try/catch、不含分支——零帧合并点，COMPUTE_FRAMES 恒可计算；
 * 回调内部（SqlHookCallbacks.onConnection）自吞一切 Throwable，NCDFE 类失败面收口在
 * 织入期而非运行期（对比：反射内联方案的 NoClassDefFoundError 运行期吞吃——DebugTools 式）。
 *
 * 只改方法体（新局部槽占 maxLocals，COMPUTE_MAXS 重算），不增删方法字段——retransform 合规。
 * 空值短路 / 幂等（不二次包装）/ 禁用开关全在回调首部完成，注入序列保持最短。
 */
public final class ConnectWeaver implements Opcodes {

    static final String CALLBACK_CLASS = "cn/song/mybatisplus/sqlvis/agent/hook/SqlHookCallbacks";
    static final String CONNECT_DESC = "(Ljava/lang/String;Ljava/util/Properties;)Ljava/sql/Connection;";

    private ConnectWeaver() {
    }

    public static byte[] weave(ClassLoader definingLoader, byte[] classfileBuffer) {
        // 织入期可见性判定：定义类 loader（null=bootstrap → 走 system）解析不到回调类则不织入
        final ClassLoader loader = definingLoader != null
                ? definingLoader
                : ClassLoader.getSystemClassLoader();
        try {
            if (loader != ClassLoader.getSystemClassLoader()) {
                loader.loadClass(CALLBACK_CLASS.replace('/', '.'));
            }
        } catch (Throwable t) {
            return classfileBuffer; // 驱动 loader 看不见回调 → 该类不增强（安全降级）
        }

        final ClassNode node = new ClassNode();
        new ClassReader(classfileBuffer).accept(node, 0);
        boolean changed = false;
        for (MethodNode mn : node.methods) {
            if (!CONNECT_DESC.equals(mn.desc) || (mn.access & ACC_ABSTRACT) != 0
                    || (mn.access & ACC_NATIVE) != 0) {
                continue;
            }
            if (weaveMethod(mn)) {
                changed = true;
            }
        }
        if (!changed) {
            return classfileBuffer;
        }
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** 在 mn 的每个 ARETURN 前插入回调块（实例方法：url=槽 1；静态方法：url=槽 0，conn 新槽=maxLocals）。 */
    private static boolean weaveMethod(MethodNode mn) {
        final boolean isStatic = (mn.access & ACC_STATIC) != 0;
        final int urlSlot = isStatic ? 0 : 1;
        final int connSlot = mn.maxLocals;
        int inserted = 0;

        AbstractInsnNode insn = mn.instructions.getFirst();
        while (insn != null) {
            final AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == ARETURN) {
                mn.instructions.insertBefore(insn, hookBlock(urlSlot, connSlot));
                inserted++;
            }
            insn = next;
        }
        if (inserted > 0) {
            mn.maxLocals = connSlot + 1; // 新局部占 1 slot（Object 引用，无 long/double）
            return true;
        }
        return false;
    }

    /**
     * 零分支回调块：conn 存新槽 → invokestatic onConnection(conn, url) 返回（可能已包装的）连接
     * → checkcast 校验 → 回存 → 交回原 ARETURN。
     */
    private static InsnList hookBlock(int urlSlot, int connSlot) {
        final InsnList l = new InsnList();
        l.add(new VarInsnNode(ASTORE, connSlot));
        l.add(new VarInsnNode(ALOAD, connSlot));
        l.add(new VarInsnNode(ALOAD, urlSlot));
        l.add(new MethodInsnNode(INVOKESTATIC, CALLBACK_CLASS,
                "onConnection", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        l.add(new TypeInsnNode(CHECKCAST, "java/sql/Connection"));
        l.add(new VarInsnNode(ASTORE, connSlot));
        l.add(new VarInsnNode(ALOAD, connSlot));
        return l;
    }
}
