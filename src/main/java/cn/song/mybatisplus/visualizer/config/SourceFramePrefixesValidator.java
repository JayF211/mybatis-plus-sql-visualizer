// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/config/SourceFramePrefixesValidator.kt 移植
package cn.song.mybatisplus.visualizer.config;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import kotlin.Pair;

/**
 * T-PrefixValidate：两栏前缀登记（skip＝「源码捕获帧过滤前缀」/ biz＝「JAR 业务帧包名前缀」）的
 * 格式校验与一键修复。
 *
 * <p>为什么抽成纯函数：面板只做「展示 + 回写」，判定与修复逻辑全部收在本类——
 * 无平台依赖、单测可直接覆盖（面板层只补一条 UI 装配测试）。</p>
 *
 * <p>语义红线（勿在修复里翻转）：同一前缀同时出现在两栏时 <b>skip 优先</b>——agent 侧跳过发生在建池
 * 阶段、biz 只影响 L1 选主，运行时本就是 skip 赢（demo PR !1 E29 P2 档：业务帧被跳过后候选池
 * 抽空、跳转能力消失，故冲突必须显式暴露而不是留给用户猜）。</p>
 */
public final class SourceFramePrefixesValidator {

    private SourceFramePrefixesValidator() {}

    /** 单条前缀的合法形态：Java 标识符段以点连接，允许以点结尾（前缀语义推荐以「.」收尾）。 */
    private static final Pattern PREFIX_REGEX =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.?$");

    /** 两栏归属（决定提示文案与修复方向：冲突时保留 SKIP 删 BIZ）。 */
    public enum PrefixField { SKIP, BIZ }

    /** 非法行：{@code lineNo} 为文本框内 1 起始的原始行号（含空行计数，便于用户回找）。 */
    public record InvalidLine(
            /** 该行所属栏位。 */
            @NotNull PrefixField field,
            /** 文本框内 1 起始的原始行号（含空行计数）。 */
            int lineNo,
            /** 该行 trim 后的原文。 */
            @NotNull String text
    ) {
    }

    /** 同栏重复登记（无害但属脏数据，修复去重）。 */
    public record DuplicatePrefix(
            /** 该前缀所属栏位。 */
            @NotNull PrefixField field,
            /** 重复的前缀。 */
            @NotNull String prefix
    ) {
    }

    /** 跨栏冲突：同一前缀两栏都登记，skip 静默赢、biz 侧不生效。 */
    public record CrossFieldConflict(
            /** 两栏重复登记的前缀。 */
            @NotNull String prefix
    ) {
    }

    /**
     * 校验结果报告。
     *
     * @param invalidLines 非法行清单（两栏合并）
     * @param duplicates   同栏重复前缀清单
     * @param conflicts    跨栏冲突清单
     */
    public record Report(
            /** 非法行清单（两栏合并）。 */
            @NotNull List<InvalidLine> invalidLines,
            /** 同栏重复前缀清单。 */
            @NotNull List<DuplicatePrefix> duplicates,
            /** 跨栏冲突清单。 */
            @NotNull List<CrossFieldConflict> conflicts
    ) {
        /** 是否存在问题（任一类非空即有问题）。 */
        public boolean hasProblems() {
            return !invalidLines.isEmpty() || !duplicates.isEmpty() || !conflicts.isEmpty();
        }
    }

    /** 校验两栏文本；空行不计问题（apply 本就会丢弃），只挑非空行里的真问题。 */
    @NotNull
    public static Report validate(@NotNull String skipText, @NotNull String bizText) {
        List<Pair<Integer, String>> skipLines = nonBlankLines(skipText);
        List<Pair<Integer, String>> bizLines = nonBlankLines(bizText);
        List<InvalidLine> invalid = new ArrayList<>(invalidLines(PrefixField.SKIP, skipText));
        invalid.addAll(invalidLines(PrefixField.BIZ, bizText));
        List<DuplicatePrefix> duplicates = new ArrayList<>(duplicates(PrefixField.SKIP, skipLines));
        duplicates.addAll(duplicates(PrefixField.BIZ, bizLines));
        Set<String> skipPrefixes = new LinkedHashSet<>();
        for (Pair<Integer, String> line : skipLines) {
            skipPrefixes.add(line.getSecond());
        }
        Set<String> bizPrefixes = new LinkedHashSet<>();
        for (Pair<Integer, String> line : bizLines) {
            bizPrefixes.add(line.getSecond());
        }
        // 交集成序＝skip 栏首个出现顺序（Kotlin Set.intersect 同为保序 LinkedHashSet 语义）
        List<CrossFieldConflict> conflicts = new ArrayList<>();
        for (String prefix : skipPrefixes) {
            if (bizPrefixes.contains(prefix)) {
                conflicts.add(new CrossFieldConflict(prefix));
            }
        }
        return new Report(invalid, duplicates, conflicts);
    }

    /**
     * 一键修复（两栏一起回写）：逐行 trim → 丢空行 → 丢非法行（无法替用户发明合法包名，只能剔除并提示）
     * → 同栏去重（保留首个）→ 跨栏冲突按 skip 优先从 BIZ 侧剔除。修复结果幂等（对已修复文本再跑不变）。
     *
     * @return first=skip 栏修复后文本，second=biz 栏修复后文本
     */
    @NotNull
    public static Pair<String, String> autoFix(@NotNull String skipText, @NotNull String bizText) {
        List<String> skip = normalize(skipText);
        List<String> biz = new ArrayList<>();
        for (String line : normalize(bizText)) {
            if (!skip.contains(line)) {
                biz.add(line);
            }
        }
        return new Pair<>(String.join("\n", skip), String.join("\n", biz));
    }

    private static List<String> normalize(@NotNull String text) {
        List<String> result = new ArrayList<>();
        for (Pair<Integer, String> line : nonBlankLines(text)) {
            String value = line.getSecond();
            if (PREFIX_REGEX.matcher(value).matches() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    /** 非空行列表：first＝原始 1 起始行号（空行也计数），second＝trim 后内容。 */
    private static List<Pair<Integer, String>> nonBlankLines(@NotNull String text) {
        List<Pair<Integer, String>> result = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int idx = 0; idx < lines.length; idx++) {
            String trimmed = lines[idx].trim();
            if (!trimmed.isEmpty()) {
                result.add(new Pair<>(idx + 1, trimmed));
            }
        }
        return result;
    }

    private static List<InvalidLine> invalidLines(@NotNull PrefixField field, @NotNull String text) {
        List<InvalidLine> result = new ArrayList<>();
        for (Pair<Integer, String> line : nonBlankLines(text)) {
            if (!PREFIX_REGEX.matcher(line.getSecond()).matches()) {
                result.add(new InvalidLine(field, line.getFirst(), line.getSecond()));
            }
        }
        return result;
    }

    private static List<DuplicatePrefix> duplicates(@NotNull PrefixField field, List<Pair<Integer, String>> lines) {
        // groupingBy+eachCount 同形：出现顺序计数（LinkedHashMap），只留计数 &gt; 1 的（首次出现序）
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Pair<Integer, String> line : lines) {
            String value = line.getSecond();
            if (PREFIX_REGEX.matcher(value).matches()) {
                counts.merge(value, 1, Integer::sum);
            }
        }
        List<DuplicatePrefix> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                result.add(new DuplicatePrefix(field, entry.getKey()));
            }
        }
        return result;
    }
}
