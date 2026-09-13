package cn.song.mybatisplus.visualizer.util

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager

/**
 * 系统浏览器打开收口（T-TestGuard-03）：`BrowserUtil.browse` 不走沙盒隔离，直接
 * ShellExecute 拉起宿主机默认浏览器——单元测试沙盒里任何降级分支触达都会在定时回归
 * 任务期间周期性弹出用户浏览器（2026-09-11 HelpAction 真机实证，见
 * docs/memory-archive/pitfalls/pitfall-test-sandbox-browser-leak.md）。
 *
 * 所有「跳系统浏览器」调用统一经本入口；单元测试模式静默短路。
 * 守卫测试 BannedApiSourceScanTest 约定：src/main 中含 `BrowserUtil.browse(` 的文件
 * 必须自带 `isUnitTestMode` 短路（SqlLogPanel 帮助降级为历史先例），新调用一律走这里。
 */
object SafeBrowser {

    /** 单元测试模式跳过，正常模式系统浏览器打开 [url]。 */
    @JvmStatic
    fun browse(url: String) {
        val app = ApplicationManager.getApplication()
        if (app != null && app.isUnitTestMode) return
        BrowserUtil.browse(url)
    }
}
