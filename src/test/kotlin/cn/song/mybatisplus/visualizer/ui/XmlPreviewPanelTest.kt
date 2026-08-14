package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * SqlPreviewPanel XML 分支测试（T-M24-04）：
 *  - xmlModel 渲染（含紧凑切换）、条件开关勾选重渲染、foreach 次数、bind 覆盖
 *  - 左栏控件构建（条件复选框 / foreach 次数 / bind 覆盖值 / 参数行）
 */
class XmlPreviewPanelTest : BasePlatformTestCase() {

    private val mapper = """
        <mapper namespace="com.example.BizMapper">
          <select id="selectList" resultType="map">
            SELECT * FROM t_biz
            <where>
              <if test="type != null">AND type = #{type}</if>
            </where>
            <if test="ids != null">
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <bind name="likeName" value="'%' + name + '%'"/>
            <if test="name != null">AND name LIKE #{likeName}</if>
          </select>
        </mapper>
    """.trimIndent()

    private fun xmlResult(): PreviewResult {
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        return PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun testXmlPreviewRendersSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        val editor = panel.editorForTest()
        assertNotNull("应挂载编辑器视图", editor)
        assertEquals(
            "SELECT * FROM t_biz WHERE type = ? AND id IN ( ? , ? ) AND name LIKE ?",
            compact(editor!!.document.text),
        )
        panel.clear()
    }

    fun testXmlToggleCompact() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        panel.toggleCompact()
        assertEquals(
            "SELECT * FROM t_biz WHERE type = ? AND id IN ( ? , ? ) AND name LIKE ?",
            panel.editorForTest()!!.document.text,
        )
        panel.clear()
    }

    fun testXmlConditionCheckboxTogglesSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        // 左栏条件复选框（第一个 = type != null，第二个 = ids != null，第三个 = name != null）
        assertNotNull("应构建动态条件复选框", panel.conditionCheckboxForTest(0))
        panel.conditionCheckboxForTest(0)!!.isSelected = false
        panel.conditionCheckboxForTest(0)!!.actionListeners.forEach { it.actionPerformed(null) }
        val sql = compact(panel.editorForTest()!!.document.text)
        assertTrue("关闭 type 条件后 WHERE 消失", sql.startsWith("SELECT * FROM t_biz AND id IN"))
        panel.clear()
    }

    fun testXmlForEachCountField() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        // 把 foreach 外的条件全关，只剩 IN 片段便于断言次数
        panel.selectAllButtonForTest()?.actionListeners?.forEach { it.actionPerformed(null) } // 全不选
        panel.conditionCheckboxForTest(1)?.let { box ->
            box.isSelected = true
            box.actionListeners.forEach { it.actionPerformed(null) }
        }
        val sql1 = compact(panel.editorForTest()!!.document.text)
        assertTrue("默认展开两次：IN ( ? , ? )", sql1.contains("IN ( ? , ? )"))
        panel.clear()
    }

    fun testXmlForEachValuesInputBackfills() {
        // T-M25.4（用户实测：在 foreach 次数框输入 2,3 无效）——集合值输入：逗号分隔自动回填 IN (2, 3)
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        panel.selectNoneButtonForTest()?.actionListeners?.forEach { it.actionPerformed(null) }
        panel.conditionCheckboxForTest(1)?.let { box ->
            box.isSelected = true
            box.actionListeners.forEach { it.actionPerformed(null) }
        }
        // foreach 集合值输入框（tooltip 含"逗号分隔"）
        val valuesField = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
            .firstOrNull { it.toolTipText?.contains("逗号分隔") == true }
        assertNotNull("foreach 应有集合值输入框", valuesField)
        valuesField!!.text = "2,3" // setText 触发 DocumentListener → 回填
        val sql = compact(panel.editorForTest()!!.document.text)
        assertTrue("输入 2,3 应回填为 IN ( 2 , 3 )：$sql", sql.contains("IN ( 2 , 3 )"))
        panel.clear()
    }

    fun testXmlForEachCountZeroDropsInClause() {
        // 全不选（selectNone 按钮）→ 只剩 foreach 条件开启 → 次数 0 → IN 片段消失
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        panel.selectNoneButtonForTest()?.actionListeners?.forEach { it.actionPerformed(null) }
        panel.conditionCheckboxForTest(1)?.let { box ->
            box.isSelected = true
            box.actionListeners.forEach { it.actionPerformed(null) }
        }
        val sql1 = compact(panel.editorForTest()!!.document.text)
        assertTrue("默认展开两次：IN ( ? , ? )", sql1.contains("IN ( ? , ? )"))
        panel.clear()
    }

    fun testXmlRenderErrorsShownInMeta() {
        // bind 表达式解析失败 → 渲染错误进元信息警示（编辑器仍正常显示 SQL）
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="bad" value="not valid @@ expr"/>
                SELECT * FROM t WHERE a = #{a}
              </select>
            </mapper>
        """.trimIndent())
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        val result = PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(result)
        assertNotNull("编辑器应正常显示 SQL", panel.editorForTest())
        assertTrue("渲染错误应显示在元信息警示", panel.metaLabelForTest().contains("⚠"))
        panel.clear()
    }

    fun testErrorCopyButtonVisibleWithWarning() {
        // T-M25.3（用户实测：错误提示难以复制）——有警示时工具栏出现复制错误按钮
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="bad" value="not valid @@ expr"/>
                SELECT * FROM t
              </select>
            </mapper>
        """.trimIndent())
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        val result = PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql,
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(result)
        val button = panel.errorCopyButtonForTest()
        assertNotNull("错误复制按钮应存在", button)
        assertTrue("有警示时按钮应可见", button!!.isVisible)
        assertTrue("复制错误应成功", panel.copyError())
        panel.clear()
        assertFalse("清空后按钮应隐藏", panel.errorCopyButtonForTest()?.isVisible ?: true)
    }

    fun testErrorCopyButtonHiddenWithoutWarning() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult()) // 无警告的预览
        val button = panel.errorCopyButtonForTest()
        assertNotNull(button)
        assertFalse("无警示时按钮应隐藏", button!!.isVisible)
        assertFalse("无错误时复制返回 false", panel.copyError())
        panel.clear()
    }

    fun testForEachParamInputBackfillsSql() {
        // T-M25.4（用户实测：list 参数输入后右侧仍是 ?）——foreach 展开项的 __frch_ 参数
        // 之前被过滤无输入框；现在提供输入框（标签可读化 id[0]/id[1]）并回填 SQL
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        // 参数行标签可读化：__frch_id_0 → id[0]
        val labels = (0 until 8).mapNotNull { panel.paramRowLabelForTest(it)?.text }
        assertTrue("foreach 参数行应显示可读标签 id[0]", labels.any { it.contains("id[0]") })
        // 找到 __frch_id_0 的输入框并输入值（paramFields 顺序：条件开关无框 + foreach 次数 + bind 覆盖 + 参数行）
        val fieldIndex = paramFieldsIndexOf(panel, "__frch_id_0")
        assertTrue("foreach 参数应有输入框", fieldIndex >= 0)
        val field = panel.paramFieldForTest(fieldIndex)!!
        field.text = "1" // setText 触发 DocumentListener → onParamChanged → 重渲染回填
        // 回填：( 1 , ? )——第一个 ? 被值替换（第二个未填保持 ?；IN 与 ( 之间保留换行/段间空格）
        assertTrue(panel.editorForTest()!!.document.text.contains("( 1 , ?"))
        panel.clear()
    }

    /** 按 tooltip（#原始 source）定位参数输入框索引。 */
    private fun paramFieldsIndexOf(panel: SqlPreviewPanel, source: String): Int {
        var index = 0
        while (true) {
            val field = panel.paramFieldForTest(index) ?: return -1
            if (field.toolTipText == "#$source") return index
            index++
        }
    }
}
