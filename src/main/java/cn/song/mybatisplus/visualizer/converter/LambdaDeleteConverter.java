package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;

import java.util.List;
import java.util.Set;

/**
 * LambdaDeleteWrapper 转换器（F-10，MP 3.5+）：`DELETE FROM 表 WHERE ...`。
 * 全部调用均为条件（eq 等），复用 [QueryWrapperParser] 解析。
 */
public final class LambdaDeleteConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final LambdaDeleteConverter INSTANCE = new LambdaDeleteConverter();

    private LambdaDeleteConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.LAMBDA_DELETE);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table) {
        List<Condition> conditions = new QueryWrapperParser(table).parse(chain).getConditions();
        // joins 是 Kotlin data class 的非空默认参数（= emptyList()）；Java 侧必须显式传空集合，不能传 null
        return new DeleteStatement(table, conditions, List.of(), null, List.of());
    }
}
