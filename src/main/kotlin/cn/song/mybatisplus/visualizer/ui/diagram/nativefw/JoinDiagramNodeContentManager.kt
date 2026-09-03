package cn.song.mybatisplus.visualizer.ui.diagram.nativefw

import cn.song.mybatisplus.visualizer.ui.diagram.ColumnEntry
import com.intellij.diagram.AbstractDiagramElementManager
import com.intellij.diagram.DiagramBuilder
import com.intellij.diagram.DiagramCategory
import com.intellij.diagram.DiagramNodeContentManager
import com.intellij.diagram.presentation.DiagramState
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.ui.SimpleColoredText
import com.intellij.ui.SimpleTextAttributes
import javax.swing.Icon

/**
 * 节点内容管理器——用 [DiagramCategory] 分组（对齐 IDEA Database 原生图）。
 * 直接实现 [DiagramNodeContentManager] 接口，避免 Abstract 的持久化状态导致字段消失。
 */
object JoinDiagramNodeContentManager : DiagramNodeContentManager {

    val COLUMNS = DiagramCategory({ "Columns" }, AllIcons.Nodes.DataColumn)
    val KEY_COLUMNS = DiagramCategory({ "Key columns" }, AllIcons.Nodes.FieldPK)
    val COMMENTS = DiagramCategory({ "Comments" }, AllIcons.Nodes.Tag)

    override fun getContentCategories(): Array<DiagramCategory> = arrayOf(KEY_COLUMNS, COLUMNS, COMMENTS)

    override fun isCategoryEnabled(category: DiagramCategory): Boolean = true

    override fun setCategoryEnabled(category: DiagramCategory, enabled: Boolean) {}

    override fun getEnabledCategories(): Array<DiagramCategory> = getContentCategories()

    override fun isInCategory(
        item: Any?,
        identifyingElement: Any?,
        category: DiagramCategory,
        builder: DiagramBuilder?,
    ): Boolean = when (item) {
        is JoinDiagramItem.Column ->
            if (item.entry.isPk) category == KEY_COLUMNS else category == COLUMNS
        is JoinDiagramItem.Comment -> category == COMMENTS
        else -> false
    }

    override fun onCategoryEnabled(category: DiagramCategory, enabled: Boolean) {}

    override fun isEditCategoriesSettings(): Boolean = false
}

/**
 * 节点内容项（对齐 IDEA Database 原生的分类展示）。
 */
sealed interface JoinDiagramItem {
    data class Column(val entry: ColumnEntry) : JoinDiagramItem
    data class Comment(val text: String) : JoinDiagramItem
}

/**
 * 表节点元素管理器。
 */
class JoinTableElementManager : AbstractDiagramElementManager<JoinDiagramSnapshotRegistry.NativeEntity>() {

    override fun findInDataContext(dataContext: DataContext): JoinDiagramSnapshotRegistry.NativeEntity? = null

    override fun isAcceptableAsNode(o: Any?): Boolean = o is JoinDiagramSnapshotRegistry.NativeEntity

    override fun getNodeItems(e: JoinDiagramSnapshotRegistry.NativeEntity): Array<Any> {
        val items = mutableListOf<Any>()
        for (column in e.columns) {
            items.add(JoinDiagramItem.Column(column))
            column.comment?.takeIf { it.isNotBlank() }?.let {
                items.add(JoinDiagramItem.Comment(it))
            }
        }
        return items.toTypedArray()
    }

    override fun canCollapse(e: JoinDiagramSnapshotRegistry.NativeEntity): Boolean = false

    override fun isContainerFor(
        container: JoinDiagramSnapshotRegistry.NativeEntity,
        inner: JoinDiagramSnapshotRegistry.NativeEntity,
    ): Boolean = false

    override fun getElementTitle(e: JoinDiagramSnapshotRegistry.NativeEntity): String = e.displayName

    override fun getItemName(item: Any?, builder: DiagramBuilder): SimpleColoredText {
        val text = SimpleColoredText()
        when (item) {
            is JoinDiagramItem.Column -> {
                text.append(
                    item.entry.name,
                    if (item.entry.isPk) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                )
                item.entry.type?.let { text.append(" $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
            is JoinDiagramItem.Comment -> text.append(item.text, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
        return text
    }

    override fun getItemType(item: Any?): SimpleColoredText = SimpleColoredText()

    override fun getItemIcon(item: Any?, state: DiagramState): Icon? = when (item) {
        is JoinDiagramItem.Column ->
            if (item.entry.isPk) AllIcons.Nodes.FieldPK else AllIcons.Nodes.DataColumn
        is JoinDiagramItem.Comment -> AllIcons.Nodes.Tag
        else -> null
    }

    override fun getNodeTooltip(e: JoinDiagramSnapshotRegistry.NativeEntity): String =
        if (e.alias != e.tableName) "${e.tableName} AS ${e.alias}" else e.tableName
}
