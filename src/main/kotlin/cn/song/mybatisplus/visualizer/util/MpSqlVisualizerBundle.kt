package cn.song.mybatisplus.visualizer.util

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

/**
 * 消息 Bundle（M6-03 国际化）。
 *
 * 基于 [DynamicBundle]：根据 IDE 界面语言自动选择 properties
 * （默认英文 `MpSqlVisualizerBundle.properties`，中文 `_zh`）。
 * 所有运行时 UI 文案统一经 [message] 获取，禁止硬编码。
 */
object MpSqlVisualizerBundle {

    private const val BUNDLE_NAME = "messages.MpSqlVisualizerBundle"

    private val instance = DynamicBundle(MpSqlVisualizerBundle::class.java, BUNDLE_NAME)

    /**
     * 取本地化文案。
     *
     * @param key    文案 key（见 messages/MpSqlVisualizerBundle*.properties）
     * @param params 格式化参数（MessageFormat 风格 {0}）
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any?): String =
        instance.getMessage(key, *params)
}
