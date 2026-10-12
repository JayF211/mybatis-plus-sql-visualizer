package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * T-XmlGap W6：live template 的**自定义上下文**——只在 mapper 语句标签内可用。
 *
 * 与竞品的取舍：竞品有 6 个模板集，其中 `table`/`sql`/`ignore` 三个是它的**引导注释体系**（用户手写
 * `<!--@Table-->`/`<!--@sql-->` 喂平台注入）；我方不做校验器、不需要这套注释依赖，**不照搬**
 * （内部调研报告 §2.5 已留痕）。本类只服务两个与注释体系无关的纯骨架模板：`cd`（CDATA 包裹）、
 * `co`（foreach 骨架）。
 *
 * 模板文件在 `resources/templates/mybatis-sql.xml`（平台自动扫描插件 `templates/` 目录），
 * 其中 `<context><option name="MYBATIS_SQL" .../></context>` 的 name 必须等于本类的 [ID]。
 */
class MybatisSqlTemplateContextType : TemplateContextType(ID, "MyBatis SQL") {

    override fun isInContext(file: PsiFile, offset: Int): Boolean {
        val xmlFile = file as? XmlFile ?: return false
        if (xmlFile.rootTag?.name != "mapper") return false
        val safeOffset = offset.coerceIn(0, maxOf(0, file.textLength - 1))
        val element = file.findElementAt(safeOffset) ?: return false
        var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false)
        var depth = 0
        while (tag != null && depth++ < MAX_ANCESTOR_DEPTH) {
            if (tag.name in STATEMENT_TAGS) return true
            tag = tag.parentTag
        }
        return false
    }

    companion object {
        /** 上下文 id（模板 XML 的 `<option name>` 必须与之一致）。 */
        const val ID = "MYBATIS_SQL"
        private val STATEMENT_TAGS = setOf("select", "insert", "update", "delete", "sql")
        private const val MAX_ANCESTOR_DEPTH = 24
    }
}
