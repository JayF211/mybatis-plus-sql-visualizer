// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperColorSettingsPage.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

import java.util.List;
import java.util.Map;

/**
 * T-XmlGap W6：注入语言的<b>颜色设置页</b>（Settings → Editor → Color Scheme → MyBatis Mapper SQL）。
 *
 * <p>为什么要有：W2/W3 之前颜色全借平台语义槽（{@code KEYWORD}/{@code STRING}…），用户想调 {@code #{}}
 * 底色只能改全局主题；本页把注入语言的 14 个键暴露为可调项（键表来源 = SqlMapperSyntaxHighlighter
 * 的公开常量，逐项对应，不另立名目）。</p>
 *
 * <p>键位说明（与 W2 的取舍一致，见该波次实施记录）：{@code #{}} 与 {@code ${}} 各持一键，默认主题下分别为
 * {@code KEYWORD} 与 {@code INSTANCE_FIELD} 语义槽——本页让用户可把两者调成自己一眼能分的配色。
 * 布尔与关键字当前共用 {@code KEYWORD} 键（子代理 A1 登记项），故不单列描述符。</p>
 */
public class SqlMapperColorSettingsPage implements ColorSettingsPage {

    @Override
    @NotNull
    public String getDisplayName() {
        return "MyBatis Mapper SQL";
    }

    @Override
    @Nullable
    public Icon getIcon() {
        return IconLoader.getIcon("/icons/sqlGutter.svg", SqlMapperColorSettingsPage.class);
    }

    @Override
    @NotNull
    public SyntaxHighlighter getHighlighter() {
        return new SqlMapperSyntaxHighlighter();
    }

    @Override
    @NotNull
    public AttributesDescriptor[] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    @NotNull
    public ColorDescriptor[] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    @Nullable
    public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return null;
    }

    /**
     * 预览样例覆盖全部键：关键字 / 标识符 / 字符串 / 数字 / 注释 / 运算符 / 括号族 / 逗号 / 点 /
     * 静态引用 / {@code #{}} / {@code ${}}。
     */
    @Override
    @NotNull
    public String getDemoText() {
        return """
                -- MyBatis mapper 语句预览（注入语言着色）
                SELECT u.id, u.user_name, COUNT(*) AS cnt
                  FROM user_info u
                 WHERE u.status = 1
                   AND u.name LIKE CONCAT('%', #{keyword}, '%')
                   AND u.tenant_id = ${tenantId}
                   AND u.age BETWEEN 18 AND 60
                   AND u.type = @java.lang.Integer@MAX_VALUE
                 GROUP BY u.id, u.user_name
                HAVING COUNT(*) > 0
                 ORDER BY u.id DESC\
                """;
    }

    /** 与 SqlMapperSyntaxHighlighter 公开键一一对应（新增键时此处同步，测试有覆盖守卫）。 */
    private static final AttributesDescriptor[] DESCRIPTORS = {
            new AttributesDescriptor("关键字", SqlMapperSyntaxHighlighter.KEYWORD),
            new AttributesDescriptor("标识符（表名/列名/别名）", SqlMapperSyntaxHighlighter.IDENTIFIER),
            new AttributesDescriptor("字符串", SqlMapperSyntaxHighlighter.STRING),
            new AttributesDescriptor("注释", SqlMapperSyntaxHighlighter.COMMENT),
            new AttributesDescriptor("数字", SqlMapperSyntaxHighlighter.NUMBER),
            new AttributesDescriptor("运算符", SqlMapperSyntaxHighlighter.OPERATOR),
            new AttributesDescriptor("圆括号", SqlMapperSyntaxHighlighter.PAREN),
            new AttributesDescriptor("方括号", SqlMapperSyntaxHighlighter.BRACKET),
            new AttributesDescriptor("花括号", SqlMapperSyntaxHighlighter.BRACE),
            new AttributesDescriptor("逗号", SqlMapperSyntaxHighlighter.COMMA),
            new AttributesDescriptor("点号", SqlMapperSyntaxHighlighter.DOT),
            new AttributesDescriptor("静态引用（@类@成员）", SqlMapperSyntaxHighlighter.STATIC_REF),
            new AttributesDescriptor("预编译占位 #{}", SqlMapperSyntaxHighlighter.PARAM_HASH),
            new AttributesDescriptor("字符串替换占位 ${}", SqlMapperSyntaxHighlighter.PARAM_DOLLAR),
    };

    /** 供测试断言「描述符键集 = 高亮器键集」的公开镜像。 */
    public static final List<TextAttributesKey> DESCRIPTOR_KEYS =
            java.util.Arrays.stream(DESCRIPTORS).map(AttributesDescriptor::getKey).toList();
}
