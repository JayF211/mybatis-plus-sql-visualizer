package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-DevAcceptFix2 ⑥ 回归：**织入产物必须经 JVM 校验（define + initialize）**——堵「只解析不定义」
 * 的假绿形态。
 *
 * <p>立项动因（20261006 第二轮验收本机复现，现象＝开启增强捕获后宿主应用起不来、事件 0 条）：
 * {@code bc75d8b} 的帧补丁给每个 FrameNode 的 local 数组**直接追加 LONG**，前提「token 槽恰起于原
 * info 数末尾」是错的——原帧 locals 的 info 数可**小于** maxLocals（javac 未使用的尾部槽位在帧里
 * 不出现）。实证：mysql-connector 8.0.28 {@code StatementImpl.execute(String,String[])}
 * （{@code stack=3, locals=4, args_size=3}，原帧 locals 仅 3 项）织入后
 * {@code VerifyError: Stack map does not match the one at exception handler}，宿主一建 Statement 即崩。
 *
 * <p>既有 {@link ZeroLoadClassWriterWeaveTest} 刻意**不定义**织入产物（定义会污染增量判别式），
 * 于是「产物非法字节码」这一层无人守——本类补位：产物一律在**隔离子 ClassLoader** 里
 * {@code defineClass + Class.forName(initialize=true)}（触发链接期校验），互不污染计数，且
 * 用例形态覆盖「帧 locals 少于 maxLocals」这一漏网形态。
 */
public class StatementWeaverFramePatchVerifyTest {

    private static final StatementWeaver.Kind[] EXEC_PARAM_FACE = {StatementWeaver.Kind.EXEC_PARAM};
    private static final StatementWeaver.Kind[] STATEMENT_FACE =
            {StatementWeaver.Kind.EXEC_PARAM, StatementWeaver.Kind.EXEC_CTX,
                    StatementWeaver.Kind.BATCH, StatementWeaver.Kind.ADD_BATCH,
                    StatementWeaver.Kind.CLEAR_BATCH};
    private static final StatementWeaver.Kind[] PS_FACE =
            {StatementWeaver.Kind.EXEC_CTX, StatementWeaver.Kind.BATCH};
    private static final StatementWeaver.Kind[] PREPARE_FACE = {StatementWeaver.Kind.PREPARE};

    /**
     * 漏网形态直证：合成「maxLocals=4 而帧 locals 只列 2 项」的方法（javac 未用尾槽形态），
     * 织入后定义 + 初始化 + **实际调用**——修复前此处必红（VerifyError）。
     */
    @Test
    public void 合成未用尾槽形态_织入后定义校验并通过调用() throws Exception {
        final String dotted = "cn.song.mybatisplus.sqlvis.agent.it.TrailingSlotStmt";
        final String internal = dotted.replace('.', '/');
        final byte[] original = synthTrailingSlotClass(internal);

        final byte[] woven = StatementWeaver.weaveSpec(EXEC_PARAM_FACE, systemLoader(), original);
        assertFalse("织入须真实发生（changed 路径）", java.util.Arrays.equals(original, woven));

        final VerificationLoader loader = new VerificationLoader();
        final Class<?> wovenClass = loader.defineAndInitialize(dotted, woven);
        assertNotNull(wovenClass);
        // 实际调用：校验通过还须语义可用（织入回调自吞异常，不影响返回值）
        final Object instance = wovenClass.getDeclaredConstructor().newInstance();
        final Method execute = wovenClass.getMethod("execute", String.class);
        assertEquals("夹具方法自身语义应保持（start 分支返回 true）", Boolean.TRUE, execute.invoke(instance, "select 1"));

        assertFrameTokenSlotAligned(woven, "execute", "(Ljava/lang/String;)Z");
    }

    /**
     * 真实驱动类覆盖面：h2 2.3.232 语句/连接类织入后定义 + 初始化（不实例化——构造需 session），
     * 逐面覆盖 EXEC_PARAM/EXEC_CTX/BATCH 与 PREPARE。
     */
    @Test
    public void 真实h2驱动类_织入后定义校验通过() throws Exception {
        assertWovenDefinable("org.h2.jdbc.JdbcStatement", STATEMENT_FACE);
        assertWovenDefinable("org.h2.jdbc.JdbcPreparedStatement", PS_FACE);
        assertWovenDefinable("org.h2.jdbc.JdbcConnection", PREPARE_FACE);
    }

    /** 帧槽位对齐直证：织入后每个帧点的 locals 槽数 == tokenSlot + 2，且末元素为 LONG。 */
    @Test
    public void 帧locals槽位与token槽对齐() {
        final byte[] original = synthTrailingSlotClass("cn/song/mybatisplus/sqlvis/agent/it/AlignProbe");
        final byte[] woven = StatementWeaver.weaveSpec(EXEC_PARAM_FACE, systemLoader(), original);
        assertFrameTokenSlotAligned(woven, "execute", "(Ljava/lang/String;)Z");
    }

    // ---------- 断言与工具 ----------

    /**
     * 帧 locals 槽位对齐断言（与头插 {@code LSTORE tokenSlot} 的写入位置逐槽对齐）：
     * tokenSlot = 原 maxLocals（夹具=4，帧原列 2 项）→ 补 TOP 至 4 槽后追加 LONG，
     * 故末元素 LONG 的起始槽位必须是 4，总槽数 6（long 占 2）。
     */
    private static void assertFrameTokenSlotAligned(byte[] woven, String methodName, String desc) {
        final ClassNode node = new ClassNode();
        // EXPAND_FRAMES：帧一律以 F_NEW 展开形态入树（locals 恒非 null）——压缩形态下
        // F_SAME/F_APPEND 不携带 locals，直接断言会误判「帧无 locals」
        new ClassReader(woven).accept(node, ClassReader.EXPAND_FRAMES);
        boolean sawFrame = false;
        for (MethodNode mn : node.methods) {
            if (!methodName.equals(mn.name) || !desc.equals(mn.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (!(insn instanceof FrameNode)) {
                    continue;
                }
                final List<Object> local = ((FrameNode) insn).local;
                assertNotNull("帧应带 locals 信息（本夹具为 F_FULL 形态）", local);
                final int[] slots = countSlots(local);
                assertEquals("帧 locals 槽数应为 token 槽位 + long 占位（4 + 2）", 6, slots[0]);
                assertEquals("末元素必须是被补入的 LONG", Opcodes.LONG, local.get(local.size() - 1));
                sawFrame = true;
            }
        }
        assertTrue("夹具应含至少一个显式帧点（否则本断言无鉴别力）", sawFrame);
    }

    /** 帧 locals 槽数（long/double 占 2，其余占 1）；返回单元素数组便于断言消息里带读数。 */
    private static int[] countSlots(List<Object> local) {
        int slots = 0;
        for (Object entry : local) {
            slots += (entry == Opcodes.LONG || entry == Opcodes.DOUBLE) ? 2 : 1;
        }
        return new int[]{slots};
    }

    /** 织入 → 隔离 loader 定义 + 初始化（触发链接期校验；初始化失败即校验未过）。 */
    private void assertWovenDefinable(String dotted, StatementWeaver.Kind[] face) throws Exception {
        final byte[] original = resourceBytes(dotted.replace('.', '/') + ".class");
        final byte[] woven = StatementWeaver.weaveSpec(face, systemLoader(), original);
        assertFalse(dotted + " 织入须真实发生", java.util.Arrays.equals(original, woven));
        final VerificationLoader loader = new VerificationLoader();
        assertNotNull(dotted + " 织入产物必须可通过 JVM 校验并初始化", loader.defineAndInitialize(dotted, woven));
    }

    private static ClassLoader systemLoader() {
        return ClassLoader.getSystemClassLoader();
    }

    private static byte[] resourceBytes(String path) {
        try (InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream(path)) {
            assertNotNull("classpath 缺失夹具：" + path, in);
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 隔离子 ClassLoader：defineClass 只在本 loader 生效，不污染系统 loader 的装载计数与
     * findLoadedClass 判别式；父为测试 classpath（agent 类与驱动类可解析）。
     */
    private static final class VerificationLoader extends ClassLoader {
        VerificationLoader() {
            super(StatementWeaverFramePatchVerifyTest.class.getClassLoader());
        }

        Class<?> defineAndInitialize(String dotted, byte[] bytes) throws Exception {
            final Class<?> defined = defineClass(dotted, bytes, 0, bytes.length);
            // initialize=true 触发链接（校验）+ 初始化——校验不过的产物在此抛 VerifyError
            Class.forName(dotted, true, this);
            return defined;
        }
    }

    /**
     * 合成「未用尾槽」夹具：{@code execute(String)Z} 的 maxLocals=4，而帧 locals 只列 2 项
     * （javac 未使用尾槽的真实形态，mysql StatementImpl 同族）。手写 visitMaxs 固定 maxLocals，
     * 故写入器不得用 COMPUTE_MAXS（会按实际使用重算成 2，夹具形态消失）。
     */
    private static byte[] synthTrailingSlotClass(String internalName) {
        final ClassWriter cw = new ClassWriter(0); // 显式 maxs，禁重算（夹具形态即 maxLocals > 帧 locals 项数）
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Object", null);

        final MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "execute",
                "(Ljava/lang/String;)Z", null, null);
        mv.visitCode();
        final Label start = new Label();
        final Label handler = new Label();
        final Label after = new Label();
        mv.visitTryCatchBlock(start, handler, handler, "java/lang/RuntimeException");
        mv.visitLabel(start);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, after);
        mv.visitLabel(handler);
        // F_FULL 帧：locals 只列 2 项（this + String），异常在栈上
        mv.visitFrame(Opcodes.F_FULL, 2,
                new Object[]{internalName, "java/lang/String"}, 1, new Object[]{"java/lang/RuntimeException"});
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(after);
        mv.visitFrame(Opcodes.F_FULL, 2,
                new Object[]{internalName, "java/lang/String"}, 1, new Object[]{Opcodes.INTEGER});
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 4); // ← maxLocals=4 而帧只列 2 项：未用尾槽形态（槽位对齐的真判别式）
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
