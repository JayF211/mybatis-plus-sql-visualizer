// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/update/BarrierRegistry.kt
package cn.song.mybatisplus.visualizer.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jetbrains.annotations.NotNull;

/**
 * F48（T-PerfQuick5）：动态卸载屏障在役登记簿——编程式/结构性平台注册的运行时台账。
 *
 * <p>缘起（性能十七轮 §5.2）：平台只自动摘 EP 声明的扩展，<b>编程式显式注册无人摘除</b>——
 * 动态禁用/更新时旧 classloader 被平台注册表钉住。本登记簿与归档档
 * {@code docs/memory-archive/bug-fixes/project-dynamic-unload-barriers.md} 呼应：把「哪些注册
 * 会挡卸载、各自按什么路径解除」从散落注释收敛为单一事实源，新增编程式注册必须在此登记。</p>
 *
 * <p>摘除路径两型（本登记簿只收<b>屏障项</b>——EP 声明扩展平台卸载时自动移除、本就无屏障，不入册）：
 * <ul>
 * <li>{@link RemovalPath#DISPOSER_CASCADE_ON_UNLOAD}：编程式注册挂 Disposer 子节点于插件应用级
 *   宿主，平台 {@code DynamicPlugins.unloadPlugin} dispose 插件应用级容器时级联摘除
 *   （挂靠定案=ExecDocAutosaveHost KDoc 同款：容器级联释放是平台对插件级注册的惯例摘法，
 *   卸载回调内反手 dispose 自身类有 classloader 时序风险，故不走 DynamicPluginListener）；</li>
 * <li>{@link RemovalPath#STRUCTURAL_NOT_REMOVABLE}：语言级/文件类型级结构性注册，平台不支持
 *   运行时移除（既知形态，登记不修）。</li>
 * </ul>
 */
public final class BarrierRegistry {

    private BarrierRegistry() {}

    /** 摘除路径（KDoc 详注）。 */
    public enum RemovalPath { DISPOSER_CASCADE_ON_UNLOAD, STRUCTURAL_NOT_REMOVABLE }

    /**
     * 在役登记条目。
     *
     * @param id        屏障项唯一标识（幂等键：同 id 覆盖）
     * @param component 被登记的平台注册点（人可读描述，如语言注册表 / addExplicitExtension 调用）
     * @param removal   摘除路径（见 {@link RemovalPath}）
     * @param note      备注：判定依据 / 实证出处 / 是否可摘（登记簿单一事实源）
     */
    public record Entry(
            @NotNull String id,
            @NotNull String component,
            @NotNull RemovalPath removal,
            @NotNull String note) {}

    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    /** 登记一条屏障（幂等：同 id 覆盖）。 */
    public static void register(Entry entry) {
        ENTRIES.removeIf(existing -> Objects.equals(existing.id(), entry.id()));
        ENTRIES.add(entry);
    }

    /** 在役清单快照（测试/巡检用）。 */
    public static List<Entry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    // ---- 静态在册预置（结构性项随类加载登记，无运行时动作） ----

    /** F48 附带发现：{@code cn.song.mybatisplus.visualizer.ui.inject.SqlMapperLanguage} 本身是语言级结构性屏障。 */
    public static final Entry SQL_MAPPER_LANGUAGE_BARRIER = new Entry(
            "sql-mapper-language",
            "SqlMapperLanguage (Language registry)",
            RemovalPath.STRUCTURAL_NOT_REMOVABLE,
            "lexer-only 注入语言；语言级结构性注册平台不支持运行时移除（归档档 project-dynamic-unload-barriers 同族既知形态），登记不修");

    /** F48 主项：SqlMapperParserDefinition 编程式 addExplicitExtension（含 Disposer 摘除挂钩）。 */
    public static final Entry SQL_MAPPER_PARSER_DEFINITION_BARRIER = new Entry(
            "sql-mapper-parser-definition",
            "LanguageParserDefinitions.INSTANCE.addExplicitExtension(MpSqlMapper)",
            RemovalPath.DISPOSER_CASCADE_ON_UNLOAD,
            "V39 实证（243 源码 KeyedExtensionCollector.java:80-92）：三参重载 Disposer.register(parent, removeExplicitExtension)；parent=插件应用级宿主 SqlMapperInjectionBarrierHost，动态卸载容器级联摘除");

    static {
        register(SQL_MAPPER_LANGUAGE_BARRIER);
        register(SQL_MAPPER_PARSER_DEFINITION_BARRIER);
    }
}
