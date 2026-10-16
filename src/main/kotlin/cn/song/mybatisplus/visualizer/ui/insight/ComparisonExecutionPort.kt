package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.execute.*

/** Small execution seam: UI tests exercise real consent without opening a database connection. */
internal fun interface ComparisonExecutionPort {
    fun execute(request: SqlComparisonRequest, safety: ComparisonSafetyContext, target: ComparisonTargetSession,
                cancellation: ComparisonCancellation, result: (ComparisonExecutionResult) -> Unit)
}
