package cn.song.mybatisplus.visualizer.execute.fixture;

import java.util.Collections;
import java.util.List;

/**
 * 模拟平台 {@code DatabaseRunners$3} / {@code $4$1} 的访问级别：类本身与构造器均为包私有，
 * 仅 {@code getDisplayName()} / {@code getSubRunners()} 为 public。
 *
 * <p>用于验证生产反射必须显式 {@code isAccessible = true}：否则从插件包调用
 * {@code Method.invoke} 会抛 {@code IllegalAccessException}，组项被当成普通项保留，
 * 而它的 runnable 是空操作，最终表现为准备门 60 秒超时。</p>
 */
final class HiddenGroupRunner {
    private final String displayName;
    private final List<Object> subRunners;

    HiddenGroupRunner(String displayName, List<Object> subRunners) {
        this.displayName = displayName;
        this.subRunners = subRunners;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<Object> getSubRunners() {
        return subRunners;
    }
}
