package cn.song.mybatisplus.sqlvistest;

import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;

/**
 * T-PS4-09 测试夹具：深栈递归构造器。recurse 不在 agent 前缀包（不被跳过），深递归把
 * 「首个非框架帧（key，栈顶数帧即得）」与「全量 pool 构建（覆盖全部递归帧）」的成本分离——
 * 缓存命中 O(1) 对 miss O(depth) 的计时防回潮断言用。
 */
public final class DeepCaller {

    public static StackLocator.Frame recurse(int depth) {
        if (depth <= 0) {
            return StackLocator.locate((ClassLoader) null);
        }
        return recurse(depth - 1);
    }

    private DeepCaller() {
    }
}
