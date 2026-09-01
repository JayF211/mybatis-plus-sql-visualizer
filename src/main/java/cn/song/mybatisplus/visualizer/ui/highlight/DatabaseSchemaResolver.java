package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源 schema 解析（M35，方案 B：反射隔离，Community 兼容）。
 *
 * 当 IntelliJ IDEA Ultimate / DataGrip 加载了 `com.intellij.database` 插件时，
 * 从已配置的数据源读取指定表的真实字段注释（DB COMMENT），供补全合并——
 * 数据源注释比实体类 `@TableName/@ApiModelProperty` 注释更权威（贴近线上 DDL）。
 *
 * 全反射访问，不 import `com.intellij.database` 任何类——Community 运行时该插件不存在
 * 也能编译/加载（与 ExecuteSqlSupport 同一隔离策略）。插件可用性经 `Class.forName`
 * 探测标记类判定，不可用时空转（补全不展示数据源注释，仍用实体注释）。
 *
 * 遍历路径：`DataSourceManager.getManagers(project)` → `getDataSources()` → 每个
 * `DbDataSource.getDasObject()` → `DasModel.traverser().toList()` 筛 `DasTable` →
 * 按名匹配 → `DasTable.getDasChildren(ObjectKind.COLUMN)` → 每列 `getName()/getComment()`。
 */
public final class DatabaseSchemaResolver {

    public static final DatabaseSchemaResolver INSTANCE = new DatabaseSchemaResolver();

    private static final Logger LOG = Logger.getInstance(DatabaseSchemaResolver.class);

    /** database 插件标记类（探测用，M17 同 ExecuteSqlSupport）。 */
    private static final String MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";

    /** DasTable / DasColumn / DasObject 的二进制名（ObjectKind 判类型用）。 */
    private static final String DAS_TABLE = "com.intellij.database.model.DasTable";
    private static final String DAS_COLUMN = "com.intellij.database.model.DasColumn";
    private static final String DAS_OBJECT = "com.intellij.database.model.DasObject";
    private static final String OBJECT_KIND = "com.intellij.database.model.ObjectKind";

    /** 列名 → 数据源注释（表名匹配不到返回空 Map，调用方回落实体注释）。 */
    public static Map<String, String> columnComments(Project project, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return Map.of();
        }
        if (!isDatabaseAvailable()) {
            return Map.of();
        }
        return ReadAction.compute(() -> tryCompute(project, tableName));
    }

    private static Map<String, String> tryCompute(Project project, String tableName) {
        List<Object> dataSources = dataSources(project);
        if (dataSources == null) {
            return Map.of();
        }
        if (dataSources.isEmpty()) {
            return Map.of();
        }
        Class<?> dasTableClass;
        try {
            dasTableClass = Class.forName(DAS_TABLE);
        } catch (Throwable e) {
            return Map.of();
        }
        for (Object ds : dataSources) {
            Map<String, String> found = collectFromDataSource(ds, tableName, dasTableClass);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return Map.of();
    }

    /** database 插件是否可用（Ultimate/DataGrip）。 */
    private static boolean isDatabaseAvailable() {
        try {
            Class.forName(MANAGER_CLASS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 获取所有数据源（反射 `DataSourceManager.getManagers(project)` → `getDataSources()`）。 */
    private static List<Object> dataSources(Project project) {
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Method managersMethod = managerClass.getMethod("getManagers", Project.class);
            Object managers = managersMethod.invoke(null, project);
            if (!(managers instanceof List<?>)) {
                return List.of();
            }
            List<?> mgrList = (List<?>) managers;
            List<Object> dsList = new ArrayList<>();
            for (Object m : mgrList) {
                if (m == null) {
                    continue;
                }
                Method getDataSources = m.getClass().getMethod("getDataSources");
                Object list = getDataSources.invoke(m);
                if (!(list instanceof List<?>)) {
                    continue;
                }
                for (Object it : (List<?>) list) {
                    if (it != null) {
                        dsList.add(it);
                    }
                }
            }
            return dsList;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从单个数据源收集匹配表的列注释（遍历 DasModel.traverser().toList()）。 */
    private static Map<String, String> collectFromDataSource(Object ds, String tableName, Class<?> dasTableClass) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Class<?> dasObjClass = Class.forName(DAS_OBJECT);
            Class<?> dasColumnClass = Class.forName(DAS_COLUMN);
            Object dasObject = ds.getClass().getMethod("getDasObject").invoke(ds);
            if (dasObject == null) {
                return Map.of();
            }
            Object model = dasObject.getClass().getMethod("getModel").invoke(dasObject);
            if (model == null) {
                return Map.of();
            }
            Object traverser = model.getClass().getMethod("traverser").invoke(model);
            if (traverser == null) {
                return Map.of();
            }
            // JBIterable.toList() —— 反射遍历所有 DasObject
            Method toList = traverser.getClass().getMethod("toList");
            Object elements = toList.invoke(traverser);
            if (!(elements instanceof List<?>)) {
                return Map.of();
            }
            Class<?> objectKindClass = Class.forName(OBJECT_KIND);
            Object columnKind = null;
            for (Object ec : objectKindClass.getEnumConstants()) {
                if (ec instanceof Enum<?> && ((Enum<?>) ec).name().equals("COLUMN")) {
                    columnKind = ec;
                    break;
                }
            }
            if (columnKind == null) {
                return Map.of();
            }
            Method getNameM = dasObjClass.getMethod("getName");
            Method getCommentM = dasObjClass.getMethod("getComment");
            Method getChildrenM = dasObjClass.getMethod("getDasChildren", objectKindClass);

            for (Object element : (List<?>) elements) {
                if (element == null || !dasTableClass.isInstance(element)) {
                    continue;
                }
                String tName = (String) getNameM.invoke(element);
                if (tName == null) {
                    continue;
                }
                if (!tName.equalsIgnoreCase(tableName)) {
                    continue;
                }
                Object columns = getChildrenM.invoke(element, columnKind);
                if (!(columns instanceof Iterable<?>)) {
                    continue;
                }
                for (Object col : (Iterable<?>) columns) {
                    if (col == null || !dasColumnClass.isInstance(col)) {
                        continue;
                    }
                    String cName = (String) getNameM.invoke(col);
                    if (cName == null) {
                        continue;
                    }
                    String comment = (String) getCommentM.invoke(col);
                    if (!cName.isBlank() && comment != null && !comment.isBlank()) {
                        out.put(cName, comment);
                    }
                }
            }
        } catch (Throwable e) {
            LOG.debug("DatabaseSchemaResolver.collectFromDataSource failed: " + e.getMessage());
        }
        return out;
    }

    private DatabaseSchemaResolver() {
    }
}
