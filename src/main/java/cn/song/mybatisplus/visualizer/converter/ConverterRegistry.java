package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * 转换器注册表：按 Wrapper 类型分发到具体转换器。
 *
 * 新增 Wrapper 类型时在此追加实现（如 MPJ 联表、字符串版 QueryWrapper）。
 */
public final class ConverterRegistry {

    /** 单例（Kotlin object 转换）。 */
    public static final ConverterRegistry INSTANCE = new ConverterRegistry();

    private final List<WrapperConverter> converters;

    private ConverterRegistry() {
        this.converters = List.of(
                LambdaQueryConverter.INSTANCE,
                LambdaUpdateConverter.INSTANCE,
                LambdaDeleteConverter.INSTANCE,
                MpjLambdaConverter.INSTANCE,
                MpjQueryConverter.INSTANCE,
                MpjLambdaQueryConverter.INSTANCE,
                QueryWrapperConverter.INSTANCE,
                UpdateWrapperConverter.INSTANCE,
                UpdateJoinConverter.INSTANCE,
                DeleteJoinConverter.INSTANCE,
                // XB（M44）：xbatis（原 mybatis-mp）QueryChain/UpdateChain/DeleteChain 同构链
                XbatisConverter.INSTANCE
        );
    }

    /** 全部已注册转换器（顺序即优先级；supportedTypes 互斥，顺序无实际影响）。 */
    public List<WrapperConverter> getConverters() {
        return converters;
    }

    /**
     * 转换调用链。
     *
     * @param chain   调用链
     * @param table   表信息
     * @param project 调用方项目（T-FS-04，语义见 {@link WrapperConverter#convert}）；null = 未提供
     * @return 语句模型；无匹配转换器时返回 null
     */
    public SqlStatement convert(WrapperChain chain, TableInfo table, Project project) {
        for (WrapperConverter c : converters) {
            if (c.getSupportedTypes().contains(chain.getWrapperType())) {
                return c.convert(chain, table, project);
            }
        }
        return null;
    }

    /** Kotlin 默认参数等价重载：{@code convert(chain, table)}（project = null）。 */
    public SqlStatement convert(WrapperChain chain, TableInfo table) {
        return convert(chain, table, null);
    }
}
