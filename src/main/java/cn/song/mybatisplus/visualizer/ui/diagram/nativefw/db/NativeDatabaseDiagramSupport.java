package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db;

import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel;
import cn.song.mybatisplus.visualizer.ui.diagram.EntityNode;
import cn.song.mybatisplus.visualizer.ui.diagram.RelationshipLine;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.RawDataSource;
import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.database.psi.DataSourceManager;
import com.intellij.diagram.DiagramBuilder;
import com.intellij.diagram.DiagramBuilderFactory;
import com.intellij.diagram.DiagramDataKeys;
import com.intellij.diagram.DiagramDataModel;
import com.intellij.diagram.DiagramProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.components.JBPanel;
import com.intellij.uml.UmlVirtualFileSystem;

import javax.swing.JComponent;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T-Embed-03/04：Database 原生「显示图」嵌入门面（B 路径）。
 *
 * <p>与 nativefw.NativeJoinDiagramSupport（A 路径，自实现 SqlJoinDiagramProvider）的差异：
 * 本路径完全复用 Database 插件自己的图——节点=真实 DbTable（注释/类型/主键分区/图例全原生），
 * JOIN 连接线经 {@link JoinVirtualFkProvider}（modelRelationProvider EP 虚拟外键）注入。
 *
 * <p>降级契约：无数据源 / 任一表定位失败 / Database 或 Diagrams 插件缺席 / 框架异常
 * → {@link #buildTabComponent} 返回 null，调用方回落 A 路径（自实现 Provider）→ 自绘。
 *
 * <p>隔离边界：本文件 import com.intellij.database.* / com.intellij.uml.*，仅在
 * Ultimate（两插件齐备）被调用方触发加载；所有公开方法包 try/catch(Throwable)
 * （含 NoClassDefFoundError——Community 下类解析失败被吞掉，与 A 路径门面同语义）。
 */
public final class NativeDatabaseDiagramSupport {

    private static final Logger LOG = Logger.getInstance(NativeDatabaseDiagramSupport.class);

    /** Database 图 Provider id（DbDiagramProvider.getID() 字节码实证）。 */
    private static final String DATABASE_PROVIDER_ID = "DATABASE";

    /** clientProperty key：组件 → 内部 FileEditor（dispose 用，与 A 路径 key 互不冲突）。 */
    private static final String NATIVE_EDITOR_KEY = "mpsql.native.database.editor";

    private NativeDatabaseDiagramSupport() {
    }

    /**
     * 为 JOIN 模型构建 Database 原生图嵌入组件。
     *
     * @return 原生组件；任一前置条件不满足或框架异常返回 null（调用方降级）
     */
    public static JComponent buildTabComponent(Project project, DiagramModel model, String title, Disposable parent) {
        if (project == null || model.isEmpty()) {
            return null;
        }
        try {
            for (EntityNode e : model.getEntities()) {
                if (e.getTableName().isBlank()) {
                    return null;
                }
            }
            List<String> tableNames = new ArrayList<>();
            for (EntityNode e : model.getEntities()) {
                tableNames.add(e.getTableName());
            }
            Map<String, DbElement> located = ReadAction.compute(() -> locateDbTables(project, tableNames));
            if (located == null || located.size() < tableNames.size()) {
                return null;
            }
            LOG.info("Native Database diagram (B path) taking over: tables=" + located.keySet());
            registerJoinForeignKeys(model, located);

            String mainTable = null;
            for (EntityNode e : model.getEntities()) {
                if (e.isMain()) {
                    mainTable = e.getTableName();
                    break;
                }
            }
            DbElement mainElement = located.containsKey(mainTable)
                    ? located.get(mainTable) : located.values().iterator().next();
            // 主表排首（fillDataModel 顺序即初始画布顺序），其余表随后
            List<DbElement> orderedElements = new ArrayList<>();
            orderedElements.add(mainElement);
            for (Map.Entry<String, DbElement> entry : located.entrySet()) {
                if (!entry.getKey().equals(mainTable)) {
                    orderedElements.add(entry.getValue());
                }
            }
            FileEditor editor = buildEmbeddableEditor(project, orderedElements, title);
            JBPanel<JBPanel<?>> panel = new JBPanel<>(new BorderLayout());
            panel.setOpaque(false);
            panel.add(editor.getComponent(), BorderLayout.CENTER);
            Disposer.register(parent, editor);
            panel.putClientProperty(NATIVE_EDITOR_KEY, editor);
            return panel;
        } catch (Throwable t) {
            LOG.warn("Native Database diagram (B path) unavailable, falling back to A path", t);
            JoinFkRegistry.clear();
            return null;
        }
    }

    /** 释放嵌入的 Database 图编辑器（换图/清空调用），并回收虚拟外键登记。 */
    public static void disposeNativeEditor(JComponent component) {
        try {
            if (component != null) {
                Object editor = component.getClientProperty(NATIVE_EDITOR_KEY);
                if (editor instanceof Disposable) {
                    Disposer.dispose((Disposable) editor);
                }
            }
        } catch (Throwable ignored) {
            // 释放失败不阻断降级链
        } finally {
            JoinFkRegistry.clear();
        }
    }

    /** 回收 JOIN 虚拟外键登记（打开失败 / 独立路径）。 */
    public static void clearRegisteredFk() {
        JoinFkRegistry.clear();
    }

    /**
     * 把模型里的 ON 等值列配对登记为虚拟外键。
     * 方向：JOIN 表 = FK child（columns），主表 = ref（refColumns）——与 MPJ 外键语义一致。
     */
    private static void registerJoinForeignKeys(DiagramModel model, Map<String, DbElement> located) {
        JoinFkRegistry.clear();
        for (RelationshipLine rel : model.getRelationships()) {
            String mainTable = tableNameByAlias(model, rel.getFromAlias());
            if (mainTable == null) {
                continue;
            }
            String joinTable = tableNameByAlias(model, rel.getToAlias());
            if (joinTable == null || !located.containsKey(mainTable) || !located.containsKey(joinTable)) {
                continue;
            }
            for (kotlin.Pair<String, String> pair : rel.getColumnPairs()) {
                JoinFkRegistry.put(joinTable, mainTable, pair.getSecond(), pair.getFirst());
            }
        }
    }

    private static String tableNameByAlias(DiagramModel model, String alias) {
        for (EntityNode e : model.getEntities()) {
            if (e.getAlias().equals(alias)) {
                return e.getTableName();
            }
        }
        return null;
    }

    /**
     * 按表名在全部数据源中定位 {@link DbElement}（遍历 DasModel → DasTable 名称匹配 → DbPsiFacade）。
     * 任一表未找到时该表缺值；调用方以「全部命中」为 B 路径前置条件。异常返回 null。
     */
    private static Map<String, DbElement> locateDbTables(Project project, List<String> tableNames) {
        try {
            Set<String> wanted = new HashSet<>();
            for (String n : tableNames) {
                wanted.add(n.trim().toLowerCase());
            }
            Map<String, DbElement> found = new LinkedHashMap<>();
            DbPsiFacade facade = DbPsiFacade.getInstance(project);
            List<DataSourceManager<?>> managers = DataSourceManager.getManagers(project);
            for (DataSourceManager<?> manager : managers) {
                if (found.size() == wanted.size()) {
                    break;
                }
                List<? extends RawDataSource> sources = manager.getDataSources();
                for (RawDataSource ds : sources) {
                    if (found.size() == wanted.size()) {
                        break;
                    }
                    // RawDataSource → DasDataSource.getModel()（DasModel），编译期直调
                    Object model;
                    try {
                        model = ds.getModel();
                    } catch (Throwable t) {
                        continue;
                    }
                    if (model == null) {
                        continue;
                    }
                    Object traverser;
                    try {
                        traverser = ((com.intellij.database.model.DasModel) model).traverser();
                    } catch (Throwable t) {
                        continue;
                    }
                    // T-Perf-09：惰性迭代 + 命中即停（此前 toList() 全量物化再筛——数千表 schema
                    // 下即使全部命中也遍历完整棵模型）；model 为 null（未内省）已在上行跳过
                    Iterable<?> elements;
                    try {
                        elements = (Iterable<?>) traverser;
                    } catch (Throwable t) {
                        continue;
                    }
                    for (Object element : elements) {
                        if (found.size() == wanted.size()) {
                            break;
                        }
                        if (!(element instanceof DasTable)) {
                            continue;
                        }
                        DasTable table = (DasTable) element;
                        String name = table.getName() != null ? table.getName().toLowerCase() : null;
                        if (name == null) {
                            continue;
                        }
                        if (wanted.contains(name) && !found.containsKey(name)) {
                            DbElement dbElement = facade.findElement(table);
                            if (dbElement != null) {
                                found.put(name, dbElement);
                            }
                        }
                    }
                }
            }
            return found;
        } catch (Throwable t) {
            LOG.warn("NativeDatabaseDiagramSupport.locateDbTables failed: " + t);
            return null;
        }
    }

    /**
     * 构建可嵌入的 Database 原生图编辑器——镜像 ShowDiagramBase 真实开图链：
     * findFileByUrl 单例 VFS → ORIGINAL_ELEMENT → WriteAction createBuilder →
     * <b>dataModel.addElement 逐表填充（= 原生 fillDataModel，javap 实证其就是
     * WriteAccess 内循环 addElement；缺此步 DbDiagramDataModel 无初始节点 → 画布空白）</b> →
     * queryUpdate 重排填充。
     */
    private static FileEditor buildEmbeddableEditor(Project project, List<DbElement> elements, String title) {
        DiagramProvider<DbElement> provider = DiagramProvider.findByID(DATABASE_PROVIDER_ID);
        if (provider == null) {
            throw new IllegalStateException("Database diagram provider not registered: " + DATABASE_PROVIDER_ID);
        }

        String url = UmlVirtualFileSystem.PROTOCOL_PREFIX + DATABASE_PROVIDER_ID + "/"
                + sanitizeFqn(elements.get(0));
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
        if (!(file instanceof UmlVirtualFileSystem.UmlVirtualFile)) {
            throw new IllegalStateException("VirtualFileManager returned null for " + url);
        }
        UmlVirtualFileSystem.UmlVirtualFile umlFile = (UmlVirtualFileSystem.UmlVirtualFile) file;

        umlFile.setProject(project);
        umlFile.setInitialized(true);
        umlFile.setPresentableName(title);
        umlFile.putUserData(DiagramDataKeys.ORIGINAL_ELEMENT, elements.get(0));

        DiagramBuilder builder = WriteAction.compute(() -> {
            DiagramBuilder b = DiagramBuilderFactory.getInstance()
                    .createBuilder(project, provider, elements.get(0), umlFile);
            // 镜像 ShowDiagramBase.fillDataModel（WriteAccess 内逐元素 addElement）；
            // getDataModel() 返回 DiagramDataModel<?>（star projection），需显式窄化
            @SuppressWarnings("unchecked")
            DiagramDataModel<Object> dataModel = (DiagramDataModel<Object>) b.getDataModel();
            for (DbElement element : elements) {
                dataModel.addElement(element);
            }
            // T-Embed-10.4 根因（javap initializeDataModelAsync 实证）：addElement 只登记
            // myTablesAddedByUser（DbTable 分支 createDbNode 只返回不写 myNodes），节点生成必须走
            // refreshDataModel——原生 fillDataModel 后正是调 refreshDataModelAsync。
            // T-Embed-10.5：calculateEdges 开头 isCategoryEnabled(VIRTUAL_FOREIGN_KEYS) 为 false
            // 直接不建虚拟外键边——嵌入场景 content manager 新实例该分类默认禁用，refresh 前强制开启
            enableVirtualFkCategory(dataModel);
            dataModel.refreshDataModel();
            int nodeCount = -1;
            try {
                nodeCount = dataModel.getNodes().size();
            } catch (Throwable ignored) {
            }
            LOG.info("B path fillDataModel+refresh done: elements=" + elements.size()
                    + ", nodes=" + nodeCount);
            return b;
        });

        FileEditor editor;
        try {
            editor = (FileEditor) Class.forName("com.intellij.uml.UmlFileEditorImpl")
                    .getDeclaredConstructor(DiagramBuilder.class, VirtualFile.class)
                    .newInstance(builder, umlFile);
        } catch (Throwable t) {
            throw new IllegalStateException("Cannot instantiate UmlFileEditorImpl", t);
        }

        builder.queryUpdate()
                .withDataReload()
                .withRelayout()
                .withPresentationUpdate()
                .runAsync()
                .thenRun(() -> {
                    // T-Embed-10 探针：刷新（refreshDataModel 会重建 nodes）后节点是否幸存
                    try {
                        LOG.info("B path after queryUpdate: nodes=" + builder.getDataModel().getNodes().size());
                    } catch (Throwable ignored) {
                    }
                });
        return editor;
    }

    /** FQN：仅表名；sanitize 防 `.` 被判为 .uml 格式、`/` 破坏 URL 分段。 */
    private static String sanitizeFqn(DbElement element) {
        String name;
        try {
            name = element.getName();
        } catch (Throwable t) {
            name = null;
        }
        String base = name != null ? name : "table";
        return Pattern.compile("[./\\\\]").matcher(base).replaceAll("_");
    }

    /**
     * 强制启用 VIRTUAL_FOREIGN_KEYS 分类（calculateEdges 的建边开关，javap 实证）。
     * 分类常量为 DbDiagramProvider$Holder 私有静态字段（package-private 类），反射读取；
     * nodeContentManager 经 DiagramDataModel 接口直调（同 uml-support public API）。
     */
    private static void enableVirtualFkCategory(DiagramDataModel<Object> dataModel) {
        try {
            Class<?> holderClass = Class.forName("com.intellij.database.diagram.DbDiagramProvider$Holder");
            java.lang.reflect.Field field = holderClass.getDeclaredField("VIRTUAL_FOREIGN_KEYS");
            field.setAccessible(true);
            com.intellij.diagram.DiagramCategory category =
                    (com.intellij.diagram.DiagramCategory) field.get(null);
            com.intellij.diagram.DiagramNodeContentManager contentManager = dataModel.getNodeContentManager();
            if (contentManager != null) {
                contentManager.setCategoryEnabled(category, true);
            }
            LOG.info("B path VIRTUAL_FOREIGN_KEYS category enabled");
        } catch (Throwable e) {
            LOG.warn("B path enable VIRTUAL_FOREIGN_KEYS failed (edges may be absent)", e);
        }
    }
}
