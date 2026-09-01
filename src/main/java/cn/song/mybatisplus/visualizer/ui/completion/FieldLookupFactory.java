package cn.song.mybatisplus.visualizer.ui.completion;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper 实体字段补全项工厂（F-5，阶段 B）。
 *
 * <p>在 {@code .eq(|)} / {@code .like(|)} / {@code .set(|)} 等方法的<b>首参位置</b>提示实体字段名（驼峰），
 * 带字段类型图标 + 注释灰色尾缀 + lambda 自动补全插入处理器（展开为 {@code Entity::getField}）。
 *
 * <p>实体解析（{@link EntityResolver}）仅在补全触发时执行，失败时返回空列表（仅提供方法名补全）。
 * 字段名补全仅在<b>已知 Wrapper 链中</b>触发（由调用方 {@link WrapperCompletionContributor} 守卫）。
 */
public final class FieldLookupFactory {

    private FieldLookupFactory() {
    }

    /**
     * 生成实体字段名补全项。
     *
     * @param project     当前项目
     * @param chainHead   链首方法调用（用于解析实体类）
     * @param currentCall 当前所在方法调用（用于判定首参位置）
     * @return 字段补全项列表；实体解析失败时返回空列表
     */
    @NotNull
    public static List<LookupElement> createLookups(
            @NotNull Project project,
            @NotNull PsiMethodCallExpression chainHead,
            @NotNull PsiMethodCallExpression currentCall) {
        TableInfo tableInfo = resolveEntityTable(project, chainHead);
        if (tableInfo == null) {
            return List.of();
        }
        List<LookupElement> result = new ArrayList<>();
        for (ColumnInfo column : tableInfo.getColumns()) {
            result.add(buildFieldLookup(column));
        }
        return result;
    }

    /**
     * 解析链首对应的实体表信息（F-5，阶段 B1）。
     *
     * <p>策略（纯文本 + PSI 遍历，无 resolve——符合 M10 性能红线）：
     * <ol>
     *   <li>从链首的 new 构造类型实参 / 工厂调用类型实参提取实体类名（文本）；</li>
     *   <li>在链首所在文件内按类名查找 PsiClass（PSI 子树遍历，无 resolve）；</li>
     *   <li>命中时走 {@link EntityResolver} 解析（注解/命名策略兜底）；</li>
     *   <li>未命中（实体类不在当前文件）→ 返回 null（降级：仅方法名补全）。</li>
     * </ol>
     */
    @Nullable
    private static TableInfo resolveEntityTable(@NotNull Project project, @NotNull PsiMethodCallExpression chainHead) {
        String entityClassName = extractEntityClassName(chainHead);
        if (entityClassName == null) {
            return null;
        }
        com.intellij.psi.PsiFile file = chainHead.getContainingFile();
        if (file == null) {
            return null;
        }
        // 在当前文件内按类名查找 PsiClass（PSI 遍历，无 resolve）
        PsiClass entityClass = null;
        for (PsiClass pc : PsiTreeUtil.collectElementsOfType(file, PsiClass.class)) {
            if (entityClassName.equals(pc.getName())) {
                entityClass = pc;
                break;
            }
        }
        if (entityClass == null) {
            return null;
        }
        try {
            return new EntityResolver().resolve(entityClass);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从链首提取实体类名（纯文本，无 resolve）：
     * <ul>
     *   <li>new 构造：从构造类型实参文本提取（{@code new LambdaQueryWrapper<DeviceInfoEntity>()} → {@code DeviceInfoEntity}）</li>
     *   <li>工厂/服务链：从工厂调用类型实参文本提取（{@code Wrappers.<DeviceInfoEntity>lambdaQuery()} → {@code DeviceInfoEntity}）</li>
     * </ul>
     * 提取失败时返回 null。
     */
    @Nullable
    private static String extractEntityClassName(@NotNull PsiMethodCallExpression chainHead) {
        com.intellij.psi.PsiExpression qualifier = chainHead.getMethodExpression().getQualifierExpression();

        // new 构造：qualifier 是 PsiNewExpression
        if (qualifier instanceof PsiNewExpression) {
            PsiType type = ((PsiNewExpression) qualifier).getType();
            if (!(type instanceof PsiClassType)) {
                return null;
            }
            PsiType[] params = ((PsiClassType) type).getParameters();
            if (params.length == 0) {
                return null;
            }
            return (params[0] instanceof PsiClassType) ? ((PsiClassType) params[0]).getClassName() : null;
        }

        // 工厂/服务链：从链首调用的类型实参提取
        PsiType[] typeArgs = chainHead.getTypeArguments();
        if (typeArgs.length > 0) {
            return (typeArgs[0] instanceof PsiClassType) ? ((PsiClassType) typeArgs[0]).getClassName() : null;
        }

        // 链式续链（wrapper.eq(...) 的 wrapper 是变量引用）：从 qualifier 引用变量声明类型提取
        if (!(qualifier instanceof PsiReferenceExpression)) {
            return null;
        }
        PsiReferenceExpression refExpr = (PsiReferenceExpression) qualifier;
        PsiVariable resolvedVar = (refExpr.resolve() instanceof PsiVariable) ? (PsiVariable) refExpr.resolve() : null;
        if (resolvedVar == null) {
            return null;
        }
        PsiType varType = resolvedVar.getType();
        if (!(varType instanceof PsiClassType)) {
            return null;
        }
        PsiType[] varParams = ((PsiClassType) varType).getParameters();
        if (varParams.length == 0) {
            return null;
        }
        return (varParams[0] instanceof PsiClassType) ? ((PsiClassType) varParams[0]).getClassName() : null;
    }

    /**
     * 构建单个字段补全项（F-5，阶段 B2）：
     * <ul>
     *   <li>展示名 = 字段名（驼峰，如 {@code stationId}）</li>
     *   <li>图标：字段节点</li>
     *   <li>tailText = 列名（蛇形，如 {@code station_id}）</li>
     *   <li>typeText = 字段类型（如 {@code Long}）</li>
     *   <li>插入处理器：lambda 自动补全（展开为 {@code Entity::getField}）</li>
     * </ul>
     */
    @NotNull
    private static LookupElement buildFieldLookup(@NotNull ColumnInfo column) {
        LookupElementBuilder builder = LookupElementBuilder.create(column.getFieldName())
                .withIcon(AllIcons.Nodes.Field)
                .withInsertHandler(LAMBDA_INSERT_HANDLER);
        // 列名 tailText（蛇形）
        LookupElementBuilder withColumnTail;
        if (!column.getColumnName().equals(column.getFieldName())) {
            withColumnTail = builder.withTailText("  " + column.getColumnName(), true);
        } else {
            withColumnTail = builder;
        }
        // 字段类型 typeText（短名）
        String shortType = column.getFieldType() != null
                ? column.getFieldType().substring(column.getFieldType().lastIndexOf('.') + 1) : null;
        if (shortType != null) {
            return withColumnTail.withTypeText(shortType);
        }
        return withColumnTail;
    }

    /**
     * 字段名插入处理器（F-5，阶段 B3）：插入字段名后追加 {@code ::} 触发方法引用补全。
     * 仅在下一字符不是 {@code :} 时插入（避免重复）。
     */
    @NotNull
    private static final InsertHandler<LookupElement> LAMBDA_INSERT_HANDLER = new InsertHandler<LookupElement>() {
        @Override
        public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
            Editor editor = context.getEditor();
            int caretOffset = editor.getCaretModel().getOffset();
            Document doc = editor.getDocument();
            String lookupString = item.getLookupString();
            // 插入字段名（替换前缀）
            doc.replaceString(context.getStartOffset(), caretOffset, lookupString);
            int newOffset = context.getStartOffset() + lookupString.length();
            // 追加 :: 触发方法引用补全
            if (newOffset >= doc.getTextLength() || doc.getCharsSequence().charAt(newOffset) != ':') {
                doc.insertString(newOffset, "::");
                editor.getCaretModel().moveToOffset(newOffset + 2);
            }
        }
    };
}
