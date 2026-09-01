package cn.song.mybatisplus.visualizer.ui.diagram;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 列信息。 */
public final class ColumnEntry {
    @NotNull public final String name;
    @Nullable public final String type;
    public final boolean isPk;

    public ColumnEntry(@NotNull String name, @Nullable String type, boolean isPk) {
        this.name = name;
        this.type = type;
        this.isPk = isPk;
    }

    @NotNull public String getName() { return name; }
    @Nullable public String getType() { return type; }
    public boolean isPk() { return isPk; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnEntry)) return false;
        ColumnEntry that = (ColumnEntry) o;
        return isPk == that.isPk && name.equals(that.name) && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, isPk);
    }

    @Override
    public String toString() {
        return "ColumnEntry{name='" + name + "', type='" + type + "', isPk=" + isPk + '}';
    }
}
