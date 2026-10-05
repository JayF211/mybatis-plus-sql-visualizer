package cn.song.mybatisplus.visualizer.mcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * 极简 class 文件生成器（T-McpDynamicToolset 真机 ASM 不可达修复，20261005）——零外部依赖。
 *
 * 生成的壳类形态与动态注册所需完全一致：
 * `final class <ShellName> extends <SuperClass> implements <IfaceClass>`，仅一个无参构造器
 * （aload_0 → invokespecial 父类构造 → return），三钩子/工具方法全部继承。
 *
 * **为什么不用 org.objectweb.asm**：平台 262 自带 asm 库（`intellij.libraries.asm.jar`），
 * `org.objectweb.asm` 因此是平台已知包——插件 classloader 对该包 parent-first 接管，插件自带
 * asm jar 的同包类被跳过；平台 asm 又在对插件不可见的内部库 jar——两处落空 → 真机
 * `NoClassDefFoundError: org/objectweb/asm/ClassWriter`（20261005 日常 IDE 262.10968 六轮日志
 * 实锤，见开发计划 20261005-1335）。壳结构固定且极小，直接按 class 文件规范写字节即可，
 * 不依赖任何字节码库；壳为运行期生成、不进插件 jar（verifier 不可见，与 ASM 版形态一致）。
 *
 * 版本 V17（major 61）：与插件编译目标一致，243 JBR 21 可加载（与旧 ASM 版逐字节对齐，
 * 守卫测试断言 major=61 不变）。
 */
internal object TinyClassFileWriter {

    /** 生成壳类字节码：`final class <shellName> extends <superName> implements <ifaceName>`。 */
    fun shellBytes(shellName: String, superName: String, ifaceName: String): ByteArray {
        val bos = ByteArrayOutputStream(64)
        val out = DataOutputStream(bos)
        val cp = ConstantPool()
        val thisClass = cp.classRef(shellName.replace('.', '/'))
        val superClass = cp.classRef(superName.replace('.', '/'))
        val ifaceClass = cp.classRef(ifaceName.replace('.', '/'))
        val initName = cp.utf8("<init>")
        val voidDesc = cp.utf8("()V")
        val codeAttr = cp.utf8("Code")
        val superInit = cp.methodRef(superClass, cp.nameAndType(initName, voidDesc))

        out.writeInt(0xCAFEBABE.toInt())
        out.writeShort(0) // minor_version
        out.writeShort(V17_MAJOR) // major_version
        out.writeShort(cp.size() + 1) // constant_pool_count（1-based，index 0 保留）
        cp.writeTo(out)
        out.writeShort(ACC_PUBLIC or ACC_FINAL or ACC_SUPER)
        out.writeShort(thisClass)
        out.writeShort(superClass)
        out.writeShort(1) // interfaces_count
        out.writeShort(ifaceClass)
        out.writeShort(0) // fields_count
        out.writeShort(1) // methods_count
        // 唯一构造器 <init>()V
        out.writeShort(ACC_PUBLIC)
        out.writeShort(initName)
        out.writeShort(voidDesc)
        out.writeShort(1) // attributes_count: Code
        out.writeShort(codeAttr)
        out.writeInt(CODE_ATTR_LEN) // attribute_length
        out.writeShort(1) // max_stack（aload_0 + invokespecial，栈深至多 1）
        out.writeShort(1) // max_locals（this）
        out.writeInt(5) // code_length
        out.writeByte(0x2a) // aload_0
        out.writeByte(0xb7) // invokespecial
        out.writeShort(superInit) // 父类 <init> methodref
        out.writeByte(0xb1) // return
        out.writeShort(0) // exception_table_length
        out.writeShort(0) // Code attributes_count
        out.writeShort(0) // class attributes_count
        return bos.toByteArray()
    }

    private const val V17_MAJOR = 61
    private const val ACC_PUBLIC = 0x0001
    private const val ACC_FINAL = 0x0010
    private const val ACC_SUPER = 0x0020
    // Code 属性 info 长度：max_stack(2) + max_locals(2) + code_length(4) + code(5) +
    //   exception_table_length(2) + exceptions(0) + attributes_count(2) + attributes(0)
    private const val CODE_ATTR_LEN = 2 + 2 + 4 + 5 + 2 + 0 + 2 + 0

    /** 极简常量池：只支持本壳类需要的四种条目（Utf8 / Class / NameAndType / Methodref），
     *  索引按添加序 1-based 分配，条目内部引用前序索引（JVM 规范要求前向引用）。 */
    private class ConstantPool {
        private val items = ArrayList<(DataOutputStream) -> Unit>()
        private val utf8Index = HashMap<String, Int>()

        fun utf8(value: String): Int {
            utf8Index[value]?.let { return it }
            val index = items.size + 1
            items.add { out -> out.writeByte(1); out.writeUTF(value) }
            utf8Index[value] = index
            return index
        }

        fun classRef(name: String): Int {
            val nameIdx = utf8(name) // 先解析（急切），writeTo 迭代期不得再改 items
            val index = items.size + 1
            items.add { out -> out.writeByte(7); out.writeShort(nameIdx) }
            return index
        }

        fun nameAndType(nameIdx: Int, descriptorIdx: Int): Int {
            val index = items.size + 1
            items.add { out -> out.writeByte(12); out.writeShort(nameIdx); out.writeShort(descriptorIdx) }
            return index
        }

        fun methodRef(classIdx: Int, nameAndTypeIdx: Int): Int {
            val index = items.size + 1
            items.add { out -> out.writeByte(10); out.writeShort(classIdx); out.writeShort(nameAndTypeIdx) }
            return index
        }

        fun size(): Int = items.size

        fun writeTo(out: DataOutputStream) {
            items.forEach { it(out) }
        }
    }
}
