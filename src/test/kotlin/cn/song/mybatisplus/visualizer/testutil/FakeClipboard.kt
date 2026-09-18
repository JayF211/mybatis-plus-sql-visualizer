package cn.song.mybatisplus.visualizer.testutil

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/**
 * 测试用**内存剪贴板**（替换平台 [CopyPasteManager] 应用服务，测试全程不碰系统剪贴板）。
 *
 * 背景（T-ClipboardIso）：Windows 剪贴板是全机唯一的互斥资源（`WClipboard.openClipboard`），
 * 任何时刻只允许一个进程持有。此前测试直接读写 `Toolkit.systemClipboard`：
 *  - **用户在跑测试时复制东西** → 剪贴板被占用 → `IllegalStateException: cannot open system clipboard`
 *    在 `setUp` 快照处炸掉整类用例（20260918 实证：SqlPreviewPanelTest 单类 10 例全红）；
 *  - 并行分片 JVM / 其他并行会话的测试进程互抢 → 同类假失败（既有缓解是 buildSrc 把含
 *    `CopyPasteManager`/`systemClipboard` 字样的类固定进第 1 片，只治分片内互抢）；
 *  - 测试写入还会**覆盖用户自己的剪贴板**（原快照/还原写法在还原失败时更糟）。
 *
 * 本助手把剪贴板访问收敛到进程内内存：`UiTextClipboard.copy(...)` 等生产路径照常执行
 * （走 `CopyPasteManager.getInstance()`），但内容落在本对象里，断言直接读 [text]。
 */
class FakeClipboardManager : CopyPasteManager() {

    /** 当前内容（单槽，语义同系统剪贴板「后写覆盖」）。 */
    private var current: Transferable? = null

    /** 历史栈（后写在后），供 `getAllContents` 断言（平台复制栈语义）。 */
    private val history = mutableListOf<Transferable>()

    override fun addContentChangedListener(listener: CopyPasteManager.ContentChangedListener) = Unit

    override fun addContentChangedListener(
        listener: CopyPasteManager.ContentChangedListener,
        parentDisposable: Disposable,
    ) = Unit

    override fun removeContentChangedListener(listener: CopyPasteManager.ContentChangedListener) = Unit

    override fun areDataFlavorsAvailable(vararg flavors: DataFlavor): Boolean =
        flavors.all { current?.isDataFlavorSupported(it) == true }

    override fun getContents(): Transferable? = current

    override fun <T : Any?> getContents(flavor: DataFlavor): T? {
        val contents = current ?: return null
        if (!contents.isDataFlavorSupported(flavor)) return null
        @Suppress("UNCHECKED_CAST")
        return contents.getTransferData(flavor) as? T
    }

    override fun getAllContents(): Array<Transferable> = history.toTypedArray()

    override fun setContents(contents: Transferable) {
        current = contents
        history.add(contents)
    }

    override fun isCutElement(element: Any?): Boolean = false

    override fun stopKillRings() = Unit

    override fun stopKillRings(document: Document) = Unit

    /** 当前剪贴板文本（断言用；无内容/非文本 → null）。 */
    fun text(): String? = getContents<String>(DataFlavor.stringFlavor)

    /** 预置文本内容（测试前置数据用，等价于用户先复制了一段文本）。 */
    fun seedText(text: String) = setContents(StringSelection(text))

    /** 清空（含历史），避免用例间串味。 */
    fun reset() {
        current = null
        history.clear()
    }
}

/**
 * 安装内存剪贴板到当前 Application 服务位，返回假实现供断言。
 *
 * @param parentDisposable 释放时自动还原平台实现——传 `testRootDisposable` 即可随用例类回收
 *   （`BasePlatformTestCase.testRootDisposable`）。
 */
fun installFakeClipboard(parentDisposable: Disposable): FakeClipboardManager {
    val fake = FakeClipboardManager()
    FakeClipboardInstaller.replaceClipboard(fake, parentDisposable)
    return fake
}
