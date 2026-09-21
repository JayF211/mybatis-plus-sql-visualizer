// T-SyncK2JMaster115 自 master src/test/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelPrewarmConsumeTest.kt 移植
package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * T-PreviewDocLock2 F3 回归守护：预热产物「就绪 → 消费」链的分支走向。
 *
 * 缺陷形态（commit `11e2671f` 重构引入，真机表现为预览对话框恒带降级横幅、补全/注释载体退化）：
 * 发布侧把 `vf to psi` 写了**两遍**（`prewarmedTempFile = vf to psi` 与 `await.complete(vf to psi)`
 * 各造一个 Pair 实例），而消费侧用**引用相等** `prewarmedTempFile === prewarmed` 判「产物尚未被
 * 消费」——两个实例永不相等，判定恒假，于是无论预热成功与否都落
 * [SqlPreviewPanel] 的 `fallbackPreviewDocument`（内存 PSI + DEGRADED 横幅）。
 *
 * 本测试以**真实消费链**锁死该不变式（不做纯函数抽取——恒假判定出在「生产两个实例」这一侧，
 * 纯谓词测试无法复现）：
 *  - [test预热产物就绪_交付值与字段同实例]：发布侧不变式（字段 ≡ await 交付值）；
 *  - [test预热产物可用_走临时文件分支不落内存PSI]：编辑器文档落在项目内临时文件上 + 降级横幅不挂起
 *    （内存 PSI 降级产物无 VFS 文件，二者可判然区分）；
 *  - [test产物已消费_二次装配降级内存PSI]：反证分支断言可判别 + 锁定「消费一次即作废」语义。
 *    **本用例锁的是「已知降级」而非期望行为**（复核 20260920）：`releaseEditor()` 作废产物却不重臂
 *    `prewarmAwait`，故控制台 attach 后又 detach 时，同一面板的二次装配会走这条降级链——详见
 *    `docs/验收文档/20260920-待分析问题清单.md` P9（重臂修法会与 `clear()` 的既有重臂双开竞争
 *    `previewTempFile`，故留待专门立项）。
 *
 * headless 前提：预热跑在池线程（无 EDT 泵），故用例以有界轮询等 [PrewarmAwait] 出结果再驱动消费；
 * 预热在 light fixture 下必须成功，失败即环境异常（断言带说明，不做静默跳过——静默跳过会让本用例
 * 在缺陷回归时假绿）。
 */
class SqlPreviewPanelPrewarmConsumeTest : BasePlatformTestCase() {

    // K2J 互操作：master 侧 PreviewResult 的具名实参 + 缺省值 → k2j Java 7 参便捷构造器位置实参。
    private fun result() = PreviewResult(
        "SELECT id\nFROM user\nWHERE username = ?",
        "SELECT id FROM user WHERE username = ?",
        listOf("username"),
        null,
        WrapperType.LAMBDA_QUERY,
        "user",
        listOf("id", "username"),
    )

    /**
     * 有界等待预热线程出结果（成功或失败）。返回产物：null = 预热失败/放弃。
     * 预算 60s：light fixture 下预热为毫秒级，但**每个 JVM 的首个面板**要付冷启动代价
     * （插件类加载 + `SqlDialectMappings` 服务首次创建 + VFS refresh/PSI 首次解析；本机实测 6–10s），
     * 留足余量排除慢机/并行构建误判。
     *
     * **等待期间必须持续抽干 EDT 队列**：预热线程的方言预映射（`DialectPreMapping` 两段式）把
     * `setMapping` 经 `invokeLater` 派给 EDT 并有界等待，纯 sleep 会把这 2s 预算整个烧掉、
     * 还会让后续任何需要 EDT 的步骤排队到超时（实测：纯 sleep 等待会撞满预算，边等边抽队列即稳过）。
     */
    private fun awaitPrewarmProduct(panel: SqlPreviewPanel): Pair<com.intellij.openapi.vfs.VirtualFile, com.intellij.psi.PsiFile>? {
        val await = panel.prewarmAwaitForTest()
        assertNotNull("面板构造期应已发起预热", await)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && !await!!.isDone()) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("预热应在预算内出结果（成功或失败）", await!!.isDone())
        return await.joinOrNull()
    }

    private fun editorDocumentFile(panel: SqlPreviewPanel): com.intellij.openapi.vfs.VirtualFile? {
        val doc = panel.editorForTest()?.document
        assertNotNull("应已创建预览编辑器", doc)
        return FileDocumentManager.getInstance().getFile(doc!!)
    }

    // ------------------------------------------------------------------
    // 一、发布侧不变式
    // ------------------------------------------------------------------

    fun test预热产物就绪_交付值与字段同实例() {
        val panel = SqlPreviewPanel(project)
        try {
            val product = awaitPrewarmProduct(panel)
            assertNotNull(
                "headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）",
                product,
            )
            assertSame(
                "发布侧不变式：prewarmedTempFile 与 await 交付值必须是同一 Pair 实例" +
                    "（写两遍 `vf to psi` 会让消费侧引用相等判定恒假）",
                product,
                panel.prewarmedTempFileForTest(),
            )
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // 二、主路径：产物可用 → 临时文件分支
    // ------------------------------------------------------------------

    fun test预热产物可用_走临时文件分支不落内存PSI() {
        val panel = SqlPreviewPanel(project)
        try {
            assertNotNull(
                "headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）",
                awaitPrewarmProduct(panel),
            )

            panel.showResult(result())
            UIUtil.dispatchAllInvocationEvents()

            val file = editorDocumentFile(panel)
            assertNotNull(
                "预热产物可用 → 编辑器文档应挂在项目内临时文件上（临时文件分支）；" +
                    "为 null 即落回内存 PSI/裸文档降级分支",
                file,
            )
            assertTrue(
                "临时文件应位于项目预览目录内，实际：${file!!.path}",
                file.path.contains("sql-visualizer"),
            )
            assertNotNull("主路径 psiFile 应带真实 VFS 文件（内存 PSI 降级产物无 VFS 文件）", panel.psiFileForTest()?.virtualFile)
            assertNotEquals(
                "主路径成功应清除降级横幅（DEGRADED 仅在降级分支挂起）",
                DegradationBanner.Kind.DEGRADED,
                panel.degradationBannerForTest().kindForTest(),
            )
            assertNull("产物被消费后字段应置 null（一次性语义）", panel.prewarmedTempFileForTest())
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // 三、反证：产物已被消费 → 降级分支（同时锁定一次性语义）
    // ------------------------------------------------------------------

    fun test产物已消费_二次装配降级内存PSI() {
        val panel = SqlPreviewPanel(project)
        try {
            assertNotNull(
                "headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）",
                awaitPrewarmProduct(panel),
            )
            panel.showResult(result())
            UIUtil.dispatchAllInvocationEvents()
            assertNotNull("首次装配应走临时文件分支", editorDocumentFile(panel))

            // 释放编辑器但**不**重新预热（clear 会重预热）：产物字段已被消费置 null，
            // 而 prewarmAwait 仍是同一份已完成产物 —— 二次装配必须判定「产物不可用」而降级。
            panel.releaseEditorForTest()
            assertNull("释放后产物字段应保持 null", panel.prewarmedTempFileForTest())

            panel.showResult(result())
            UIUtil.dispatchAllInvocationEvents()

            assertNull(
                "已消费产物不得被二次复用（一次性语义）——二次装配文档应无 VFS 文件（内存 PSI/裸文档）",
                editorDocumentFile(panel),
            )
            assertNotNull("降级分支应保留内存 PSI 补全载体", panel.psiFileForTest())
        } finally {
            panel.clear()
        }
    }
}
