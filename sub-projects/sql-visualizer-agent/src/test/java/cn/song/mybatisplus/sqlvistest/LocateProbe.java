package cn.song.mybatisplus.sqlvistest;

import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;

/**
 * T-PS4-09 测试夹具：用户形态定位帧——不在 agent 前缀包（不被跳过）、测试产物来自目录（L1 命中），
 * 供缓存行为断言用。key = {@code LocateProbe#locateHere}，与他用例天然隔离。
 */
public final class LocateProbe {

    public static StackLocator.Frame locateHere() {
        return StackLocator.locate((ClassLoader) null);
    }

    private LocateProbe() {
    }
}
