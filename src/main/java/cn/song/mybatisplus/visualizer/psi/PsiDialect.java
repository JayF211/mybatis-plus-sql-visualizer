package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;

/**
 * T-M23.5：psi 层共享的当前方言读取（EntityResolver.inferTableName 同款模式）——
 * 设置服务不可用时兜底 MySQL（测试环境零影响）。
 *
 * <p>供批量文本模板（{@link BatchOperationConverter}）与解析期 raw 拼接
 * （LambdaUpdateConverter / UpdateJoinConverter / QueryWrapperParser）做保留字引用。
 *
 * <p>由 Kotlin 顶层函数 {@code internal fun currentDialect(): Dialect} 转换而来。
 */
public final class PsiDialect {

    private PsiDialect() {
    }

    /**
     * 读取当前方言；设置服务不可用（如测试环境无 Application）时兜底 MySQL。
     *
     * @return 当前方言（未知名称 → {@code MySqlDialect}）
     */
    public static Dialect currentDialect() {
        try {
            return DialectRegistry.INSTANCE.byName(PluginSettings.getInstance().getState().getDialect());
        } catch (Throwable ignored) {
            return DialectRegistry.INSTANCE.byName(null);
        }
    }
}
