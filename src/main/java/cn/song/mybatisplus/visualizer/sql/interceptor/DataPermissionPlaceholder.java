package cn.song.mybatisplus.visualizer.sql.interceptor;

import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * DataPermission 拦截器模拟（F-6 Phase C）：占位提示 + 用户模拟条件注入。
 *
 * <p>DataPermissionInterceptor 运行时根据用户角色动态追加条件，无法静态模拟。
 * 本插件提供两类补偿：</p>
 * <ul>
 *  <li>{@link #buildWarning}：警告区提示运行时差异（对齐 BlockAttack 警示风格）</li>
 *  <li>{@link #injectMockCondition}：用户手动填写的 WHERE 片段追加到语句末尾</li>
 * </ul>
 *
 * <p>本类为纯函数，不依赖 PSI / IDE，可独立单测。</p>
 */
public final class DataPermissionPlaceholder {

    private DataPermissionPlaceholder() {}

    /** DataPermission 运行时差异提示文案（对齐 BlockAttack 警示风格）。 */
    public static final String WARNING_MESSAGE =
        "⚠ DataPermissionInterceptor 会根据用户角色动态追加条件，预览可能与运行时存在差异";

    /**
     * 构建 DataPermission 运行时差异提示。
     *
     * @param enabled dataPermissionHint 开关是否开启
     * @return 提示文案（开关关闭时返回 null）
     */
    public static String buildWarning(boolean enabled) {
        return enabled ? WARNING_MESSAGE : null;
    }

    /**
     * 向语句末尾追加用户填写的模拟 WHERE 片段。
     *
     * <p>仅 SELECT/UPDATE/DELETE 注入（INSERT 无 WHERE，本方法不处理）；
     * 模拟条件为空时不注入（返回原实例）。</p>
     *
     * @param statement     当前语句模型
     * @param mockCondition 用户填写的 WHERE 片段（如 {@code dept_id IN (1,2,3)}），空白表示无模拟条件
     * @return 追加条件后的新语句（无需追加时返回原实例）
     */
    public static SqlStatement injectMockCondition(SqlStatement statement, String mockCondition) {
        String condition = mockCondition == null ? "" : mockCondition.trim();
        if (condition.isBlank()) return statement;
        List<Condition> appended = new ArrayList<>();
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;
            appended.addAll(select.getConditions());
            appended.add(new Condition.Raw(condition));
            return select.copy(appended);
        }
        if (statement instanceof UpdateStatement) {
            UpdateStatement update = (UpdateStatement) statement;
            appended.addAll(update.getConditions());
            appended.add(new Condition.Raw(condition));
            return update.copy(update.getTable(), update.getSets(), appended,
                update.getRawTail(), update.getTableAlias(), update.getJoins());
        }
        if (statement instanceof DeleteStatement) {
            DeleteStatement delete = (DeleteStatement) statement;
            appended.addAll(delete.getConditions());
            appended.add(new Condition.Raw(condition));
            return delete.copy(appended);
        }
        return statement;
    }
}
