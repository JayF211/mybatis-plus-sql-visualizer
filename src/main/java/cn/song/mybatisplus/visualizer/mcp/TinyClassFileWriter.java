// T-SyncK2JMaster29（波C）新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/TinyClassFileWriter.kt（8f89c72..3b1ceb5 增量）
package cn.song.mybatisplus.visualizer.mcp;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 class 文件生成器（T-McpDynamicToolset 真机 ASM 不可达修复，20261005）——零外部依赖。
 *
 * <p>生成的壳类形态与动态注册所需完全一致：
 * {@code final class <ShellName> extends <SuperClass> implements <IfaceClass>}，仅一个无参构造器
 * （aload_0 → invokespecial 父类构造 → return），三钩子/工具方法全部继承。</p>
 *
 * <p><b>为什么不用 org.objectweb.asm</b>：平台 262 自带 asm 库（{@code intellij.libraries.asm.jar}），
 * {@code org.objectweb.asm} 因此是平台已知包——插件 classloader 对该包 parent-first 接管，插件自带
 * asm jar 的同包类被跳过；平台 asm 又在对插件不可见的内部库 jar——两处落空 → 真机
 * {@code NoClassDefFoundError: org/objectweb/asm/ClassWriter}（20261005 日常 IDE 262.10968 六轮日志
 * 实锤，见开发计划 20261005-1335）。壳结构固定且极小，直接按 class 文件规范写字节即可，
 * 不依赖任何字节码库；壳为运行期生成、不进插件 jar（verifier 不可见，与 ASM 版形态一致）。</p>
 *
 * <p>版本 V17（major 61）：与插件编译目标一致，243 JBR 21 可加载（与旧 ASM 版逐字节对齐，
 * 守卫测试断言 major=61 不变）。</p>
 *
 * <p>移植注记：master 的 {@code internal object} 按本仓惯例落为 final class + {@link #INSTANCE} +
 * 全静态成员（internal 升 public）；常量池条目写盘闭包落为 {@link Entry} 函数式接口
 * （{@link DataOutputStream} 写出抛受检 {@link IOException}，{@code java.util.function.Consumer}
 * 装不下；master 无受检异常概念，整体按既有 {@code sneakyThrow} 惯例透传）；
 * {@code 0xCAFEBABE} 在 Kotlin 是 Long 字面量（超 Int 范围）须 {@code .toInt()}，Java 十六进制
 * int 字面量按 32 位回绕合法直写，字节语义一致。utf8 索引在 {@code classRef} 内<b>构建期急切解析</b>
 * （master 提交 c7a75a3：惰性解析曾致 writeTo 迭代期 ConcurrentModificationException，已改急切）。</p>
 */
public final class TinyClassFileWriter {

    public static final TinyClassFileWriter INSTANCE = new TinyClassFileWriter();

    private TinyClassFileWriter() {
    }

    /**
     * 生成壳类字节码：{@code final class <shellName> extends <superName> implements <ifaceName>}。
     */
    public static byte[] shellBytes(String shellName, String superName, String ifaceName) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bos);
            ConstantPool cp = new ConstantPool();
            int thisClass = cp.classRef(shellName.replace('.', '/'));
            int superClass = cp.classRef(superName.replace('.', '/'));
            int ifaceClass = cp.classRef(ifaceName.replace('.', '/'));
            int initName = cp.utf8("<init>");
            int voidDesc = cp.utf8("()V");
            int codeAttr = cp.utf8("Code");
            int superInit = cp.methodRef(superClass, cp.nameAndType(initName, voidDesc));

            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // minor_version
            out.writeShort(V17_MAJOR); // major_version
            out.writeShort(cp.size() + 1); // constant_pool_count（1-based，index 0 保留）
            cp.writeTo(out);
            out.writeShort(ACC_PUBLIC | ACC_FINAL | ACC_SUPER);
            out.writeShort(thisClass);
            out.writeShort(superClass);
            out.writeShort(1); // interfaces_count
            out.writeShort(ifaceClass);
            out.writeShort(0); // fields_count
            out.writeShort(1); // methods_count
            // 唯一构造器 <init>()V
            out.writeShort(ACC_PUBLIC);
            out.writeShort(initName);
            out.writeShort(voidDesc);
            out.writeShort(1); // attributes_count: Code
            out.writeShort(codeAttr);
            out.writeInt(CODE_ATTR_LEN); // attribute_length
            out.writeShort(1); // max_stack（aload_0 + invokespecial，栈深至多 1）
            out.writeShort(1); // max_locals（this）
            out.writeInt(5); // code_length
            out.writeByte(0x2a); // aload_0
            out.writeByte(0xb7); // invokespecial
            out.writeShort(superInit); // 父类 <init> methodref
            out.writeByte(0xb1); // return
            out.writeShort(0); // exception_table_length
            out.writeShort(0); // Code attributes_count
            out.writeShort(0); // class attributes_count
            return bos.toByteArray();
        } catch (IOException e) {
            // master 无受检异常：内存流写出实际不可能抛，透传保形（Kotlin 语义 = 原样抛出）
            throw sneakyThrow(e);
        }
    }

    private static final int V17_MAJOR = 61;
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_SUPER = 0x0020;
    // Code 属性 info 长度：max_stack(2) + max_locals(2) + code_length(4) + code(5) +
    //   exception_table_length(2) + exceptions(0) + attributes_count(2) + attributes(0)
    private static final int CODE_ATTR_LEN = 2 + 2 + 4 + 5 + 2 + 0 + 2 + 0;

    /** 常量池条目写盘闭包（DataOutputStream 写出抛受检 IOException，专用函数式接口承载）。 */
    @FunctionalInterface
    private interface Entry {
        void writeTo(DataOutputStream out) throws IOException;
    }

    /**
     * 极简常量池：只支持本壳类需要的四种条目（Utf8 / Class / NameAndType / Methodref），
     * 索引按添加序 1-based 分配，条目内部引用前序索引（JVM 规范要求前向引用）。
     */
    private static final class ConstantPool {

        private final List<Entry> items = new ArrayList<>();
        private final Map<String, Integer> utf8Index = new HashMap<>();

        private int utf8(String value) {
            Integer existing = utf8Index.get(value);
            if (existing != null) {
                return existing;
            }
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(1);
                out.writeUTF(value);
            });
            utf8Index.put(value, index);
            return index;
        }

        private int classRef(String name) {
            int nameIdx = utf8(name); // 先解析（急切），writeTo 迭代期不得再改 items
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(7);
                out.writeShort(nameIdx);
            });
            return index;
        }

        private int nameAndType(int nameIdx, int descriptorIdx) {
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(12);
                out.writeShort(nameIdx);
                out.writeShort(descriptorIdx);
            });
            return index;
        }

        private int methodRef(int classIdx, int nameAndTypeIdx) {
            int index = items.size() + 1;
            items.add(out -> {
                out.writeByte(10);
                out.writeShort(classIdx);
                out.writeShort(nameAndTypeIdx);
            });
            return index;
        }

        private int size() {
            return items.size();
        }

        private void writeTo(DataOutputStream out) throws IOException {
            for (Entry item : items) {
                item.writeTo(out);
            }
        }
    }

    /** 受检异常透传（本仓既有 sneakyThrow 惯例，McpToolsetSupport/DatabaseSchemaResolver 同款）。 */
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
