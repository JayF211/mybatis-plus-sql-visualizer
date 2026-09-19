package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import com.intellij.openapi.project.Project;

/**
 * T-M23.5：psi 层共享的当前方言读取（EntityResolver.inferTableName 同款模式）——
 * 设置服务不可用时兜底 MySQL（测试环境零影响）。
 *
 * <p>供批量文本模板（{@link BatchOperationConverter}）与解析期 raw 拼接
 * （LambdaUpdateConverter / UpdateJoinConverter / QueryWrapperParser）做保留字引用。</p>
 *
 * <p>T-DialectAutoInfer：方言模式 auto 开启时走智能推断（agent 最近连接 > IDE 数据源 > MySQL 回退），
 * 手动模式按设置值；settings 不可用（测试环境）仍兜底 MySQL。
 * 手动值解析由 {@link DialectRegistry#effective} 内部完成，此处不再读 PluginSettings。</p>
 *
 * <p>由 Kotlin 顶层函数 {@code internal fun currentDialect(project: Project? = null): Dialect} 转换而来。</p>
 */
public final class PsiDialect {

    private PsiDialect() {
    }

    /**
     * 读取当前方言（project 缺省 null：无项目上下文，auto 推断退化为 agent/全局设置）。
     * 设置服务不可用（如测试环境无 Application）时兜底 MySQL。
     *
     * @param project 当前项目；null 表示无项目上下文
     * @return 当前方言（未知名称 → {@code MySqlDialect}）
     */
    public static Dialect currentDialect(Project project) {
        try {
            // T-TS-FixC：模式与手动值均由 DialectRegistry.effective 内部按「项目级 > 全局」解析，
            // 本层只负责把宿主 project 传下去（master 形态：DialectRegistry.effective(project = project)）
            return DialectRegistry.INSTANCE.effective(project);
        } catch (Throwable ignored) {
            return DialectRegistry.INSTANCE.byName(null);
        }
    }

    /** Kotlin 默认参数等价重载：{@code currentDialect()}（project = null）。 */
    public static Dialect currentDialect() {
        return currentDialect(null);
    }
}
