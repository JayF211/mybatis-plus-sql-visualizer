package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.util.Key

/**
 * SQL 自动折叠公共应用器（T-AutoFold F0）：六条 SQL 预览编辑器支链的唯一收口。
 *
 * 按 [SqlAutoFoldPlanner] 的规划对目标编辑器**全量重建本方折叠区域**（先摘自己上一轮清单、
 * 再按当前文本添加）——每次程序性写入后调用即覆盖平台可能的重算抹除（防御策略，见计划 §2.3）。
 *
 * 区域清单挂 Editor UserData（[KEY_LAST_FOLD_REGIONS]）：跨类共享、随编辑器释放自然消失；
 * 清除时**只摘清单内的**，绝不 [com.intellij.openapi.editor.FoldingModel.getAllFoldRegions]
 * 全清——原生控制台/平台可能挂有 ConsoleFolding 等他人区域，越权摘除即事故。
 *
 * 开关关闭/任何平台异常 → 清除本方区域后静默返回（写入管线零折叠操作，行为与关闭前逐字节
 * 一致；折叠是锦上添花，不因平台异常影响内容展示）。全部调用点已在 EDT，
 * [com.intellij.openapi.editor.FoldingModel.runBatchFoldingOperation] 为 UI 层批量刷新，
 * 无 read/write 锁问题。
 *
 * 外观最小接线：开启态下若 [com.intellij.openapi.editor.EditorSettings.isFoldingOutlineShown]
 * 为 false 仅补设该一项（双击弹窗紧凑外观、原生控制台需要；关闭态不动任何外观设置）。
 */
object SqlAutoFolding {

    /** 本方折叠区域清单（Editor UserData；批操作外更新，批操作内只读）。 */
    private val KEY_LAST_FOLD_REGIONS = Key.create<Array<FoldRegion>>("MP_SQL_AUTO_FOLD_REGIONS")

    private val LOG = Logger.getInstance(SqlAutoFolding::class.java)

    /** 按当前设置对 editor 重建折叠区域；text = 当前全文。关/异常 → 清除本方区域后静默返回。 */
    fun applyTo(editor: Editor, text: String) {
        if (editor.isDisposed) return
        val state = runCatching { PluginSettings.getInstance().state }.getOrNull()
        val enabled = state?.autoFoldEnabled ?: false
        if (!enabled) {
            clearOwnRegions(editor)
            return
        }
        val threshold = state?.autoFoldFieldThreshold ?: PluginSettings.DEFAULT_AUTO_FOLD_FIELD_THRESHOLD
        runCatching {
            if (!editor.settings.isFoldingOutlineShown) {
                editor.settings.isFoldingOutlineShown = true
            }
        }
        val template = runCatching { MpSqlVisualizerBundle.message("preview.fold.placeholder") }
            .getOrElse { "… {0} columns" }
        val specs = runCatching { SqlAutoFoldPlanner.plan(text, threshold, template) }
            .getOrElse {
                LOG.warn("[T-AutoFold] plan failed, skip folding: ${it.message}")
                emptyList()
            }
        runCatching {
            editor.foldingModel.runBatchFoldingOperation {
                clearOwnRegions(editor)
                val added = ArrayList<FoldRegion>(specs.size)
                for (spec in specs) {
                    if (spec.startOffset < 0 || spec.endOffset > text.length || spec.startOffset >= spec.endOffset) continue
                    // 相交即拒（返回 null）：规划器自保证互斥，此处跳过即可
                    val region = editor.foldingModel.addFoldRegion(spec.startOffset, spec.endOffset, spec.placeholder)
                    if (region != null) {
                        region.isExpanded = false
                        added += region
                    }
                }
                editor.putUserData(KEY_LAST_FOLD_REGIONS, added.toTypedArray())
            }
        }.onFailure {
            LOG.warn("[T-AutoFold] apply failed: ${it.message}")
        }
    }

    /** 清除本方上一轮区域（ UserData 清单内逐个摘除；他人区域不碰）。 */
    private fun clearOwnRegions(editor: Editor) {
        val old = editor.getUserData(KEY_LAST_FOLD_REGIONS) ?: return
        editor.putUserData(KEY_LAST_FOLD_REGIONS, null)
        runCatching {
            editor.foldingModel.runBatchFoldingOperation {
                for (region in old) {
                    if (region.isValid) {
                        editor.foldingModel.removeFoldRegion(region)
                    }
                }
            }
        }
    }
}
