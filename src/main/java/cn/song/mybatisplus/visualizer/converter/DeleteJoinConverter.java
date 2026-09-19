package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DeleteJoinWrapper 转换器（T-M21-08，MPJ 官方联表删除，1.4.5+）。
 * `DELETE t,t1 FROM user t LEFT JOIN address t1 ON (...) WHERE (t.id = ?)`。
 *
 * 删除目标控制：delete(Class...)/delete(String...) → 删除目标表别名列表；deleteAll() → 主别名 + 全部 join 别名；
 * 无 delete 调用 → 默认仅主别名。
 */
public final class DeleteJoinConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final DeleteJoinConverter INSTANCE = new DeleteJoinConverter();

    private static final String MAIN_ALIAS_DEFAULT = "t";
    private static final String SET_ALIAS = "setAlias";
    private static final String DELETE = "delete";
    private static final String DELETE_ALL = "deleteAll";

    private DeleteJoinConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.DELETE_JOIN);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table, Project project) {
        String mainAlias = chain.getConstructorAlias();
        if (mainAlias == null) {
            WrapperCall setAlias = null;
            for (WrapperCall c : chain.getCalls()) {
                if (SET_ALIAS.equals(c.getMethodName())) { setAlias = c; break; }
            }
            if (setAlias != null) {
                SqlValue v = WrapperConverterKt.asValue(argAt(setAlias.getArgs(), 0));
                if (v instanceof SqlValue.Literal lit) mainAlias = lit.getUnquoted();
            }
        }
        if (mainAlias == null) mainAlias = MAIN_ALIAS_DEFAULT;

        List<WrapperCall> joinCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (MpjJoinSupport.JOIN_METHODS.contains(call.getMethodName())) joinCalls.add(call);
        }
        List<JoinClause> joins = new ArrayList<>();
        for (WrapperCall call : joinCalls) {
            // T-FS-04：project 透传至类名兜底推断
            JoinClause j = MpjJoinSupport.INSTANCE.parseJoin(call, mainAlias, project);
            if (j != null) joins.add(j);
        }

        List<String> deleteTargets = parseDeleteTargets(chain, table, mainAlias, joins);

        List<WrapperCall> conditionCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            String m = call.getMethodName();
            if (!MpjJoinSupport.JOIN_METHODS.contains(m) && !SET_ALIAS.equals(m)
                    && !DELETE.equals(m) && !DELETE_ALL.equals(m)) {
                conditionCalls.add(call);
            }
        }
        List<cn.song.mybatisplus.visualizer.model.Condition> conditions = new QueryWrapperParser(table, mainAlias)
                .parse(new WrapperChain(chain.getWrapperType(), chain.getEntityClass(), conditionCalls, null, false, false, false, false, null))
                .getConditions();

        return new DeleteStatement(table, conditions, deleteTargets, mainAlias, joins);
    }

    /** 解析删除目标表别名列表（T-M21-08）。多调用 / 重复目标去重（保序）。 */
    private List<String> parseDeleteTargets(WrapperChain chain, TableInfo table, String mainAlias, List<JoinClause> joins) {
        List<WrapperCall> deleteCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (DELETE.equals(call.getMethodName()) || DELETE_ALL.equals(call.getMethodName())) deleteCalls.add(call);
        }
        if (deleteCalls.isEmpty()) return List.of(mainAlias);

        List<String> targets = new ArrayList<>();
        for (WrapperCall call : deleteCalls) {
            if (DELETE_ALL.equals(call.getMethodName())) {
                targets.add(mainAlias);
                for (JoinClause j : joins) targets.add(j.getAlias());
                continue;
            }
            for (WrapperArg arg : call.getArgs()) {
                SqlValue literal = WrapperConverterKt.asValue(arg);
                if (!(literal instanceof SqlValue.Literal lit)) continue;
                boolean isClass = !lit.getRaw().startsWith("'") && !lit.getRaw().startsWith("\"");
                String name = isClass ? lit.getRaw().replace(".class", "").trim() : lit.getUnquoted();
                if (name.isEmpty()) continue;
                if (isClass) {
                    if (name.equals(table.getEntityClassName()) || name.equals(chain.getEntityClass())) {
                        targets.add(mainAlias);
                    } else {
                        targets.add(lowerFirst(name));
                    }
                } else {
                    targets.add(name);
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(targets));
    }

    private static String lowerFirst(String name) {
        if (name.isEmpty()) return name;
        return name.substring(0, 1).toLowerCase() + name.substring(1);
    }

    private static WrapperArg argAt(List<WrapperArg> args, int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }
}
