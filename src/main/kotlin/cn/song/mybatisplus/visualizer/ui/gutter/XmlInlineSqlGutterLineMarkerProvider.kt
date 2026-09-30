package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.XmlInlineSqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * 通用 XML 内嵌 SQL 预览图标（O-72 T-XmlInlineSql）：叶子标签文本首词命中 SQL
 * 关键字（CDATA 或普通文本，根元素与标签名不限）→ 该标签行显示预览图标。
 *
 * 作为 mapper / pages / hbm 三套白名单之后的**兜底通道**，认领条件与排除集合
 * 见 [XmlInlineSqlDetector]；总开关 `xmlInlineSqlEnabled`（默认开）。
 *
 * **性能红线**：快速路径只做局部语法判定（叶子判定 + 截断前缀首词），零 resolve、
 * 零全文件遍历、零转换；点击转换走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="XML" .../>`。
 */
class XmlInlineSqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：候选标签的 name token
        val token = element as? XmlToken ?: return null
        if (token.tokenType != XmlTokenType.XML_NAME) return null
        val tag = token.parent as? XmlTag ?: return null
        // F51（T-PerfQuick5）：F19 快路径（xmlInlineSqlEnabled+总闸）与 R2 分组显隐并闸——
        // 方法头单次取 state 后传 (Class, State?) 重载，每到达元素 app 级服务查找 2→1
        // （原形态：gutterFastPathEnabled 内 1 次 + enabledFor(Class, Boolean) 包装内 1 次）。
        // 语义逐值等价：state 不可读承接两道闸原 runCatching 兜底（全放行）；可读时合取式
        // xmlInlineSqlEnabled && showGutterIcon && 组开关 与原两道闸串联逐值相同
        // （GutterGroupsTest testF51_合成对照 真值表在案）。
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && (!gutterState.xmlInlineSqlEnabled || !gutterState.showGutterIcon)) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐
        if (!XmlInlineSqlDetector.isSqlCarrierTag(tag)) return null
        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.xmlInlineSql.tooltip") },
            XmlInlineSqlNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.xmlInlineSql.accessible.name") },
        )
    }

    /** 点击处理：后台解析 + 渲染（HQL 转换失败回退原文直显），结果走统一展示入口。 */
    private object XmlInlineSqlNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val tag = elt.parent as? XmlTag ?: return
            SqlPreviewService.previewAsync(project, {
                XmlInlineSqlPreviewService.preview(project, tag)
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", XmlInlineSqlGutterLineMarkerProvider::class.java)

        /**
         * 选择区内通用 SQL 载体候选（tag 起始 offset 在选区范围内，按 offset 排序）——
         * ConvertToSqlAction 选择区候选交互复用；排除集合保证与 mapper 语句候选不重叠；
         * 纯 PSI 遍历零 resolve，internal 供测试。
         */
        internal fun carriersInRange(file: XmlFile, start: Int, end: Int): List<XmlTag> =
            PsiTreeUtil.collectElementsOfType(file, XmlTag::class.java)
                .filter {
                    it.textRange.startOffset in start until end && XmlInlineSqlDetector.isSqlCarrierTag(it)
                }
                .sortedBy { it.textRange.startOffset }
    }
}
