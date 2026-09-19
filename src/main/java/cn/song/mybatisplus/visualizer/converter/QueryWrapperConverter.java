package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import com.intellij.openapi.project.Project;

import java.util.Set;

/**
 * 字符串版 QueryWrapper 转换器（F-17）。
 *
 * `QueryWrapper` 的列与条件均为字符串字段版，由 psi 层解析为 [WrapperArg.ValueArg] 字面量，
 * [QueryWrapperParser] 已支持字符串列原样透传，本转换器仅需注册 [WrapperType.QUERY] 类型并委托解析。
 */
public final class QueryWrapperConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final QueryWrapperConverter INSTANCE = new QueryWrapperConverter();

    private QueryWrapperConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.QUERY);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table, Project project) {
        return new QueryWrapperParser(table).parse(chain);
    }
}
