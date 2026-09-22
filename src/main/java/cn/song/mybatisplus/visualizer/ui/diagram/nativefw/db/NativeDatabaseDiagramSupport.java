package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db;

import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel;
import cn.song.mybatisplus.visualizer.ui.diagram.EntityNode;
import cn.song.mybatisplus.visualizer.ui.diagram.RelationshipLine;
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramOpener;
import com.intellij.database.DbModelRegistry;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.RawDataSource;
import com.intellij.database.psi.DbElement;
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
 * <p>隔离边界：DB 插件耦合实现（{@code com.intellij.database.*} 类型引用）全部下沉嵌套类
 * {@link DbCoupled}，本类常量池零 DB 引用——Community（无 database 插件，测试默认 classpath
 * 即按此排除 database-plugin.jar）下本类可正常加载，{@link #disposeNativeEditor} /
 * {@link #clearRegisteredFk} 这两个「面板清理」入口照常生效；DB 路径真正走到时才解析嵌套类，
 * 失败由 {@link #buildTabComponent} 吞掉返回 null（回落 A 路径）。
 *
 * <p>T-SyncK2JMaster309（k2j 适配）：master 的 Kotlin object 无需此分层——JVM 对 Kotlin 字节码
 * 在该调用点不需解析 DB 类型（-Xlog:class+load 对照实证：master 加载成功且零 DasObject 解析）；
 * k2j 的 Java 字节码链接期验证即需解析签名中的 DB 类型，整类加载失败且异常抛在调用方帧
 * （类内 try/catch 够不着）。分层后两树对外行为一致。
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
     * <p>T-SyncK2JMaster309：实现下沉 {@link DbCoupled}——见类注释「Community 可加载性」。
     * 本方法自身不引用任何 database-plugin 类型，故 Community（无该插件）下本类可正常加载；
     * 桩类解析失败时在此吞掉并返回 null，调用方按契约回落 A 路径。
     *
     * @return 原生组件；任一前置条件不满足或框架异常返回 null（调用方降级）
     */
    public static JComponent buildTabComponent(Project project, DiagramModel model, String title, Disposable parent) {
        try {
            return DbCoupled.build(project, model, title, parent);
        } catch (Throwable t) {
            LOG.warn("Native Database diagram (B path) unavailable, falling back to A path", t);
            clearJoinFkState();
            return null;
        }
    }


    /**
     * 释放嵌入的 Database 图编辑器（换图/清空调用）。
     *
     * <p>T-EmbedFkOrder F2：<b>不再在此清空 {@link JoinFkRegistry}</b>——dispose 只负责释放编辑器，
     * 清空注册表会误伤「当前图」刚登记的虚拟外键（真机故障：{@code showJoinDiagram} 先建新图登记、
     * 后清旧图，旧图 dispose 把新登记整批抹掉 → 只剩节点没连线）。清理职责改为显式语义点：
     * 登记前 {@code registerJoinForeignKeys} 自带 clear（新图恰好只留自己的 FK）、无 JOIN 移除标签页与
     * 面板 clear() 时调用 {@link #clearRegisteredFk}。
     */
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
        }
    }

    /** 回收 JOIN 虚拟外键登记（打开失败 / 独立路径）。 */
    public static void clearRegisteredFk() {
        clearJoinFkState();
    }

    /**
     * 成对回收 JOIN 虚拟外键的两份会话状态（{@link JoinFkRegistry} 语义 + {@link JoinFkRefTables} 实例）。
     *
     * <p>两份状态必须同生同灭：只清语义会让 provider 拿着陈旧的表实例给新图登记外键，只清实例则留下
     * 「登记有、实例缺」的静默丢边。此方法被 plain {@code test} 分片（无 database jar）经
     * {@code SqlPreviewPanel.unloadJoinDiagram} 间接触达，故实例侧清理包 runCatching——类解析失败时
     * 实例表必然也没被填充过，无泄漏风险（与本类「所有公开方法容忍 NoClassDefFoundError」同语义）。
     */
    private static void clearJoinFkState() {
        JoinFkRegistry.clear();
        try {
            JoinFkRefTables.clear();
        } catch (Throwable ignored) {
            // runCatching{...} 等价：JoinFkRefTables 带 DasTable 平台类型签名，无 database jar 时
            // 类解析失败 → 实例表必然也没被填充过，无泄漏风险
        }
    }

    /**
     * {@code locateDbTables} 最近一次的逐段计数（B 路径降级原因的<b>可断言</b>出口）。
     *
     * <p>T-TS-08 只把这些读数写进日志，于是「B 路径从未真跑过」在测试里既不可见也不可失败
     * （{@code HeavyNativeDatabaseDiagramJoinChainTest} 的软返回分支一记绿就没人再看日志）。
     * 取出来之后，调用方可以区分两种降级：「数据源里根本没定位到表」（环境受限，可跳过）
     * 与「表定位到了却没接管」（{@code nameHits > 0}，属新故障，必须判失败）。
     *
     * <p>T-SyncK2JMaster184（k2j 适配）：字段在门面类、写点在本类嵌套 {@link DbCoupled#locateDbTables}
     * ——master 为同一 object 内赋值，语义不变。
     */
    private static volatile LocateCounts lastCounts;

    /** 见 {@link #lastCounts} 注释。 */
    public static LocateCounts lastLocateCounts() {
        return lastCounts;
    }

    /** {@code locateDbTables} 逐段计数快照。 */
    public static final class LocateCounts {
        private final int managers;
        private final int dataSources;
        private final int modelNull;
        private final int traverserMiss;
        private final int roots;
        private final int iterated;
        private final int dasTables;
        private final int nameHits;
        private final int registryMiss;

        public LocateCounts(int managers, int dataSources, int modelNull, int traverserMiss,
                            int roots, int iterated, int dasTables, int nameHits, int registryMiss) {
            this.managers = managers;
            this.dataSources = dataSources;
            this.modelNull = modelNull;
            this.traverserMiss = traverserMiss;
            this.roots = roots;
            this.iterated = iterated;
            this.dasTables = dasTables;
            this.nameHits = nameHits;
            this.registryMiss = registryMiss;
        }

        public int getManagers() {
            return managers;
        }

        public int getDataSources() {
            return dataSources;
        }

        public int getModelNull() {
            return modelNull;
        }

        public int getTraverserMiss() {
            return traverserMiss;
        }

        public int getRoots() {
            return roots;
        }

        public int getIterated() {
            return iterated;
        }

        public int getDasTables() {
            return dasTables;
        }

        public int getNameHits() {
            return nameHits;
        }

        public int getRegistryMiss() {
            return registryMiss;
        }

        /** 是否已在数据源模型里定位到目标表（区别「环境受限」与「定位成功却未接管」的分界）。 */
        public boolean isLocatedAny() {
            return nameHits > 0;
        }
    }

    /**
     * DB 插件耦合实现：{@code com.intellij.database.*} 类型引用集中于此嵌套类。
     *
     * <p>T-SyncK2JMaster309（k2j 适配）：master 的 Kotlin object 在 Community（无 database 插件，
     * 测试默认 classpath 即按此排除 database-plugin.jar）下**能加载**——JVM 对 Kotlin 字节码
     * 在本调用点不需解析 DB 类型（-Xlog:class+load 对照实证：master 加载成功且零 DasObject 解析）。
     * k2j 是 Java 字节码，链接期验证即需解析签名中的 DB 类型，整个类加载失败（NoClassDefFoundError
     * 抛在调用方帧，类内 try/catch 够不着）。故把 DB 耦合实现整体下沉本嵌套类：外层类常量池零 DB
     * 引用 → Community 可加载，{@code clearRegisteredFk}/{@code disposeNativeEditor} 照常生效；
     * DB 路径真正走到时才解析本嵌套类，失败即被 {@link #buildTabComponent} 吞掉回落 A 路径——
     * 与类注释声明的隔离契约一致。
     */
    private static final class DbCoupled {

        /** 原 {@link #buildTabComponent} 本体（含数据源定位、虚拟外键登记、原生编辑器构建）。 */
        static JComponent build(Project project, DiagramModel model, String title, Disposable parent) {
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
                // T-TS-08：此处原为静默 `return null`，是「用例绿 ≠ B 路径已验证」不可诊断的根因——
                // HeavyNativeDatabaseDiagramJoinChainTest 在 component==null 处软跳（五组断言全未
                // 执行却计为绿），而日志里既没有降级痕迹也没有原因，无法区分「B 路径真跑过」与
                // 「从未进入」。降级本身是设计内契约（见类注释），只补可诊断性，不改行为。
                java.util.Set<String> foundKeys = (located != null) ? located.keySet() : java.util.Set.of();
                List<String> missing = new ArrayList<>();
                for (String n : tableNames) {
                    if (!foundKeys.contains(n.trim().toLowerCase())) {
                        missing.add(n);
                    }
                }
                LOG.warn("Native Database diagram (B path) not taken: located=" + foundKeys
                        + " wanted=" + tableNames + " missing=" + missing
                        + " —— 缺表 = 该表未在已内省的数据源模型里定位到（数据源未内省 / 表名不匹配 / "
                        + "DbModelRegistry 无对应 DbElement），按契约回落 A 路径");
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
            clearJoinFkState();
            return null;
        }
        }

    /**
     * 把模型里的 ON 等值列配对登记为虚拟外键。
     * 方向：JOIN 表 = FK child（columns），主表 = ref（refColumns）——与 MPJ 外键语义一致。
     *
     * <p>N6：同时把定位到的表实例登记进 {@link JoinFkRefTables}——平台两参建边回调只给表名，
     * 没有实例就还原不出 {@code getRefTable()}，边会被 {@code nodeMap.get(asDbElement(...))} 静默丢掉。
     */
    private static void registerJoinForeignKeys(DiagramModel model, Map<String, DbElement> located) {
        clearJoinFkState();
        // T-PerfSweep6 T-PS6-07：本次建图的日志探针复位——建图首行 `getForeignKeys` 回调打 INFO、
        // 其余 DEBUG，故每次建图都要把「首行」还给本次（纯 JVM 对象，无 database jar 也能加载）
        JoinVirtualFkProbe.reset();
        for (Map.Entry<String, DbElement> entry : located.entrySet()) {
            // DbTable 同时实现 DbElement 与 DasTable；定位链取到的正是它，
            // 且它就是 dataModel.addElement 收到的那个实例（calculateEdges 的 nodeMap 键）
            if (entry.getValue() instanceof DasTable) {
                JoinFkRefTables.put(entry.getKey(), (DasTable) entry.getValue());
            }
        }
        for (RelationshipLine rel : model.getRelationships()) {
            String mainTable = tableNameByAlias(model, rel.getFromAlias());
            if (mainTable == null) {
                continue;
            }
            String joinTable = tableNameByAlias(model, rel.getToAlias());
            if (joinTable == null) {
                continue;
            }
            // locateDbTables 以**小写表名**为键，此处必须同口径归一——否则表名含大写时命中失败，
            // 整条 JOIN 被静默跳过（登记为空 → 无边，且日志只显示 entries=0）
            if (!located.containsKey(mainTable.trim().toLowerCase())
                    || !located.containsKey(joinTable.trim().toLowerCase())) {
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
     * 按表名在全部数据源中定位 {@link DbElement}（遍历 DasModel → DasTable 名称匹配 → DbModelRegistry）。
     * 任一表未找到时该表缺值；调用方以「全部命中」为 B 路径前置条件。异常返回 null。
     *
     * <p>T-MarketInternal：原 {@code DbPsiFacade.findElement(DasObject)} scheduled-for-removal——
     * 243/262 的实现链为 DbPsiFacadeImpl → DatabaseCoreUiService.findElement →
     * {@code project.getService(DbModelRegistry).findDbElement(das)}（jar 反编译双版本实证）。
     * DatabaseCoreUiService.findElement 本身仍标 deprecated，故直取链尾的
     * {@link DbModelRegistry}（project 级 service，243 database-plugin.jar /
     * 262 intellij.database.core.impl.jar 均 public、非 deprecated、非 Internal）。
     */
    private static Map<String, DbElement> locateDbTables(Project project, List<String> tableNames) {
        try {
            Set<String> wanted = new HashSet<>();
            for (String n : tableNames) {
                wanted.add(n.trim().toLowerCase());
            }
            Map<String, DbElement> found = new LinkedHashMap<>();
            // T-TS-08：定位链的逐段计数——调用方只在「全部命中」时才走 B 路径，缺表时仅凭
            // 命中集合无法判断卡在哪一段（无数据源 / 数据源未内省(model=null) / traverser 不可迭代 /
            // 表名没匹配 / DbElement 没登记），故把每段读数一并留痕（纯日志，无控制流影响）。
            int managers = 0;
            int dataSources = 0;
            int modelNull = 0;
            int traverserMiss = 0;
            int roots = 0;
            int iterated = 0;
            int dasTables = 0;
            int nameHits = 0;
            int registryMiss = 0;
            Set<String> dsClasses = new java.util.LinkedHashSet<>();
            DbModelRegistry modelRegistry = project.getService(DbModelRegistry.class);
            List<DataSourceManager<?>> managersList = DataSourceManager.getManagers(project);
            for (DataSourceManager<?> manager : managersList) {
                managers++;
                if (found.size() == wanted.size()) {
                    break;
                }
                List<? extends RawDataSource> sources = manager.getDataSources();
                for (RawDataSource ds : sources) {
                    dataSources++;
                    dsClasses.add(ds.getClass().getSimpleName());
                    if (found.size() == wanted.size()) {
                        break;
                    }
                    // RawDataSource → DasDataSource.getModel()（DasModel），编译期直调
                    Object model;
                    try {
                        model = ds.getModel();
                    } catch (Throwable t) {
                        modelNull++;
                        continue;
                    }
                    if (model == null) {
                        modelNull++;
                        continue;
                    }
                    // model 非 null 却遍历不出表时，须区分「模型是空的（未同步内省结果）」与
                    // 「模型有根但遍历器没吐出东西」——roots 读数正是这个分界（本地实测的关键证据）
                    try {
                        // = modelRoots.toList().size（物化迭代后取数；迭代抛异常 → -1）。
                        // getModelRoots() 返回 JBIterable（Iterable 非 Collection），须手动迭代计数
                        roots += countIterable(((com.intellij.database.model.DasModel) model).getModelRoots());
                    } catch (Throwable t) {
                        roots += -1;
                    }
                    // T-Perf-09：惰性迭代 + 命中即停（此前 toList() 全量物化再筛——数千表 schema
                    // 下即使全部命中也遍历完整棵模型）；model 为 null（未内省）已在上行跳过
                    Iterable<?> elements = null;
                    try {
                        Object traverser = ((com.intellij.database.model.DasModel) model).traverser();
                        elements = (traverser instanceof Iterable<?>) ? (Iterable<?>) traverser : null;
                    } catch (Throwable t) {
                        elements = null;
                    }
                    if (elements == null) {
                        traverserMiss++;
                        continue;
                    }
                    for (Object element : elements) {
                        iterated++;
                        if (found.size() == wanted.size()) {
                            break;
                        }
                        if (!(element instanceof DasTable)) {
                            continue;
                        }
                        dasTables++;
                        DasTable table = (DasTable) element;
                        String name = table.getName() != null ? table.getName().toLowerCase() : null;
                        if (name == null) {
                            continue;
                        }
                        if (wanted.contains(name) && !found.containsKey(name)) {
                            nameHits++;
                            DbElement dbElement = modelRegistry.findDbElement(table);
                            if (dbElement == null) {
                                registryMiss++;
                                continue;
                            }
                            found.put(name, dbElement);
                        }
                    }
                }
            }
            LOG.info("B path locateDbTables: wanted=" + wanted + " found=" + found.keySet()
                    + " managers=" + managers
                    + " dataSources=" + dataSources + "(dsClasses=" + dsClasses + ")"
                    + " modelNull=" + modelNull + " traverserMiss=" + traverserMiss
                    + " roots=" + roots + " iterated=" + iterated + " dasTables=" + dasTables
                    + " nameHits=" + nameHits + " registryMiss=" + registryMiss);
            lastCounts = new LocateCounts(
                    managers, dataSources, modelNull,
                    traverserMiss, roots, iterated,
                    dasTables, nameHits, registryMiss);
            return found;
        } catch (Throwable t) {
            LOG.warn("NativeDatabaseDiagramSupport.locateDbTables failed: " + t);
            lastCounts = null;
            return null;
        }
    }

    /** = Kotlin {@code Iterable.toList().size}（物化迭代计数；调用方对异常计 -1）。 */
    private static int countIterable(Iterable<?> iterable) {
        int n = 0;
        for (Object ignored : iterable) {
            n++;
        }
        return n;
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

        // T-PreviewBgFit（问题二）：同 JOIN 路径——开关必须在**异步布局触发之前**置位，
        // 布局管线收尾时才读得到它并自适应（见 NativeJoinDiagramOpener.enableAutoFit 注释）。
        NativeJoinDiagramOpener.enableAutoFit(builder);

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
}