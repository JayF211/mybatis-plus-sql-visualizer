package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import cn.song.mybatisplus.visualizer.util.TableConfigSignature;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JPA 实体解析器：从 JPA 注解实体 [PsiClass] 解析出 [TableInfo]。
 *
 * 规则（对齐 JPA 规范 / Hibernate 默认行为）：
 *  - 表名：优先 @Table(name="xxx") 注解，否则按命名策略由类名推断（驼峰转下划线）
 *  - 列名：优先 @Column(name="xxx") 注解，否则按命名策略由字段名推断
 *  - 主键：@Id 标记
 *  - @Transient 字段（非持久化）跳过
 *  - @ManyToOne + @JoinColumn(name="xxx") 解析为外键列
 *  - @OneToMany / @OneToOne / @ManyToMany 关联字段跳过（非简单列）
 *  - @MappedSuperclass 父类字段继承（父类字段在前，子类字段在后）
 *  - 字段按角色分组稳定排序——@Id(PRIMARY) 置顶、业务字段(BUSINESS) 居中、
 *    审计字段(AUDIT) 与系统字段(SYSTEM) 沉底，组内保持声明顺序
 *  - 解析结果按「PsiFile + classQualifiedName + configSignature」三维缓存，
 *    文件修改戳（modificationStamp）+ 配置签名双维度失效
 */
public class JpaEntityResolver {

    /**
     * 解析 JPA 实体类为表信息（带缓存）。
     *
     * @param entityClass JPA 实体 PsiClass
     * @return 表信息；类名缺失等异常情况返回带推断表名、空列的表信息（保证不抛异常）
     */
    @NotNull
    public TableInfo resolve(@NotNull PsiClass entityClass) {
        String className = entityClass.getName();
        if (className == null) return new TableInfo("", "", List.of(), null, null, null, List.of());
        PsiFile file = entityClass.getContainingFile();
        if (file == null) return resolveInternal(entityClass);
        String qName = entityClass.getQualifiedName() != null ? entityClass.getQualifiedName() : className;
        String configSig = TableConfigSignature.projectSignature();

        JpaCacheKey key = new JpaCacheKey(file, qName, configSig);
        long stamp = file.getModificationStamp();
        JpaEntry entry = cache.get(key);
        if (entry != null) {
            if (entry.stamp() == stamp) return entry.value();
        }

        TableInfo value = resolveInternal(entityClass);
        cache.put(key, new JpaEntry(stamp, value));
        return value;
    }

    /**
     * 内部解析（完整 PSI 遍历，缓存未命中时调用）。
     */
    @NotNull
    private TableInfo resolveInternal(@NotNull PsiClass entityClass) {
        String className = entityClass.getName();
        if (className == null) return new TableInfo("", "", List.of(), null, null, null, List.of());
        // 表名：优先 @Table(name="xxx")，否则按命名策略由类名推断
        String tableName = annotationAttributeValue(entityClass, TABLE_ANNOTATION, "name");
        if (tableName == null) tableName = decorateTableName(inferTableName(className));

        // 递归父类收集字段（支持 @MappedSuperclass 继承；父类字段在前、子类字段在后）
        List<PsiField> fields = collectFieldsIncludingSuper(entityClass);
        // 按角色分组稳定排序（PRIMARY 置顶 → BUSINESS 居中 → AUDIT/SYSTEM 沉底，组内保持声明顺序）
        List<ColumnInfo> columns = fields.stream()
                .map(field -> resolveColumn(field, ignoredFieldNames()))
                .filter(c -> c != null)
                .sorted(Comparator.comparing(ColumnInfo::getRole))
                .collect(Collectors.toList());
        // 去重：同一列名只保留第一个（简单字段优先于 @ManyToOne 关联字段）
        LinkedHashMap<String, ColumnInfo> byCol = new LinkedHashMap<>();
        for (ColumnInfo c : columns) {
            byCol.putIfAbsent(c.getColumnName(), c);
        }
        columns = new ArrayList<>(byCol.values());
        // 表注释（类级注解 > javadoc > 行注释）
        String comment = resolveComment(entityClass);
        // 收集 @ManyToOne / @OneToOne 关联元数据
        List<AssociationInfo> associations = new ArrayList<>();
        for (PsiField field : fields) {
            AssociationInfo a = resolveAssociation(field);
            if (a != null) associations.add(a);
        }
        return new TableInfo(className, tableName, columns, null, null, comment, associations);
    }

    /**
     * 递归收集类及其所有父类的字段（支持 @MappedSuperclass 继承）：
     *  - 父类字段在前、子类字段在后；
     *  - 仅沿 superClass 类继承链——接口字段不纳入；
     *  - seen 集合防循环继承（异常 PSI 场景防御）；
     *  - 父类本身标注 @MappedSuperclass 或 @Entity 时字段均纳入。
     */
    @NotNull
    private List<PsiField> collectFieldsIncludingSuper(@NotNull PsiClass clazz) {
        return collectFieldsIncludingSuper(clazz, new LinkedHashSet<>());
    }

    private List<PsiField> collectFieldsIncludingSuper(
            @NotNull PsiClass clazz,
            @NotNull Set<PsiClass> seen) {
        if (!seen.add(clazz)) return List.of();
        List<PsiField> superFields = clazz.getSuperClass() != null
                ? collectFieldsIncludingSuper(clazz.getSuperClass(), seen)
                : List.of();
        List<PsiField> selfFields = new ArrayList<>(Arrays.asList(clazz.getFields()));
        List<PsiField> result = new ArrayList<>(superFields);
        result.addAll(selfFields);
        return result;
    }

    /**
     * 解析单个字段为列信息；非持久化字段（@Transient / 关联注解 @OneToMany/@ManyToMany 等）返回 null。
     */
    @Nullable
    private ColumnInfo resolveColumn(@NotNull PsiField field, @NotNull Set<String> ignoredFields) {
        String fieldName = field.getName();

        // @Transient：非持久化字段，跳过
        if (hasAnnotation(field, TRANSIENT_ANNOTATION)) return null;

        // 一对多 / 多对多关联字段跳过（非简单列，对应集合类型属性）
        if (hasAnnotation(field, ONE_TO_MANY_ANNOTATION) || hasAnnotation(field, MANY_TO_MANY_ANNOTATION)) return null;

        // 忽略字段名配置（`;` 分隔，按 Java 字段名匹配）
        if (fieldName != null && ignoredFields.contains(fieldName)) return null;

        // serialVersionUID 默认忽略
        if (fieldName != null && fieldName.toLowerCase().replace("_", "").equals("serialversionuid")) return null;

        // 列名：优先 @Column(name="xxx") 或 @JoinColumn(name="xxx")（@ManyToOne 外键列），否则驼峰转下划线
        boolean isId = hasAnnotation(field, ID_ANNOTATION);
        boolean isManyToOne = hasAnnotation(field, MANY_TO_ONE_ANNOTATION);
        boolean isOneToOne = hasAnnotation(field, ONE_TO_ONE_ANNOTATION);

        String columnName;
        if (isManyToOne) {
            columnName = annotationAttributeValue(field, JOIN_COLUMN_ANNOTATION, "name");
            if (columnName == null) columnName = NamingStrategy.INSTANCE.toSnakeCase(fieldName) + "_id";
        } else if (isOneToOne) {
            columnName = annotationAttributeValue(field, JOIN_COLUMN_ANNOTATION, "name");
            if (columnName == null) columnName = NamingStrategy.INSTANCE.toSnakeCase(fieldName) + "_id";
        } else {
            columnName = annotationAttributeValue(field, COLUMN_ANNOTATION, "name");
            if (columnName == null) columnName = NamingStrategy.INSTANCE.toSnakeCase(fieldName);
        }

        // 字段类型（参数回填引号推断用）；PSI 类型解析异常时兜底 null
        String fieldType;
        try {
            fieldType = field.getType().getCanonicalText();
        } catch (Exception e) {
            fieldType = null;
        }

        // 字段注释
        String comment = resolveComment(field);

        // 角色分组——主键置顶(PRIMARY)、关联外键(BUSINESS)、普通业务字段(BUSINESS)
        ColumnRole role = isId ? ColumnRole.PRIMARY : ColumnRole.BUSINESS;

        return new ColumnInfo(fieldName, columnName, isId, false, fieldType, null, comment, role);
    }

    /**
     * 解析单个字段的关联信息（@ManyToOne / @OneToOne）。
     */
    @Nullable
    private AssociationInfo resolveAssociation(@NotNull PsiField field) {
        boolean isManyToOne = hasAnnotation(field, MANY_TO_ONE_ANNOTATION);
        boolean isOneToOne = hasAnnotation(field, ONE_TO_ONE_ANNOTATION);
        if (!isManyToOne && !isOneToOne) return null;

        String fieldName = field.getName();
        // 外键列名：@JoinColumn(name="xxx") 或默认 {fieldName}_id
        String joinColumn = annotationAttributeValue(field, JOIN_COLUMN_ANNOTATION, "name");
        if (joinColumn == null) joinColumn = NamingStrategy.INSTANCE.toSnakeCase(fieldName) + "_id";

        // 目标实体类：从字段类型获取
        PsiClass targetClass = resolveTargetEntityClass(field);
        if (targetClass == null) return null;
        String targetTableName = resolveTargetTableName(targetClass);

        // 别名：实体类名首字母小写
        String targetName = targetClass.getName();
        String base = targetName != null ? targetName : fieldName;
        String targetAlias = base.substring(0, 1).toLowerCase() + base.substring(1);

        return new AssociationInfo(fieldName, joinColumn, targetTableName, targetAlias);
    }

    /**
     * 解析 @ManyToOne/@OneToOne 字段的目标实体类。
     */
    @Nullable
    private PsiClass resolveTargetEntityClass(@NotNull PsiField field) {
        if (!(field.getType() instanceof PsiClassType type)) return null;
        return type.resolve();
    }

    /**
     * 从目标实体 PsiClass 获取表名。
     *
     * 优先 @Table(name="xxx")，否则按命名策略推断。
     */
    @NotNull
    private String resolveTargetTableName(@NotNull PsiClass targetClass) {
        String explicitTable = annotationAttributeValue(targetClass, TABLE_ANNOTATION, "name");
        if (explicitTable != null) return explicitTable;
        String className = targetClass.getName();
        if (className == null) return NamingStrategy.INSTANCE.toSnakeCase("unknown");
        return decorateTableName(inferTableName(className));
    }

    // ------------------------------------------------------------------
    // 注释读取（四级优先级：SCHEMA > ANNOTATION > JAVADOC > LINE_COMMENT）
    // ------------------------------------------------------------------

    /**
     * 注释来源优先级（schema 预留）：
     * SCHEMA（数据源 schema 列/表注释）最高，当前实现 schema 返回 null——
     * 实际生效顺序 = 注解 > javadoc > 行注释。
     */
    public enum CommentSource {
        SCHEMA, ANNOTATION, JAVADOC, LINE_COMMENT
    }

    /**
     * 提取字段注释（PsiField）：注解白名单 > javadoc > 行注释。
     */
    @Nullable
    private String resolveComment(@NotNull PsiField field) {
        String docText = field.getDocComment() != null ? field.getDocComment().getText() : null;
        return firstNonBlank(Arrays.asList(
                null,
                annotationComment(field),
                javadocComment(docText),
                lineComment(field)));
    }

    /** 提取类级注释：类注解白名单 > 类 javadoc > 类上方行注释。 */
    @Nullable
    private String resolveComment(@NotNull PsiClass psiClass) {
        String docText = psiClass.getDocComment() != null ? psiClass.getDocComment().getText() : null;
        return firstNonBlank(Arrays.asList(
                null,
                annotationComment(psiClass),
                javadocComment(docText),
                lineComment(psiClass)));
    }

    /** 取最高优先级非空注释（按来源顺序，schema 为空时注解生效）。 */
    @Nullable
    private String firstNonBlank(@NotNull List<String> comments) {
        for (String c : comments) {
            if (c != null && !c.isBlank()) return c.trim();
        }
        return null;
    }

    /**
     * 注解注释提取：白名单注解按专属属性，通用属性（description/name/comment/desc）兜底；
     * 排除 JPA 核心映射注解（@Entity/@Table/@Column/@Id 等——name/value 是表名/列名非注释）。
     */
    @Nullable
    private String annotationComment(@NotNull PsiClass psi) {
        return annotationComment(psi.getModifierList() != null ? psi.getModifierList().getAnnotations() : new PsiAnnotation[0]);
    }

    private String annotationComment(@NotNull PsiField field) {
        return annotationComment(field.getModifierList() != null ? field.getModifierList().getAnnotations() : new PsiAnnotation[0]);
    }

    private String annotationComment(@NotNull PsiAnnotation[] annotations) {
        for (PsiAnnotation annotation : annotations) {
            String simpleName = annotation.getNameReferenceElement() != null
                    ? annotation.getNameReferenceElement().getReferenceName() : null;
            if (simpleName == null) continue;
            // JPA 核心映射注解不参与注释提取（name/value 是表名/列名）
            if (JPA_MAPPING_ANNOTATION_NAMES.contains(simpleName)) continue;
            // 白名单注解专属属性
            List<String> attrs = COMMENT_ANNOTATION_ATTRIBUTES.get(simpleName);
            if (attrs != null) {
                for (String attr : attrs) {
                    String v = attributeString(annotation, attr);
                    if (v != null && !v.isBlank()) return v;
                }
            }
            // 通用属性兜底
            for (String attr : COMMENT_ATTRIBUTE_NAMES) {
                String v = attributeString(annotation, attr);
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }

    /**
     * JavaDoc 描述提取：去 /** *​/ 与行首 *，取 @ 标签前描述段落，空行折叠为空格。
     */
    @Nullable
    private String javadocComment(@Nullable String docText) {
        if (docText == null) return null;
        String text = docText;
        if (text.startsWith("/**")) text = text.substring(3);
        if (text.endsWith("*/")) text = text.substring(0, text.length() - 2);
        String result = Arrays.stream(text.split("\n"))
                .map(line -> {
                    String t = line.trim();
                    if (t.startsWith("*")) t = t.substring(1).trim();
                    return t;
                })
                .takeWhile(t -> !t.startsWith("@"))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.joining(" "));
        return result.isEmpty() ? null : result;
    }

    /**
     * 行注释提取（兜底）：// xxx 行注释。
     */
    @Nullable
    private String lineComment(@NotNull PsiElement element) {
        // 路径 ①：元素自身文本首行（LightPlatform 注释绑定进 textRange）
        String ownFirstLine = element.getText().split("\n", 2)[0].trim();
        if (ownFirstLine.startsWith("//")) {
            String comment = ownFirstLine.substring(2).trim();
            if (!comment.isBlank()) return comment;
        }
        // 路径 ②：文档文本向上扫描
        int offset = element.getTextRange().getStartOffset();
        String before = element.getContainingFile().getText().substring(0, offset);
        String[] lines = before.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("@")) continue;
            if (line.startsWith("//")) {
                String comment = line.substring(2).trim();
                return comment.isBlank() ? null : comment;
            }
            return null;
        }
        return null;
    }

    /** 注解 → 注释属性白名单（swagger/easyexcel 等业务注解的注释属性）。 */
    private static final Map<String, List<String>> COMMENT_ANNOTATION_ATTRIBUTES = Map.of(
            "Schema", List.of("description"),
            "ApiModelProperty", List.of("value", "description"),
            "ExcelProperty", List.of("value"),
            "DictField", List.of("description", "comment", "name")
    );

    /** 通用注释属性（非白名单注解兜底提取，不取 value——value 常为标识符）。 */
    private static final List<String> COMMENT_ATTRIBUTE_NAMES = List.of("description", "name", "comment", "desc");

    /** JPA 核心映射注解（name/value 是表名/列名而非注释，不参与提取）。 */
    private static final Set<String> JPA_MAPPING_ANNOTATION_NAMES = Set.of(
            "Entity", "Table", "Column", "Id", "Transient",
            "ManyToOne", "OneToMany", "OneToOne", "ManyToMany",
            "JoinColumn", "JoinTable", "MappedSuperclass"
    );

    // ------------------------------------------------------------------
    // 注解辅助
    // ------------------------------------------------------------------

    private boolean hasAnnotation(@NotNull PsiClass psi, @NotNull String qualifiedName) {
        return findAnnotation(psi, qualifiedName) != null;
    }

    private boolean hasAnnotation(@NotNull PsiField field, @NotNull String qualifiedName) {
        return findAnnotation(field, qualifiedName) != null;
    }

    /**
     * 按注解查找：优先全限定名；粘贴片段/测试环境注解类不可解析时，
     * 按短名兜底匹配（@Table → Table）。
     */
    @Nullable
    private PsiAnnotation findAnnotation(@NotNull PsiClass psi, @NotNull String qualifiedName) {
        if (psi.getModifierList() != null) {
            PsiAnnotation a = psi.getModifierList().findAnnotation(qualifiedName);
            if (a != null) return a;
        }
        return findAnnotationByShortName(
                psi.getModifierList() != null ? psi.getModifierList().getAnnotations() : null, qualifiedName);
    }

    private PsiAnnotation findAnnotation(@NotNull PsiField field, @NotNull String qualifiedName) {
        if (field.getModifierList() != null) {
            PsiAnnotation a = field.getModifierList().findAnnotation(qualifiedName);
            if (a != null) return a;
        }
        return findAnnotationByShortName(
                field.getModifierList() != null ? field.getModifierList().getAnnotations() : null, qualifiedName);
    }

    @Nullable
    private PsiAnnotation findAnnotationByShortName(
            @Nullable PsiAnnotation[] annotations,
            @NotNull String qualifiedName) {
        if (annotations == null) return null;
        String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        for (PsiAnnotation a : annotations) {
            if (a.getNameReferenceElement() != null
                    && simpleName.equals(a.getNameReferenceElement().getReferenceName())) {
                return a;
            }
        }
        return null;
    }

    /**
     * 读取注解的 value 属性字符串值（如 @Table("t_user") → t_user）。
     * 无注解或无值返回 null。
     */
    @Nullable
    private String annotationAttributeValue(@NotNull PsiClass psi, @NotNull String qualifiedName, @NotNull String attribute) {
        PsiAnnotation annotation = findAnnotation(psi, qualifiedName);
        if (annotation == null) return null;
        return attributeString(annotation, attribute);
    }

    @Nullable
    private String annotationAttributeValue(@NotNull PsiField field, @NotNull String qualifiedName, @NotNull String attribute) {
        PsiAnnotation annotation = findAnnotation(field, qualifiedName);
        if (annotation == null) return null;
        return attributeString(annotation, attribute);
    }

    @Nullable
    private String attributeString(@NotNull PsiAnnotation annotation, @NotNull String attribute) {
        PsiAnnotationMemberValue memberValue = annotation.findAttributeValue(attribute);
        if (memberValue == null) return null;
        if (!(memberValue instanceof PsiLiteralExpression literal)) return null;
        Object value = literal.getValue();
        if (!(value instanceof String s)) return null;
        // 空白视为未指定（回退命名策略）
        return s.isBlank() ? null : s;
    }

    // ------------------------------------------------------------------
    // 伴生对象（静态）成员
    // ------------------------------------------------------------------

    /** JPA 注解全限定名（jakarta.persistence）。 */
    private static final String ENTITY_ANNOTATION = "jakarta.persistence.Entity";
    private static final String TABLE_ANNOTATION = "jakarta.persistence.Table";
    private static final String COLUMN_ANNOTATION = "jakarta.persistence.Column";
    private static final String ID_ANNOTATION = "jakarta.persistence.Id";
    private static final String TRANSIENT_ANNOTATION = "jakarta.persistence.Transient";
    private static final String MANY_TO_ONE_ANNOTATION = "jakarta.persistence.ManyToOne";
    private static final String ONE_TO_MANY_ANNOTATION = "jakarta.persistence.OneToMany";
    private static final String ONE_TO_ONE_ANNOTATION = "jakarta.persistence.OneToOne";
    private static final String MANY_TO_MANY_ANNOTATION = "jakarta.persistence.ManyToMany";
    private static final String JOIN_COLUMN_ANNOTATION = "jakarta.persistence.JoinColumn";
    private static final String JOIN_TABLE_ANNOTATION = "jakarta.persistence.JoinTable";
    private static final String MAPPED_SUPERCLASS_ANNOTATION = "jakarta.persistence.MappedSuperclass";

    /** JPA 实体解析缓存键（PsiFile + classQualifiedName + configSignature）。 */
    private record JpaCacheKey(
            PsiFile file,
            String classQualifiedName,
            String configSignature
    ) {
    }

    /** 缓存条目：文件修改戳 + 解析结果。 */
    private record JpaEntry(long stamp, TableInfo value) {
    }

    /** 缓存键 → 条目（项目级，跟随 JpaEntityResolver 生命周期）。 */
    private static final ConcurrentHashMap<JpaCacheKey, JpaEntry> cache = new ConcurrentHashMap<>();

    /** 清空缓存（配置变更 / 项目关闭时调用）。 */
    public static void clearCache() {
        cache.clear();
    }

    /** 当前缓存条目数（测试 / 诊断用）。 */
    public static int cacheSize() {
        return cache.size();
    }

    /**
     * 无 @Table 时的表名推断（默认 SNAKE 驼峰转下划线；CAMEL 纯保留驼峰仅首字母小写）。
     */
    private static String inferTableName(@NotNull String className) {
        String strategy;
        try {
            strategy = PluginSettings.getInstance().getState().getTableNameInference();
        } catch (Exception e) {
            strategy = PluginSettings.INFERENCE_SNAKE;
        }
        if (PluginSettings.INFERENCE_CAMEL.equals(strategy)) {
            return className.substring(0, 1).toLowerCase() + className.substring(1);
        }
        return NamingStrategy.INSTANCE.toSnakeCase(className);
    }

    /**
     * 统一表名修饰链：
     * 最终表名 = 添加前缀 + 移除后缀(移除前缀(表名)) + 添加后缀。
     */
    private static String decorateTableName(@NotNull String raw) {
        String removedPrefix = raw;
        for (String p : removeTablePrefixList()) {
            if (raw.startsWith(p)) {
                removedPrefix = raw.substring(p.length());
                break;
            }
        }
        String removedSuffix = removedPrefix;
        for (String s : removeTableSuffixList()) {
            if (removedPrefix.endsWith(s)) {
                removedSuffix = removedPrefix.substring(0, removedPrefix.length() - s.length());
                break;
            }
        }
        return tablePrefix() + removedSuffix + tableSuffix();
    }

    /**
     * 实体类不可解析的兜底推断统一入口（按配置推断策略 + 统一修饰链）。
     */
    public static String inferDecoratedTableName(@NotNull String className) {
        return decorateTableName(inferTableName(className));
    }

    /** `;` 分隔配置解析为列表（trim + 去空；设置服务不可用时兜底空列表）。 */
    private static List<String> listSetting(@NotNull Function<PluginSettings.State, String> get) {
        String raw;
        try {
            raw = get.apply(PluginSettings.getInstance().getState());
        } catch (Exception e) {
            raw = "";
        }
        return Arrays.stream(raw.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String tablePrefix() {
        try {
            return PluginSettings.getInstance().getState().getTablePrefix();
        } catch (Exception e) {
            return "";
        }
    }

    private static String tableSuffix() {
        try {
            return PluginSettings.getInstance().getState().getTableSuffix();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> removeTablePrefixList() {
        return listSetting(s -> s.getRemoveTablePrefix());
    }

    private static List<String> removeTableSuffixList() {
        return listSetting(s -> s.getRemoveTableSuffix());
    }

    /** 忽略字段名集合（`;` 分隔，按 Java 字段名匹配）。 */
    private static Set<String> ignoredFieldNames() {
        return new LinkedHashSet<>(listSetting(s -> s.getIgnoredFields()));
    }
}
