package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;

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
     * @param chain 调用链（PSI 解析产物）
     * @param table 实体解析的表信息
     * @return 语句模型（交给 SqlGenerator 渲染）
     */
    SqlStatement convert(WrapperChain chain, TableInfo table);
}
