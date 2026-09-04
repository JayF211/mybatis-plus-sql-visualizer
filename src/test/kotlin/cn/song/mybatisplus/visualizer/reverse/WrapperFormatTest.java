package cn.song.mybatisplus.visualizer.reverse;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Wrapper 形态分发纯函数单测（T-M36.3）：配置 + 是否有实体 → 最终生成形态。
 */
public class WrapperFormatTest {

    @Test
    public void AUTO_有实体Lambda无实体字符串() throws Exception {
        assertEquals(GeneratedWrapper.LAMBDA, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.AUTO, true));
        assertEquals(GeneratedWrapper.QUERY, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.AUTO, false));
    }

    @Test
    public void QUERY_恒字符串() throws Exception {
        assertEquals(GeneratedWrapper.QUERY, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.QUERY, true));
        assertEquals(GeneratedWrapper.QUERY, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.QUERY, false));
    }

    @Test
    public void LAMBDA_无实体回落字符串() throws Exception {
        assertEquals(GeneratedWrapper.LAMBDA, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.LAMBDA, true));
        assertEquals(GeneratedWrapper.QUERY, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.LAMBDA, false));
    }

    @Test
    public void MPJ_LAMBDA_有实体MPJ无实体回落字符串() throws Exception {
        assertEquals(GeneratedWrapper.MPJ_LAMBDA, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.MPJ_LAMBDA, true));
        assertEquals(GeneratedWrapper.QUERY, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.MPJ_LAMBDA, false));
    }

    @Test
    public void WRAPPERS_有实体lambdaQuery工厂无实体query工厂() throws Exception {
        assertEquals(GeneratedWrapper.WRAPPERS_LAMBDA, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.WRAPPERS, true));
        assertEquals(GeneratedWrapper.WRAPPERS_QUERY, WrapperFormatKt.resolveWrapperFormat(ReverseWrapperFormat.WRAPPERS, false));
    }

    @Test
    public void 持久化值解析_未知值回落AUTO() throws Exception {
        assertEquals(ReverseWrapperFormat.AUTO, ReverseWrapperFormat.fromPersisted(null));
        assertEquals(ReverseWrapperFormat.AUTO, ReverseWrapperFormat.fromPersisted("UNKNOWN"));
        assertEquals(ReverseWrapperFormat.MPJ_LAMBDA, ReverseWrapperFormat.fromPersisted("MPJ_LAMBDA"));
        assertEquals(ReverseWrapperFormat.WRAPPERS, ReverseWrapperFormat.fromPersisted("WRAPPERS"));
    }
}
