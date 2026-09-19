// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/SqlCompletionCandidates.kt 移植
package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-DbSchemaCompletion：列补全候选的合并与展示（纯逻辑，可表驱动测试）——顶层函数载体。
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
 * <p>展示层 {@link #columnLookupElement} 把注释与 DB 类型串一起放进 tailText，让「这条来自库」在弹窗里可见；
 * 注释仍注册为 lookup string（输入「姓名」可命中 {@code username}，M16 语义保留）。
 */
public final class SqlCompletionCandidatesKt {

    private SqlCompletionCandidatesKt() {
    }

    /** 合并列候选：实体列在前，数据源独有列追加；同名列数据源信息优先。 */
    public static List<ColumnCandidate> mergeColumnCandidates(SqlCompletionContext context) {
        LinkedHashMap<String, ColumnCandidate> merged = new LinkedHashMap<>();
        for (String column : context.columns) {
            if (!column.isBlank()) {
                merged.put(column, new ColumnCandidate(column, context.columnComments.get(column), null));
            }
        }
        for (Map.Entry<String, DatabaseSchemaResolver.ColumnMeta> entry : context.schemaColumns.entrySet()) {
            String column = entry.getKey();
            if (column.isBlank()) {
                continue;
            }
            DatabaseSchemaResolver.ColumnMeta meta = entry.getValue();
            ColumnCandidate fromEntity = merged.get(column);
            String comment = (meta.comment() != null && !meta.comment().isBlank())
                    ? meta.comment()
                    : (fromEntity != null ? fromEntity.comment() : null);
            String typeSpec = (meta.typeSpec() != null && !meta.typeSpec().isBlank())
                    ? meta.typeSpec()
                    : (fromEntity != null ? fromEntity.typeSpec() : null);
            merged.put(column, new ColumnCandidate(column, comment, typeSpec));
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 候选的 lookup 展示：tailText = 注释 + DB 类型串（各自非空才拼，空格分隔）；注释可搜索。
     *
     * <p>与表名候选的 {@code withComment}（仅注释）分开：列候选多一段类型串，类型正是「来自数据源」的可视标记。
     */
    public static LookupElement columnLookupElement(ColumnCandidate candidate) {
        List<String> tailParts = new ArrayList<>();
        if (candidate.comment() != null && !candidate.comment().isBlank()) {
            tailParts.add(candidate.comment());
        }
        if (candidate.typeSpec() != null && !candidate.typeSpec().isBlank()) {
            tailParts.add(candidate.typeSpec());
        }
        String tail = String.join("  ", tailParts);
        LookupElementBuilder builder = LookupElementBuilder.create(candidate.name());
        if (!tail.isEmpty()) {
            builder = builder.withTailText("  " + tail);
        }
        if (candidate.comment() != null && !candidate.comment().isBlank()) {
            builder = builder.withLookupString(candidate.comment());
        }
        return builder;
    }
}
