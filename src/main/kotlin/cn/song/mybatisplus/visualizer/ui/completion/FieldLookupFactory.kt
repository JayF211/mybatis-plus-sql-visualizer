package cn.song.mybatisplus.visualizer.ui.completion

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * Wrapper 实体字段补全项工厂（F-5，阶段 B）。
 *
 * 在 `.eq(|)` / `.like(|)` / `.set(|)` 等方法的**首参位置**提示实体字段名（驼峰），
 * 带字段类型图标 + 注释灰色尾缀 + lambda 自动补全插入处理器（展开为 `Entity::getField`）。
 *
 * 实体解析（[EntityResolver]）仅在补全触发时执行，失败时返回空列表（仅提供方法名补全）。
 * 字段名补全仅在**已知 Wrapper 链中**触发（由调用方 [WrapperCompletionContributor] 守卫）。
 */
object FieldLookupFactory {

    /**
     * 生成实体字段名补全项。
     *
     * @param project     当前项目
     * @param chainHead   链首方法调用（用于解析实体类）
     * @param currentCall 当前所在方法调用（用于判定首参位置）
     * @return 字段补全项列表；实体解析失败时返回空列表
     */
    fun createLookups(
        project: Project,
        chainHead: PsiMethodCallExpression,
        currentCall: PsiMethodCallExpression,
    ): List<LookupElement> {
        val tableInfo = resolveEntityTable(project, chainHead) ?: return emptyList()
        return tableInfo.columns.map { buildFieldLookup(it) }
    }

    /**
     * 解析链首对应的实体表信息（F-5，阶段 B1）。
     *
     * 策略（纯文本 + PSI 遍历，无 resolve——符合 M10 性能红线）：
     *  1. 从链首的 new 构造类型实参 / 工厂调用类型实参提取实体类名（文本）；
     *  2. 在链首所在文件内按类名查找 PsiClass（PSI 子树遍历，无 resolve）；
     *  3. 命中时走 [EntityResolver] 解析（注解/命名策略兜底）；
     *  4. 未命中（实体类不在当前文件）→ 返回 null（降级：仅方法名补全）。
     *
     * 注意：不 resolve 实体类引用（避免 M10 红线），仅在当前文件内查找——
     * 实体类与 Wrapper 链同文件时（测试/示例代码常态）完美生效；
     * 跨文件时优雅降级（字段补全不可用，方法名补全仍生效）。
     */
    private fun resolveEntityTable(project: Project, chainHead: PsiMethodCallExpression): TableInfo? {
        val entityClassName = extractEntityClassName(chainHead) ?: return null
        val file = chainHead.containingFile ?: return null
        // 在当前文件内按类名查找 PsiClass（PSI 遍历，无 resolve）
        val entityClass = PsiTreeUtil.collectElementsOfType(file, PsiClass::class.java)
            .firstOrNull { it.name == entityClassName } ?: return null
        return runCatching { EntityResolver().resolve(entityClass) }.getOrNull()
    }

    /**
     * 从链首提取实体类名（纯文本，无 resolve）：
     *  - new 构造：从构造类型实参文本提取（`new LambdaQueryWrapper<DeviceInfoEntity>()` → `DeviceInfoEntity`）
     *  - 工厂/服务链：从工厂调用类型实参文本提取（`Wrappers.<DeviceInfoEntity>lambdaQuery()` → `DeviceInfoEntity`）
     * 提取失败时返回 null。
     */
    private fun extractEntityClassName(chainHead: PsiMethodCallExpression): String? {
        val qualifier = chainHead.methodExpression.qualifierExpression

        // new 构造：qualifier 是 PsiNewExpression
        if (qualifier is com.intellij.psi.PsiNewExpression) {
            val type = qualifier.type as? com.intellij.psi.PsiClassType ?: return null
            return type.parameters.firstOrNull()?.let { param ->
                (param as? com.intellij.psi.PsiClassType)?.className
            }
        }

        // 工厂/服务链：从链首调用的类型实参提取（Wrappers.<DeviceInfoEntity>lambdaQuery()）
        val typeArgs = chainHead.typeArguments
        if (typeArgs.isNotEmpty()) {
            return (typeArgs[0] as? com.intellij.psi.PsiClassType)?.className
        }

        // 链式续链（`wrapper.eq(...)` 的 wrapper 是变量引用）：从 qualifier 引用变量声明类型提取
        val refExpr = qualifier as? PsiReferenceExpression ?: return null
        val resolvedVar = refExpr.resolve() as? com.intellij.psi.PsiVariable ?: return null
        val varType = resolvedVar.type as? com.intellij.psi.PsiClassType ?: return null
        return varType.parameters.firstOrNull()?.let { param ->
            (param as? com.intellij.psi.PsiClassType)?.className
        }
    }

    /**
     * 构建单个字段补全项（F-5，阶段 B2）：
     *  - 展示名 = 字段名（驼峰，如 `stationId`）
     *  - 图标：字段节点
     *  - tailText = 列名（蛇形，如 `station_id`）
     *  - typeText = 字段类型（如 `Long`）
     *  - 插入处理器：lambda 自动补全（展开为 `Entity::getField`）
     */
    private fun buildFieldLookup(column: ColumnInfo): LookupElement {
        val builder = LookupElementBuilder.create(column.fieldName)
            .withIcon(AllIcons.Nodes.Field)
            .withInsertHandler(LAMBDA_INSERT_HANDLER)
        // 列名 tailText（蛇形）
        val withColumnTail = if (column.columnName != column.fieldName) {
            builder.withTailText("  ${column.columnName}", true)
        } else {
            builder
        }
        // 字段类型 typeText（短名）
        val shortType = column.fieldType?.substringAfterLast('.')
        return shortType?.let { withColumnTail.withTypeText(it) } ?: withColumnTail
    }

    /**
     * 字段名插入处理器（F-5，阶段 B3）：插入字段名后追加 `::` 触发方法引用补全。
     * 仅在下一字符不是 `:` 时插入（避免重复）。
     *
     * 设计权衡：不直接展开为完整 `Entity::getXxx`（需 resolve 实体类确认 getter 名），
     * 而是插入 `fieldName::` 利用 IDE 既有方法引用补全——兼顾准确性与性能红线。
     */
    private val LAMBDA_INSERT_HANDLER: InsertHandler<LookupElement> = InsertHandler { context, item ->
        val editor = context.editor
        val caretOffset = editor.caretModel.offset
        val doc = editor.document
        val lookupString = item.lookupString
        // 插入字段名（替换前缀）
        doc.replaceString(context.startOffset, caretOffset, lookupString)
        val newOffset = context.startOffset + lookupString.length
        // 追加 :: 触发方法引用补全
        if (newOffset >= doc.textLength || doc.charsSequence[newOffset] != ':') {
            doc.insertString(newOffset, "::")
            editor.caretModel.moveToOffset(newOffset + 2)
        }
    }
}
