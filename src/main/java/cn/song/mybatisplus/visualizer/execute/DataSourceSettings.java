package cn.song.mybatisplus.visualizer.execute;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 默认数据源管理（M36）：持久化用户选择的默认数据源，封装数据源枚举。
 *
 * 反射隔离：所有 `com.intellij.database.*` 引用经 {@link Class#forName} 加载，
 * Community 下永不触碰。持久化经 {@link PersistentStateComponent}（项目级）存储数据源 ID。
 *
 * LocalDataSource 方法：getUniqueId()=ID（AbstractDataSource），getName()=显示名。
 */
@State(
        name = "MpSqlVisualizerDataSource",
        storages = @Storage("mp-sql-visualizer-datasource.xml")
)
@Service(Service.Level.PROJECT)
public final class DataSourceSettings implements PersistentStateComponent<DataSourceSettings.State> {

    /** DataSourceStorage 类名（反射入口）。 */
    private static final String DS_STORAGE_CLASS = "com.intellij.database.dataSource.DataSourceStorage";

    /** LocalDataSource 类名（反射用）。 */
    private static final String LOCAL_DS_CLASS = "com.intellij.database.dataSource.LocalDataSource";

    private State state = new State();

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    /** 持久化状态（默认数据源 ID + 名称）。 */
    public static final class State {
        /** 默认数据源 ID。 */
        public String defaultDataSourceId = "";
        /** 默认数据源显示名。 */
        public String defaultDataSourceName = "";

        public State() {
        }

        public State(@NotNull String defaultDataSourceId, @NotNull String defaultDataSourceName) {
            this.defaultDataSourceId = defaultDataSourceId;
            this.defaultDataSourceName = defaultDataSourceName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State state = (State) o;
            return Objects.equals(defaultDataSourceId, state.defaultDataSourceId)
                    && Objects.equals(defaultDataSourceName, state.defaultDataSourceName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(defaultDataSourceId, defaultDataSourceName);
        }

        @Override
        public String toString() {
            return "State(defaultDataSourceId=" + defaultDataSourceId
                    + ", defaultDataSourceName=" + defaultDataSourceName + ")";
        }
    }

    /** DataSourceStorage 类名（反射入口）。 */
    @NotNull
    public static DataSourceSettings getInstance(@NotNull Project project) {
        return project.getService(DataSourceSettings.class);
    }

    @Nullable
    public static String getDefaultId(@NotNull Project project) {
        String id = getInstance(project).state.defaultDataSourceId;
        return id.isBlank() ? null : id;
    }

    @Nullable
    public static String getDefaultName(@NotNull Project project) {
        String name = getInstance(project).state.defaultDataSourceName;
        return name.isBlank() ? null : name;
    }

    public static void setDefault(@NotNull Project project, @NotNull String id, @NotNull String name) {
        getInstance(project).state = new State(id, name);
    }

    public static void clearDefault(@NotNull Project project) {
        getInstance(project).state = new State();
    }

    public static boolean hasDefault(@NotNull Project project) {
        return !getInstance(project).state.defaultDataSourceId.isBlank();
    }

    /**
     * 获取当前项目所有已配置的数据源列表（反射调用 DataSourceStorage）。
     */
    @NotNull
    public static List<Object> getAllDataSources(@NotNull Project project) {
        try {
            Class<?> storageClass = Class.forName(DS_STORAGE_CLASS);
            Method getStorageMethod = storageClass.getMethod("getStorage", Project.class);
            Object storage = getStorageMethod.invoke(null, project);
            if (storage == null) return List.of();
            Method getDataSourcesMethod = storageClass.getMethod("getDataSources");
            Object dataSources = getDataSourcesMethod.invoke(storage);
            if (!(dataSources instanceof List)) return List.of();
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) dataSources;
            return list.stream().filter(Objects::nonNull).collect(Collectors.toList());
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /**
     * 根据 ID 查找 LocalDataSource 对象（反射调用）。
     */
    @Nullable
    public static Object getDataSourceById(@NotNull Project project, @NotNull String id) {
        try {
            Class<?> storageClass = Class.forName(DS_STORAGE_CLASS);
            Method getStorageMethod = storageClass.getMethod("getStorage", Project.class);
            Object storage = getStorageMethod.invoke(null, project);
            if (storage == null) return null;
            Method getByIdMethod = storageClass.getMethod("getDataSourceById", String.class);
            return getByIdMethod.invoke(storage, id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 获取默认数据源的 LocalDataSource 对象（反射）。
     */
    @Nullable
    public static Object getDefaultDataSource(@NotNull Project project) {
        String id = getDefaultId(project);
        if (id == null) return null;
        return getDataSourceById(project, id);
    }

    /**
     * 反射获取 LocalDataSource 的显示名称（getName()）。
     */
    @NotNull
    public static String getName(@NotNull Object ds) {
        try {
            Method getNameMethod = Class.forName(LOCAL_DS_CLASS).getMethod("getName");
            Object result = getNameMethod.invoke(ds);
            return result instanceof String ? (String) result : "Unknown";
        } catch (Throwable ignored) {
            return "Unknown";
        }
    }

    /**
     * 反射获取 LocalDataSource 的唯一 ID（getUniqueId()）。
     */
    @Nullable
    public static String getUniqueId(@NotNull Object ds) {
        try {
            Method getUniqueIdMethod = Class.forName(LOCAL_DS_CLASS).getMethod("getUniqueId");
            Object result = getUniqueIdMethod.invoke(ds);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 反射获取 LocalDataSource 的图标。
     */
    @Nullable
    public static Icon getIcon(@NotNull Object ds) {
        try {
            Method getIconMethod = Class.forName(LOCAL_DS_CLASS).getMethod("getIcon");
            Object result = getIconMethod.invoke(ds);
            return result instanceof Icon ? (Icon) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
