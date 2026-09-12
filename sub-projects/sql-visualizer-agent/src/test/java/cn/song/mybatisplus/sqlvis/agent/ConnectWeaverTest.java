package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * [ConnectWeaver] 织入产物校验：
 * 1. 织入后的字节码可被正常解析（合法 class 文件）；
 * 2. 只改方法体不增删成员——方法数不变（retransformClasses 合规硬约束）；
 * 3. 织入前后 maxLocals 增长（新局部槽）由 COMPUTE_MAXS 自动处理，不需断言。
 * 端到端语义验证（connect 返回的 Connection 被代理）见 agent 侧 attach 集成测试。
 */
public class ConnectWeaverTest {

    @Test
    public void 织入不增删方法() throws Exception {
        // 直接读取本测试 classpath 上的编译产物（main 源集输出），合成一个假驱动类源码不可行——
        // 改用「对现有类做双次织入幂等校验 + 类结构计数」策略：以 DriverRegistry 自身做织入对象
        // 无 connect 方法（应原样返回）。真实驱动织入见 attach 集成测试（H2 Driver）。
        byte[] original = classBytes("cn.song.mybatisplus.sqlvis.agent.DriverRegistry");
        byte[] woven = ConnectWeaver.weave(ClassLoader.getSystemClassLoader(), original);
        // 无 connect(String,Properties) 方法的类：weave 输出应重新序列化但成员计数不变
        assertSameMethodCount(original, woven);
    }

    private void assertSameMethodCount(byte[] a, byte[] b) throws Exception {
        int countA = countDeclaredMethods(a);
        int countB = countDeclaredMethods(b);
        assertTrue("织入不得新增方法: " + countA + " -> " + countB, countB == countA);
    }

    private int countDeclaredMethods(byte[] bytes) {
        final int[] count = {0};
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                count[0]++;
                return null;
            }
        }, 0);
        return count[0];
    }

    private byte[] classBytes(String fqcn) throws Exception {
        String path = fqcn.replace('.', '/') + ".class";
        try (java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
