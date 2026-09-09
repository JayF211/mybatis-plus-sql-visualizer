package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.CheckResult
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.InstallResult
import cn.song.mybatisplus.visualizer.update.PluginVersion
import cn.song.mybatisplus.visualizer.update.UpdateManifest
import cn.song.mybatisplus.visualizer.update.UpdateSource
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.RestartDialogImpl
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane

/**
 * 设置页「更新」分区（M28）：当前版本 / 启动自动检查开关 / 检查更新 / 下载并安装 /
 * 重启提示——状态机渲染（Idle→Checking→UpToDate|Available→Downloading→Installed|Failed）。
 *
 * 异步流程全部经 [PluginUpdateRunner]（check/downloadAndInstall），本类只做 UI 编排与
 * 渲染（网络/平台安装不在本类）；测试可直接调用 [renderCheck]/[renderInstall] 驱动状态
 * （仿 ShortcutEditor 的组件内嵌模式，随语言切换经 [updateTexts] 刷新）。
 */
class UpdateSection(private val settings: PluginSettings) : JPanel(GridBagLayout()) {

    /** 分区状态（internal 供测试断言；状态渲染集中 [renderState]）。 */
    internal var state: State = State.Idle
        private set

    internal enum class State { Idle, Checking, UpToDate, Available, Downloading, Installed, Failed }

    private var pendingManifest: UpdateManifest? = null

    private var failureReason: String? = null

    /**
     * 检查编排 seam（T-M28.1）：测试注入 fake 不触网；默认走 [PluginUpdateRunner.check] 真实实现。
     * 与按钮绑定配套——交互测试经 doClick 驱动全链路。
     */
    internal var checkRunner: () -> PluginUpdateRunner.CheckResult =
        { PluginUpdateRunner.check(PluginVersion.current()) }

    /** 下载安装编排 seam（T-M28.1：测试注入 fake；默认真实实现，indicator 驱动进度条）。 */
    internal var installRunner: (UpdateManifest, ProgressIndicator?) -> PluginUpdateRunner.InstallResult =
        { manifest, indicator -> PluginUpdateRunner.downloadAndInstall(manifest, indicator) }

    /** 重启回调 seam（T-M28.1：测试注入 fake 不弹原生重启确认框）。 */
    internal var restartAction: () -> Unit = { RestartDialogImpl.restartWithConfirmation() }

    internal val versionLabel = JBLabel()
    internal val autoCheckCheck = JCheckBox()
    internal val checkButton = JButton()
    internal val statusLabel = JBLabel()
    internal val progressBar = JProgressBar()
    internal val notesArea = JBTextArea(5, 42)
    internal val installButton = JButton()
    internal val installedLabel = JBLabel()
    internal val restartButton = JButton()

    /** 变更日志标题（T-M28.4：标题行 = 左标题 + 右 Gitee 发布页链接）。 */
    internal val notesTitleLabel = JBLabel().apply { name = "notesTitle" }

    /** 变更日志 → Gitee 发布页链接（用户要求可点击跳转）。 */
    internal val giteeLink = LinkLabel<Any>(MpSqlVisualizerBundle.message("settings.update.viewOnGitee"), null) { _, _ ->
        BrowserUtil.browse(UpdateSource.GITEE_RELEASES_URL)
    }

    private val notesContainer = JPanel(BorderLayout(0, 2)).apply {
        add(JPanel(BorderLayout(8, 0)).apply {
            add(notesTitleLabel, BorderLayout.WEST)
            add(giteeLink, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JScrollPane(notesArea).apply {
            border = UIUtil.getTextFieldBorder()
            isVisible = false
        }, BorderLayout.CENTER)
    }

    private val doneContainer = JPanel(BorderLayout(8, 0)).apply {
        add(installedLabel, BorderLayout.WEST)
        add(restartButton, BorderLayout.EAST)
    }

    /** T-ErrReport：需求池报告入口（手动形态对话框 + 需求池网页链接）。 */
    internal val reportProblemButton = JButton()

    /** 需求池网页直达链接（浏览反馈/投票/进度）。 */
    internal val feedbackPoolLink = LinkLabel<Any>("", null) { _, _ ->
        BrowserUtil.browse(cn.song.mybatisplus.visualizer.feedback.FeedbackApi.SITE_BASE + "/about/feedback")
    }

    private val feedbackContainer = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        add(reportProblemButton)
        add(feedbackPoolLink)
    }

    init {
        notesArea.isEditable = false
        notesArea.lineWrap = true
        notesArea.wrapStyleWord = true
        // T-M28.1：三按钮事件绑定（此前缺失——用户实测点击检查更新无反应；测试盲区：只测渲染未测交互）
        checkButton.addActionListener { runCheck() }
        installButton.addActionListener { runInstall() }
        restartButton.addActionListener { restartIde() }
        // T-ErrReport：报告问题（手动形态，无异常预填）
        reportProblemButton.addActionListener {
            cn.song.mybatisplus.visualizer.feedback.FeedbackReporting.openManualDialog()
        }
        buildLayout()
        resetState()
        updateTexts()
    }

    // ------------------------------------------------------------------
    // 布局（GridBag 行：版本/开关/按钮+状态/进度/日志/安装完成）
    // ------------------------------------------------------------------

    private fun buildLayout() {
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 0, 2, 0)
        }
        fun row(component: javax.swing.JComponent) {
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            add(component, gbc)
        }

        // 版本内容行（当前版本 + 检查/安装按钮 + 状态反馈）；「版本信息」标签由设置面板
        // addRow 提供（统一标签列——与插件缓存/内部构建号左对齐，用户反馈对齐问题）
        row(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(versionLabel)
            add(checkButton)
            add(installButton)
            add(statusLabel)
        })
        row(autoCheckCheck)
        row(progressBar)
        row(notesContainer)
        row(doneContainer)
        // T-ErrReport：需求池报告入口（报告问题按钮 + 需求池网页链接）
        row(feedbackContainer)
        // 底部占位（与设置页整体一致：内容顶置）
        gbc.gridy++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        add(JPanel(), gbc)
    }

    // ------------------------------------------------------------------
    // 状态机渲染
    // ------------------------------------------------------------------

    /** 检查结果渲染（EDT；测试直接调用）。 */
    internal fun renderCheck(result: CheckResult) {
        when (result) {
            is CheckResult.Available -> {
                pendingManifest = result.manifest
                notesArea.text = renderNotes(result.manifest)
                state = State.Available
            }
            CheckResult.UpToDate -> {
                pendingManifest = null
                state = State.UpToDate
            }
            is CheckResult.Failure -> {
                pendingManifest = null
                state = State.Failed
                failureReason = result.message
            }
        }
        renderState()
    }

    /** 日志区内容：changeNotes + 兼容性警告（sinceBuild 高于当前 IDE 构建时提示不阻塞）。 */
    private fun renderNotes(manifest: UpdateManifest): String = buildString {
        append(manifest.changeNotes ?: "—")
        val current = currentBuildMajor()
        if (manifest.warnsIncompatible(current)) {
            append("\n").append(
                MpSqlVisualizerBundle.message("settings.update.incompatible", manifest.sinceBuild, current)
            )
        }
    }

    /** 当前 IDE 构建主号（如 `243`）；读取失败返回 null。 */
    private fun currentBuildMajor(): Int? =
        Regex("^\\d+").find(ApplicationInfo.getInstance().build.asStringWithoutProductCode())
            ?.value?.toIntOrNull()

    /** 安装结果渲染（EDT；测试直接调用）。安装失败保留 pendingManifest 以区分失败文案。 */
    internal fun renderInstall(result: InstallResult) {
        state = if (result is InstallResult.Installed) State.Installed else State.Failed
        if (result is InstallResult.Failed) failureReason = result.message
        renderState()
    }

    private fun renderState() {
        val s = state
        checkButton.isEnabled = s != State.Checking && s != State.Downloading && s != State.Installed
        installButton.isVisible = s == State.Available || s == State.Downloading
        installButton.isEnabled = s == State.Available
        restartButton.isVisible = s == State.Installed
        installedLabel.isVisible = s == State.Installed
        progressBar.isVisible = s == State.Downloading
        progressBar.isIndeterminate = true
        notesContainer.isVisible = s == State.Available
        doneContainer.isVisible = s == State.Installed
        when (s) {
            State.Checking -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.checking")
            State.UpToDate -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.upToDate")
            State.Available -> statusLabel.text =
                MpSqlVisualizerBundle.message("settings.update.available", pendingManifest?.version ?: "?")
            State.Downloading -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.downloading")
            State.Installed -> statusLabel.text = MpSqlVisualizerBundle.message("settings.update.installed")
            State.Failed -> statusLabel.text = MpSqlVisualizerBundle.message(
                "settings.update.${if (pendingManifest == null) "checkFailed" else "installFailed"}",
                failureReason ?: "?",
            )
            State.Idle -> statusLabel.text = " "
        }
    }

    /** 版本行/开关/日志与按钮文本刷新（语言切换 rebuild 时调用，仿 ShortcutEditor.updateTexts）。 */
    internal fun updateTexts() {
        versionLabel.text = MpSqlVisualizerBundle.message("settings.update.currentVersion") +
            ": " + (PluginVersion.current() ?: "—")
        autoCheckCheck.text = MpSqlVisualizerBundle.message("settings.update.autoCheck")
        autoCheckCheck.toolTipText = MpSqlVisualizerBundle.message("settings.update.autoCheck.tip")
        checkButton.text = MpSqlVisualizerBundle.message("settings.update.check")
        installButton.text = MpSqlVisualizerBundle.message("settings.update.downloadInstall")
        installedLabel.text = MpSqlVisualizerBundle.message("settings.update.installed")
        restartButton.text = MpSqlVisualizerBundle.message("settings.update.restartNow")
        notesTitleLabel.text = MpSqlVisualizerBundle.message("settings.update.changelog")
        giteeLink.text = MpSqlVisualizerBundle.message("settings.update.viewOnGitee")
        reportProblemButton.text = MpSqlVisualizerBundle.message("settings.update.reportProblem")
        feedbackPoolLink.text = MpSqlVisualizerBundle.message("settings.update.feedbackPool")
        renderState()
    }

    /** 从配置加载（Settings 打开/Reset 时调用）。 */
    internal fun resetState() {
        autoCheckCheck.isSelected = settings.state.autoCheckUpdate
        pendingManifest = null
        failureReason = null
        state = State.Idle
        renderState()
    }

    // ------------------------------------------------------------------
    // 异步流程（Task.Backgroundable；回调 EDT + 判活，面板销毁则静默）
    // ------------------------------------------------------------------

    /** 检查更新（防重入：Checking/Downloading 中忽略）。 */
    internal fun runCheck() {
        if (state == State.Checking || state == State.Downloading) return
        state = State.Checking
        renderState()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.check"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = checkRunner()
                ApplicationManager.getApplication().invokeLater {
                    if (isDisplayable) renderCheck(result)
                }
            }
        })
    }

    /** 下载并安装（进度条 fraction 经 [SectionProgress] 驱动；防重入：Downloading 中忽略）。 */
    internal fun runInstall() {
        val manifest = pendingManifest ?: return
        if (state == State.Downloading) return
        state = State.Downloading
        renderState()
        val progress = SectionProgress(progressBar)
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            MpSqlVisualizerBundle.message("update.task.download"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = installRunner(manifest, progress)
                ApplicationManager.getApplication().invokeLater {
                    if (isDisplayable) renderInstall(result)
                }
            }
        })
    }

    /** 重启 IDE（IDE 原生重启确认框，可选「稍后」——用户拍板，不自动重启）。 */
    internal fun restartIde() {
        restartAction()
    }

    /** 进度条适配：下载 fraction → 确定态进度（EDT 更新）。 */
    private class SectionProgress(private val bar: JProgressBar) : ProgressIndicatorBase() {
        override fun setFraction(fraction: Double) {
            super.setFraction(fraction)
            val value = (fraction * 100).toInt().coerceIn(0, 100)
            ApplicationManager.getApplication().invokeLater {
                bar.isIndeterminate = fraction <= 0
                bar.value = value
            }
        }
    }
}
