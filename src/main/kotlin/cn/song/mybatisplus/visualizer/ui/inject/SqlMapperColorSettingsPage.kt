package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/**
 * T-XmlGap W6：注入语言的**颜色设置页**（Settings → Editor → Color Scheme → MyBatis Mapper SQL）。
 *
 * 为什么要有：W2/W3 之前颜色全借平台语义槽（`KEYWORD`/`STRING`…），用户想调 `#{}` 底色只能改全局
 * 主题；本页把注入语言的 14 个键暴露为可调项（键表来源 = [SqlMapperSyntaxHighlighter] 的公开常量，
 * 逐项对应，不另立名目）。
 *
 * 键位说明（与 W2 的取舍一致，见该波次实施记录）：`#{}` 与 `${}` 各持一键，默认主题下分别为
 * `KEYWORD` 与 `INSTANCE_FIELD` 语义槽——本页让用户可把两者调成自己一眼能分的配色。
 * 布尔与关键字当前共用 `KEYWORD` 键（子代理 A1 登记项），故不单列描述符。
 */
class SqlMapperColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "MyBatis Mapper SQL"

    override fun getIcon(): Icon = com.intellij.openapi.util.IconLoader.getIcon("/icons/sqlGutter.svg", javaClass)

    override fun getHighlighter(): SyntaxHighlighter = SqlMapperSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    /**
     * 预览样例覆盖全部键：关键字 / 标识符 / 字符串 / 数字 / 注释 / 运算符 / 括号族 / 逗号 / 点 /
     * 静态引用 / `#{}` / `${}`。
     */
    override fun getDemoText(): String = """
        -- MyBatis mapper 语句预览（注入语言着色）
        SELECT u.id, u.user_name, COUNT(*) AS cnt
          FROM user_info u
         WHERE u.status = 1
           AND u.name LIKE CONCAT('%', #{keyword}, '%')
           AND u.tenant_id = ${'$'}{tenantId}
           AND u.age BETWEEN 18 AND 60
           AND u.type = @java.lang.Integer@MAX_VALUE
         GROUP BY u.id, u.user_name
        HAVING COUNT(*) > 0
         ORDER BY u.id DESC
    """.trimIndent()

    private companion object {
        /** 与 [SqlMapperSyntaxHighlighter] 公开键一一对应（新增键时此处同步，测试有覆盖守卫）。 */
        val DESCRIPTORS: Array<AttributesDescriptor> = arrayOf(
            AttributesDescriptor("关键字", SqlMapperSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("标识符（表名/列名/别名）", SqlMapperSyntaxHighlighter.IDENTIFIER),
            AttributesDescriptor("字符串", SqlMapperSyntaxHighlighter.STRING),
            AttributesDescriptor("注释", SqlMapperSyntaxHighlighter.COMMENT),
            AttributesDescriptor("数字", SqlMapperSyntaxHighlighter.NUMBER),
            AttributesDescriptor("运算符", SqlMapperSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("圆括号", SqlMapperSyntaxHighlighter.PAREN),
            AttributesDescriptor("方括号", SqlMapperSyntaxHighlighter.BRACKET),
            AttributesDescriptor("花括号", SqlMapperSyntaxHighlighter.BRACE),
            AttributesDescriptor("逗号", SqlMapperSyntaxHighlighter.COMMA),
            AttributesDescriptor("点号", SqlMapperSyntaxHighlighter.DOT),
            AttributesDescriptor("静态引用（@类@成员）", SqlMapperSyntaxHighlighter.STATIC_REF),
            AttributesDescriptor("预编译占位 #{}", SqlMapperSyntaxHighlighter.PARAM_HASH),
            AttributesDescriptor("字符串替换占位 ${'$'}{}", SqlMapperSyntaxHighlighter.PARAM_DOLLAR),
        )

        /** 供测试断言「描述符键集 = 高亮器键集」的公开镜像。 */
        internal val DESCRIPTOR_KEYS: List<TextAttributesKey> = DESCRIPTORS.map { it.key }
    }
}
