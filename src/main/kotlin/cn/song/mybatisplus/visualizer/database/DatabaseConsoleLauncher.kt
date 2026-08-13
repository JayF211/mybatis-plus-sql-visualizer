package cn.song.mybatisplus.visualizer.database

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * 数据库执行器（M17 隔离实现，M17.1 修正）。
 *
 * **隔离约定**：本类是主 jar 中唯一直接引用 `com.intellij.database` 的类，经
 * [cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport] **反射**调用——
 * 仅当 Database 插件已加载（Ultimate/DataGrip）时才被 JVM 解析，Community 下永不加载。
 *
 * 流程（API 按 2025.3.5 与 2026.2.1 双版本核对一致）：
 *  1. 数据源枚举：`LocalDataSourceManager.getInstance(project).dataSources`；
 *  2. 选择：0 个 → 引导创建；1 个 → 直用；多个 → **IDE 原生搜索面板**
 *     （`ChooseByNamePopup`，GotoClass/Search Everywhere 同款——搜索 + 图标 + 列表）；
 *  3. 临时文件：系统临时目录 `mpsql-exec-*.sql`，写入当前 SQL；
 *  4. 会话与控制台：`DatabaseSessionManager.getSession(project, ds)` →
 *     `JdbcConsole.newConsole(project).forFile(vf).useSession(session).build()`；
 *  5. **关联持久化（M17.1 修正——编辑器右上角数据源显示的关键）**：
 *     `JdbcConsoleProvider.attachConsole(project, session, vf)` 把「文件 ↔ 会话/数据源」
 *     关联注册到 IDE——打开后编辑器状态栏/右上角显示所选数据源，Ctrl+Enter 直接执行；
 *  6. 打开编辑器并提示。
 */
object DatabaseConsoleLauncher {

    /**
     * 执行入口（经反射调用）：完整流程见类 KDoc。任何一步失败 → 错误提示（不抛异常到调用方）。
     */
    @JvmStatic
    fun execute(project: Project, sql: String) {
        runCatching {
            val sources = listDataSources(project)
            if (sources.isEmpty()) {
                showInfo(project, MpSqlVisualizerBundle.message("execute.noDataSource"))
                return@runCatching
            }
            val dataSource = chooseDataSource(project, sources) ?: return@runCatching
            val console = createTempConsole(project, dataSource, sql)
            FileEditorManager.getInstance(project).openFile(console.virtualFile, true)
            showInfo(project, MpSqlVisualizerBundle.message("execute.done", dataSource.name))
        }.onFailure { error ->
            showError(project, MpSqlVisualizerBundle.message("execute.failed", error.message ?: error.toString()))
        }
    }

    /**
     * 项目全部本地数据源（读操作）。元素级类型过滤（`getDataSources()` 返回泛型擦除的
     * List——`as? List<LocalDataSource>` 的强转不检查元素类型，改用 filterIsInstance 逐元素过滤，
     * 避免非 LocalDataSource 元素导致后续 ClassCastException 而静默返回空）。
     */
    private fun listDataSources(project: Project): List<LocalDataSource> =
        ReadAction.compute<List<LocalDataSource>, Throwable> {
            LocalDataSourceManager.getInstance(project).dataSources.filterIsInstance<LocalDataSource>()
        }

    /**
     * 选择数据源：0 个 → 提示引导创建（返回 null）；1 个 → 直接使用；多个 → **IDE 原生搜索面板**
     * （[ChooseByNamePopup]——GotoClass/Search Everywhere 同款：搜索 + 图标 + 列表）。
     * internal 供测试（测试沙盒无 database 插件，仅编译期可引用）。
     */
    internal fun chooseDataSource(project: Project, sources: List<LocalDataSource>): LocalDataSource? = when {
        sources.isEmpty() -> {
            showInfo(project, MpSqlVisualizerBundle.message("execute.noDataSource"))
            null
        }
        sources.size == 1 -> sources.first()
        else -> {
            var chosen: LocalDataSource? = null
            val popup = ChooseByNamePopup.createPopup(project, DataSourceByNameModel(sources), null)
            popup.invoke(
                // Callback 为抽象类（非接口）——匿名对象需带括号初始化
                object : ChooseByNamePopupComponent.Callback() {
                    override fun elementChosen(element: Any?) {
                        chosen = element as? LocalDataSource
                    }

                    override fun onClose() {}
                },
                ModalityState.current(),
                false,
            )
            chosen
        }
    }

    /** 数据源显示名：名称 + 数据库类型（同名下可区分）。 */
    internal fun displayName(dataSource: LocalDataSource): String = buildString {
        append(dataSource.name)
        runCatching { dataSource.dbms?.let { append(" ($it)") } }.onFailure { }
    }

    /**
     * 创建临时 .sql 文件 + 数据源会话 + 控制台 + **attach 关联**（M17.1 修正：
     * 仅 build 不 attach 时 IDE 无从得知文件与数据源的关系，编辑器右上角不显示所选数据源）。
     *
     * @return 已绑定数据源的控制台（其 [JdbcConsole.virtualFile] 即临时文件）
     */
    private fun createTempConsole(project: Project, dataSource: LocalDataSource, sql: String): JdbcConsole {
        val ioFile = FileUtil.createTempFile("mpsql-exec-", ".sql", true)
        FileUtil.writeToFile(ioFile, sql)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: error("临时文件创建失败: ${ioFile.absolutePath}")
        // 会话（含数据源）→ 控制台 → attach 到文件（文件↔数据源关联持久化）
        val session = DatabaseSessionManager.getSession(project, dataSource)
        val console = JdbcConsole.newConsole(project)
            .forFile(vf)
            .useSession(session)
            .build()
        JdbcConsoleProvider.attachConsole(project, session, vf)
        return console
    }

    private fun showInfo(project: Project, message: String) {
        runCatching { Messages.showInfoMessage(project, message, MpSqlVisualizerBundle.message("execute.title")) }
    }

    private fun showError(project: Project, message: String) {
        runCatching { Messages.showErrorDialog(project, message, MpSqlVisualizerBundle.message("execute.title")) }
    }

    /** IDE 原生搜索面板模型（ChooseByNameModel，2025.3 重构后签名；2025.3/2026.2 一致）。 */
    private class DataSourceByNameModel(private val sources: List<LocalDataSource>) : ChooseByNameModel {

        override fun getPromptText(): String = MpSqlVisualizerBundle.message("execute.chooseDataSource")

        override fun getNotInMessage(): String = ""

        override fun getNotFoundMessage(): String = ""

        override fun getCheckBoxName(): String? = null

        override fun getCheckBoxMnemonic(): Char = '\u0000'

        override fun loadInitialCheckBoxState(): Boolean = false

        override fun saveInitialCheckBoxState(state: Boolean) {}

        /** 列表项渲染（M17.2：数据源类型图标 + 名称/类型——IDE 原生数据源下拉观感）。 */
        override fun getListCellRenderer(): javax.swing.ListCellRenderer<Any> = DataSourceCellRenderer()

        override fun getNames(checkBoxState: Boolean): Array<String> =
            sources.map { displayName(it) }.toTypedArray()

        override fun getElementsByName(name: String, checkBoxState: Boolean, pattern: String): Array<Any> =
            sources.filter { displayName(it).contains(name, ignoreCase = true) }.toTypedArray()

        override fun getElementName(element: Any): String = (element as? LocalDataSource)?.let { displayName(it) } ?: ""

        override fun getSeparators(): Array<String> = emptyArray()

        override fun getFullName(element: Any): String? = (element as? LocalDataSource)?.name

        override fun getHelpId(): String? = null

        override fun willOpenEditor(): Boolean = false

        override fun useMiddleMatching(): Boolean = true

        /**
         * 列表单元格渲染器：ChooseByNamePopup 渲染的是 [getNames] 的字符串（displayName）——
         * 经字符串反查数据源，展示**数据源类型图标** + 名称 + 数据库类型（原生观感）。
         */
        private inner class DataSourceCellRenderer : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as javax.swing.JLabel
                val name = value?.toString()
                val ds = sources.firstOrNull { displayName(it) == name }
                if (ds != null) {
                    c.icon = runCatching { ds.icon }.getOrNull()
                    c.text = displayName(ds)
                }
                return c
            }
        }
    }
}
