package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-SearchToggleNativeLook 接线守卫：**日志页搜索框的文本变更必须接到防抖搜索链上**。
 *
 * 背景（2026-10-10 实测）：`67b167f` 把搜索框迁到平台原生 `SearchTextArea` 时漏接了
 * `DocumentListener` —— `onSearchTextChanged()` / `searchDebounceTimer` 成了零调用孤儿，
 * 日志页「键入即过滤」「隐藏搜索条即清过滤」两条真实路径失效（`hideSearch()` 里那句
 * 「非空时触发 DocumentListener 清过滤并刷新」的注释成了空头承诺）。
 *
 * 为什么既有测试全绿也漏掉它：[SqlLogPanelFilterTest] 用 `searchFieldForTest.text = …` +
 * `applySearchTextForTest()` **直调内部入口**，自己代替了「document 变更 → 防抖 → applySearchText」
 * 这一段（headless 下 Swing Timer 不可控，这是该钩子存在的理由）；孤儿私有函数只出 Kotlin
 * `unused` 警告、构建未开 warnings-as-errors。即**过滤算法有覆盖、事件接线零覆盖**——本守卫补的
 * 就是这段接线（同 [cn.song.mybatisplus.visualizer.acp.AcpTransportScopeSourceScanTest] 的
 * 「实现有、接线漏」口径）。
 *
 * 反向验证（2026-10-10）：临时删掉 `nativeSearch.addDocumentListener(` 所在的装配块 → 本用例必红。
 *
 * k2j 移植注（生产为 Java）：扫描根 `src/main/java`（[SqlLogPanel] 承载在
 * `SqlLogPanel.java`）；回调形态 Kotlin 的 `override fun x(e) = …` 单表达式 → Java
 * 的 `public void x(DocumentEvent e)` 方法体 + 体内 `onSearchTextChanged();` 调用，
 * 三回调断言改按方法体包含形态；防抖入口定义断言同步改 Java 形态。
 */
class SqlLogPanelSearchWiringGuardTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    @Test
    fun `日志页搜索框文本变更必须接到防抖搜索链`() {
        val src = File(repoRoot(), "src/main/java/cn/song/mybatisplus/visualizer/log/SqlLogPanel.java").readText()

        // ① 接线本体：搜索框的 document 必须挂上监听。`NativeSearchTextField.addDocumentListener`
        //    是专为兼容 `SearchTextField.addDocumentListener` 暴露的 document 委托。
        assertTrue(
            "搜索框未接 DocumentListener——日志页「键入即过滤」失效（T-SearchToggleNativeLook 迁移回归点）：" +
                "应出现 nativeSearch.addDocumentListener(",
            src.contains("nativeSearch.addDocumentListener("),
        )

        // ② 三个回调都必须落到防抖入口（少一个 = 该类编辑动作不触发过滤）。
        //    Java 匿名 DocumentListener：每个回调方法体内含 `onSearchTextChanged();`。
        listOf("insertUpdate", "removeUpdate", "changedUpdate").forEach { cb ->
            val body = Regex("""public void $cb\(DocumentEvent e\) \{[^}]*\}""").find(src)?.value ?: ""
            assertTrue(
                "搜索框 $cb 回调未接 onSearchTextChanged()——该编辑动作不触发过滤",
                body.contains("onSearchTextChanged();"),
            )
        }

        // ③ 防空绿：定义 + 三处调用 ≥ 4。扫描规则失效（改签名/换写法）时立刻红，而不是静默通过。
        val hits = Regex("""\bonSearchTextChanged\b""").findAll(src).count()
        assertTrue("守卫未扫到 onSearchTextChanged 的接线点——扫描规则已失效（空绿）：$hits", hits >= 4)

        // ④ 接线指向的目标还在（防抖入口与计时器被顺手删掉时，③ 仍可能凑够数）。
        assertTrue("防抖入口定义缺失：private void onSearchTextChanged()", src.contains("private void onSearchTextChanged()"))
        assertTrue("防抖计时器 searchDebounceTimer 缺失", src.contains("searchDebounceTimer"))
    }
}
