package cn.song.mybatisplus.visualizer.ui.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * 工具栏图标统一入口（T-ToolbarIconSemantics）。
 *
 * 四个页签共用：SQL 日志、日志转 SQL、SQL→Java、SQL 历史。
 *
 * 图形统一克隆自 IntelliJ expui 图标集（Apache 2.0，资源内保留原始版权注释），
 * 而不是引用 [com.intellij.icons.AllIcons] 字段——AllIcons 在 243 会回落经典实心图标、
 * 在 262 使用 expui 线性图标，同一按钮跨版本形态不一致。克隆到插件资源后，
 * 所有 IDE 版本都渲染同一套图形。
 */
object MpvsIcons {

    /** 从插件资源加载工具栏图标；同目录的 `_dark.svg` 由 IconLoader 按主题自动选用。 */
    private fun icon(name: String): Icon =
        IconLoader.getIcon("/icons/toolbar/$name.svg", MpvsIcons::class.java)

    // ---------- SQL 日志 ----------

    /** 捕捉开始：绿色三角。 */
    val CaptureStart: Icon = icon("run")

    /** 捕捉停止：红色方块。 */
    val CaptureStop: Icon = icon("stop")

    /** 增强捕获暂停：双竖条。 */
    val AgentPause: Icon = icon("pause")

    /** 增强捕获恢复：绿色三角带竖条。 */
    val AgentResume: Icon = icon("resume")

    /** 清空列表 / 输入区 / 结果区：垃圾桶。 */
    val Clear: Icon = icon("clear")

    /** 设置：齿轮。 */
    val Settings: Icon = icon("settings")

    /** 屏蔽规则：自绘「眼睛 + 红斜线」。 */
    val Suppress: Icon = icon("eyeOff")

    /** 搜索：放大镜。 */
    val Search: Icon = icon("search")

    /** 停靠预览：分栏面板。 */
    val DockedPreview: Icon = icon("dockedPreview")

    /** 滚动到底：列表 + 向下箭头。 */
    val ScrollToEnd: Icon = icon("scrollToEnd")

    /** 排序方式：降序条。 */
    val Sort: Icon = icon("sortDuration")

    /** 交流群：品牌聊天气泡（仓内自绘，不属于平台克隆）。 */
    val Community: Icon = IconLoader.getIcon("/icons/communityChat.svg", MpvsIcons::class.java)

    /** 帮助：问号。 */
    val Help: Icon = icon("help")

    /** SQL 类型过滤：漏斗。 */
    val TypeFilter: Icon = icon("filter")

    // ---------- 四页签共用 ----------

    /** 转换 / 执行：绿色三角（与捕捉开始同形）。 */
    val Convert: Icon = icon("run")

    /** 执行还原产物：绿色三角。 */
    val Execute: Icon = icon("run")

    /** 从剪贴板读取：粘贴。 */
    val Paste: Icon = icon("paste")

    /** 复制：复制。 */
    val Copy: Icon = icon("copy")

    /** 格式化 / 压缩多行：格式化代码。 */
    val Format: Icon = icon("format")

    /** 导出：导出（SQL 日志与 SQL 历史右键统一）。 */
    val Export: Icon = icon("export")

    /** 返回：左箭头。 */
    val Back: Icon = icon("back")

    /** 刷新：刷新。 */
    val Refresh: Icon = icon("refresh")

    /** Diff 对比。 */
    val Diff: Icon = icon("diff")

    /** 跳转源码：编辑源。 */
    val EditSource: Icon = icon("editSource")

    /** 跳转 Mapper XML：XML 文件图标。 */
    val MapperXml: Icon = icon("mapperXml")

    /** Mapper 接口方法侧的配对跳转标记（红头）。 */
    val MapperMethod: Icon = icon("mapperMethod")

    /** Mapper XML 语句侧的配对跳转标记（青头）。 */
    val MapperStatement: Icon = icon("mapperStatement")
}
