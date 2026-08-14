package cn.song.mybatisplus.visualizer.heavy

import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame

/**
 * Heavy 语义黄金用例（T-M23.13，完整平台语义）。
 *
 * LightPlatform（BasePlatformTestCase）测试环境存在**语义盲区**（方法引用 resolve 返回 null /
 * lambda 参数类型推断不可用），本类在 **HeavyPlatformTestCase**（完整平台 + 磁盘项目 +
 * 索引）下固化真实 IDE 语义能力，作为语义敏感场景的黄金回归骨架。
 *
 * 配置要点（2025.3.5 平台实证，坑记录）：
 * - 用 HeavyPlatformTestCase **自带 project**（不手动 createFixtureBuilder——手动建的 fixture
 *   与自带 project 分离，module 无源码根；JavaModuleFixtureBuilder 编译 classpath 不可用、
 *   ProjectLibraryTable/LibraryTablesRegistrar 已移除、addModuleLibrary 3 参版已删除——
 *   mp-core 的 module classpath 挂载方案待后续调研，见开发计划 §2.4）；
 * - 文件经 `createTestProjectStructure()` 落磁盘（可被索引），不用 configureByText 虚拟文件；
 * - VFS 写操作须包 `WriteCommandAction.runWriteCommandAction(project, Computable {...})`。
 *
 * 环境能力实证结论（Heavy vs Light）：
 * - **lambda 参数类型推断：Heavy 可用、Light 不可用**（T-M23.12 三图标缺陷的复现/回归手段）；
 * - **方法引用 resolve：Heavy 亦返回 null**（测试环境平台限制，与真实 IDE 不同——
 *   T-M23.10 的 inferEntityFromChain 文件级类名解析补偿在两种环境都需要）；
 * - 场景 B lambda 参数守卫行为：Heavy 语义环境下与 Light 一致（黄金回归）。
 *
 * 速度约束：Heavy 类初始化较慢（完整平台启动 + 索引构建），**用例数严格控制**——
 * 每个里程碑的语义敏感逻辑在此补黄金用例（见 docs/开发计划/20260814-1648-Heavy语义黄金用例测试-开发计划.md）。
 */
class HeavyAnchorSemanticsTest : HeavyPlatformTestCase() {

    /** 在项目 src 根下创建 Java 源文件（磁盘 + 索引），返回 PsiFile。 */
    private fun addSourceFile(name: String, content: String): PsiFile {
        return WriteCommandAction.runWriteCommandAction(project, Computable {
            val root = createTestProjectStructure()
            val src = root.findChild("src") ?: root.createChildDirectory(this, "src")
            val vf = src.createChildData(this, name)
            VfsUtil.saveText(vf, content.trimIndent())
            PsiManager.getInstance(project).findFile(vf)!!
        })
    }


    fun testLambdaParameterInferenceAvailable() {
        // 环境能力实证：lambda 参数类型推断在 Heavy 下可用（Light 下 `o.type` 为空——
        // T-M23.12 三图标缺陷复现不出的根因）；用 fixture 自定义函数式接口（不依赖外部库）
        val file = addSourceFile(
            "Demo.java",
            """
            import java.util.function.Consumer;

            public class Demo {
                public void query() {
                    Chain chain = new ChainImpl();
                    chain.and(o -> o.apply());
                }

                interface Chain { Chain and(Consumer<Chain> c); }
                static class ChainImpl implements Chain {
                    public Chain and(Consumer<Chain> c) { c.accept(this); return this; }
                }
            }
            """,
        )
        val lambdaParam = PsiTreeUtil.findChildOfType(file, PsiParameter::class.java)!!
        assertNotNull("Heavy 下 lambda 参数类型应可推断（Light 下为 null）", lambdaParam.type)
    }

    fun testLambdaParameterOnWrapperLikeTypeStillDeduped() {
        // T-M23.12 修复行为在 Heavy 语义环境下的黄金回归：即使 lambda 参数类型可推断，
        // 场景 B 守卫仍排除 lambda 参数（不标锚点）、wrapper 变量行标锚点（恒 1 个图标）
        val file = addSourceFile(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceEntity> wrapper = new LambdaQueryWrapper<DeviceEntity>();
                    wrapper.and(o -> o.eq(DeviceEntity::getId, 1L));
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val lambdaParam = PsiTreeUtil.findChildOfType(file, PsiParameter::class.java)!!
        assertNull("lambda 参数不应标锚点（场景 B 守卫，与 Light 一致）", WrapperAnchorDetector.anchorElement(lambdaParam.nameIdentifier))
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)!!
        assertSame(
            "wrapper 变量行应为锚点",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
    }
}
