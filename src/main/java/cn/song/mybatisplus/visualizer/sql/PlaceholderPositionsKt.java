// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/sql/PlaceholderPositions.kt 移植
package cn.song.mybatisplus.visualizer.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * string-aware {@code ?} 占位符定位（T-HibDualCapture；T-PSG-01 由 {@code log} 包上提到 {@code sql} 包，
 * 供日志还原 / 复制可执行 SQL / 面板回填三条拼接链共用同一份位点口径）：单引号字符串字面量
 * （{@code ''} 转义）与 {@code --} 行注释、块注释内的 {@code ?} 不算占位符——中文问句、PG {@code ?|}
 * 操作符不再被误计数/误回填。还原（{@code LogSqlRestorer.restore}）与预览识别计数
 * （{@code log.countPlaceholders} 薄封装）共用本函数，避免错位。
 *
 * <p>双引号标识符内的 {@code ?} 不在保护范围（标准 SQL 标识符不含 {@code ?}，误伤面可忽略）。</p>
 *
 * <p>移植注记：master 侧为顶层 {@code internal fun placeholderPositions}，Java 侧按 k2j 既定惯例
 * （顶层声明 → {@code XKt} 门面，见 {@code LogSqlRestorerKt} 先例）落成本门面类的静态方法。</p>
 */
public final class PlaceholderPositionsKt {

    private PlaceholderPositionsKt() {
    }

    /** 位点清单（升序，元素为 {@code ?} 在 {@code sql} 中的下标）。 */
    public static List<Integer> placeholderPositions(String sql) {
        List<Integer> positions = new ArrayList<>();
        boolean inString = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    inString = false;
                }
            } else {
                if (c == '\'') {
                    inString = true;
                } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    while (i < sql.length() && sql.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i++;
                } else if (c == '?') {
                    positions.add(i);
                }
            }
            i++;
        }
        return positions;
    }
}
