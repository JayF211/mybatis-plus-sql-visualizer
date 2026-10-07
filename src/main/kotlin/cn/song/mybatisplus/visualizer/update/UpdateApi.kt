package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import java.nio.file.Path

/**
 * HTTP 层（M28）：清单获取与插件包下载——平台内置 [HttpRequests]（尊重 IDE 代理设置），
 * 零第三方依赖。**单测不测本类**：编排层 [PluginUpdateRunner] 以函数 seam 注入替代
 * （PluginUpdateRunnerTest 全分支覆盖），本类由 runIde 沙盒端到端验证。
 *
 * R62（T-LaneB，V114）：清单获取改出 [FetchOutcome] 三分支——网络不通与 HTTP 状态码
 * **不再合并为 null**（旧口径 404 与超时同归 `Failure("network unavailable")`，用户无从
 * 判断该去哪下载）。`throwStatusCodeException(true)` 下非 2xx 抛
 * `HttpRequests$HttpStatusException.getStatusCode()`（243 基线 app-client.jar javap 实证）。
 */
object UpdateApi {

    private val LOG = Logger.getInstance(UpdateApi::class.java)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** 清单获取结果（R62 三态判别的数据源；[PluginUpdateRunner.check] 按分支映射 FailureKind）。 */
    sealed interface FetchOutcome {
        /** 成功取到清单文本。 */
        data class Success(val text: String) : FetchOutcome

        /** HTTP 层有响应但状态码非 2xx（404/410=通道缺失；其余按网络态降级并带状态码）。 */
        data class HttpError(val status: Int) : FetchOutcome

        /** 连接层失败（超时/DNS/拒绝连接等）。 */
        data class NetworkError(val description: String) : FetchOutcome
    }

    /** 获取清单（结果三分支；错误记日志——降级由调用方处理：设置页显示失败原因，启动检查静默）。 */
    fun fetchOutcome(url: String): FetchOutcome = runCatching {
        HttpRequests.request(url)
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .productNameAsUserAgent()
            .throwStatusCodeException(true)
            .readString(null)
    }.fold(
        onSuccess = { FetchOutcome.Success(it) },
        onFailure = { error ->
            LOG.warn("update manifest fetch failed: $url — ${error.message}")
            val status = (error as? HttpRequests.HttpStatusException)?.statusCode
            if (status != null) FetchOutcome.HttpError(status)
            else FetchOutcome.NetworkError(error.message ?: error.toString())
        },
    )

    /**
     * 获取清单文本（兼容视图：仅成功返回文本，其余 null——R62 前的调用方语义；
     * 新代码走 [fetchOutcome]）。
     */
    fun fetchManifest(url: String): String? = (fetchOutcome(url) as? FetchOutcome.Success)?.text

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
