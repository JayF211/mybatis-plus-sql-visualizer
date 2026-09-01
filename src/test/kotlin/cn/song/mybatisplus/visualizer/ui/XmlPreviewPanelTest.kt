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
import java.awt.Toolkit
import java.awt.datatransfer.Transferable

/**
 * SqlPreviewPanel XML 分支测试（T-M24-04）：
 *  - xmlModel 渲染（含紧凑切换）、条件开关勾选重渲染、foreach 次数、bind 求值回显
 *  - 左栏控件构建（条件复选框 / foreach 次数 / 参数行；T-M26.4：bind 分区已删除，
 *    bind 值由「参数」区唯一承担——求值结果回显、输入覆盖）
 */
class XmlPreviewPanelTest : BasePlatformTestCase() {

    // 测试前快照系统剪贴板，tearDown 恢复，避免 copyError 污染用户剪贴板
    private var clipboardSnapshot: Transferable? = null

    // T-M27.4：渲染文本断言类测试关闭自动格式化（格式化行为由 SqlFormatterTest +
    // SqlPreviewPanelTest.testRenderAutoFormatsSql 专项覆盖；此处模拟"关闭格式化"场景）
    override fun setUp() {
        super.setUp()
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = false }
        clipboardSnapshot = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
    }

    override fun tearDown() {
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true }
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(clipboardSnapshot, null) }
        super.tearDown()
    }

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
        return PreviewResult(rendered.sql, rendered.sql.replace(Regex("\\s+"), " ").trim(), rendered.mappings.map { it.property }, ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }), null, null, null, emptyList(), emptyMap(), null, null, emptyList(), emptyList(), null, model, emptyMap(), null, null, null, null)
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun testXmlPreviewRendersSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        val editor = panel.editorForTest()
        assertNotNull("应挂载编辑器视图", editor)
        assertEquals(
            "SELECT * FROM t_biz WHERE type = ? AND id IN ( ? , ? ) AND name LIKE '%1%'",
            compact(editor!!.document.text),
        )
        panel.clear()
    }

    fun testXmlToggleCompact() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        panel.toggleCompact()
        // T-M25.5：bind 自动求值回填（'%' + name + '%'，mock name="1" → '%1%'）
        assertEquals(
            "SELECT * FROM t_biz WHERE type = ? AND id IN ( ? , ? ) AND name LIKE '%1%'",
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
        // T-M29.4：foreach 展开项默认引号（`IN ( '2' , '3' )`，数值隐式转换语义不变）
        assertTrue("输入 2,3 应回填为 IN ( '2' , '3' )：$sql", sql.contains("IN ( '2' , '3' )"))
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

    fun testXmlBindValuesOnlyInParamSection() {
        // T-M26.4（用户反馈：bind 变量区与参数区重复展示同一组值）——bind 分区已删除：
        // bind 求值结果仅在「参数」区显示（唯一入口：输入覆盖、清空恢复自动值）
        val panel = SqlPreviewPanel(project)
        panel.showResult(xmlResult())
        // bind 名在参数区有输入行（求值结果回显：'%' + name + '%'，mock name="1" → "%1%"）
        val bindRow = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
            .firstOrNull { it.toolTipText == "#likeName" }
        assertNotNull("bind 变量应在参数区有输入行", bindRow)
        assertEquals("bind 求值结果应回显到参数行", "%1%", bindRow!!.text)
        // 参数输入覆盖 bind 值 → SQL 回填覆盖值（等价于原 bind 覆盖输入框）
        bindRow.text = "xx"
        assertTrue(
            "参数输入应覆盖 bind 值：" + compact(panel.editorForTest()!!.document.text),
            compact(panel.editorForTest()!!.document.text).contains("name LIKE 'xx'"),
        )
        panel.clear()
    }

    fun testXmlLikeParamQuotedForPureNumericInput() {
        // T-M26.5（用户反馈：模糊查询字段类型推断不正确）——XML 渲染 SQL 的 LIKE 参数
        // 回填恒加引号：String 列（如 deviceCode）的纯数字输入不再被数字启发式误判为数字
        // （#keyword="111" → LIKE CONCAT('%', '111', '%')）
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                SELECT * FROM t WHERE code LIKE CONCAT('%', #{keyword}, '%')
              </select>
            </mapper>
        """.trimIndent())
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        val result = PreviewResult(rendered.sql, rendered.sql, rendered.mappings.map { it.property }, ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }), null, null, null, emptyList(), emptyMap(), null, null, emptyList(), emptyList(), null, model, emptyMap(), null, null, null, null)
        val panel = SqlPreviewPanel(project)
        panel.showResult(result)
        val keywordField = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
            .firstOrNull { it.toolTipText == "#keyword" }
        assertNotNull("应有关键词参数输入行", keywordField)
        keywordField!!.text = "111"
        assertTrue(
            "LIKE 参数回填应带引号：" + compact(panel.editorForTest()!!.document.text),
            compact(panel.editorForTest()!!.document.text).contains("LIKE CONCAT('%', '111', '%')"),
        )
        panel.clear()
    }

    fun testXmlParamQuotedByDefault() {
        // T-M29.4（用户实测：Map 入参无列类型信息）——XML 参数回填默认带引号：
        // `settlement_period >= #{p.settlementStartDate}` 输入 2025 → `>= '2025'`（LocalDate 年份输入
        // 不再被数字启发式裸渲染）；数值列（dic_credit_status）输入 2 → `= '2'`（隐式转换语义不变）
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                SELECT * FROM t
                <where>
                    t.deleted = 0
                    <if test="p.settlementStartDate != null">
                        AND t.settlement_period &gt;= #{p.settlementStartDate}
                    </if>
                    <if test="p.dicCreditStatus != null">
                        AND t.dic_credit_status = #{p.dicCreditStatus}
                    </if>
                </where>
              </select>
            </mapper>
        """.trimIndent())
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = model.renderSql()
        val result = PreviewResult(rendered.sql, rendered.sql, rendered.mappings.map { it.property }, ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }), null, null, null, emptyList(), emptyMap(), null, null, emptyList(), emptyList(), null, model, emptyMap(), null, null, null, null)
        val panel = SqlPreviewPanel(project)
        panel.showResult(result)
        val fields = (0 until 20).mapNotNull { panel.paramFieldForTest(it) }
        val dateField = fields.firstOrNull { it.toolTipText == "#p.settlementStartDate" }
        val statusField = fields.firstOrNull { it.toolTipText == "#p.dicCreditStatus" }
        assertNotNull("应有日期参数输入行", dateField)
        assertNotNull("应有状态参数输入行", statusField)
        dateField!!.text = "2025"
        statusField!!.text = "2"
        val sql = compact(panel.editorForTest()!!.document.text)
        assertTrue("日期参数应带引号：$sql", sql.contains("t.settlement_period >= '2025'"))
        assertTrue("数值参数默认也带引号（隐式转换等价）：$sql", sql.contains("t.dic_credit_status = '2'"))
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
        val result = PreviewResult(rendered.sql, rendered.sql.replace(Regex("\\s+"), " ").trim(), rendered.mappings.map { it.property }, ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }), null, null, null, emptyList(), emptyMap(), null, null, emptyList(), emptyList(), null, model, emptyMap(), null, null, null, null)
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
        val result = PreviewResult(rendered.sql, rendered.sql, emptyList(), null, null, null, null, emptyList(), emptyMap(), null, null, emptyList(), emptyList(), null, model, emptyMap(), null, null, null, null)
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

}
