package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db

import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import com.intellij.database.model.DasObject
import com.intellij.database.model.DasTable
import com.intellij.database.model.RawDataSource
import com.intellij.database.psi.DbElement
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.psi.DataSourceManager
import com.intellij.diagram.DiagramBuilder
import com.intellij.diagram.DiagramBuilderFactory
import com.intellij.diagram.DiagramDataKeys
import com.intellij.diagram.DiagramProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBPanel
import com.intellij.uml.UmlVirtualFileSystem
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * T-Embed-03/04：Database 原生「显示图」嵌入门面（B 路径）。
 *
 * 与 [cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramSupport]（A 路径，
 * 自实现 SqlJoinDiagramProvider）的差异：本路径完全复用 Database 插件自己的图——
 * 节点=真实 DbTable（注释/类型/主键分区/图例全原生），JOIN 连接线经
 * [JoinVirtualFkProvider]（modelRelationProvider EP 虚拟外键）注入。
 *
 * 降级契约：无数据源 / 任一表定位失败 / Database 或 Diagrams 插件缺席 / 框架异常
 * → [buildTabComponent] 返回 null，调用方回落 A 路径（自实现 Provider）→ 自绘。
 *
 * 隔离边界：本文件 import com.intellij.database.* / com.intellij.uml.*，仅在
 * Ultimate（两插件齐备）被调用方触发加载；所有公开方法包 try/catch(Throwable)
 * （含 NoClassDefFoundError——Community 下类解析失败被吞掉，与 A 路径门面同语义）。
 */
object NativeDatabaseDiagramSupport {

    /** Database 图 Provider id（DbDiagramProvider.getID() 字节码实证）。 */
    private const val DATABASE_PROVIDER_ID = "DATABASE"

    /** clientProperty key：组件 → 内部 FileEditor（dispose 用，与 A 路径 key 互不冲突）。 */
    private const val NATIVE_EDITOR_KEY = "mpsql.native.database.editor"

    /**
     * 为 JOIN 模型构建 Database 原生图嵌入组件。
     *
     * @return 原生组件；任一前置条件不满足或框架异常返回 null（调用方降级）
     */
    fun buildTabComponent(
        project: Project?,
        model: DiagramModel,
        title: String,
        parent: Disposable,
    ): JComponent? {
        if (project == null || model.isEmpty) return null
        return try {
            if (model.entities.any { it.tableName.isBlank() }) return null
            val tableNames = model.entities.map { it.tableName }
            val located = ReadAction.compute<Map<String, DbElement>, Throwable> {
                locateDbTables(project, tableNames)
            }
            if (located == null || located.size < tableNames.size) return null
            registerJoinForeignKeys(model, located)

            val mainElement = located[model.entities.first { it.isMain }.tableName]
                ?: located.values.first()
            val editor = buildEmbeddableEditor(project, mainElement, title)
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(editor.component, BorderLayout.CENTER)
                Disposer.register(parent, editor)
                putClientProperty(NATIVE_EDITOR_KEY, editor)
            }
        } catch (t: Throwable) {
            JoinFkRegistry.clear()
            null
        }
    }

    /** 释放嵌入的 Database 图编辑器（换图/清空调用），并回收虚拟外键登记。 */
    fun disposeNativeEditor(component: JComponent?) {
        try {
            val editor = component?.getClientProperty(NATIVE_EDITOR_KEY) as? Disposable
            editor?.let { Disposer.dispose(it) }
        } catch (t: Throwable) {
            // 释放失败不阻断降级链
        } finally {
            JoinFkRegistry.clear()
        }
    }

    /** 回收 JOIN 虚拟外键登记（打开失败 / 独立路径）。 */
    fun clearRegisteredFk() = JoinFkRegistry.clear()

    /**
     * 把模型里的 ON 等值列配对登记为虚拟外键。
     * 方向：JOIN 表 = FK child（columns），主表 = ref（refColumns）——与 MPJ 外键语义一致。
     */
    private fun registerJoinForeignKeys(model: DiagramModel, located: Map<String, DbElement>) {
        JoinFkRegistry.clear()
        for (rel in model.relationships) {
            val mainTable = model.entities.firstOrNull { it.alias == rel.fromAlias }?.tableName ?: continue
            val joinTable = model.entities.firstOrNull { it.alias == rel.toAlias }?.tableName ?: continue
            if (located[mainTable] == null || located[joinTable] == null) continue
            for ((mainColumn, joinColumn) in rel.columnPairs) {
                JoinFkRegistry.put(joinTable, mainTable, joinColumn, mainColumn)
            }
        }
    }

    /**
     * 按表名在全部数据源中定位 [DbElement]（遍历 DasModel → DasTable 名称匹配 → DbPsiFacade）。
     * 任一表未找到时该表缺值；调用方以「全部命中」为 B 路径前置条件。异常返回 null。
     */
    private fun locateDbTables(project: Project, tableNames: List<String>): Map<String, DbElement>? {
        return try {
            val wanted = tableNames.map { it.trim().lowercase() }.toSet()
            val found = mutableMapOf<String, DbElement>()
            val facade = DbPsiFacade.getInstance(project)
            for (manager in DataSourceManager.getManagers(project)) {
                if (found.size == wanted.size) break
                val sources: List<RawDataSource> = manager.dataSources
                for (ds in sources) {
                    if (found.size == wanted.size) break
                    // RawDataSource → DasDataSource.getModel()（DasModel），编译期直调
                    val model = runCatching { ds.model }.getOrNull() ?: continue
                    val elements = runCatching { model.traverser().toList() }.getOrNull() ?: continue
                    for (element in elements) {
                        if (element !is DasTable) continue
                        val name = element.name?.lowercase() ?: continue
                        if (name in wanted && name !in found) {
                            val dbElement = facade.findElement(element) ?: continue
                            found[name] = dbElement
                        }
                    }
                }
            }
            found
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 构建可嵌入的 Database 原生图编辑器——镜像 A 路径 NativeJoinDiagramOpener 的字节码实证
     * 链路（findFileByUrl 单例 VFS → ORIGINAL_ELEMENT → WriteAction createBuilder →
     * 反射 UmlFileEditorImpl → queryUpdate），仅 provider/元素换为 DATABASE + DbElement。
     */
    private fun buildEmbeddableEditor(project: Project, mainElement: DbElement, title: String): FileEditor {
        val provider = DiagramProvider.findByID<DbElement>(DATABASE_PROVIDER_ID)
            ?: error("Database diagram provider not registered: $DATABASE_PROVIDER_ID")

        val url = "${UmlVirtualFileSystem.PROTOCOL_PREFIX}$DATABASE_PROVIDER_ID/${sanitizeFqn(mainElement)}"
        val umlFile = VirtualFileManager.getInstance().findFileByUrl(url) as? UmlVirtualFileSystem.UmlVirtualFile
            ?: error("VirtualFileManager returned null for $url")

        umlFile.setProject(project)
        umlFile.setInitialized(true)
        umlFile.setPresentableName(title)
        umlFile.putUserData(DiagramDataKeys.ORIGINAL_ELEMENT, mainElement)

        val builder: DiagramBuilder = WriteAction.compute<DiagramBuilder, Throwable> {
            DiagramBuilderFactory.getInstance().createBuilder(project, provider, mainElement, umlFile)
        }

        @Suppress("UNCHECKED_CAST")
        val editor: FileEditor = Class.forName("com.intellij.uml.UmlFileEditorImpl")
            .getDeclaredConstructor(DiagramBuilder::class.java, VirtualFile::class.java)
            .newInstance(builder, umlFile) as FileEditor

        builder.queryUpdate()
            .withDataReload()
            .withRelayout()
            .withPresentationUpdate()
            .runAsync()
        return editor
    }

    /** FQN：仅表名；sanitize 防 `.` 被判为 .uml 格式、`/` 破坏 URL 分段。 */
    private fun sanitizeFqn(element: DbElement): String =
        (element.name ?: "table").replace(Regex("[./\\\\]"), "_")
}
