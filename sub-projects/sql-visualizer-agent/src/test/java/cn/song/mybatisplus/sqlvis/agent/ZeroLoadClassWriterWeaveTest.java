package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-P0（20261006 dev 验收⑥）回归：织入器 COMPUTE_FRAMES 的 getCommonSuperClass 按
 * {@code Class.forName(type, false, ClassWriter 所属 loader)} 解析类型层级，而 transform
 * 回调运行于被织类自身 defineClass1 <b>进行中</b>（JVM ClassFileLoadHook 时点）——同线程
 * 重入 loadClass(被织类) 时 findLoadedClass 返回 null、findClass 二次 defineClass，宿主
 * 启动即炸 {@code LinkageError: attempted duplicate class definition}（selfdev-framework-demo
 * / JDK8u181 / mysql-connector 8.0.28 ConnectionImpl prepare 面实录）。本测试只织入
 * （字节进出）不定义织入产物——被织类一经 define 本身就是一次加载，会污染判别式。
 *
 * <p>四层判别（20261006 探针实证定形，-verbose:class 读数见任务报告）：
 * ①【红信号·首织增量】weaveSpec 首织 h2 类时 COMPUTE_FRAMES 会 Class.forName 该类帧合并的
 * 新鲜类型（探针：JdbcPreparedStatement 首织 +12 个 org.h2.value.*）——暖机织入 JdbcStatement
 * 吸收框架一次性装载（InnerClassNode/Handle/枚举 switch-map 等，与织入器是否类加载无关），
 * 度量窗只含被测类一次纯织入：修复形态（COMPUTE_MAXS-only + [ZeroLoadClassWriter] 零加载
 * 兜底）下增量恒 0，COMPUTE_FRAMES 回归即 > 0。暖机集纪律：任何方法的暖机集不得织入其他
 * 方法的被测类（合并集两两不相交经探针核实），红信号与 JUnit 方法执行序无关；被测试 JVM
 * 预载过合并类型的候选（如 JdbcConnection 对 java.lang.invoke 族）增量层退化为不变量，
 * 此时红信号由②③层接管。
 * ②【被织类自身零定义】每例织入后 findLoadedClass 仍 == null——P0 宿主炸点恰是「被织类自身
 * 进行中定义被二次 define」的精确探针。
 * ③【字节码红线守卫】两织入器编译产物不得出现 {@code new org/objectweb/asm/ClassWriter}
 * （直建即 COMPUTE_FRAMES 回流通道），写入器构造必须走 ZeroLoadClassWriter；该守卫环境无关、
 * 恒携带红信号，为增量层在特定 JVM 预载态下被掩蔽的候选兜底。
 * ④【兜底直证】ZeroLoadClassWriter 对不存在类型直接返回保守公共父类，含跨类型合并点的合成类
 * 经 COMPUTE_FRAMES 全链序列化成功（默认 ClassWriter 同输入抛 TypeNotPresentException——
 * 即 transform 期 Class.forName 的机制标本）。
 *
 * <p>夹具：测试 classpath 上的 h2 2.3.232 驱动类字节——经 getResource 直读、不 Class.forName；
 * 前置 findLoadedClass 反射断言未预载（--add-opens java.base/java.lang，见 build.gradle.kts
 * test 任务）。ConnectWeaver.weave 按签名织入、不查驱动名单，故可用 h2 Driver 驱动完整路径。
 */
public class ZeroLoadClassWriterWeaveTest {

    private static final ClassLoadingMXBean CLASS_LOADING = ManagementFactory.getClassLoadingMXBean();

    private static final StatementWeaver.Kind[] STATEMENT_FACE =
            {StatementWeaver.Kind.EXEC_PARAM, StatementWeaver.Kind.EXEC_CTX,
                    StatementWeaver.Kind.BATCH, StatementWeaver.Kind.ADD_BATCH,
                    StatementWeaver.Kind.CLEAR_BATCH};
    private static final StatementWeaver.Kind[] PS_FACE =
            {StatementWeaver.Kind.EXEC_CTX, StatementWeaver.Kind.BATCH};
    private static final StatementWeaver.Kind[] PREPARE_FACE = {StatementWeaver.Kind.PREPARE};

    @Before
    public void warmUpWeaverFramework() {
        // 织入框架主干（ASM 树 API / 写入器 / ZeroLoadClassWriter / 本测试反射工具类）的装载
        // 先落在计数窗外：用已上 classpath 的夹具类走真实 changed 路径各一遍（产物丢弃、不定义）。
        ConnectWeaver.weave(ClassLoader.getSystemClassLoader(),
                resourceBytes("cn/song/mybatisplus/sqlvis/agent/FakeDriver.class"));
        StatementWeaver.weaveSpec(STATEMENT_FACE, ClassLoader.getSystemClassLoader(),
                resourceBytes("cn/song/mybatisplus/sqlvis/agent/FakeVendorFixtures$VendorStmt.class"));
        StatementWeaver.weaveSpec(PREPARE_FACE, ClassLoader.getSystemClassLoader(),
                resourceBytes("cn/song/mybatisplus/sqlvis/agent/FakeVendorFixtures$VendorConn.class"));
    }

    /** prepare 面织入零类加载——P0 炸点（ConnectionImpl.onConnection 同面）直接对应。 */
    @Test
    public void prepare面织入_JdbcConnection零类加载() {
        // 暖机：statement 面首织吸收框架一次性装载。暖机集纪律：任何方法的暖机集不得织入
        // 其他方法的被测类（否则 COMPUTE_FRAMES 回归态下暖机会预载对方合并类型，跨用例掩蔽
        // 红信号——20261006 run4 实证）。
        warmWeave("org.h2.jdbc.JdbcStatement", STATEMENT_FACE);
        assertWeaveZeroLoad("org.h2.jdbc.JdbcConnection", PREPARE_FACE, false);
    }

    /** PS 面织入零类加载——EXEC_CTX/BATCH 形态，红信号主力（探针：首织 +12 个合并类型）。 */
    @Test
    public void PS面织入_JdbcPreparedStatement零类加载() {
        // 暖机：statement 面首织吸收框架一次性装载（刻意不含被测 JdbcPreparedStatement，
        // 其合并类型 org.h2.value.* 与 JdbcStatement 帧合并集不相交——探针实证）
        warmWeave("org.h2.jdbc.JdbcStatement", STATEMENT_FACE);
        assertWeaveZeroLoad("org.h2.jdbc.JdbcPreparedStatement", PS_FACE, false);
    }

    /**
     * connect 面织入不变量——h2 Driver 帧合并无新鲜类型（探针实证），增量判别在此不携带红信号
     * （回归由③字节码红线守卫兜住）；本例仍守住 connect 路径「零加载 + 真实织入 + 自身零定义」。
     * 暖机集同样避开 JdbcPreparedStatement（保护 PS 面红信号次序无关）。
     */
    @Test
    public void connect面织入_h2Driver零类加载() {
        warmWeave("org.h2.jdbc.JdbcStatement", STATEMENT_FACE);
        warmWeave("org.h2.jdbc.JdbcConnection", PREPARE_FACE);
        assertWeaveZeroLoad("org.h2.Driver", null, true);
    }

    /**
     * ③字节码红线守卫（环境无关）：两织入器编译产物不得直建 org/objectweb/asm/ClassWriter
     * （直建 = COMPUTE_FRAMES 回流通道），必须经 ZeroLoadClassWriter。
     */
    @Test
    public void 织入器字节码红线_禁直建ClassWriter() throws Exception {
        assertNoDirectClassWriter("cn.song.mybatisplus.sqlvis.agent.ConnectWeaver");
        assertNoDirectClassWriter("cn.song.mybatisplus.sqlvis.agent.StatementWeaver");
    }

    /**
     * ④兜底直证：同名直返、异名保守 Object（对不存在类型零类加载——默认实现此处即抛
     * TypeNotPresentException）；含跨类型合并点的合成类经 COMPUTE_FRAMES 全链序列化成功。
     */
    @Test
    public void 兜底直证_不存在类型COMPUTE_FRAMES零加载() throws Exception {
        final ZeroLoadClassWriter writer = new ZeroLoadClassWriter(COMPUTE_FRAMES_FLAG());
        assertEquals("两参相同返回该值（同类型合并无需解析）",
                "a/b", writer.getCommonSuperClass("a/b", "a/b"));
        assertEquals("异名保守取 Object（默认实现此处 Class.forName(\"a.X\") 即炸）",
                "java/lang/Object", writer.getCommonSuperClass("a/X", "b/Y"));

        final byte[] out = synthMergeClassThroughZeroLoadWriter();
        new ClassReader(out); // COMPUTE_FRAMES 产物可解析（a/X/b/Y 不存在仍全链成功）
    }

    // ---------- ①②公共缝 ----------

    /** 逐例判别：前置未载 → 度量窗内一次纯织入 → 增量 == 0 + 真实 changed + 自身仍未载 + 可解析。 */
    private void assertWeaveZeroLoad(String dotted, StatementWeaver.Kind[] face, boolean connectFace) {
        assertNotLoaded(dotted);
        final byte[] original = resourceBytes(dotted.replace('.', '/') + ".class");
        final long before = CLASS_LOADING.getTotalLoadedClassCount();
        final byte[] woven = weaveOnce(dotted, face, connectFace, original);
        final long delta = CLASS_LOADING.getTotalLoadedClassCount() - before;
        assertEquals(dotted + ": 织入期类加载增量必须为 0（COMPUTE_FRAMES 的 getCommonSuperClass "
                + "会 Class.forName 解析帧合并类型 → 宿主 transform 期类加载，被织类自身命中进行中"
                + "定义即 LinkageError）", 0L, delta);
        assertFalse(dotted + " 织入须真实发生（changed 路径，否则增量判定无鉴别力）",
                Arrays.equals(original, woven));
        assertNotLoaded(dotted); // ②被织类自身进行中定义被二次 define = P0 宿主炸点，零容忍
        new ClassReader(woven); // 织入产物可解析（合法性冒烟，纯字节解析不加载类）
    }

    /** 暖机织入（不度量）：吸收目标类内容的框架一次性装载与其帧合并类型（不定义被织类）。 */
    private void warmWeave(String dotted, StatementWeaver.Kind[] face) {
        weaveOnce(dotted, face, false, resourceBytes(dotted.replace('.', '/') + ".class"));
    }

    private static byte[] weaveOnce(String dotted, StatementWeaver.Kind[] face, boolean connectFace,
            byte[] bytes) {
        return connectFace
                ? ConnectWeaver.weave(ClassLoader.getSystemClassLoader(), bytes)
                : StatementWeaver.weaveSpec(face, ClassLoader.getSystemClassLoader(), bytes);
    }

    // ---------- ③字节码守卫 ----------

    private void assertNoDirectClassWriter(String className) throws Exception {
        final byte[] bytes = resourceBytes(className.replace('.', '/') + ".class");
        final int[] directNew = {0};
        final int[] zeroNew = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW) {
                            if ("org/objectweb/asm/ClassWriter".equals(type)) {
                                directNew[0]++;
                            }
                            if ("cn/song/mybatisplus/sqlvis/agent/ZeroLoadClassWriter".equals(type)) {
                                zeroNew[0]++;
                            }
                        }
                        super.visitTypeInsn(opcode, type);
                    }
                };
            }
        }, 0);
        assertEquals(className + " 不得直建 org/objectweb/asm/ClassWriter（COMPUTE_FRAMES 禁用红线，"
                + "直建即帧合并类加载回流）", 0, directNew[0]);
        assertTrue(className + " 写入器构造必须走 ZeroLoadClassWriter", zeroNew[0] > 0);
    }

    // ---------- ④兜底直证 ----------

    private static int COMPUTE_FRAMES_FLAG() {
        // 不直引 ClassWriter.COMPUTE_FRAMES 常量名以防静态扫描误伤本测试自身语义；值 = 2（ASM 契约）
        return 2;
    }

    /** 合成含跨类型合并点（a/X 与 b/Y 汇流）的类，经 ZeroLoadClassWriter(COMPUTE_FRAMES) 序列化。 */
    private byte[] synthMergeClassThroughZeroLoadWriter() {
        final ClassNode node = new ClassNode();
        node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sqlvis/it/SynMerge", null,
                "java/lang/Object", null);
        final MethodNode mv = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V",
                null, null);
        final LabelNode elseStart = new LabelNode();
        final LabelNode join = new LabelNode();
        mv.instructions.add(new InsnNode(Opcodes.ICONST_0));
        mv.instructions.add(new JumpInsnNode(Opcodes.IFEQ, elseStart));
        mv.instructions.add(new TypeInsnNode(Opcodes.NEW, "a/UnresolvableX"));
        mv.instructions.add(new InsnNode(Opcodes.DUP));
        mv.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,
                "a/UnresolvableX", "<init>", "()V", false));
        mv.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        mv.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
        mv.instructions.add(elseStart);
        mv.instructions.add(new TypeInsnNode(Opcodes.NEW, "b/UnresolvableY"));
        mv.instructions.add(new InsnNode(Opcodes.DUP));
        mv.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,
                "b/UnresolvableY", "<init>", "()V", false));
        mv.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        mv.instructions.add(join);
        mv.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(mv);
        final ZeroLoadClassWriter writer = new ZeroLoadClassWriter(COMPUTE_FRAMES_FLAG());
        node.accept(writer);
        return writer.toByteArray();
    }

    // ---------- 辅助 ----------

    /** 夹具前提/红线：目标类尚未被测试 JVM 装载（findLoadedClass 反射探查，不触发加载）。 */
    private static void assertNotLoaded(String dottedName) {
        try {
            final Method findLoadedClass =
                    ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            findLoadedClass.setAccessible(true);
            assertNull("夹具前提/红线：" + dottedName + " 已被测试 JVM 装载——增量判定失去鉴别力，"
                            + "或织入路径发生了对该类的解析加载（P0 机制）",
                    findLoadedClass.invoke(ClassLoader.getSystemClassLoader(), dottedName));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
}
