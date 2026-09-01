package cn.song.mybatisplus.visualizer.sql.interceptor

import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.UpdateStatement

/**
 * DataPermission 拦截器模拟（F-6 Phase C）：占位提示 + 用户模拟条件注入。
 *
 * DataPermissionInterceptor 运行时根据用户角色动态追加条件，无法静态模拟。
 * 本插件提供两类补偿：
 *  - [buildWarning]：警告区提示运行时差异（对齐 BlockAttack 警示风格）
 *  - [injectMockCondition]：用户手动填写的 WHERE 片段追加到语句末尾
 *
 * 本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
object DataPermissionPlaceholder {

    /** DataPermission 运行时差异提示文案（对齐 BlockAttack 警示风格）。 */
    const val WARNING_MESSAGE =
        "⚠ DataPermissionInterceptor 会根据用户角色动态追加条件，预览可能与运行时存在差异"

    /**
     * 构建 DataPermission 运行时差异提示。
     *
     * @param enabled dataPermissionHint 开关是否开启
     * @return 提示文案（开关关闭时返回 null）
     */
    fun buildWarning(enabled: Boolean): String? = if (enabled) WARNING_MESSAGE else null

    /**
     * 向语句末尾追加用户填写的模拟 WHERE 片段。
     *
     * 仅 SELECT/UPDATE/DELETE 注入（INSERT 无 WHERE，本方法不处理）；
     * 模拟条件为空时不注入（返回原实例）。
     *
     * @param statement      当前语句模型
     * @param mockCondition  用户填写的 WHERE 片段（如 `dept_id IN (1,2,3)`），空白表示无模拟条件
     * @return 追加条件后的新语句（无需追加时返回原实例）
     */
    fun injectMockCondition(statement: SqlStatement, mockCondition: String): SqlStatement {
        val condition = mockCondition.trim()
        if (condition.isBlank()) return statement
        return when (statement) {
            is SelectStatement -> statement.copy(statement.conditions + Condition.Raw(condition))
            is UpdateStatement -> statement.copy(statement.table, statement.sets, statement.conditions + Condition.Raw(condition), statement.rawTail, statement.tableAlias, statement.joins)
            is DeleteStatement -> statement.copy(statement.conditions + Condition.Raw(condition))
        }
    }
}
