package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * 文本复制统一收口（T-UiNative P0）：剪贴板写入统一走平台 [CopyPasteManager]，
 * 进 IDE 复制历史（Ctrl+Shift+V 可回贴）并与编辑器复制栈互通；此前多处直写
 * `Toolkit.systemClipboard + StringSelection` 绕过平台机制，与既有 CopyPasteManager
 * 调用形成双轨（docs/调研报告/20260915-2327-IDEA原生能力UI与功能优化-调研报告.md §5.1）。
 *
 * 不做 isUnitTestMode 短路：CopyPasteManager 在单元测试沙盒内可用（SqlClickPreview、
 * SqlShareManager 等既有调用已验证），且列表批量复制等测试断言依赖真实落剪贴板。
 */
object UiTextClipboard {

    /** 文本写入系统剪贴板（经平台 CopyPasteManager）。 */
    @JvmStatic
    fun copy(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    /**
     * 读取系统剪贴板文本（O-92 增设读侧，与 [copy] 同轨收口——读写同走平台 CopyPasteManager，
     * 与编辑器复制栈互通）。剪贴板无文本内容（空 / 非文本 flavor）时返回 null。
     * API 权威：243 基线 app-client.jar javap 核实 `CopyPasteManager.getContents(DataFlavor)`
     * 在场（泛型按期望类型 String? 推断）；单元测试沙盒内可用（与 [copy] 同一先例）。
     */
    @JvmStatic
    fun readText(): String? =
        CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
}
