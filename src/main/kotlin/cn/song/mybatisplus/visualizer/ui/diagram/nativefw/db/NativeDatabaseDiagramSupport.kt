package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db

import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramOpener
import com.intellij.database.model.DasObject
import com.intellij.database.model.DasTable
import com.intellij.database.model.RawDataSource
import com.intellij.database.psi.DbElement
import com.intellij.database.psi.DataSourceManager
import com.intellij.diagram.DiagramBuilder
import com.intellij.diagram.DiagramBuilderFactory
import com.intellij.diagram.DiagramDataKeys
import com.intellij.diagram.DiagramDataModel
import com.intellij.diagram.DiagramProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
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

    private val LOG = Logger.getInstance(NativeDatabaseDiagramSupport::class.java)

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
            if (located == null || located.size < tableNames.size) {
                // T-TS-08：此处原为静默 `return null`，是「用例绿 ≠ B 路径已验证」不可诊断的根因——
                // HeavyNativeDatabaseDiagramJoinChainTest 在 component==null 处软跳（五组断言全未
                // 执行却计为绿），而日志里既没有降级痕迹也没有原因，无法区分「B 路径真跑过」与
                // 「从未进入」。降级本身是设计内契约（见类注释），只补可诊断性，不改行为。
                val foundKeys = located?.keys ?: emptySet()
                LOG.warn(
                    "Native Database diagram (B path) not taken: located=$foundKeys " +
                        "wanted=$tableNames missing=${tableNames.filter { it.trim().lowercase() !in foundKeys }} " +
                        "—— 缺表 = 该表未在已内省的数据源模型里定位到（数据源未内省 / 表名不匹配 / " +
                        "DbModelRegistry 无对应 DbElement），按契约回落 A 路径",
                )
                return null
            }
            LOG.info("Native Database diagram (B path) taking over: tables=${located.keys}")
            registerJoinForeignKeys(model, located)

            val mainTable = model.entities.first { it.isMain }.tableName
            val mainElement = located[mainTable] ?: located.values.first()
            // 主表排首（fillDataModel 顺序即初始画布顺序），其余表随后
            val orderedElements = listOf(mainElement) + located.entries
                .filter { it.key != mainTable }
                .map { it.value }
            val editor = buildEmbeddableEditor(project, orderedElements, title)
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(editor.component, BorderLayout.CENTER)
                Disposer.register(parent, editor)
                putClientProperty(NATIVE_EDITOR_KEY, editor)
            }
        } catch (t: Throwable) {
            LOG.warn("Native Database diagram (B path) unavailable, falling back to A path", t)
            clearJoinFkState()
            null
        }
    }

    /**
     * 释放嵌入的 Database 图编辑器（换图/清空调用）。
     *
     * T-EmbedFkOrder F2：**不再在此清空 [JoinFkRegistry]**——dispose 只负责释放编辑器，清空注册表
     * 会误伤「当前图」刚登记的虚拟外键（真机故障：`showJoinDiagram` 先建新图登记、后清旧图，
     * 旧图 dispose 把新登记整批抹掉 → 只剩节点没连线）。清理职责改为显式语义点：
     * 登记前 `registerJoinForeignKeys` 自带 clear（新图恰好只留自己的 FK）、无 JOIN 移除标签页与
     * 面板 clear() 时调用 [clearRegisteredFk]。
     */
    fun disposeNativeEditor(component: JComponent?) {
        try {
            val editor = component?.getClientProperty(NATIVE_EDITOR_KEY) as? Disposable
            editor?.let { Disposer.dispose(it) }
        } catch (t: Throwable) {
            // 释放失败不阻断降级链
        }
    }

    /** 回收 JOIN 虚拟外键登记（打开失败 / 独立路径）。 */
    fun clearRegisteredFk() = clearJoinFkState()

    /**
     * 成对回收 JOIN 虚拟外键的两份会话状态（[JoinFkRegistry] 语义 + [JoinFkRefTables] 实例）。
     *
     * 两份状态必须同生同灭：只清语义会让 provider 拿着陈旧的表实例给新图登记外键，只清实例则留下
     * 「登记有、实例缺」的静默丢边。此方法被 plain `test` 分片（无 database jar）经
     * `SqlPreviewPanel.unloadJoinDiagram` 间接触达，故实例侧清理包 runCatching——类解析失败时
     * 实例表必然也没被填充过，无泄漏风险（与本类「所有公开方法容忍 NoClassDefFoundError」同语义）。
     */
    private fun clearJoinFkState() {
        JoinFkRegistry.clear()
        runCatching { JoinFkRefTables.clear() }
    }

    /**
     * 把模型里的 ON 等值列配对登记为虚拟外键。
     * 方向：JOIN 表 = FK child（columns），主表 = ref（refColumns）——与 MPJ 外键语义一致。
     *
     * N6：同时把定位到的表实例登记进 [JoinFkRefTables]——平台两参建边回调只给表名，
     * 没有实例就还原不出 `getRefTable()`，边会被 `nodeMap.get(asDbElement(...))` 静默丢掉。
     */
    private fun registerJoinForeignKeys(model: DiagramModel, located: Map<String, DbElement>) {
        clearJoinFkState()
        // T-PerfSweep6 T-PS6-07：本次建图的日志探针复位——建图首行 `getForeignKeys` 回调打 INFO、
        // 其余 DEBUG，故每次建图都要把「首行」还给本次（纯 JVM 对象，无 database jar 也能加载）
        JoinVirtualFkProbe.reset()
        for ((name, element) in located) {
            // DbTable 同时实现 DbElement 与 DasTable；定位链取到的正是它，
            // 且它就是 dataModel.addElement 收到的那个实例（calculateEdges 的 nodeMap 键）
            (element as? DasTable)?.let { JoinFkRefTables.put(name, it) }
        }
        for (rel in model.relationships) {
            val mainTable = model.entities.firstOrNull { it.alias == rel.fromAlias }?.tableName ?: continue
            val joinTable = model.entities.firstOrNull { it.alias == rel.toAlias }?.tableName ?: continue
            // locateDbTables 以**小写表名**为键，此处必须同口径归一——否则表名含大写时命中失败，
            // 整条 JOIN 被静默跳过（登记为空 → 无边，且日志只显示 entries=0）
            if (located[mainTable.trim().lowercase()] == null ||
                located[joinTable.trim().lowercase()] == null
            ) {
                continue
            }
            for ((mainColumn, joinColumn) in rel.columnPairs) {
                JoinFkRegistry.put(joinTable, mainTable, joinColumn, mainColumn)
            }
        }
    }

    /**
     * 按表名在全部数据源中定位 [DbElement]（遍历 DasModel → DasTable 名称匹配 → DbModelRegistry）。
     * 任一表未找到时该表缺值；调用方以「全部命中」为 B 路径前置条件。异常返回 null。
     *
     * T-MarketInternal：原 `DbPsiFacade.findElement(DasObject)` scheduled-for-removal——
     * 243/262 的实现链为 DbPsiFacadeImpl → DatabaseCoreUiService.findElement →
     * `project.getService(DbModelRegistry).findDbElement(das)`（jar 反编译双版本实证）。
     * DatabaseCoreUiService.findElement 本身仍标 deprecated，故直取链尾的
     * [com.intellij.database.DbModelRegistry]（project 级 service，243 database-plugin.jar /
     * 262 intellij.database.core.impl.jar 均 public、非 deprecated、非 Internal）。
     */
    private fun locateDbTables(project: Project, tableNames: List<String>): Map<String, DbElement>? {
        return try {
            val wanted = tableNames.map { it.trim().lowercase() }.toSet()
            val found = mutableMapOf<String, DbElement>()
            // T-TS-08：定位链的逐段计数——调用方只在「全部命中」时才走 B 路径，缺表时仅凭
            // 命中集合无法判断卡在哪一段（无数据源 / 数据源未内省(model=null) / traverser 不可迭代 /
            // 表名没匹配 / DbElement 没登记），故把每段读数一并留痕（纯日志，无控制流影响）。
            var managers = 0
            var dataSources = 0
            var modelNull = 0
            var traverserMiss = 0
            var roots = 0
            var iterated = 0
            var dasTables = 0
            var nameHits = 0
            var registryMiss = 0
            val dsClasses = LinkedHashSet<String>()
            val modelRegistry = project.getService(com.intellij.database.DbModelRegistry::class.java)
            for (manager in DataSourceManager.getManagers(project)) {
                managers++
                if (found.size == wanted.size) break
                val sources: List<RawDataSource> = manager.dataSources
                for (ds in sources) {
                    dataSources++
                    dsClasses.add(ds.javaClass.simpleName)
                    if (found.size == wanted.size) break
                    // RawDataSource → DasDataSource.getModel()（DasModel），编译期直调
                    val model = runCatching { ds.model }.getOrNull()
                    if (model == null) {
                        modelNull++
                        continue
                    }
                    // model 非 null 却遍历不出表时，须区分「模型是空的（未同步内省结果）」与
                    // 「模型有根但遍历器没吐出东西」——roots 读数正是这个分界（本地实测的关键证据）
                    roots += runCatching { model.modelRoots.toList().size }.getOrDefault(-1)
                    // T-Perf-09：惰性迭代 + 命中即停（此前 toList() 全量物化再筛——数千表 schema
                    // 下即使全部命中也遍历完整棵模型）；model 为 null（未内省）已在上行跳过
                    val elements = runCatching { model.traverser() }.getOrNull()
                        ?.let { it as? Iterable<*> }
                    if (elements == null) {
                        traverserMiss++
                        continue
                    }
                    for (element in elements) {
                        iterated++
                        if (found.size == wanted.size) break
                        if (element !is DasTable) continue
                        dasTables++
                        val name = element.name?.lowercase() ?: continue
                        if (name in wanted && name !in found) {
                            nameHits++
                            val dbElement = modelRegistry.findDbElement(element)
                            if (dbElement == null) {
                                registryMiss++
                                continue
                            }
                            found[name] = dbElement
                        }
                    }
                }
            }
            LOG.info(
                "B path locateDbTables: wanted=$wanted found=${found.keys} managers=$managers " +
                    "dataSources=$dataSources(dsClasses=$dsClasses) modelNull=$modelNull traverserMiss=$traverserMiss " +
                    "roots=$roots iterated=$iterated dasTables=$dasTables nameHits=$nameHits registryMiss=$registryMiss",
            )
            lastCounts = LocateCounts(
                managers = managers, dataSources = dataSources, modelNull = modelNull,
                traverserMiss = traverserMiss, roots = roots, iterated = iterated,
                dasTables = dasTables, nameHits = nameHits, registryMiss = registryMiss,
            )
            found
        } catch (t: Throwable) {
            LOG.warn("NativeDatabaseDiagramSupport.locateDbTables failed: ${t}")
            lastCounts = null
            null
        }
    }

    /**
     * [locateDbTables] 最近一次的逐段计数（B 路径降级原因的**可断言**出口）。
     *
     * T-TS-08 只把这些读数写进日志，于是「B 路径从未真跑过」在测试里既不可见也不可失败
     * （`HeavyNativeDatabaseDiagramJoinChainTest` 的软返回分支一记绿就没人再看日志）。
     * 取出来之后，调用方可以区分两种降级：「数据源里根本没定位到表」（环境受限，可跳过）
     * 与「表定位到了却没接管」（`nameHits > 0`，属新故障，必须判失败）。
     */
    @Volatile
    private var lastCounts: LocateCounts? = null

    /** 见 [lastCounts] 注释。 */
    fun lastLocateCounts(): LocateCounts? = lastCounts

    /** [locateDbTables] 逐段计数快照。 */
    data class LocateCounts(
        val managers: Int,
        val dataSources: Int,
        val modelNull: Int,
        val traverserMiss: Int,
        val roots: Int,
        val iterated: Int,
        val dasTables: Int,
        val nameHits: Int,
        val registryMiss: Int,
    ) {
        /** 是否已在数据源模型里定位到目标表（区别「环境受限」与「定位成功却未接管」的分界）。 */
        val locatedAny: Boolean get() = nameHits > 0
    }

    /**
     * 构建可嵌入的 Database 原生图编辑器——镜像 ShowDiagramBase 真实开图链：
     * findFileByUrl 单例 VFS → ORIGINAL_ELEMENT → WriteAction createBuilder →
     * **dataModel.addElement 逐表填充（= 原生 fillDataModel，javap 实证其就是
     * WriteAccess 内循环 addElement；缺此步 DbDiagramDataModel 无初始节点 → 画布空白）** →
     * queryUpdate 重排填充。
     */
    private fun buildEmbeddableEditor(project: Project, elements: List<DbElement>, title: String): FileEditor {
        val provider = DiagramProvider.findByID<DbElement>(DATABASE_PROVIDER_ID)
            ?: error("Database diagram provider not registered: $DATABASE_PROVIDER_ID")

        val url = "${UmlVirtualFileSystem.PROTOCOL_PREFIX}$DATABASE_PROVIDER_ID/${sanitizeFqn(elements.first())}"
        val umlFile = VirtualFileManager.getInstance().findFileByUrl(url) as? UmlVirtualFileSystem.UmlVirtualFile
            ?: error("VirtualFileManager returned null for $url")

        umlFile.setProject(project)
        umlFile.setInitialized(true)
        umlFile.setPresentableName(title)
        umlFile.putUserData(DiagramDataKeys.ORIGINAL_ELEMENT, elements.first())

        val builder: DiagramBuilder = WriteAction.compute<DiagramBuilder, Throwable> {
            val b = DiagramBuilderFactory.getInstance().createBuilder(project, provider, elements.first(), umlFile)
            // 镜像 ShowDiagramBase.fillDataModel（WriteAccess 内逐元素 addElement）；
            // getDataModel() 返回 DiagramDataModel<?>（star projection），Kotlin 侧需显式窄化
            @Suppress("UNCHECKED_CAST")
            val dataModel = b.dataModel as DiagramDataModel<Any>
            for (element in elements) {
                dataModel.addElement(element)
            }
            // T-Embed-10.4 根因（javap initializeDataModelAsync 实证）：addElement 只登记
            // myTablesAddedByUser（DbTable 分支 createDbNode 只返回不写 myNodes），节点生成必须走
            // refreshDataModel——原生 fillDataModel 后正是调 refreshDataModelAsync。
            // 此前仅 queryUpdate().withDataReload() 未触发它 → nodes 恒 0 画布空白。
            // T-Embed-10.5：calculateEdges 开头 isCategoryEnabled(VIRTUAL_FOREIGN_KEYS) 为 false
            // 直接不建虚拟外键边——嵌入场景 content manager 新实例该分类默认禁用，refresh 前强制开启
            enableVirtualFkCategory(dataModel)
            dataModel.refreshDataModel()
            LOG.info(
                "B path fillDataModel+refresh done: elements=${elements.size}, " +
                    "nodes=${runCatching { dataModel.nodes.size }.getOrNull()}",
            )
            b
        }

        @Suppress("UNCHECKED_CAST")
        val editor: FileEditor = Class.forName("com.intellij.uml.UmlFileEditorImpl")
            .getDeclaredConstructor(DiagramBuilder::class.java, VirtualFile::class.java)
            .newInstance(builder, umlFile) as FileEditor

        // T-PreviewBgFit（问题二）：同 JOIN 路径——开关必须在**异步布局触发之前**置位，
        // 布局管线收尾时才读得到它并自适应（见 NativeJoinDiagramOpener.enableAutoFit 注释）。
        NativeJoinDiagramOpener.enableAutoFit(builder)

        builder.queryUpdate()
            .withDataReload()
            .withRelayout()
            .withPresentationUpdate()
            .runAsync()
            .thenRun {
                // T-Embed-10 探针：刷新（refreshDataModel 会重建 nodes）后节点是否幸存
                runCatching {
                    LOG.info("B path after queryUpdate: nodes=${builder.dataModel.nodes.size}")
                }
            }
        return editor
    }

    /** FQN：仅表名；sanitize 防 `.` 被判为 .uml 格式、`/` 破坏 URL 分段。 */
    private fun sanitizeFqn(element: DbElement): String =
        (element.name ?: "table").replace(Regex("[./\\\\]"), "_")

    /**
     * 强制启用 VIRTUAL_FOREIGN_KEYS 分类（calculateEdges 的建边开关，javap 实证）。
     * 分类常量为 DbDiagramProvider$Holder 私有静态字段（package-private 类），反射读取；
     * nodeContentManager 经 DiagramDataModel 接口直调（同 uml-support public API）。
     */
    private fun enableVirtualFkCategory(dataModel: DiagramDataModel<Any>) {
        runCatching {
            val holderClass = Class.forName("com.intellij.database.diagram.DbDiagramProvider\$Holder")
            val category = holderClass.getDeclaredField("VIRTUAL_FOREIGN_KEYS")
                .apply { isAccessible = true }
                .get(null) as com.intellij.diagram.DiagramCategory
            dataModel.nodeContentManager?.setCategoryEnabled(category, true)
            LOG.info("B path VIRTUAL_FOREIGN_KEYS category enabled")
        }.onFailure { e ->
            LOG.warn("B path enable VIRTUAL_FOREIGN_KEYS failed (edges may be absent)", e)
        }
    }
}
