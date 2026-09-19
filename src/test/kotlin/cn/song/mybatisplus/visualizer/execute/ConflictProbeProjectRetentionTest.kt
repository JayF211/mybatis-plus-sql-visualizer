package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.project.Project
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * T-PS5-08：`conflictProbeProjects` 弱引用可达性测试（普通 JUnit，项目用恒等语义代理桩）。
 *
 * 缺陷背景（第八轮报告发现 9）：F6 冲突探针的「已安装项目集」原为**强引用** ConcurrentHashMap
 * 键集，把 Project 对象钉到显式 `remove`（走项目关闭 Disposer，但 `hookProjectClose` 在
 * `hookProjectsClose` 已登记时提前 return、不再登记第二次项目级 Disposer → 存在不可回收窗口）。
 * 改弱键集后：项目唯一强引用消失即可回收，去重语义不变。
 *
 * GC 宽松写法（防 flake）：多轮 `System.gc()` + 分配压力，仍存活才判失败。
 */
class ConflictProbeProjectRetentionTest {

    @After
    fun cleanup() {
        DataSourceSelector.conflictProbeProjectsForTest().clear()
    }

    /** 恒等语义项目桩（WeakHashMap 键语义依赖 equals/hashCode 按引用）。 */
    private fun fakeProject(): Project {
        lateinit var proxy: Project
        proxy = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { p, method, args ->
            when (method.name) {
                "equals" -> args?.isNotEmpty() == true && args[0] === p
                "hashCode" -> System.identityHashCode(p)
                "toString" -> "FakeProject"
                else -> null
            }
        } as Project
        return proxy
    }

    /**
     * 登记一个项目桩并只返回弱引用——强引用留在本函数栈帧内，返回后即可回收
     * （调用方栈帧不得持有，否则 GC 断言必然失败）。
     */
    private fun registerFakeProject(): java.lang.ref.WeakReference<Project> {
        val p = fakeProject()
        val set = DataSourceSelector.conflictProbeProjectsForTest()
        set.add(p)
        assertTrue("登记后集合内可见（去重语义不变）", set.contains(p))
        assertFalse("重复登记不新增条目", set.add(p))
        return java.lang.ref.WeakReference(p)
    }

    @Test
    fun `项目强引用消失后可回收`() {
        val ref = registerFakeProject()
        // 宽松 GC：多轮 gc + 分配压力（弱键集合不得阻止项目回收）
        repeat(10) {
            if (ref.get() == null) return
            System.gc()
            Thread.sleep(20)
            allocatePressure()
        }
        if (ref.get() != null) {
            System.gc()
            Thread.sleep(50)
        }
        assertTrue("弱键集不阻止项目回收（强引用消失 + GC 后应被回收）", ref.get() == null)
    }

    private fun allocatePressure() {
        // 约 4MB 短命对象，制造回收压力（勿保留引用）
        var sink = 0
        val blocks = ArrayList<ByteArray>()
        repeat(4) {
            val b = ByteArray(1 shl 20)
            sink += b.size
            blocks += b
        }
        if (sink < 0) println(blocks.size) // 防优化消除（不可达分支）
    }
}
