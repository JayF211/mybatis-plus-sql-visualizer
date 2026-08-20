package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.Action
import javax.swing.JComponent

/**
 * SQL 预览弹窗（M3-02，F-07 弹窗展示方式）。
 *
 * 由 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 触发，
 * 复用 [SqlPreviewPanel] 展示 SQL 与参数对照，底部提供"复制 / 关闭"。
 *
 * M14：默认尺寸按屏幕比例自适应（宽 62% / 高 65%，下限 900×600、上限 85%），
 * 并记忆用户拉伸后的尺寸（[rememberLastWindowSize]）。
 */
class SqlPreviewDialog(
    private val project: Project?,
    private val result: PreviewResult,
) : DialogWrapper(project, true) {

    private val panel = SqlPreviewPanel(project)

    init {
        title = MpSqlVisualizerBundle.message("dialog.preview.title")
        panel.showResult(result)
        // 注册面板到弹窗 Disposer 树：弹窗 dispose 时级联释放面板及其 messageBus 连接
        Disposer.register(disposable, panel)
        // T-M23.6：复制/执行 SQL 成功提示的「关闭 SQL 面板」按钮 → 关闭预览弹窗
        panel.onClosePanelRequested = { dispose() }
        // 最小尺寸兜底（防止拉伸时缩没；DialogWrapper 无 setMinimumSize，对中心面板设置）
        panel.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        init()
        // 关闭按钮文案走插件 bundle（平台 cancelAction 随 IDE 语言包，插件语言设置独立于 IDE，避免中文环境显示英文 Cancel）
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"))
        // 弹窗默认尺寸（按屏幕比例自适应；环境异常兜底）
        val size = initialSize()
        setSize(size.width, size.height)
    }

    override fun createCenterPanel(): JComponent = panel

    /** T-M23.6：internal 测试访问器（面板「关闭 SQL 面板」回调链路断言，仿 SqlPreviewPanel.editorForTest）。 */
    internal fun panelForTest(): SqlPreviewPanel? = panel

    override fun createActions(): Array<Action> = arrayOf(
        // 执行 SQL（M17：选择数据源 → 临时文件 → 可执行；提示由面板/支持层统一处理）
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("panel.executeSql")) {
            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                panel.executeSql()
            }
        },
        // 复制 SQL（F-08，提示由面板统一处理）
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("panel.copySql")) {
            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                panel.copySql()
            }
        },
        // 关闭
        cancelAction,
    )

    /** 尺寸记忆 key（非 null → DialogWrapper 经 DimensionService 记忆用户拉伸尺寸，下次打开保持）。 */
    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    companion object {
        /** 尺寸记忆 key。 */
        const val DIMENSION_KEY = "cn.song.mybatisplus.visualizer.SqlPreviewDialog"

        /** 最小弹窗尺寸。 */
        const val MIN_WIDTH = 640
        const val MIN_HEIGHT = 420

        /** 屏幕占比（宽/高）。 */
        const val SCREEN_RATIO_WIDTH = 0.62
        const val SCREEN_RATIO_HEIGHT = 0.65

        /** 屏幕占比上限（防止小屏超屏）。 */
        const val SCREEN_RATIO_CAP = 0.85

        /** 最小默认尺寸（小屏/异常环境兜底）。 */
        const val DEFAULT_WIDTH = 900
        const val DEFAULT_HEIGHT = 600

        /**
         * 计算弹窗默认尺寸（M14）：按屏幕比例（宽 62% / 高 65%），
         * 夹在「下限 900×600」与「屏幕 85% 上限」之间；小屏时**上限优先（不超屏）**；
         * headless/无屏幕信息时兜底 900×600。
         * 独立静态方法便于单测（mock 屏幕）。
         */
        internal fun initialSize(screenWidth: Int = screenWidthOf(), screenHeight: Int = screenHeightOf()): Dimension {
            if (screenWidth <= 0 || screenHeight <= 0) return Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT)
            val w = fitWithin((screenWidth * SCREEN_RATIO_WIDTH).toInt(), screenWidth, DEFAULT_WIDTH)
            val h = fitWithin((screenHeight * SCREEN_RATIO_HEIGHT).toInt(), screenHeight, DEFAULT_HEIGHT)
            return Dimension(w, h)
        }

        /**
         * 尺寸夹取：正常屏 → 比例值夹在 [minDefault, 屏幕 85%]；
         * 小屏（上限 < 下限）→ 取上限（不超屏优先于保下限）。
         */
        private fun fitWithin(ratioValue: Int, screenSize: Int, minDefault: Int): Int {
            val cap = (screenSize * SCREEN_RATIO_CAP).toInt()
            return if (minDefault <= cap) ratioValue.coerceIn(minDefault, cap) else cap
        }

        private fun screenWidthOf(): Int = runCatching { Toolkit.getDefaultToolkit().screenSize.width }.getOrDefault(0)

        private fun screenHeightOf(): Int = runCatching { Toolkit.getDefaultToolkit().screenSize.height }.getOrDefault(0)
    }
}
