package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.project.DumbAware
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F93（T-PerfSweep25）顺手件判别：ResultMapColumnCompletionContributor 补 DumbAware 标记——
 * 对齐 XmlSqlCompletionContributor / MybatisConfigCompletionContributor / WrapperCompletionContributor
 * 三兄弟口径（T-XmlCmpl2 教训：平台按 `instanceof DumbAware` 判定贡献器 dumb 期可用性，
 * 缺该接口 dumb 期被静默跳过；仅覆写 isDumbAware() 不够，须实现标记接口）。
 * 反向验证：摘掉 `, DumbAware` 标记 → 此用例红。
 */
class ResultMapColumnCompletionDumbAwareTest {

    @Test
    fun testF93_补全贡献器DumbAware对齐三兄弟() {
        assertTrue(
            "resultMap 补全贡献器须实现 DumbAware 标记接口（修复前 dumb 期静默缺席 → 红）",
            DumbAware::class.java.isAssignableFrom(ResultMapColumnCompletionContributor::class.java),
        )
    }
}
