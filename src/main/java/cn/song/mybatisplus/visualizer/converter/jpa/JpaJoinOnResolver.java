package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import java.util.ArrayList;
import java.util.List;

/**
 * JOIN ON 条件真实化解析器（J7，覆盖矩阵 §3「JOIN [LEFT/INNER] ... ON」行）。
 *
 * 此前 JOIN FETCH / JOIN 的 ON 条件是固定猜测 `副表别名.id = 主表别名.&lt;relation&gt;_id`
 * 并标注「推断的ON条件，请验证」。本解析器按三级优先产出真实 ON 条件：
 *
 *  1. **PSI 注解级**（[resolveWithPsi]，需调用方传入 Project）：
 *     - `@ManyToOne` / `@OneToOne`（持有外键侧）：读 `@JoinColumn(name)` 外键列 +
 *       `referencedColumnName`（缺省时读目标实体 `@Id` 字段的真实主键列名）→
 *       `副表别名.&lt;被引用列&gt; = 主表别名.&lt;外键列&gt;`
 *     - `@OneToMany(mappedBy = "x")`（外键在副表侧）：反查目标实体 `x` 字段的
 *       `@JoinColumn(name)` → `主表别名.&lt;被引用列&gt; = 副表别名.&lt;外键列&gt;`（方向正确的真实 ON）
 *  2. **关联元数据级**（[resolve]，纯字符串管道可用）：
 *     `TableInfo.associations`（JpaEntityResolver 解析 `@JoinColumn(name)` 的产物）中的
 *     外键列是真实的，副表主键列按惯例取 `id`（注解级主键列名须走第 1 级）
 *  3. **降级**（[fallback]）：无任何元数据（`@MapsId` 派生标识、复合外键 `@JoinColumns({..})`、
 *     `@ManyToMany`/`@JoinTable` 中间表、实体类不可解析）→ 维持既有猜测文本 +
 *     「推断的ON条件，请验证」注释，绝不抛异常。
 *
 * PSI 读取只封装在本文件内（converter/jpa 自有文件），只读不修改 psi/jpa/ 下任何文件。
 *
 * 无状态单例，可独立单测。
 */
public final class JpaJoinOnResolver {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpaJoinOnResolver INSTANCE = new JpaJoinOnResolver();

    private JpaJoinOnResolver() {
    }

    /**
     * JOIN ON 解析产物。
     *
     * @param onCondition ON 条件文本（直接拼入 `JOIN 表 别名 ON &lt;onCondition&gt;`）
     * @param resolved    true = 基于真实注解/关联元数据；false = 降级猜测（附请验证注释）
     * @param note        降级原因说明（resolved = true 时为 null）
     * @param targetTable 副表表名（PSI 注解级解析时携带，供 JOIN 改写取真实副表名；
     *                    关联元数据/降级路径为 null——改写方自有表名来源）
     */
    public static final class OnResolution {
        private final String onCondition;
        private final boolean resolved;
        private final String note;
        private final String targetTable;

        public OnResolution(String onCondition, boolean resolved) {
            this(onCondition, resolved, null, null);
        }

        public OnResolution(String onCondition, boolean resolved, String note, String targetTable) {
            this.onCondition = onCondition;
            this.resolved = resolved;
            this.note = note;
            this.targetTable = targetTable;
        }

        public String getOnCondition() { return onCondition; }
        public boolean isResolved() { return resolved; }
        public String getNote() { return note; }
        public String getTargetTable() { return targetTable; }
    }

    /**
     * 关联元数据级解析（第 2 级）：从 [TableInfo.associations] 取真实外键列。
     *
     * @param table         主表信息（含 associations）
     * @param rootAlias     主表（根实体）别名，如 `u`
     * @param relationField 关联属性名（Java 字段名），如 `department`
     * @param joinAlias     副表别名，如 `d`
     */
    public static OnResolution resolve(TableInfo table, String rootAlias, String relationField, String joinAlias) {
        cn.song.mybatisplus.visualizer.model.AssociationInfo association = table.associationByField(relationField);
        if (association == null) return fallback(rootAlias, relationField, joinAlias);
        // association.joinColumn 由 JpaEntityResolver 从 @JoinColumn(name) 读取，是真实外键列；
        // 副表主键列无元数据，按惯例取 id（注解级主键列名 / referencedColumnName 走 resolveWithPsi）
        return new OnResolution(joinAlias + ".id = " + rootAlias + "." + association.getJoinColumn(), true);
    }

    /**
     * PSI 注解级解析（第 1 级）：直接读取实体字段上的 JPA 关联注解。
     *
     * 任何一步不可得（实体类找不到 / 注解读不到 / @MapsId / 复合外键 / @JoinTable）
     * 都降级到 [resolve]（有 associations 时）或 [fallback]，绝不抛异常。
     *
     * @param project         项目（PSI 查找用）
     * @param entityClassName 根实体类简单名，如 `UserEntity`
     * @param table           主表信息（降级到关联元数据级时使用；可为 null）
     */
    public static OnResolution resolveWithPsi(Project project, String entityClassName,
                                              String rootAlias, String relationField, String joinAlias) {
        return resolveWithPsi(project, entityClassName, rootAlias, relationField, joinAlias, null);
    }

    public static OnResolution resolveWithPsi(Project project, String entityClassName,
                                              String rootAlias, String relationField, String joinAlias,
                                              TableInfo table) {
        return resolveWithPsi(project, entityClassName, rootAlias, relationField, joinAlias, table, null);
    }

    public static OnResolution resolveWithPsi(Project project, String entityClassName,
                                              String rootAlias, String relationField, String joinAlias,
                                              TableInfo table, PsiElement context) {
        PsiClass entityClass = JpaAliasTableResolver.recoverJpaPsi(() -> findEntityClass(project, entityClassName, context));
        if (entityClass == null) return degrade(rootAlias, relationField, joinAlias, table, "实体类不可解析");
        return resolveWithPsi(entityClass, rootAlias, relationField, joinAlias, table);
    }

    /**
     * PSI 注解级解析——PsiClass 直入形态（核心实现）。
     *
     * 与名称查找形态 [resolveWithPsi] 等价；调用方已有 PsiClass 时用本形态，
     * 免一次按名检索（LightPlatform 测试环境也无需依赖索引）。
     */
    public static OnResolution resolveWithPsi(PsiClass entityClass, String rootAlias,
                                              String relationField, String joinAlias) {
        return resolveWithPsi(entityClass, rootAlias, relationField, joinAlias, null);
    }

    public static OnResolution resolveWithPsi(PsiClass entityClass, String rootAlias,
                                              String relationField, String joinAlias, TableInfo table) {
        try {
            PsiField field = null;
            for (PsiField candidate : entityClass.getAllFields()) {
                if (relationField.equals(candidate.getName())) {
                    field = candidate;
                    break;
                }
            }
            if (field == null) return degrade(rootAlias, relationField, joinAlias, table, "关联字段不可解析");

            List<JoinColumnRef> joinColumns = readJoinColumns(field);
            String mappedBy = stringAttribute(field, ONE_TO_MANY, "mappedBy");
            boolean hasJoinTable = findAnnotationByShortName(field, JOIN_TABLE) != null;
            boolean hasMapsId = findAnnotationByShortName(field, MAPS_ID) != null;

            // 降级形态：@MapsId 派生标识 / 复合外键 / @JoinTable 中间表 / @ManyToMany
            if (hasMapsId) {
                return degrade(rootAlias, relationField, joinAlias, table, "@MapsId 派生标识");
            }
            if (joinColumns.size() > 1) {
                return degrade(rootAlias, relationField, joinAlias, table, "复合外键（多 @JoinColumn）");
            }
            if (hasJoinTable || findAnnotationByShortName(field, MANY_TO_MANY) != null) {
                return degrade(rootAlias, relationField, joinAlias, table, "@JoinTable 中间表关联");
            }

            boolean isManyToOne = findAnnotationByShortName(field, MANY_TO_ONE) != null;
            boolean isOneToOne = findAnnotationByShortName(field, ONE_TO_ONE) != null;
            boolean isOneToMany = findAnnotationByShortName(field, ONE_TO_MANY) != null;

            if (isManyToOne || (isOneToOne && mappedBy == null)) {
                // 外键在主表侧：@ManyToOne / @OneToOne（无 mappedBy 的持有方）
                PsiClass targetClass = resolveTargetEntityClass(field);
                if (targetClass == null) return degrade(rootAlias, relationField, joinAlias, table, "目标实体不可解析");
                String fkName = !joinColumns.isEmpty() && joinColumns.get(0).name != null
                        ? joinColumns.get(0).name
                        : NamingStrategy.INSTANCE.toSnakeCase(relationField) + "_id";
                String referenced = !joinColumns.isEmpty() && joinColumns.get(0).referencedColumnName != null
                        ? joinColumns.get(0).referencedColumnName
                        : primaryKeyColumn(targetClass);
                if (referenced == null) return degrade(rootAlias, relationField, joinAlias, table, "目标实体主键不可解析");
                return new OnResolution(
                        joinAlias + "." + referenced + " = " + rootAlias + "." + fkName,
                        true,
                        null,
                        tableNameOf(targetClass));
            }
            if (isOneToMany && mappedBy != null) {
                // 外键在副表侧：@OneToMany(mappedBy = "x")，反查目标实体 x 字段的 @JoinColumn
                PsiClass targetClass = resolveTargetEntityClass(field);
                if (targetClass == null) return degrade(rootAlias, relationField, joinAlias, table, "目标实体不可解析");
                PsiField inverseField = null;
                for (PsiField candidate : targetClass.getAllFields()) {
                    if (mappedBy.equals(candidate.getName())) {
                        inverseField = candidate;
                        break;
                    }
                }
                if (inverseField == null) return degrade(rootAlias, relationField, joinAlias, table, "mappedBy 反向字段不可解析");
                List<JoinColumnRef> inverseJoinColumns = readJoinColumns(inverseField);
                if (inverseJoinColumns.size() > 1) {
                    return degrade(rootAlias, relationField, joinAlias, table, "反向复合外键");
                }
                String fkName = !inverseJoinColumns.isEmpty() && inverseJoinColumns.get(0).name != null
                        ? inverseJoinColumns.get(0).name
                        : NamingStrategy.INSTANCE.toSnakeCase(mappedBy) + "_id";
                String referenced = !inverseJoinColumns.isEmpty() && inverseJoinColumns.get(0).referencedColumnName != null
                        ? inverseJoinColumns.get(0).referencedColumnName
                        : primaryKeyColumn(entityClass);
                if (referenced == null) return degrade(rootAlias, relationField, joinAlias, table, "主表主键不可解析");
                return new OnResolution(
                        rootAlias + "." + referenced + " = " + joinAlias + "." + fkName,
                        true,
                        null,
                        tableNameOf(targetClass));
            }
            // 单向 @OneToMany 无 mappedBy（JPA 规范默认 @JoinTable）→ 降级
            return degrade(rootAlias, relationField, joinAlias, table, "无关联注解元数据");
        } catch (RuntimeException ignored) {
            if (ignored instanceof com.intellij.openapi.progress.ProcessCanceledException
                    || ignored instanceof com.intellij.openapi.diagnostic.ControlFlowException) throw ignored;
            return degrade(rootAlias, relationField, joinAlias, table, "PSI 读取异常");
        }
    }

    /**
     * 降级：先尝试关联元数据级，再退到猜测文本（与既有输出完全一致，保证存量回归不变）。
     */
    private static OnResolution degrade(String rootAlias, String relationField, String joinAlias,
                                        TableInfo table, String reason) {
        if (table != null) {
            OnResolution associationBased = resolve(table, rootAlias, relationField, joinAlias);
            if (associationBased.resolved) return associationBased;
        }
        OnResolution guessed = fallback(rootAlias, relationField, joinAlias);
        return new OnResolution(guessed.onCondition, guessed.resolved, reason, null);
    }

    /**
     * 猜测降级（第 3 级）：维持 J7 之前的固定猜测文本——
     * `副表别名.id = 主表别名.&lt;relation&gt;_id /* 推断的ON条件，请验证 *&#47;`。
     * 存量测试（HqlConverterJoinFetchTest 等）按此文本断言，不可改动。
     */
    public static OnResolution fallback(String rootAlias, String relationField, String joinAlias) {
        return new OnResolution(
                joinAlias + ".id = " + rootAlias + "." + relationField + "_id /* 推断的ON条件，请验证 */",
                false);
    }

    // ------------------------------------------------------------------
    // PSI 读取辅助（本文件自有封装，只读；不依赖 psi/jpa 内部实现）
    // ------------------------------------------------------------------

    /** 按查询宿主模块可见性消歧，禁止以索引顺序作为优先级。 */
    private static PsiClass findEntityClass(Project project, String className, PsiElement context) {
        if (className == null || className.isBlank()) return null;
        return cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep.resolve(project, className, context).psiClass();
    }

    /**
     * 解析关联字段的目标实体类：
     *  - 非泛型（`private DepartmentEntity department`）→ 字段类型本身
     *  - 集合泛型（`private List&lt;OrderEntity&gt; orders`）→ 第一个泛型实参
     *  - 目标是 JDK 类型（java.util.* 等）/ 不可解析 → null
     */
    private static PsiClass resolveTargetEntityClass(PsiField field) {
        if (!(field.getType() instanceof PsiClassType classType)) return null;
        PsiType[] parameters;
        try {
            parameters = classType.getParameters();
        } catch (RuntimeException ignored) {
            if (ignored instanceof com.intellij.openapi.progress.ProcessCanceledException
                    || ignored instanceof com.intellij.openapi.diagnostic.ControlFlowException) throw ignored;
            parameters = PsiClassType.EMPTY_ARRAY;
        }
        PsiClassType candidate = parameters.length == 0
                ? classType
                : (parameters[0] instanceof PsiClassType firstParam ? firstParam : null);
        if (candidate == null) return null;
        PsiClass resolved = candidate.resolve();
        if (resolved == null) return null;
        String qualifiedName = resolved.getQualifiedName();
        if (qualifiedName == null) return resolved;
        return qualifiedName.startsWith("java.") ? null : resolved;
    }

    /**
     * 读取实体主键列名：`@Id` 字段的 `@Column(name)`，缺省按驼峰转下划线。
     * `@EmbeddedId` / 复合主键 / 找不到 `@Id` → null（由调用方降级）。
     */
    private static String primaryKeyColumn(PsiClass clazz) {
        List<PsiField> idFields = new ArrayList<>();
        for (PsiField candidate : clazz.getAllFields()) {
            if (findAnnotationByShortName(candidate, ID) != null) idFields.add(candidate);
        }
        if (idFields.size() != 1) return null; // 0 = 无主键元数据；>1 = 复合主键
        PsiField idField = idFields.get(0);
        if (findAnnotationByShortName(idField, EMBEDDED_ID) != null) return null;
        String byColumn = stringAttribute(idField, COLUMN, "name");
        if (byColumn != null) return byColumn;
        return NamingStrategy.INSTANCE.toSnakeCase(idField.getName());
    }

    /** 目标实体表名：`@Table(name)` 优先，缺省按类名驼峰转下划线（简化推断，无前缀后缀修饰）。 */
    private static String tableNameOf(PsiClass clazz) {
        PsiAnnotation explicit = null;
        try {
            PsiModifierList modifierList = clazz.getModifierList();
            if (modifierList != null) {
                for (PsiAnnotation annotation : modifierList.getAnnotations()) {
                    if (annotation.getNameReferenceElement() != null
                            && TABLE.equals(annotation.getNameReferenceElement().getReferenceName())) {
                        explicit = annotation;
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            if (ignored instanceof com.intellij.openapi.progress.ProcessCanceledException
                    || ignored instanceof com.intellij.openapi.diagnostic.ControlFlowException) throw ignored;
            // 注解读取失败按缺省推断处理
        }
        String fromAnnotation = explicit != null ? stringAttribute(explicit, "name") : null;
        if (fromAnnotation != null) return fromAnnotation;
        String name = clazz.getName();
        return name != null ? NamingStrategy.INSTANCE.toSnakeCase(name) : null;
    }

    /** 读取字段上的 @JoinColumn 列表（含 @JoinColumns({..}) 容器形态；无则空列表）。 */
    private static List<JoinColumnRef> readJoinColumns(PsiField field) {
        PsiAnnotation direct = findAnnotationByShortName(field, JOIN_COLUMN);
        if (direct != null) return List.of(readJoinColumn(direct));

        PsiAnnotation container = findAnnotationByShortName(field, JOIN_COLUMNS);
        if (container == null) return List.of();
        PsiAnnotationMemberValue value;
        try {
            value = container.findAttributeValue("value");
        } catch (RuntimeException ignored) {
            if (ignored instanceof com.intellij.openapi.progress.ProcessCanceledException
                    || ignored instanceof com.intellij.openapi.diagnostic.ControlFlowException) throw ignored;
            return List.of();
        }
        if (value == null) return List.of();
        // 数组形态注解值是 PsiArrayInitializerMemberValue（非 Kotlin Array），需单独分支
        List<PsiAnnotation> members = new ArrayList<>();
        if (value instanceof PsiArrayInitializerMemberValue arrayValue) {
            for (PsiElement initializer : arrayValue.getInitializers()) {
                if (initializer instanceof PsiAnnotation annotation) members.add(annotation);
            }
        } else if (value instanceof PsiAnnotation annotation) {
            members.add(annotation);
        }
        List<JoinColumnRef> result = new ArrayList<>();
        for (PsiAnnotation member : members) {
            result.add(readJoinColumn(member));
        }
        return result;
    }

    private static JoinColumnRef readJoinColumn(PsiAnnotation annotation) {
        return new JoinColumnRef(
                stringAttribute(annotation, "name"),
                stringAttribute(annotation, "referencedColumnName"));
    }

    /** @JoinColumn 解析产物（name / referencedColumnName 均可为 null = 未指定）。 */
    private static final class JoinColumnRef {
        final String name;
        final String referencedColumnName;

        JoinColumnRef(String name, String referencedColumnName) {
            this.name = name;
            this.referencedColumnName = referencedColumnName;
        }
    }

    /** 读注解 String 属性值（非字面量 / 不可解析返回 null；空白视为未指定）。 */
    private static String stringAttribute(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue memberValue;
        try {
            memberValue = annotation.findAttributeValue(attribute);
        } catch (RuntimeException ignored) {
            if (ignored instanceof com.intellij.openapi.progress.ProcessCanceledException
                    || ignored instanceof com.intellij.openapi.diagnostic.ControlFlowException) throw ignored;
            return null;
        }
        if (memberValue == null) return null;
        String value = memberValue instanceof PsiLiteralExpression literal
                && literal.getValue() instanceof String literalText ? literalText : null;
        if (value == null) return null;
        return !value.isBlank() ? value : null;
    }

    /** 按短名查找注解（兼容 javax / jakarta 全限定名与裸短名，与 JpaEntityResolver 同策略）。 */
    private static PsiAnnotation findAnnotationByShortName(PsiField field, String simpleName) {
        PsiModifierList modifierList = field.getModifierList();
        if (modifierList == null) return null;
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            if (annotation.getNameReferenceElement() != null
                    && simpleName.equals(annotation.getNameReferenceElement().getReferenceName())) {
                return annotation;
            }
        }
        return null;
    }

    private static String stringAttribute(PsiField field, String annotationShortName, String attribute) {
        PsiAnnotation annotation = findAnnotationByShortName(field, annotationShortName);
        if (annotation == null) return null;
        return stringAttribute(annotation, attribute);
    }

    // JPA 注解短名（短名匹配同时覆盖 javax.persistence / jakarta.persistence 两种 FQN）
    private static final String MANY_TO_ONE = "ManyToOne";
    private static final String ONE_TO_ONE = "OneToOne";
    private static final String ONE_TO_MANY = "OneToMany";
    private static final String MANY_TO_MANY = "ManyToMany";
    private static final String JOIN_COLUMN = "JoinColumn";
    private static final String JOIN_COLUMNS = "JoinColumns";
    private static final String JOIN_TABLE = "JoinTable";
    private static final String MAPS_ID = "MapsId";
    private static final String ID = "Id";
    private static final String EMBEDDED_ID = "EmbeddedId";
    private static final String COLUMN = "Column";
    private static final String TABLE = "Table";
}
