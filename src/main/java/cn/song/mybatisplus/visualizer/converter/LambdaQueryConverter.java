package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;

import java.util.Set;

/** LambdaQueryWrapper 转换器（F-01）：委托 [QueryWrapperParser] 实现。 */
public final class LambdaQueryConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final LambdaQueryConverter INSTANCE = new LambdaQueryConverter();

    private LambdaQueryConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.LAMBDA_QUERY);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table) {
        return new QueryWrapperParser(table).parse(chain);
    }
}
