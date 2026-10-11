// T-SyncK2JMaster482 自 master sql/interceptor/PaginationSimulator.kt 移植（R12 T-PaginationSim）
package cn.song.mybatisplus.visualizer.sql.interceptor;

import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 分页拦截器模拟（R12 T-PaginationSim，对齐 F-6 拦截器模拟族三件先例形态）。
 *
 * <p>模拟 MP PaginationInnerInterceptor 在 mapper 方法/XML/注解语句路径的两类产出：
 * <ul>
 *  <li><b>LIMIT 追加</b>：按 {@link cn.song.mybatisplus.visualizer.sql.Dialect#renderLimit} 渲染（调用方执行，
 *      本类负责构造占位 {@link LimitClause}）；</li>
 *  <li><b>COUNT 包裹</b>（D1=A：预览附第二条语句）：{@code SELECT COUNT(*) FROM (原SQL) TOTAL} 基础形态
 *      （D2=A：优化形态——去 ORDER BY 直查表/左联裁剪——登记后续，未实现）。</li>
 * </ul>
 *
 * <p>形态权威来源（V13b 证伪结论，2026-09-30 本地 jar 反编译，非记忆）：
 * <ul>
 *  <li>MP 3.5.17 {@code com.baomidou.mybatisplus.extension.toolkit.SqlParserUtils.getOriginalCountSql}
 *      常量池 {@code SELECT COUNT(*) FROM (%s) TOTAL}（别名 TOTAL、大写）；</li>
 *  <li>MP 3.5.17 {@code Page()} 无参构造默认 current=1 / size=10 / searchCount=true；</li>
 *  <li>MP 3.5.17 {@code IPage.searchCount()} 为 false 时 {@code PaginationInnerInterceptor.willDoQuery}
 *      不执行 COUNT 查询（D3=A 跳过口径的运行时依据）；</li>
 *  <li>MP 3.5.17 {@code BaseMapper} IPage 重载全集 = selectList(IPage,Wrapper) / selectMaps(IPage,Wrapper) /
 *      selectPage(P,Wrapper) / selectMapsPage(P,Wrapper)，<b>无 selectObjs(IPage,...)</b>。</li>
 * </ul>
 *
 * <p>本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
public final class PaginationSimulator {

    private PaginationSimulator() {}

    /** COUNT 包裹表别名（MP getOriginalCountSql 固定形态）。 */
    private static final String COUNT_ALIAS = "TOTAL";

    /** 已含 LIMIT 判定（V13c 边界判定；顶层或子查询一律从保守）。 */
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)\\bLIMIT\\b");

    /**
     * COUNT 包裹（基础形态，D2=A）：{@code SELECT COUNT(*) FROM (原SQL) TOTAL}。
     *
     * @param sql 原 SELECT 文本（建议压缩为单行——包裹形态可读性；MP 运行时传 BoundSql 原文）
     */
    @NotNull
    public static String wrapCountSql(@NotNull String sql) {
        return "SELECT COUNT(*) FROM (" + sql + ") " + COUNT_ALIAS;
    }

    /**
     * XML/注解路径的占位 {@link LimitClause}（mapper 方法 Page 形参运行时值静态不可得）：
     * offset 来源标注 {@code (形参.current - 1) * 形参.size} 计算公式、size 来源标注 {@code 形参.size}
     * （对齐 T-M29-03 运行时占位口径：面板回填后即真实值；{@code 形参.current/size} 为 IPage 访问器语义）。
     *
     * @param paramName mapper 方法 Page/IPage 形参名
     */
    @NotNull
    public static LimitClause placeholderLimitClause(@NotNull String paramName) {
        return new LimitClause(
                new SqlValue.Placeholder("(" + paramName + ".current - 1) * " + paramName + ".size"),
                new SqlValue.Placeholder(paramName + ".size"));
    }

    /**
     * V13c 边界判定：语句文本是否已含 LIMIT 子句（顶层或子查询一律从保守——已含即不模拟追加，
     * 避免双 LIMIT 产出；对 MP 运行时「字面拼接不分现况」的偏离已登记为预览侧保守口径）。
     */
    public static boolean hasExistingLimit(@NotNull String sql) {
        return LIMIT_PATTERN.matcher(sql).find();
    }

    /** 分页占位 {@code ?} 的参数来源清单（offset 公式在前、size 在后，顺序与 SQL 中出现序一致）。 */
    @NotNull
    public static List<String> placeholderParamSources(@NotNull String paramName) {
        return List.of(
                "(" + paramName + ".current - 1) * " + paramName + ".size",
                paramName + ".size");
    }
}
