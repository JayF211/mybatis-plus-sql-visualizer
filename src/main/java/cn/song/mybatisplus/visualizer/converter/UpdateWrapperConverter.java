package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import com.intellij.openapi.project.Project;

import java.util.Set;

/**
 * 字符串版 UpdateWrapper 转换器（F-17）。
 * set/setSql 解析复用 [LambdaUpdateConverter]，条件解析复用 [QueryWrapperParser]。
 */
public final class UpdateWrapperConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final UpdateWrapperConverter INSTANCE = new UpdateWrapperConverter();

    private UpdateWrapperConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.UPDATE);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table, Project project) {
        return LambdaUpdateConverter.INSTANCE.convert(chain, table, project);
    }
}
