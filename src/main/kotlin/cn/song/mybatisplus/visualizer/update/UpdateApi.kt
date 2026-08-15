package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import java.nio.file.Path

/**
 * HTTP 层（M28）：清单获取与插件包下载——平台内置 [HttpRequests]（尊重 IDE 代理设置），
 * 零第三方依赖。**单测不测本类**：编排层 [PluginUpdateRunner] 以函数 seam 注入替代
 * （PluginUpdateRunnerTest 全分支覆盖），本类由 runIde 沙盒端到端验证。
 */
object UpdateApi {

    private val LOG = Logger.getInstance(UpdateApi::class.java)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * 获取清单文本（失败返回 null，错误记日志——降级由调用方处理：
     * 设置页显示失败原因，启动检查静默）。
     */
    fun fetchManifest(url: String): String? = runCatching {
        HttpRequests.request(url)
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .productNameAsUserAgent()
            .throwStatusCodeException(true)
            .readString(null)
    }.onFailure { error ->
        LOG.warn("update manifest fetch failed: $url — ${error.message}")
    }.getOrNull()

    /**
     * 下载插件包到目标文件（[indicator] 驱动进度 fraction，内容长度未知时不确定态；
     * Gitee Release 附件跳转 CDN，HttpRequests 默认跟随 redirect）。
     * 失败抛 IOException，由调用方清理临时文件并提示。
     */
    fun download(url: String, target: Path, indicator: ProgressIndicator?) {
        HttpRequests.request(url)
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .productNameAsUserAgent()
            .throwStatusCodeException(true)
            .saveToFile(target, indicator)
    }
}
