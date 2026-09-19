package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import com.intellij.openapi.project.Project;

import java.util.Set;

/**
 * Wrapper 转换器 SPI（T-M4-01，F-01/09/10/11/12 扩展点）。
 *
 * 每个 Wrapper 类型一个实现；新增类型只需实现本接口并注册进 [ConverterRegistry]，
 * UI 层（SqlPreviewService）无需改动即可接入（F-20 可扩展性要求）。
 */
public interface WrapperConverter {

    /** 该转换器支持的 Wrapper 类型集合。 */
    Set<WrapperType> getSupportedTypes();

    /**
     * 将 Wrapper 调用链转换为 SQL 语句模型。
     *
     * @param chain   调用链（PSI 解析产物）
     * @param table   实体解析的表信息
     * @param project 调用方项目（T-FS-04）：<b>类名兜底推断</b>（联表/collection 子表的实体类
     *   PSI 不可解析时）按该项目读取项目级表配置（前缀/后缀/推断策略）。null = 未提供，
     *   沿用「首个打开项目」的既有语义（见
     *   {@link cn.song.mybatisplus.visualizer.psi.EntityResolver#inferDecoratedTableName(String, Project)}）——
     *   纯单测与无项目上下文的调用方不受影响，与改动前逐字节一致。
     * @return 语句模型（交给 SqlGenerator 渲染）
     */
    SqlStatement convert(WrapperChain chain, TableInfo table, Project project);

    /** Kotlin 默认参数等价重载：{@code convert(chain, table)}（project = null）。 */
    default SqlStatement convert(WrapperChain chain, TableInfo table) {
        return convert(chain, table, null);
    }
}
