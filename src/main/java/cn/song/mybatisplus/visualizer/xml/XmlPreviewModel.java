package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * XML 预览模型（T-M24-04）：驱动预览面板的动态条件勾选与参数输入。
 *
 * renderSql 是唯一渲染源——初始渲染与面板勾选重建共用，保证一致；
 * 纯逻辑（依赖 OgnlEvaluator / XmlSqlRenderer），不依赖 PSI / IDE，可独立单测。
 *
 * 模拟参数策略（M24 版，文档化）：
 *  - 顶层变量（bind / ${} 表达式引用的根标识符，排除 _parameter/_databaseId）
 *    统一占位为字符串 "1"；
 *  - if/when 真假由面板开关直接决定（启发式默认），表达式求值仅在无开关覆盖时发生；
 *  - foreach 展开次数由面板输入（默认 DEFAULT_FOREACH_SIZE），collection 表达式全文作为 mock 键；
 *  - bind 覆盖值优先（DynamicContext.isProtected）。
 */
public class XmlPreviewModel {

    /** foreach 默认展开次数（IN 场景两元素可直观展示 separator）。 */
    public static final int DEFAULT_FOREACH_SIZE = 2;

    private final String statementId;
    private final XmlStatementKind kind;
    private final List<XmlConditionView> conditionViews;
    private final List<XmlForEachView> forEachViews;
    private final List<XmlBindView> bindViews;
    private final String parameterType;
    private final List<String> errors;
    private final Set<String> variables;
    private final Set<String> paramProps;
    private final Map<String, Object> staticFieldValues;
    private final Map<String, Object> extraBindings;
    private final Map<String, Boolean> paramQuotes;
    private final SqlNode rootNode;

    private XmlPreviewModel(
            String statementId,
            XmlStatementKind kind,
            List<XmlConditionView> conditionViews,
            List<XmlForEachView> forEachViews,
            List<XmlBindView> bindViews,
            String parameterType,
            List<String> errors,
            Set<String> variables,
            Set<String> paramProps,
            Map<String, Object> staticFieldValues,
            Map<String, Object> extraBindings,
            Map<String, Boolean> paramQuotes,
            SqlNode rootNode
    ) {
        this.statementId = statementId;
        this.kind = kind;
        this.conditionViews = conditionViews;
        this.forEachViews = forEachViews;
        this.bindViews = bindViews;
        this.parameterType = parameterType;
        this.errors = errors;
        this.variables = variables;
        this.paramProps = paramProps;
        this.staticFieldValues = staticFieldValues;
        this.extraBindings = extraBindings;
        this.paramQuotes = paramQuotes;
        this.rootNode = rootNode;
    }

    public String getStatementId() {
        return statementId;
    }

    public XmlStatementKind getKind() {
        return kind;
    }

    public List<XmlConditionView> getConditionViews() {
        return conditionViews;
    }

    public List<XmlForEachView> getForEachViews() {
        return forEachViews;
    }

    public List<XmlBindView> getBindViews() {
        return bindViews;
    }

    public String getParameterType() {
        return parameterType;
    }

    public List<String> getErrors() {
        return errors;
    }

    public Set<String> getVariables() {
        return variables;
    }

    public Set<String> getParamProps() {
        return paramProps;
    }

    public Map<String, Object> getStaticFieldValues() {
        return staticFieldValues;
    }

    public Map<String, Object> getExtraBindings() {
        return extraBindings;
    }

    public Map<String, Boolean> getParamQuotes() {
        return paramQuotes;
    }

    public SqlNode getRootNode() {
        return rootNode;
    }

    /**
     * T-M29.5：参数路径 → 引号策略（精确匹配优先，前缀匹配兜底）。
     * 未命中返回 null（面板兜底引号）。
     */
    public Boolean quoteFor(String source) {
        if (paramQuotes.containsKey(source)) {
            return paramQuotes.get(source);
        }
        for (Map.Entry<String, Boolean> e : paramQuotes.entrySet()) {
            if (source.startsWith(e.getKey() + ".")) {
                return e.getValue();
            }
        }
        return null;
    }

    public XmlPreviewState defaultState() {
        Set<String> enabledIds = conditionViews.stream()
                .filter(XmlConditionView::defaultEnabled)
                .map(XmlConditionView::id)
                .collect(Collectors.toSet());
        Map<String, Integer> forEachCounts = forEachViews.stream()
                .collect(Collectors.toMap(XmlForEachView::collectionExpr, v -> DEFAULT_FOREACH_SIZE));
        return new XmlPreviewState(enabledIds, forEachCounts, Set.of(), Map.of());
    }

    public RenderedXmlSql renderSql() {
        return renderSql(defaultState());
    }

    public RenderedXmlSql renderSql(XmlPreviewState state) {
        return new XmlSqlRenderer().render(
                rootNode,
                mockBindings(state),
                conditionViews.stream().collect(Collectors.toMap(XmlConditionView::id, v -> state.enabledIds().contains(v.id()))),
                state.bindOverrides().keySet(),
                staticFieldValues,
                bindViews.stream().map(XmlBindView::name).collect(Collectors.toSet())
        );
    }

    private Map<String, Object> mockBindings(XmlPreviewState state) {
        Map<String, Object> bindings = new HashMap<>();
        // 变量/参数名占位双写：_parameter 对象属性 + bindings 顶层
        Set<String> mockPropsKeys = new HashSet<>(variables);
        mockPropsKeys.addAll(paramProps);
        Map<String, Object> mockProps = new HashMap<>();
        for (String k : mockPropsKeys) {
            mockProps.put(k, "1");
        }
        bindings.put(DynamicContext.PARAMETER_OBJECT_KEY, new MockObject(mockProps));
        for (String k : mockProps.keySet()) {
            bindings.put(k, "1");
        }
        bindings.put(DynamicContext.DATABASE_ID_KEY, null);
        for (XmlForEachView view : forEachViews) {
            if (state.forEachNulls().contains(view.collectionExpr())) {
                bindings.put(view.collectionExpr(), null);
            } else {
                bindings.put(view.collectionExpr(),
                        new MockIterable(state.forEachCounts().getOrDefault(view.collectionExpr(), DEFAULT_FOREACH_SIZE)));
            }
        }
        for (Map.Entry<String, String> e : state.bindOverrides().entrySet()) {
            bindings.put(e.getKey(), e.getValue());
        }
        // T-M26-B：MP 占位 mock 覆盖（ew → 片段对象，置于最后优先级最高）
        bindings.putAll(extraBindings);
        return bindings;
    }

    /** 启发式默认勾选态：含 == null → false（变量默认非 null），其余 true（保守展开）。 */
    public static boolean defaultTruth(String test) {
        return !test.contains("== null");
    }

    /** 由解析出的语句构建预览模型（变量收集 + 条件/foreach/bind 视图）。 */
    public static XmlPreviewModel from(
            XmlStatement statement,
            List<String> errors,
            Set<String> extraParamProps,
            Map<String, Object> staticFieldValues,
            Map<String, Object> extraBindings,
            Map<String, Boolean> paramQuotes
    ) {
        List<XmlConditionView> conditionViews = new ArrayList<>();
        List<XmlForEachView> forEachViews = new ArrayList<>();
        List<XmlBindView> bindViews = new ArrayList<>();
        Set<String> variables = new HashSet<>();
        Set<String> paramProps = new HashSet<>();
        collect(statement.node(), conditionViews, forEachViews, bindViews, variables, paramProps);
        variables.remove(DynamicContext.PARAMETER_OBJECT_KEY);
        variables.remove(DynamicContext.DATABASE_ID_KEY);
        paramProps.addAll(extraParamProps);
        return new XmlPreviewModel(
                statement.id(),
                statement.kind(),
                conditionViews,
                forEachViews,
                bindViews,
                statement.parameterType(),
                errors,
                variables,
                paramProps,
                staticFieldValues,
                extraBindings,
                paramQuotes,
                statement.node()
        );
    }

    public static XmlPreviewModel from(XmlStatement statement, List<String> errors, Set<String> extraParamProps, Map<String, Object> staticFieldValues, Map<String, Object> extraBindings) {
        return from(statement, errors, extraParamProps, staticFieldValues, extraBindings, Map.of());
    }

    public static XmlPreviewModel from(
            XmlStatement statement,
            List<String> errors,
            Map<String, Object> staticFieldValues,
            Map<String, Object> extraBindings,
            Map<String, Boolean> paramQuotes
    ) {
        return from(statement, errors, Set.of(), staticFieldValues, extraBindings, paramQuotes);
    }

    public static XmlPreviewModel from(XmlStatement statement, List<String> errors, Set<String> extraParamProps, Map<String, Object> staticFieldValues) {
        return from(statement, errors, extraParamProps, staticFieldValues, Map.of(), Map.of());
    }

    public static XmlPreviewModel from(XmlStatement statement, List<String> errors, Set<String> extraParamProps) {
        return from(statement, errors, extraParamProps, Map.of(), Map.of(), Map.of());
    }

    public static XmlPreviewModel from(XmlStatement statement, List<String> errors) {
        return from(statement, errors, Set.of(), Map.of(), Map.of(), Map.of());
    }

    /** 表达式根路径分派：_parameter.x → 模拟参数对象属性；其余根（排除保留键）→ 变量。 */
    private static void collectExpressionPaths(String expression, Set<String> variables, Set<String> paramProps) {
        for (String path : OgnlEvaluator.rootPaths(expression)) {
            String prefix = DynamicContext.PARAMETER_OBJECT_KEY + ".";
            if (path.startsWith(prefix)) {
                paramProps.add(path.substring(prefix.length()));
            } else if (!path.equals(DynamicContext.PARAMETER_OBJECT_KEY) && !path.equals(DynamicContext.DATABASE_ID_KEY)) {
                variables.add(path);
            }
        }
    }

    private static void collect(
            SqlNode node,
            List<XmlConditionView> conditionViews,
            List<XmlForEachView> forEachViews,
            List<XmlBindView> bindViews,
            Set<String> variables,
            Set<String> paramProps
    ) {
        if (node instanceof MixedSqlNode m) {
            for (SqlNode c : m.getChildren()) {
                collect(c, conditionViews, forEachViews, bindViews, variables, paramProps);
            }
        } else if (node instanceof StaticTextSqlNode) {
            // no-op
        } else if (node instanceof TextSqlNode t) {
            List<String> dollarTokens = new ArrayList<>();
            TokenParser.parse(t.getText(), "${", "}", tok -> {
                dollarTokens.add(tok);
                return tok;
            });
            for (String tok : dollarTokens) {
                collectExpressionPaths(tok, variables, paramProps);
            }
        } else if (node instanceof IfSqlNode i) {
            conditionViews.add(new XmlConditionView(i.getTest(), i.getTest(), defaultTruth(i.getTest())));
            collect(i.getContents(), conditionViews, forEachViews, bindViews, variables, paramProps);
        } else if (node instanceof ChooseSqlNode c) {
            for (SqlNode w : c.getWhenNodes()) {
                collect(w, conditionViews, forEachViews, bindViews, variables, paramProps);
            }
            if (c.getDefaultNode() != null) {
                collect(c.getDefaultNode(), conditionViews, forEachViews, bindViews, variables, paramProps);
            }
        } else if (node instanceof TrimSqlNode tr) {
            collect(tr.getContents(), conditionViews, forEachViews, bindViews, variables, paramProps);
        } else if (node instanceof ForeachSqlNode f) {
            forEachViews.add(new XmlForEachView(f.getCollectionExpression(), f.getItem()));
            collect(f.getContents(), conditionViews, forEachViews, bindViews, variables, paramProps);
        } else if (node instanceof VarDeclSqlNode v) {
            bindViews.add(new XmlBindView(v.getName(), v.getExpression()));
            collectExpressionPaths(v.getExpression(), variables, paramProps);
        }
    }
}
