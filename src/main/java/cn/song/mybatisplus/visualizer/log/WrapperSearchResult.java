package cn.song.mybatisplus.visualizer.log;

import java.util.List;

/**
 * 搜索结果封装。
 */
public sealed interface WrapperSearchResult
        permits WrapperSearchResult.Single, WrapperSearchResult.Multiple, WrapperSearchResult.None {

    /** 找到唯一候选。 */
    record Single(WrapperCandidate candidate) implements WrapperSearchResult {
    }

    /** 找到多个候选。 */
    record Multiple(List<WrapperCandidate> candidates) implements WrapperSearchResult {
    }

    /** 未找到任何候选。 */
    final class None implements WrapperSearchResult {
        public static final None INSTANCE = new None();

        private None() {
        }
    }
}
