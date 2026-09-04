package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;

/**
 * F-44-7：特殊来源标记——MP 分页 COUNT 查询。
 * MP PaginationInnerInterceptor 自动执行的 COUNT 查询不经过本拦截器，无源码标记。
 * 使用特殊标记 "__MP_PAGINATION_COUNT__" 标识，UI 层据此展示友好文案。
 */
public final class SourceMarkers {

    public static final String MP_PAGINATION_COUNT = "__MP_PAGINATION_COUNT__";

    private SourceMarkers() {
    }

    /** MP 分页 COUNT 查询的来源标记。 */
    public static Pair<String, Integer> mpPaginationCount() {
        return new Pair<>(MP_PAGINATION_COUNT, 0);
    }

    /** 是否为 MP 分页 COUNT 查询标记。 */
    public static boolean isMpPaginationCount(String sourceFile) {
        return MP_PAGINATION_COUNT.equals(sourceFile);
    }
}
