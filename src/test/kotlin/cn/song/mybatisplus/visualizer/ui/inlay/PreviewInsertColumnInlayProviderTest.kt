package cn.song.mybatisplus.visualizer.ui.inlay

import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import com.intellij.codeInsight.hints.CollectorWithSettings
import com.intellij.codeInsight.hints.InlayDumpUtil
import com.intellij.codeInsight.hints.InlayHintsSinkImpl
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * PreviewInsertColumnInlayProvider 作用域/闸门测试（T-InsertColumnInlay O-44b）。
 *
 * **本任务的硬判据**（计划 §四 验收 1）：用户自己的 `.sql` 文件**不得**出现本插件的 inlay。
 * inlay 的 EP 是 language 级的（`com.intellij.codeInsight.inlayProvider` + `language="SQL"`），
 * 注册即对 IDE 内所有 SQL 编辑器生效，故作用域判定是 provider 的第一道闸门，也是本文件的主体。
 *
 * 判据形态取「**有无 collector**」：provider 返回 null ⇒ 平台侧零 inlay 条目（不是「产出了再过滤」）。
 * 正向判据取**真实渲染链**：`CollectorWithSettings.collectTraversingAndApply` 把提示投进真实
 * `InlayModel`，再由 `InlayDumpUtil` 读回文本（平台自家 `InlayHintsProviderTestCase` 同款链路）——
 * 即「提示确实落到编辑器上」，而非只断言内部数据。
 *
 * 覆盖矩阵（对应计划 §四 验收 1~4 与 §三 T-ICI-04 用例 ①②③④）：
 *  1. 主路径预览文件（`mpsql-preview.sql`）+ userData + INSERT → 出提示（端到端渲染）；
 *  2. 二级内存 PSI 文件名（`mpsql-preview-memory.sql`）→ 同样出提示（锚点覆盖降级链第二级）；
 *  3. **用户文件**（普通名 / 测试缝名 `preview.sql`）→ 零提示（含「人为挂上 userData」的最强形态：
 *     文件名锚点单独即可拦下）；反向对照 = 同一条 SQL 换个文件名就从有提示变无提示；
 *  4. 预览文件名但**缺 userData** → 零提示（双锚点缺一不可）；
 *  5. `kind != INSERT`（SELECT/UPDATE/DELETE）与无语句类型载体（非 XML 预览链）→ 零提示。
 */
class PreviewInsertColumnInlayProviderTest : BasePlatformTestCase() {

    private val provider = PreviewInsertColumnInlayProvider()

    private val insertSql = "INSERT INTO user (id, name) VALUES (1, 'admin')"

    /**
     * 走真实渲染链取提示文本；无 collector（作用域/类型闸门拦下）返回 null。
     *
     * @param attachKind 是否把语句类型写到 Editor 与 PSI 文件双载体（模拟面板
     *   `SqlPreviewPanel.attachPreviewStatementKind` 的写入）；false = 模拟「非本面板编辑器」
     */
    private fun renderHints(fileName: String, sql: String, kind: XmlStatementKind?, attachKind: Boolean = true): String? {
        myFixture.configureByText(fileName, sql)
        val file = myFixture.file
        val editor = myFixture.editor
        if (attachKind) {
            PreviewInlayScope.attach(editor, kind)
            PreviewInlayScope.attach(file, kind)
        }
        val sink = InlayHintsSinkImpl(editor)
        val collector = provider.getCollectorFor(file, editor, NoSettings(), sink) ?: return null
        CollectorWithSettings(collector, provider.key, file.language, sink).collectTraversingAndApply(editor, file, true)
        return InlayDumpUtil.dumpHintsInternal(
            sql,
            file = file,
            editor = editor,
            document = myFixture.getDocument(file),
        )
    }

    // ------------------------------------------------------------------
    // 正向：预览文件出提示（验收 2）
    // ------------------------------------------------------------------

    fun test主路径预览文件INSERT出目标列提示() {
        val dumped = renderHints(PreviewInlayScope.PREVIEW_FILE_NAME, insertSql, XmlStatementKind.INSERT)
        assertEquals(
            "预览文件上的 INSERT 应逐值表达式挂目标列名（inlay 文本 = 列名）",
            "INSERT INTO user (id, name) VALUES (/*<# id #>*/1, /*<# name #>*/'admin')",
            dumped,
        )
    }

    fun test二级内存PSI文件名同样出提示() {
        val dumped = renderHints(PreviewInlayScope.MEMORY_PREVIEW_FILE_NAME, insertSql, XmlStatementKind.INSERT)
        assertEquals(
            "降级链第二级（内存 PSI，mpsql-preview-memory.sql）锚点同样命中——" +
                "保留名漏掉它即该级 inlay 静默不出现",
            "INSERT INTO user (id, name) VALUES (/*<# id #>*/1, /*<# name #>*/'admin')",
            dumped,
        )
    }

    fun test投影自带同名列别名时不重复提示() {
        val dumped = renderHints(
            PreviewInlayScope.PREVIEW_FILE_NAME,
            "INSERT INTO user (id, name) SELECT x AS id, y FROM s",
            XmlStatementKind.INSERT,
        )
        assertEquals(
            "验收 3：`x AS id` 已自带同名列别名 id → 该投影不挂提示；`y` 照常挂 name",
            "INSERT INTO user (id, name) SELECT x AS id, /*<# name #>*/y FROM s",
            dumped,
        )
    }

    // ------------------------------------------------------------------
    // 硬判据：用户自己的 .sql 文件零提示（验收 1）
    // ------------------------------------------------------------------

    fun test用户自己的SQL文件不产提示() {
        assertNull(
            "用户自己的 .sql 文件（文件名非本插件保留名）不得出现本插件 inlay——" +
                "EP 是 language 级，这是本任务唯一的真实风险",
            renderHints("UserMapper.sql", insertSql, XmlStatementKind.INSERT),
        )
    }

    fun test用户文件即使被挂上语句类型载体也不产提示() {
        // 最强形态：userData 锚点被人为满足，仅靠文件名锚点拦下——两个锚点是合取关系
        assertNull(
            "文件名锚点须独立成立：用户文件即便带本插件 userData 也不得产提示",
            renderHints("UserMapper.sql", insertSql, XmlStatementKind.INSERT, attachKind = true),
        )
    }

    fun test测试缝文件名preview_sql不产提示() {
        // 测试缝文件名（SqlPreviewPanel.createPreviewFileForTest）**有意不列入保留名**：
        // 它是最像用户文件的普通名，列入即把「用户自己的 .sql 文件」判据打穿
        assertNull(
            "`preview.sql` 是最像用户文件的普通名，不得视为预览文件",
            renderHints("preview.sql", insertSql, XmlStatementKind.INSERT),
        )
    }

    fun test反向对照_同一条SQL仅换文件名即从有提示变无提示() {
        val preview = renderHints(PreviewInlayScope.PREVIEW_FILE_NAME, insertSql, XmlStatementKind.INSERT)
        val userFile = renderHints("UserMapper.sql", insertSql, XmlStatementKind.INSERT)
        assertNotNull("对照组：预览文件名下应有提示", preview)
        assertNull("对照组：用户文件名下应零提示（差别只来自文件名锚点，与 SQL 无关）", userFile)
        assertEquals(
            "两个文件名跑的是同一条 SQL",
            insertSql,
            "INSERT INTO user (id, name) VALUES (1, 'admin')",
        )
    }

    fun test预览文件名但缺userData时不产提示() {
        assertNull(
            "双锚点缺一不可：文件名命中但无语句类型载体（userData）→ 不产提示",
            renderHints(PreviewInlayScope.PREVIEW_FILE_NAME, insertSql, XmlStatementKind.INSERT, attachKind = false),
        )
    }

    fun test预览文件名但载体值为null时不产提示() {
        assertNull(
            "非 XML 预览链写 null（xmlModel 为 null）→ 无语句类型语义 → 不产提示",
            renderHints(PreviewInlayScope.PREVIEW_FILE_NAME, insertSql, null),
        )
    }

    // ------------------------------------------------------------------
    // 语句类型闸门（验收 2）
    // ------------------------------------------------------------------

    fun test语句类型非INSERT时不产提示() {
        for (kind in listOf(XmlStatementKind.SELECT, XmlStatementKind.UPDATE, XmlStatementKind.DELETE)) {
            assertNull(
                "kind=$kind 不得产 INSERT 目标列提示（即便文本形态是 INSERT）",
                renderHints(PreviewInlayScope.PREVIEW_FILE_NAME, insertSql, kind),
            )
        }
    }

    fun test语句类型为INSERT但文本非INSERT时零提示() {
        val dumped = renderHints(PreviewInlayScope.PREVIEW_FILE_NAME, "SELECT id, name FROM user", XmlStatementKind.INSERT)
        assertEquals(
            "kind=INSERT 而文本无 INSERT 语句 → 提示条数为零（规划器零命中）",
            "SELECT id, name FROM user",
            dumped,
        )
    }

    // ------------------------------------------------------------------
    // 纯显示层（验收 5）：inlay 不改文档文本
    // ------------------------------------------------------------------

    fun test产提示不改动文档文本() {
        renderHints(PreviewInlayScope.PREVIEW_FILE_NAME, insertSql, XmlStatementKind.INSERT)
        assertEquals("inlay 是显示层：投提示后文档文本逐字节不变", insertSql, myFixture.editor.document.text)
    }

    fun test双锚点判定函数直接读数() {        myFixture.configureByText(PreviewInlayScope.PREVIEW_FILE_NAME, insertSql)
        val file = myFixture.file
        val editor = myFixture.editor
        assertNull("无载体 → null", PreviewInlayScope.previewStatementKind(file, editor))
        PreviewInlayScope.attach(editor, XmlStatementKind.INSERT)
        assertEquals("Editor 载体命中", XmlStatementKind.INSERT, PreviewInlayScope.previewStatementKind(file, editor))
        // Editor 载体清除后回落 PSI 文件载体（Editor 优先、文件兜底）
        PreviewInlayScope.attach(editor, null)
        assertNull("Editor 载体清除且文件无载体 → null", PreviewInlayScope.previewStatementKind(file, editor))
        PreviewInlayScope.attach(file, XmlStatementKind.UPDATE)
        assertEquals("文件载体兜底命中", XmlStatementKind.UPDATE, PreviewInlayScope.previewStatementKind(file, editor))
        assertNull("文件名为用户文件时判定直接为 null（不读载体）", PreviewInlayScope.previewStatementKind(null, editor))
    }
}
