// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/SqlCompletionCandidates.kt 移植
package cn.song.mybatisplus.visualizer.ui.highlight;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-DbSchemaCompletion：列补全候选的合并与展示（纯逻辑，可表驱动测试）。
 *
 * <p>此前列候选只有实体列（{@link SqlCompletionContext#columns}），数据源 schema 读出来的列<b>仅用于覆盖
 * 同名列注释</b>——库里存在而实体类没有的列（实体未映射的审计列、库表根本没有对应实体等）完全补不出来。
 * 本文件把两个来源合成一份候选清单：
 * <ul>
 *  <li>候选 = 实体列 ∪ 数据源列（列名去重）；</li>
 *  <li><b>顺序</b>：实体列在前（保持实体/SELECT 顺序，用户视线不跳），数据源独有列按库返回顺序追加；</li>
 *  <li><b>信息优先级</b>：同名列的注释与类型取数据源（M35 口径——DB COMMENT 比实体注解权威）。</li>
 * </ul>
 *
 * <p>展示层 {@code columnLookupElement} 把注释与 DB 类型串一起放进 tailText，让「这条来自库」在弹窗里可见；
 * 注释仍注册为 lookup string（输入「姓名」可命中 {@code username}，M16 语义保留）。
 *
 * @param name    列名
 * @param comment 注释（数据源优先，回落实体注解；null/空白 = 无注释）
 * @param typeSpec DB 真实类型串（如 {@code varchar(64)}；仅数据源来源有值，实体列无类型信息时为 null）
 */
public record ColumnCandidate(
        @NotNull String name,
        @Nullable String comment,
        @Nullable String typeSpec) {
}
