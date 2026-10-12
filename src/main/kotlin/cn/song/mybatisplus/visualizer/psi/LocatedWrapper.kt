package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

// WrapperLocator 的定位结果模型
// （T-M53-II 2026-10-10：由 WrapperLocator.kt 原样搬出，同包同可见性，零行为改动。）
/**
 * 定位结果：调用链 + 实体表信息。
 *
 * @param chain Wrapper 调用链
 * @param table 实体解析的表信息（实体类缺失时为 null）
 * @param entityFile 实体类所在文件（@TableName/@TableField 注解来源；文本兜底无实体类时为 null）
 */
data class LocatedWrapper(
    val chain: WrapperChain,
    val table: TableInfo?,
    val entityFile: com.intellij.psi.PsiFile? = null,
    /**
     * O-8（缺陷 B）：字符串服务链实体解析失败标记——三通道（类型实参/调用返回类型/服务泛型实参）
     * 与链内方法引用推断全失败（table=null）。preview 侧据此返回显式降级警示而非静默 null；
     * 仅服务链形态置位（[WrapperLocator.buildChainFromFactory]），工厂/Kotlin/构造形态维持 null 语义。
     */
    val entityResolutionFailed: Boolean = false,
) {
    /** T-M19.4：解析时的实体文件修改戳——缓存命中时校验（实体文件变更即失效重算）。 */
    val entityFileStamp: Long = entityFile?.modificationStamp ?: -1L

    /**
     * T-M19.4（用户实测：实体文件新增/修改 @TableName 后预览不生效——缓存按**锚点文件**戳失效、
     * 实体文件变化不触发）：实体文件是否仍与解析时一致；无实体类（文本兜底）视为有效。
     */
    fun entityFileValid(): Boolean = entityFile == null || entityFile.modificationStamp == entityFileStamp
}

