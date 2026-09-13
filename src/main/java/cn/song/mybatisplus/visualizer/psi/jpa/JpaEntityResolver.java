package cn.song.mybatisplus.visualizer.psi.jpa;
import com.intellij.psi.PsiAnnotationMemberValue;

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

        // T-Perf-18：缓存键不持 PsiFile 强引用（VirtualFile.url 字符串标识）
        JpaCacheKey key = new JpaCacheKey(
                file.getVirtualFile() != null ? file.getVirtualFile().getUrl() : null,
                System.identityHashCode(file), qName, configSig);
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
        // 类级元数据：schema/catalog、限制子句、软删除、自定义脚本、生成器、副表、继承与判别、@IdClass
        // （T-J8 / T-J14 / T-J18，读取失败整体降级为空元数据）
        JpaMetadataReader.ClassMeta classMeta = JpaMetadataReader.readClassMeta(entityClass);
        // 表名：优先 @Table 注解（SD8：name → value 双属性兜底——relational @Table("t_user") value
        // 简写最常用），否则按命名策略由类名推断
        String tableName = tableAnnotationValue(entityClass);
        if (tableName == null) tableName = decorateTableName(inferTableName(className));

        // 递归父类收集字段（支持 @MappedSuperclass 继承；父类字段在前、子类字段在后）
        List<PsiField> fields = collectFieldsIncludingSuper(entityClass);
        Set<String> ignoredFields = ignoredFieldNames();
        // 类级 @AttributeOverride（嵌入列名覆写，字段级覆写优先于类级，T-J18）
        java.util.Map<String, String> classOverrides = JpaMetadataReader.attributeOverrides(entityClass);
        // 按角色分组稳定排序（PRIMARY 置顶 → BUSINESS 居中 → AUDIT/SYSTEM 沉底，组内保持声明顺序）
        List<ColumnInfo> columns = new ArrayList<>();
        for (PsiField field : fields) {
            columns.addAll(resolveColumns(field, ignoredFields, classOverrides));
        }
        columns.sort(Comparator.comparing(ColumnInfo::getRole));
        // 去重：同一列名只保留第一个（简单字段优先于 @ManyToOne 关联字段）
        // 示例：departmentId(@Column) 和 department(@ManyToOne+@JoinColumn) 同映射 department_id
        LinkedHashMap<String, ColumnInfo> byCol = new LinkedHashMap<>();
        for (ColumnInfo c : columns) {
            byCol.putIfAbsent(c.getColumnName(), c);
        }
        columns = new ArrayList<>(byCol.values());
        // 表注释（类级注解 > javadoc > 行注释）
        String comment = resolveComment(entityClass);
        // 收集 @ManyToOne / @OneToOne 关联元数据（T-J7：referencedColumnName/复合外键/mappedBy 侧别/@MapsId/@NotFound）
        List<AssociationInfo> associations = new ArrayList<>();
        for (PsiField field : fields) {
            AssociationInfo a = resolveAssociation(field);
            if (a != null) associations.add(a);
        }
        // 集合关联元数据（T-J7/T-J18：@OneToMany/@ManyToMany/@ElementCollection → 中间表/集合表语义）
        List<CollectionAssociationInfo> collectionAssociations = new ArrayList<>();
        for (PsiField field : fields) {
            CollectionAssociationInfo c = JpaMetadataReader.readCollectionAssociation(field,
                    this::resolveTargetTableName, tableName);
            if (c != null) collectionAssociations.add(c);
        }
        // jakarta/javax + spring-data/Easy-Query/xbatis @Version → versionColumn（T-J8/EQ9/XB）
        String versionColumn = null;
        for (PsiField versionField : fields) {
            if (JpaMetadataReader.hasAnyVersionAnnotation(versionField)) {
                for (ColumnInfo c : columns) {
                    if (versionField.getName() != null && c.getFieldName().equals(versionField.getName())) {
                        versionColumn = c.getColumnName();
                        break;
                    }
                }
                if (versionColumn != null) break;
            }
        }
        // EQ9/XB：@LogicDelete 标记字段 → logicDeleteColumn（可解析时）
        String logicDeleteColumn = null;
        for (PsiField field : fields) {
            String name = field.getName();
            if (name == null) continue;
            String candidate = JpaMetadataReader.logicDeleteColumnOf(field, name);
            if (candidate != null) {
                logicDeleteColumn = candidate;
                break;
            }
        }
        // 复合主键列清单（T-J18：@EmbeddedId / @IdClass / 多 @Id，声明顺序；单主键为空列表）
        boolean hasEmbeddedId = false;
        for (PsiField field : fields) {
            if (JpaMetadataReader.hasEmbeddedIdAnnotation(field)) {
                hasEmbeddedId = true;
                break;
            }
        }
        List<String> idColumns = new ArrayList<>();
        for (ColumnInfo c : columns) {
            if (c.isId()) idColumns.add(c.getColumnName());
        }
        List<String> compositeIdColumns = hasEmbeddedId || classMeta.getIdClassType() != null || idColumns.size() > 1
                ? idColumns : List.of();
        return new TableInfo(className, tableName, columns, logicDeleteColumn, versionColumn, comment,
                associations, classMeta.getSchema(), classMeta.getCatalog(),
                classMeta.getRestrictionClause(), classMeta.getSoftDelete(), classMeta.getCustomSqlScripts(),
                classMeta.getSequenceGenerators(), classMeta.getTableGenerators(), collectionAssociations,
                classMeta.getSecondaryTables(), classMeta.getPrimaryKeyJoinColumns(),
                classMeta.getInheritanceStrategy(), classMeta.getDiscriminatorColumn(),
                classMeta.getDiscriminatorValue(), compositeIdColumns, classMeta.getIdClassType());
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
     * 解析单个字段为列信息（可能展开为多列——嵌入对象展平，T-J18）；
     * 非持久化字段（@Transient / 集合关联注解 / mappedBy 侧 @OneToOne / 复合外键等）返回空列表。
     */
    @NotNull
    private List<ColumnInfo> resolveColumns(
            @NotNull PsiField field,
            @NotNull Set<String> ignoredFields,
            @NotNull java.util.Map<String, String> classOverrides) {
        String fieldName = field.getName();

        // @Transient / @ColumnIgnore / @Ignore：非持久化字段，跳过
        // （SD8：spring-data @Transient；EQ8：@ColumnIgnore；XB：@Ignore——M44 统一轨）
        if (JpaMetadataReader.hasTransientLikeAnnotation(field)) return List.of();

        // 一对多 / 多对多 / 元素集合关联字段跳过（非简单列，对应集合类型属性；
        // 集合语义由 TableInfo.collectionAssociations 承载）
        if (hasAnnotation(field, ONE_TO_MANY_ANNOTATION) || hasAnnotation(field, MANY_TO_MANY_ANNOTATION)
                || JpaMetadataReader.findFlex(field, JpaMetadataReader.FQN_ELEMENT_COLLECTION) != null) {
            return List.of();
        }

        // SD10（M44 止血）：@MappedCollection 集合/嵌套实体字段不落父表列（止血期不做反向列展开）
        if (JpaMetadataReader.isMappedCollectionField(field)) return List.of();
        // SD10：无注解的嵌套实体引用字段 / 元素为实体的集合字段同样跳过——
        // 否则 SELECT/INSERT 出现不存在的列（宁缺列不出错误列）
        if (JpaMetadataReader.isNestedEntityOrEntityCollectionField(field)) return List.of();

        // M44：Easy-Query @Column(exist = false) / xbatis @TableField(exists = false) 字段跳过
        if (JpaMetadataReader.hasExistFalse(field)) return List.of();

        // @OneToOne(mappedBy=...) 无外键侧：外键列在对方表，本表无列（T-J7 侧别语义——二次 SELECT 标记）
        if (hasAnnotation(field, ONE_TO_ONE_ANNOTATION) && JpaMetadataReader.mappedByOf(field) != null) {
            return List.of();
        }

        // 忽略字段名配置（`;` 分隔，按 Java 字段名匹配）
        if (fieldName != null && ignoredFields.contains(fieldName)) return List.of();

        // serialVersionUID 默认忽略
        if (fieldName != null && fieldName.toLowerCase().replace("_", "").equals("serialversionuid")) {
            return List.of();
        }

        boolean isManyToOne = hasAnnotation(field, MANY_TO_ONE_ANNOTATION);
        boolean isOneToOne = hasAnnotation(field, ONE_TO_ONE_ANNOTATION);
        boolean isEmbeddedId = JpaMetadataReader.hasEmbeddedIdAnnotation(field);

        // @Embedded / @Embeddable / @EmbeddedId：嵌入对象字段展平（T-J18）
        if (isEmbeddedId || JpaMetadataReader.isEmbeddedField(field)) {
            return flattenEmbeddedColumns(field, isEmbeddedId, classOverrides);
        }

        // @JoinColumns 复合外键：多列外键无法以单列表达，完整清单记入 AssociationInfo.joinColumns（T-J7）
        if ((isManyToOne || isOneToOne) && JpaMetadataReader.hasCompositeJoinColumns(field)) {
            return List.of();
        }

        // 列名：优先 @Column(name→value 兜底，SD8)/@JoinColumn（@ManyToOne 外键列）/
        // xbatis @TableField(value)，否则驼峰转下划线
        boolean isId = isIdentifierField(field);
        List<JoinColumnRef> toOneJoinColumnRefs = isManyToOne || isOneToOne
                ? JpaMetadataReader.readToOneJoinColumns(field, fieldName) : List.<JoinColumnRef>of();
        String columnName;
        if (!toOneJoinColumnRefs.isEmpty()) {
            // @ManyToOne / @OneToOne + @JoinColumn：外键列名（含无注解时的推断名）
            columnName = toOneJoinColumnRefs.get(0).getName();
        } else {
            // @Column / @TableField：普通列名（SD8：name → value 双属性兜底）
            columnName = JpaMetadataReader.columnValueOrSnake(field, fieldName);
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

        JoinColumnRef firstRef = toOneJoinColumnRefs.isEmpty() ? null : toOneJoinColumnRefs.get(0);
        return List.of(new ColumnInfo(fieldName, columnName, isId, false, fieldType, null, comment, role,
                firstRef != null ? firstRef.isInsertable() : JpaMetadataReader.columnInsertableOf(field),
                firstRef != null ? firstRef.isUpdatable() : JpaMetadataReader.columnUpdatableOf(field),
                isId ? JpaMetadataReader.generatedValueStrategy(field) : null,
                false,
                JpaMetadataReader.hasConvertAnnotation(field)));
    }

    /**
     * 展平嵌入对象字段为列清单（T-J18）：@AttributeOverride 覆写 > 内部字段 @Column > 驼峰转下划线；
     * @EmbeddedId 展平列全部标记主键。嵌入类型不可解析等异常场景降级为单列形态（字段名推断列名），不丢字段。
     */
    @NotNull
    private List<ColumnInfo> flattenEmbeddedColumns(
            @NotNull PsiField field,
            boolean isPrimaryKey,
            @NotNull java.util.Map<String, String> classOverrides) {
        String fieldName = field.getName();
        // 字段级 @AttributeOverride 优先于类级（合并顺序：右侧覆盖左侧同名键）
        java.util.Map<String, String> overrides = new LinkedHashMap<>(classOverrides);
        overrides.putAll(JpaMetadataReader.attributeOverrides(field));
        List<JpaMetadataReader.EmbeddedColumnSpec> specs;
        try {
            specs = JpaMetadataReader.flattenEmbeddedColumns(field, overrides);
        } catch (Throwable ignored) {
            specs = List.of();
        }
        if (specs.isEmpty()) {
            String fallbackType;
            try {
                fallbackType = field.getType().getCanonicalText();
            } catch (Throwable ignored) {
                fallbackType = null;
            }
            return List.of(new ColumnInfo(fieldName, NamingStrategy.INSTANCE.toSnakeCase(fieldName),
                    isPrimaryKey, false, fallbackType, null, resolveComment(field),
                    isPrimaryKey ? ColumnRole.PRIMARY : ColumnRole.BUSINESS,
                    true, true, null, true, false));
        }
        List<ColumnInfo> result = new ArrayList<>();
        for (JpaMetadataReader.EmbeddedColumnSpec spec : specs) {
            result.add(new ColumnInfo(spec.getFieldName(), spec.getColumnName(), isPrimaryKey, false,
                    spec.getFieldType(), null, resolveComment(spec.getField()),
                    isPrimaryKey ? ColumnRole.PRIMARY : ColumnRole.BUSINESS,
                    spec.isInsertable(), spec.isUpdatable(), null, true, false));
        }
        return result;
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
        // 外键列清单：@JoinColumn(name="xxx") / @JoinColumns 复合 / 无注解时默认 {fieldName}_id
        List<JoinColumnRef> joinColumnRefs = JpaMetadataReader.readToOneJoinColumns(field, fieldName);
        // 既有 joinColumn 字段语义不变：单列为该列名，复合外键取首列（完整清单见 joinColumns）
        String joinColumn = joinColumnRefs.get(0).getName();
        // mappedBy 侧别：本表无外键，查询语义为按主键二次 SELECT（T-J7）
        String mappedBy = JpaMetadataReader.mappedByOf(field);

        // 目标实体类：从字段类型获取
        PsiClass targetClass = resolveTargetEntityClass(field);
        if (targetClass == null) return null;
        String targetTableName = resolveTargetTableName(targetClass);

        // 别名：实体类名首字母小写
        String targetName = targetClass.getName();
        String base = targetName != null ? targetName : fieldName;
        String targetAlias = base.substring(0, 1).toLowerCase() + base.substring(1);

        return new AssociationInfo(fieldName, joinColumn, targetTableName, targetAlias,
                joinColumnRefs.get(0).getReferencedColumnName(), joinColumnRefs, mappedBy,
                mappedBy == null, null,
                JpaMetadataReader.isNotFoundIgnore(field), JpaMetadataReader.hasMapsId(field));
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
        String explicitTable = tableAnnotationValue(targetClass);
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

    /** JPA 核心映射注解（name/value 是表名/列名而非注释，不参与提取；含 M39 扩展元数据注解）。 */
    private static final Set<String> JPA_MAPPING_ANNOTATION_NAMES = Set.of(
            "Entity", "Table", "Column", "Id", "Transient",
            "ManyToOne", "OneToMany", "OneToOne", "ManyToMany",
            "JoinColumn", "JoinColumns", "JoinTable", "MappedSuperclass",
            // M39 元数据扩展（T-J7/T-J8/T-J14/T-J18）：name/value 是生成器名、SQL 脚本、覆写目标等，非注释
            "GeneratedValue", "SequenceGenerator", "SequenceGenerators",
            "TableGenerator", "TableGenerators", "Version",
            "Where", "SQLRestriction", "SoftDelete",
            "SQLDelete", "SQLDeleteAll", "SQLInsert", "SQLUpdate", "NotFound",
            "ElementCollection", "CollectionTable", "Embedded", "Embeddable", "EmbeddedId", "IdClass",
            "AttributeOverride", "AttributeOverrides", "MapsId", "Convert",
            "Inheritance", "DiscriminatorColumn", "DiscriminatorValue",
            "SecondaryTable", "SecondaryTables",
            "PrimaryKeyJoinColumn", "PrimaryKeyJoinColumns",
            "OrderBy", "OrderColumn"
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

    /**
     * 类级表名注解取值（SD8，M44）：@Table `name` → `value` 双属性兜底——
     * jakarta @Table 只有 name 属性（value 兜底天然落空），relational/Easy-Query/xbatis @Table
     * 以 value 简写为最常用形态（`@Table("t_user")`）。查找轨道：jakarta/javax FQN + relational
     * FQN + 短名兜底（findAnnotation/FQN_XBATIS_TABLE 显式轨）。
     */
    private String tableAnnotationValue(@NotNull PsiClass psi) {
        String value = annotationAttributeValue(psi, TABLE_ANNOTATION, "name");
        if (value != null) return value;
        value = annotationAttributeValue(psi, TABLE_ANNOTATION, "value");
        if (value != null) return value;
        value = annotationAttributeValue(psi, JpaMetadataReader.FQN_RELATIONAL_TABLE, "name");
        if (value != null) return value;
        value = annotationAttributeValue(psi, JpaMetadataReader.FQN_RELATIONAL_TABLE, "value");
        if (value != null) return value;
        return annotationAttributeValue(psi, JpaMetadataReader.FQN_XBATIS_TABLE, "value");
    }

    /**
     * 字段主键判定（M44 统一轨）：jakarta/javax @Id（既有短名兜底）+ spring-data @Id（SD8）+
     * xbatis @TableId（XB）+ Easy-Query @Column(primaryKey = true)（EQ8）。
     */
    private boolean isIdentifierField(@NotNull PsiField field) {
        return hasAnnotation(field, ID_ANNOTATION)
                || hasAnnotation(field, JpaMetadataReader.FQN_SD_ID)
                || hasAnnotation(field, JpaMetadataReader.FQN_XBATIS_TABLE_ID)
                || isEasyQueryPrimaryKey(field);
    }

    /** EQ8：@Column(primaryKey = true)（Easy-Query 以 Column 注解属性标记主键）。 */
    private boolean isEasyQueryPrimaryKey(@NotNull PsiField field) {
        PsiAnnotation column = JpaMetadataReader.findFlex(field, JpaMetadataReader.FQN_EASY_QUERY_COLUMN);
        if (column == null) return false;
        return Boolean.TRUE.equals(JpaMetadataReader.attrBoolean(column, "primaryKey"));
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
    private static final String ID_ANNOTATION = "jakarta.persistence.Id";
    private static final String MANY_TO_ONE_ANNOTATION = "jakarta.persistence.ManyToOne";
    private static final String ONE_TO_MANY_ANNOTATION = "jakarta.persistence.OneToMany";
    private static final String ONE_TO_ONE_ANNOTATION = "jakarta.persistence.OneToOne";
    private static final String MANY_TO_MANY_ANNOTATION = "jakarta.persistence.ManyToMany";
    private static final String JOIN_COLUMN_ANNOTATION = "jakarta.persistence.JoinColumn";
    private static final String JOIN_TABLE_ANNOTATION = "jakarta.persistence.JoinTable";
    private static final String MAPPED_SUPERCLASS_ANNOTATION = "jakarta.persistence.MappedSuperclass";

    /** JPA 实体解析缓存键（PsiFile + classQualifiedName + configSignature）。 */
    private static final class JpaCacheKey {
    private final String fileUrl;
    /** T-Perf-18：文件实例同一性辅助键（同 EntityResolver——light/mock 文件 url 可重复）。 */
    private final int fileIdentity;
    private final String classQualifiedName;
    private final String configSignature;

    public JpaCacheKey(String fileUrl, int fileIdentity, String classQualifiedName, String configSignature) {
        this.fileUrl = fileUrl;
        this.fileIdentity = fileIdentity;
        this.classQualifiedName = classQualifiedName;
        this.configSignature = configSignature;
    }

    public String getFileUrl() { return fileUrl; }
    public String fileUrl() { return fileUrl; }
    public int getFileIdentity() { return fileIdentity; }
    public int fileIdentity() { return fileIdentity; }
    public String getClassQualifiedName() { return classQualifiedName; }
    public String classQualifiedName() { return classQualifiedName; }
    public String getConfigSignature() { return configSignature; }
    public String configSignature() { return configSignature; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaCacheKey)) return false;
        JpaCacheKey that = (JpaCacheKey) o;
        return this.fileIdentity == that.fileIdentity && java.util.Objects.equals(this.fileUrl, that.fileUrl) && java.util.Objects.equals(this.classQualifiedName, that.classQualifiedName) && java.util.Objects.equals(this.configSignature, that.configSignature);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fileUrl, fileIdentity, classQualifiedName, configSignature);
    }

    @Override
    public String toString() {
        return "JpaCacheKey[" + "file=" + file + ", " + "classQualifiedName=" + classQualifiedName + ", " + "configSignature=" + configSignature + "]";
    }
}

    /** 缓存条目：文件修改戳 + 解析结果。 */
    private static final class JpaEntry {
    private final long stamp;
    private final TableInfo value;

    public JpaEntry(long stamp, TableInfo value) {
        this.stamp = stamp;
        this.value = value;
    }

    public long getStamp() { return stamp; }
    public long stamp() { return stamp; }
    public TableInfo getValue() { return value; }
    public TableInfo value() { return value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaEntry)) return false;
        JpaEntry that = (JpaEntry) o;
        return java.util.Objects.equals(this.stamp, that.stamp) && java.util.Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(stamp, value);
    }

    @Override
    public String toString() {
        return "JpaEntry[" + "stamp=" + stamp + ", " + "value=" + value + "]";
    }
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
