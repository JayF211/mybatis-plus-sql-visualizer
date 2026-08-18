package cn.song.mybatisplus.visualizer.heavy

import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertTrue

/**
 * 调用类 MPJ 能力解析 Heavy 测试：`SqlToJavaConverter.hasMpjInterface` 继承链遍历
 * （接口 + 父类递归匹配 MPJ 1.5.6 实证接口）。接口引用解析依赖真实索引/类路径——
 * Heavy 环境（磁盘项目 + 索引）；Light 环境跨文件 resolve 不可用（M27 实证）。
 * （invocationHasMpj 的按名索引检索路径 = getClassesByName + hasMpjInterface，真机行为一致。）
 */
class InvocationMpjHeavyTest : HeavyPlatformTestCase() {

    /** 在项目 src 下按包路径创建 Java 源文件（磁盘 + 索引；路径与包声明匹配——
     * 同包引用按包目录解析，路径不符会解析失败实证），返回 PsiClass。 */
    private fun addClassFile(pkg: String, name: String, content: String): PsiClass {
        return WriteCommandAction.runWriteCommandAction(project, Computable {
            val root = createTestProjectStructure()
            var dir = root.findChild("src") ?: root.createChildDirectory(this, "src")
            for (seg in pkg.split('.')) {
                if (seg.isEmpty()) continue
                dir = dir.findChild(seg) ?: dir.createChildDirectory(this, seg)
            }
            val vf = dir.createChildData(this, name)
            VfsUtil.saveText(vf, content.trimIndent())
            PsiManager.getInstance(project).findFile(vf)!!
        }).let { file ->
            PsiTreeUtil.findChildOfType(file, PsiClass::class.java)!!
        }
    }

    fun testDirectMpjInterfaceMatch() {
        val iface = addClassFile("com.github.yulichang.base", "MPJBaseService.java", """
            package com.github.yulichang.base;
            public interface MPJBaseService<T> {}
        """)
        val svc = addClassFile("demo", "UserService.java", """
            package demo;
            import com.github.yulichang.base.MPJBaseService;
            public interface UserService extends MPJBaseService<User> {}
        """)
        addClassFile("demo", "User.java", "package demo; public class User {}")
        assertTrue("直接 extends MPJBaseService 应识别 MPJ 能力", SqlToJavaConverter.hasMpjInterface(svc, project))
        assertTrue("MPJ 接口自身应识别", SqlToJavaConverter.hasMpjInterface(iface, project))
    }

    fun testIndirectMpjInterfaceMatch() {
        // 隔层继承：UserService → BaseService → MPJBaseService。中间接口与调用接口放**同一文件**
        // （同文件引用解析总是可靠；跨文件解析在 Heavy 测试环境不稳定——泛型/包路径实证）。
        // 真机 IDE 完整类路径下跨文件继承解析可靠（invocationHasMpj 按索引取类 + 本遍历）。
        addClassFile("com.github.yulichang.base", "MPJBaseService.java", """
            package com.github.yulichang.base;
            public interface MPJBaseService {}
        """)
        val svc = addClassFile("demo", "BaseService.java", """
            package demo;
            import com.github.yulichang.base.MPJBaseService;
            interface BaseService extends MPJBaseService {}
            public interface UserService extends BaseService {}
        """)
        // 文件中第二个类（UserService）为测试目标
        val userSvc = PsiTreeUtil.getChildrenOfType(svc.containingFile, PsiClass::class.java)
            ?.firstOrNull { it.name == "UserService" } ?: error("UserService not found")
        val ifaces = userSvc.interfaces.map { it.qualifiedName ?: "?" }
        assertTrue(
            "隔层继承应识别 MPJ 能力：ifaces=$ifaces",
            SqlToJavaConverter.hasMpjInterface(userSvc, project),
        )
    }

    fun testPlainAndMissingReturnFalse() {
        val plain = addClassFile("demo", "PlainService.java", "package demo; public interface PlainService {}")
        assertTrue("无 MPJ 接口的调用类应返回 false", !SqlToJavaConverter.hasMpjInterface(plain, project))
    }

    fun testGenericSupertypeWithUnresolvableArg() {
        // 模拟 jar-loaded 场景：MPJ 能力接口以全限定名引用但不可解析（未在测试项目定义）→
        // getInterfaces() 返回空数组（旧实现误判为无 MPJ 能力），
        // superTypes + canonicalText 回退应从 raw 全命名识别 MPJ 能力。
        // 注意：必须用全限定名直接 extends（不可用 import，否则 canonicalText 退化为简单名）。
        val svc = addClassFile("demo", "UserService.java", """
            package demo;
            public interface UserService extends com.github.yulichang.base.MPJBaseService {}
        """)
        assertTrue("MPJ 接口不可解析时应通过 superTypes/canonicalText 回退识别 MPJ 能力",
            SqlToJavaConverter.hasMpjInterface(svc, project))
    }
}
