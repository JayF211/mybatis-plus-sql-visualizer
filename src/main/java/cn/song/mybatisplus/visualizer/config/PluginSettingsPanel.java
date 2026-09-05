package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction;
import cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.PluginBuildInfo;
import cn.song.mybatisplus.visualizer.log.InjectionResult;
import cn.song.mybatisplus.visualizer.log.SqlSourceInterceptorAutoInjector;
import cn.song.mybatisplus.visualizer.util.PluginCacheManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentListener;

/**
 * 设置表单面板（T-M5-02，F-16 配置项 UI）。
 *
 * 展示并可编辑全部配置项；{@link #apply()}/{@link #reset()}/{@link #isModified()} 与 {@link PluginSettings} 联动，
 * 由 {@link PluginSettingsConfigurable} 接入 IDE Settings 框架（保存即生效）。
 *
 * M14 增强：
 *  - 「界面语言」下拉（默认简体中文）——保存后经 {@link #rebuild()} 重建表单即时生效；
 *  - 「快捷键」行——{@link ShortcutEditor} **内嵌编辑** Convert to SQL 快捷键，
 *    保存时直接写入 IDE 当前 Keymap（不再跳转）。
 *
 * M14.1（用户反馈）：
 *  - 移除悬停预览相关配置（autoPreview / hoverDelay 已随功能删除）；
 *  - 布局紧凑化（减小行距 + 底部 weighty 占位，消除内容区上方空白）。
 *
 * M16.2 增强：
 *  - 「表前缀」「表后缀」文本输入行（可添加/清除）——统一修饰预览 SQL 中的表名（全局语义）。
 */
public class PluginSettingsPanel {

    public static final String SECTION_PREVIEW = "preview";
    public static final String SECTION_TABLE_MAPPING = "tableMapping";
    public static final String SECTION_CODE_GEN = "codeGen";
    public static final String SECTION_SQL_LOG = "sqlLog";
    public static final String SECTION_UI = "ui";
    public static final String SECTION_UPDATE_DIAGNOSTICS = "updateDiagnostics";

    public static final Set<String> SECTION_ALL = Set.of(
        SECTION_PREVIEW,
        SECTION_TABLE_MAPPING,
        SECTION_CODE_GEN,
        SECTION_SQL_LOG,
        SECTION_UI,
        SECTION_UPDATE_DIAGNOSTICS);

    /** 自动保存文件名模板占位符（如 `$yyyy-MM-dd$`）。 */
    private static final Pattern PATTERN_PLACEHOLDER = Pattern.compile("\\$([^$]+)\\$");

    /**
     * 语言选项（值 + 当前语言下的显示文案；rebuild 时按新语言重建）。
     *
     * M19 修复：必须按 value 判等——JDK17 `JComboBox.setSelectedItem` 会以 `equals` 在 model 中查找元素，
     * **找不到则静默 return**（选中不生效、ActionEvent 不触发）；原无 equals 覆写（身份判等）时
     * `reset()`/写穿入口传入的新实例全部匹配失败——选中显示与写穿监听一并失效。
     */
    private static final class LanguageOption {

        final String value;
        final String label;

        LanguageOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LanguageOption)) return false;
            return Objects.equals(value, ((LanguageOption) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    private static LanguageOption[] languageOptions() {
        return new LanguageOption[]{
            new LanguageOption("zh", MpSqlVisualizerBundle.message("settings.language.zh")),
            new LanguageOption("en", MpSqlVisualizerBundle.message("settings.language.en")),
            new LanguageOption("AUTO", MpSqlVisualizerBundle.message("settings.language.auto")),
        };
    }

    private static LanguageOption languageOptionByValue(String value) {
        for (LanguageOption option : languageOptions()) {
            if (Objects.equals(option.value, value)) return option;
        }
        return null;
    }

    /**
     * 表名推断策略选项（值 + 当前语言下的显示文案；rebuild 时按新语言重建）。
     * 与 {@link LanguageOption} 同理必须按 value 判等（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    private static final class InferenceOption {

        final String value;
        final String label;

        InferenceOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InferenceOption)) return false;
            return Objects.equals(value, ((InferenceOption) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    private static InferenceOption[] inferenceOptions() {
        return new InferenceOption[]{
            new InferenceOption(PluginSettings.INFERENCE_SNAKE, MpSqlVisualizerBundle.message("settings.tableNameInference.snake")),
            new InferenceOption(PluginSettings.INFERENCE_CAMEL, MpSqlVisualizerBundle.message("settings.tableNameInference.camel")),
        };
    }

    private static InferenceOption inferenceOptionByValue(String value) {
        for (InferenceOption option : inferenceOptions()) {
            if (Objects.equals(option.value, value)) return option;
        }
        return null;
    }

    /**
     * 反向转换 Wrapper 形态选项（T-M36.3）：值 + 当前语言下的显示文案。
     * 与 {@link LanguageOption} 同理必须按 value 判等（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    private static final class ReverseFormatOption {

        final String value;
        final String label;

        ReverseFormatOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReverseFormatOption)) return false;
            return Objects.equals(value, ((ReverseFormatOption) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    private static ReverseFormatOption[] reverseFormatOptions() {
        return new ReverseFormatOption[]{
            new ReverseFormatOption(PluginSettings.REVERSE_AUTO, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.auto")),
            new ReverseFormatOption(PluginSettings.REVERSE_QUERY, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.query")),
            new ReverseFormatOption(PluginSettings.REVERSE_LAMBDA, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.lambda")),
            new ReverseFormatOption(PluginSettings.REVERSE_WRAPPERS, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.wrappers")),
            new ReverseFormatOption(PluginSettings.REVERSE_MPJ_LAMBDA, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.mpjLambda")),
        };
    }

    private static ReverseFormatOption reverseFormatOptionByValue(String value) {
        for (ReverseFormatOption option : reverseFormatOptions()) {
            if (Objects.equals(option.value, value)) return option;
        }
        return null;
    }

    // T-TenantLine 诊断日志：输出 UI Apply 决策链路。
    private static final Logger LOG = Logger.getInstance(PluginSettingsPanel.class);

    // ------------------------------------------------------------------
    // 表单控件
    // ------------------------------------------------------------------

    private final PluginSettings settings;
    private final Set<String> sections;

    /** 方言下拉（选项来自方言注册表）。 */
    private final JComboBox<String> dialectCombo = new JComboBox<>(
        DialectRegistry.INSTANCE.getDialects().stream().map(Dialect::getName).toArray(String[]::new));

    /** 参数替换开关。 */
    private final JBCheckBox replaceParamsCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.replaceParams"));

    /** 格式化开关。 */
    private final JBCheckBox formatCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.formatSql"));

    /** gutter 预览 SQL 图标开关（M10）。 */
    private final JBCheckBox gutterIconCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.showGutterIcon"));
    /** JOIN 图渲染方式（T-Dgm-04）：原生 Diagrams 框架 vs 经典自绘渲染器。 */
    private final JBCheckBox joinDiagramNativeCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.joinDiagramNative"));

    /** 逻辑删除注入开关（T-M15-07）：@TableLogic 实体查询/删除自动追加 `AND deleted = 0`。 */
    private final JBCheckBox injectLogicDeleteCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.injectLogicDelete"));
    private final JBTextField logicDeleteValueField = new JBTextField(6);

    /** 多租户注入开关（T-TenantLine）：SELECT/UPDATE/DELETE 自动追加 `AND tenant_id = ?`、INSERT 列清单追加租户列。 */
    private final JBCheckBox injectTenantLineCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.injectTenantLine"));

    /** 租户列名输入框（T-TenantLine）：默认 tenant_id；可按业务调整，如 org_id / shop_id。 */
    private final JBTextField tenantColumnNameField = new JBTextField(10);

    /** 租户列默认值输入框（F-6 Phase A）：UPDATE SET / INSERT 追加租户列时的字面量默认值。 */
    private final JBTextField tenantColumnDefaultField = new JBTextField(6);

    /** INSERT 也追加租户列开关（F-6 Phase A）：INSERT 列清单追加租户列。 */
    private final JBCheckBox injectTenantOnInsertCheck = new JBCheckBox();

    /** 数据权限占位提示开关（F-6 Phase C）：显示 DataPermissionInterceptor 运行时差异提示。 */
    private final JBCheckBox dataPermissionHintCheck = new JBCheckBox();

    /** 数据权限模拟条件输入框（F-6 Phase C）：用户手动填写的 WHERE 片段。 */
    private final JBTextField dataPermissionMockConditionField = new JBTextField(20);

    /** JDBC 模板 SQL 字符串预览开关：queryForList/update 等方法的字符串 SQL 实参显示预览图标。 */
    private final JBCheckBox jdbcSqlPreviewCheck = new JBCheckBox();

    /** 表前缀输入框（T-M16.2-03）：统一加在所有表名前，全局语义（@TableName 显式表名同样生效）。 */
    private final JBTextField tablePrefixField = new JBTextField(12);

    /** 表后缀输入框（T-M16.2-03）：统一加在所有表名后，全局语义；清空即恢复原样。T-M19.4：多值 `;` 分隔。 */
    private final JBTextField tableSuffixField = new JBTextField(12);

    /** 表名推断策略下拉（T-M19.4）：无 @TableName 时类名→表名的推断方式（SNAKE 默认 / CAMEL 保留驼峰）。 */
    private final JComboBox<InferenceOption> inferenceCombo = new JComboBox<>();

    /** 忽略字段名输入框（T-M19.4）：`;` 分隔的 Java 字段名——命中字段不进 SQL（SELECT 展开/补全/参数对照）。 */
    private final JBTextField ignoredFieldsField = new JBTextField(12);

    /** 移除表名前缀输入框（T-M19.4）：`;` 分隔，最终表名以哪个开头就移除哪个（首个匹配）。 */
    private final JBTextField removeTablePrefixField = new JBTextField(12);

    /** 移除表名后缀输入框（T-M19.4）：`;` 分隔，最终表名以哪个结尾就移除哪个（首个匹配）。 */
    private final JBTextField removeTableSuffixField = new JBTextField(12);

    /** 界面语言下拉（M14：默认简体中文；跟随系统 / English 可选）。 */
    private final JComboBox<LanguageOption> languageCombo = new JComboBox<>();

    /** 反向转换 Wrapper 形态下拉（T-M36.3：AUTO/QUERY/LAMBDA/MPJ_LAMBDA）。 */
    private final JComboBox<ReverseFormatOption> reverseFormatCombo = new JComboBox<>();

    /** 反向转换手动选择实体类开关（自动推断未命中/多表 JOIN 时弹实体选择弹窗）。 */
    private final JBCheckBox manualEntityCheck = new JBCheckBox();

    /** 插件缓存信息（统一条目总数——所有打开项目汇总；不细分缓存类型）。 */
    private final JBLabel cacheInfoLabel = new JBLabel(" ");

    /** 插件缓存统一清除按钮（清全部缓存，下次使用自动重建）。 */
    private final JButton clearCacheButton = new JButton(MpSqlVisualizerBundle.message("settings.cache.clear"));

    // ---------- T-M31.14 SQL 日志配置（参考 MybatisCodeHelper-Pro「MyBatis Log Setting」） ----------
    private final JBTextField logSqlPrefixField = new JBTextField(12);
    private final JBTextField logParamPrefixField = new JBTextField(12);
    private final JBCheckBox logMixedPrefixCheck = new JBCheckBox();
    private final JBCheckBox logAutoCaptureCheck = new JBCheckBox();
    private final JBCheckBox logAutoOpenCheck = new JBCheckBox();
    private final JBCheckBox logStopOnToolWindowCloseCheck = new JBCheckBox();
    private final JBCheckBox logTimestampCheck = new JBCheckBox();
    private final JBTextField logTimestampFormatField = new JBTextField(12);
    private final JBCheckBox logFormatSqlCheck = new JBCheckBox();
    private final JBTextField logFilterKeywordsField = new JBTextField(12);
    private final JBTextField logDedupeField = new JBTextField(12);
    private final JBTextField logMaxParseField = new JBTextField(12);
    private final JBTextField logMaxCountField = new JBTextField(12);
    private final JBCheckBox logHibernateEnabledCheck = new JBCheckBox();
    private final JBTextField logHibernateSqlPrefixField = new JBTextField(12);
    private final JBTextField logHibernateParamPrefixField = new JBTextField(12);
    private final JBCheckBox logJdbcEnabledCheck = new JBCheckBox();

    // ---------- F-43 自动保存历史 SQL ----------
    private final JBCheckBox logAutoSaveCheck = new JBCheckBox();
    private final JBTextField logAutoSavePathField = new JBTextField(20);
    private final JButton logAutoSaveBrowseButton = new JButton("...");
    private final JBTextField logAutoSavePatternField = new JBTextField(20);
    private final JBLabel logAutoSavePatternPreview = new JBLabel(" ");

    // ---------- O-1 日志窗口差异化（执行耗时 / 慢查询） ----------
    private final JBTextField logTimePrefixField = new JBTextField(12);
    private final JBTextField slowQueryThresholdField = new JBTextField(12);

    // ---------- F-44-7 SqlSourceInterceptor 自动注入 ----------
    private final JBCheckBox autoInjectInterceptorCheck = new JBCheckBox();
    private final JBCheckBox logShowDurationCheck = new JBCheckBox();

    /**
     * M19.1：语言写穿后「待应用」标记——语言已实时写入配置，但用户尚未点击「应用/确定」。
     * 平台 Apply 按钮启用 = `configurable.isModified()`，无此标记则写穿后 Apply 按钮灰掉
     * （用户实测反馈）；`apply()` 消费后清除。
     */
    private boolean languagePending = false;

    /** 快捷键内嵌编辑（M14.1：录制式修改 Convert to SQL 的 KeyboardShortcut，保存写入 Keymap）。 */
    private final ShortcutEditor shortcutEditor = new ShortcutEditor(ConvertToSqlAction.ACTION_ID);

    /** T-M41：SQL→Java 快捷键内嵌编辑（同款录制式，目标 action = ConvertSqlToJava）。 */
    private final ShortcutEditor reverseShortcutEditor = new ShortcutEditor(ConvertSqlToJavaAction.ACTION_ID);

    /** 更新分区（M28：检查更新/下载安装/重启提示/启动自动检查开关）。 */
    private final UpdateSection updateSection;

    // 搜索框已移至主设置页面（PluginSettingsFolderConfigurable）

    private final JPanel rootPanel;

    // ------------------------------------------------------------------
    // 构造与表单布局
    // ------------------------------------------------------------------

    public PluginSettingsPanel(PluginSettings settings) {
        this(settings, SECTION_ALL);
    }

    public PluginSettingsPanel(PluginSettings settings, Set<String> sections) {
        this.settings = settings;
        this.sections = sections;
        this.updateSection = new UpdateSection(settings);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8, 10, 8, 10));
        this.rootPanel = panel;

        buildSections(rootPanel);
        reset();
        refreshCacheInfo();
        // M19（用户实测：设置切语言确定后重开仍原样——平台 Settings 框架 OK 仅对「当前选中页」且
        // isModified()==true 的 configurable 调用 apply()，改完语言切到其他页再确定即被静默跳过；
        // M16.3 roaming 修复只覆盖磁盘持久化链路）：
        // 语言下拉**写穿式持久化**——选中即写入配置（不依赖 OK→apply 链路），并立即重建表单即时切换。
        languageCombo.addActionListener(e -> {
            Object selected = languageCombo.getSelectedItem();
            String value = selected instanceof LanguageOption ? ((LanguageOption) selected).value : null;
            if (value != null && !value.equals(settings.getState().getLanguage())) {
                settings.getState().setLanguage(value);
                // M19.1：写穿后置「待应用」标记——平台 Apply 按钮启用 = isModified()，无标记则
                // 应用按钮灰掉（用户实测反馈）；标记在 apply() 时清除（应用/确定后恢复灰属正常）
                languagePending = true;
                // M19.1：**invokeLater 调度重建**——ActionEvent 派发中途同步修改组件树时
                // UI 刷新不可靠（用户实测：左侧树即时变、右侧面板不变），延后到派发完成再重建
                SwingUtilities.invokeLater(this::rebuild);
            }
        });
    }

    /**
     * 构建表单布局（T-M31.8：分组 + TitledSeparator 分隔线，对齐 SQL 预览面板左侧分区样式）。
     * 高频组在上、低频组在下、同类相聚；rebuild 时重新调用（语言切换后组标题与文案刷新）。
     */
    private void buildSections(JPanel panel) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 6, 4, 6);

        // 组 1：SQL 预览（默认展开）
        if (sections.contains(SECTION_PREVIEW)) {
            sectionGroup(panel, gbc, "settings.section.preview", (p, g) -> {
                addRow(p, g, msg("settings.dialect"), dialectCombo);
                replaceParamsCheck.setText(msg("settings.replaceParams"));
                addRow(p, g, "", replaceParamsCheck);
                formatCheck.setText(msg("settings.formatSql"));
                addRow(p, g, "", formatCheck);
                injectLogicDeleteCheck.setText(msg("settings.injectLogicDelete"));
                addRow(p, g, "", injectLogicDeleteCheck);
                logicDeleteValueField.setToolTipText(msg("settings.logicDeleteValue.tip"));
                addRow(p, g, msg("settings.logicDeleteValue"), logicDeleteValueField);
                injectTenantLineCheck.setText(msg("settings.injectTenantLine"));
                addRow(p, g, "", injectTenantLineCheck);
                tenantColumnNameField.setToolTipText(msg("settings.tenantColumnName.tip"));
                addRow(p, g, msg("settings.tenantColumnName"), tenantColumnNameField);
                tenantColumnDefaultField.setToolTipText(msg("settings.tenantColumnDefault.tip"));
                addRow(p, g, msg("settings.tenantColumnDefault"), tenantColumnDefaultField);
                injectTenantOnInsertCheck.setText(msg("settings.injectTenantOnInsert"));
                injectTenantOnInsertCheck.setToolTipText(msg("settings.injectTenantOnInsert.tip"));
                addRow(p, g, "", injectTenantOnInsertCheck);
                dataPermissionHintCheck.setText(msg("settings.dataPermissionHint"));
                dataPermissionHintCheck.setToolTipText(msg("settings.dataPermissionHint.tip"));
                addRow(p, g, "", dataPermissionHintCheck);
                dataPermissionMockConditionField.setToolTipText(msg("settings.dataPermissionMockCondition.tip"));
                addRow(p, g, msg("settings.dataPermissionMockCondition"), dataPermissionMockConditionField);
                jdbcSqlPreviewCheck.setText(msg("settings.preview.jdbc"));
                jdbcSqlPreviewCheck.setToolTipText(msg("settings.preview.jdbc.tip"));
                addRow(p, g, "", jdbcSqlPreviewCheck);
            });
        }

        // 组 2：表名映射（默认折叠）
        if (sections.contains(SECTION_TABLE_MAPPING)) {
            sectionGroup(panel, gbc, "settings.section.tableMapping", (p, g) -> {
                tablePrefixField.setToolTipText(msg("settings.tablePrefix.tip"));
                addRow(p, g, msg("settings.tablePrefix"), tablePrefixField);
                tableSuffixField.setToolTipText(msg("settings.tableSuffix.tip"));
                addRow(p, g, msg("settings.tableSuffix"), tableSuffixField);
                removeTablePrefixField.setToolTipText(msg("settings.removeTablePrefix.tip"));
                removeTablePrefixField.getEmptyText().setText(msg("settings.separatorHint"));
                addRow(p, g, msg("settings.removeTablePrefix"), removeTablePrefixField);
                removeTableSuffixField.setToolTipText(msg("settings.removeTableSuffix.tip"));
                removeTableSuffixField.getEmptyText().setText(msg("settings.separatorHint"));
                addRow(p, g, msg("settings.removeTableSuffix"), removeTableSuffixField);
                ignoredFieldsField.setToolTipText(msg("settings.ignoredFields.tip"));
                ignoredFieldsField.getEmptyText().setText(msg("settings.separatorHint"));
                addRow(p, g, msg("settings.ignoredFields"), ignoredFieldsField);
                inferenceCombo.setModel(new DefaultComboBoxModel<>(inferenceOptions()));
                inferenceCombo.setToolTipText(msg("settings.tableNameInference.tip"));
                InferenceOption inferred = inferenceOptionByValue(settings.getState().getTableNameInference());
                inferenceCombo.setSelectedItem(inferred != null ? inferred : inferenceOptions()[0]);
                addRow(p, g, msg("settings.tableNameInference"), inferenceCombo);
            });
        }

        // 组 3：代码生成（默认折叠）
        if (sections.contains(SECTION_CODE_GEN)) {
            sectionGroup(panel, gbc, "settings.section.codeGen", (p, g) -> {
                reverseFormatCombo.setModel(new DefaultComboBoxModel<>(reverseFormatOptions()));
                reverseFormatCombo.setToolTipText(msg("settings.reverseWrapperFormat.tip"));
                ReverseFormatOption reverse = reverseFormatOptionByValue(settings.getState().getReverseWrapperFormat());
                reverseFormatCombo.setSelectedItem(reverse != null ? reverse : reverseFormatOptions()[0]);
                addRow(p, g, msg("settings.reverseWrapperFormat"), reverseFormatCombo);
                manualEntityCheck.setText(msg("settings.reverseManualEntitySelection"));
                manualEntityCheck.setToolTipText(msg("settings.reverseManualEntitySelection.tip"));
                addRow(p, g, "", manualEntityCheck);
            });
        }

        // 组 4：SQL 日志（分割线分隔 3 个子分组）
        if (sections.contains(SECTION_SQL_LOG)) {
            sectionGroup(panel, gbc, "settings.log.tab.mybatis", (p, g) -> {
                logSqlPrefixField.setToolTipText(msg("settings.log.sqlPrefix.tip"));
                addRow(p, g, msg("settings.log.sqlPrefix"), logSqlPrefixField);
                logParamPrefixField.setToolTipText(msg("settings.log.paramPrefix.tip"));
                addRow(p, g, msg("settings.log.paramPrefix"), logParamPrefixField);
                logMixedPrefixCheck.setText(msg("settings.log.mixedPrefix"));
                logMixedPrefixCheck.setToolTipText(msg("settings.log.mixedPrefix.tip"));
                addRow(p, g, "", logMixedPrefixCheck);
                logAutoCaptureCheck.setText(msg("settings.log.autoCapture"));
                addRow(p, g, "", logAutoCaptureCheck);
                logAutoOpenCheck.setText(msg("settings.log.autoOpen"));
                addRow(p, g, "", logAutoOpenCheck);
                logStopOnToolWindowCloseCheck.setText(msg("settings.log.stopOnToolWindowClose"));
                addRow(p, g, "", logStopOnToolWindowCloseCheck);
                logTimestampCheck.setText(msg("settings.log.timestamp"));
                addRow(p, g, "", logTimestampCheck);
                logTimestampFormatField.setToolTipText(msg("settings.log.timestampFormat.tip"));
                addRow(p, g, msg("settings.log.timestampFormat"), logTimestampFormatField);
                logFormatSqlCheck.setText(msg("settings.log.formatSql"));
                addRow(p, g, "", logFormatSqlCheck);
                logFilterKeywordsField.setToolTipText(msg("settings.log.filterKeywords.tip"));
                logFilterKeywordsField.getEmptyText().setText(msg("settings.separatorHint"));
                addRow(p, g, msg("settings.log.filterKeywords"), logFilterKeywordsField);
                logDedupeField.setToolTipText(msg("settings.log.dedupeWindow.tip"));
                addRow(p, g, msg("settings.log.dedupeWindow"), logDedupeField);
                logMaxParseField.setToolTipText(msg("settings.log.maxParseLength.tip"));
                addRow(p, g, msg("settings.log.maxParseLength"), logMaxParseField);
                logMaxCountField.setToolTipText(msg("settings.log.maxCount.tip"));
                addRow(p, g, msg("settings.log.maxCount"), logMaxCountField);
                logTimePrefixField.setToolTipText(msg("settings.log.timePrefix.tip"));
                addRow(p, g, msg("settings.log.timePrefix"), logTimePrefixField);
                slowQueryThresholdField.setToolTipText(msg("settings.log.slowQueryThreshold.tip"));
                addRow(p, g, msg("settings.log.slowQueryThreshold"), slowQueryThresholdField);
                logShowDurationCheck.setText(msg("settings.log.showDuration"));
                logShowDurationCheck.setToolTipText(msg("settings.log.showDuration.tip"));
                addRow(p, g, "", logShowDurationCheck);
            });

            sectionGroup(panel, gbc, "settings.log.tab.orm", (p, g) -> {
                logHibernateEnabledCheck.setText(msg("settings.log.hibernate.enabled"));
                logHibernateEnabledCheck.setToolTipText(msg("settings.log.hibernate.enabled.tip"));
                addRow(p, g, "", logHibernateEnabledCheck);
                logHibernateSqlPrefixField.setToolTipText(msg("settings.log.hibernate.sqlPrefix.tip"));
                addRow(p, g, msg("settings.log.hibernate.sqlPrefix"), logHibernateSqlPrefixField);
                logHibernateParamPrefixField.setToolTipText(msg("settings.log.hibernate.paramPrefix.tip"));
                addRow(p, g, msg("settings.log.hibernate.paramPrefix"), logHibernateParamPrefixField);
                logJdbcEnabledCheck.setText(msg("settings.log.jdbc"));
                logJdbcEnabledCheck.setToolTipText(msg("settings.log.jdbc.tip"));
                addRow(p, g, "", logJdbcEnabledCheck);
            });

            sectionGroup(panel, gbc, "settings.log.tab.autoSave", (p, g) -> {
                logAutoSaveCheck.setText(msg("settings.log.autoSave"));
                logAutoSaveCheck.setToolTipText(msg("settings.log.autoSave.tip"));
                logAutoSaveCheck.addActionListener(e -> {
                    boolean enabled = logAutoSaveCheck.isSelected();
                    logAutoSavePathField.setEnabled(enabled);
                    logAutoSaveBrowseButton.setEnabled(enabled);
                    logAutoSavePatternField.setEnabled(enabled);
                    updateAutoSavePreview();
                });
                addRow(p, g, "", logAutoSaveCheck);

                JPanel pathPanel = new JPanel(new BorderLayout(4, 0));
                pathPanel.setOpaque(false);
                logAutoSavePathField.setToolTipText(msg("settings.log.autoSave.path.tip"));
                logAutoSavePathField.getEmptyText().setText(msg("settings.log.autoSave.path.empty"));
                pathPanel.add(logAutoSavePathField, BorderLayout.CENTER);
                logAutoSaveBrowseButton.setToolTipText(msg("settings.log.autoSave.browse.tip"));
                logAutoSaveBrowseButton.addActionListener(e -> browseAutoSavePath());
                pathPanel.add(logAutoSaveBrowseButton, BorderLayout.EAST);
                addRow(p, g, msg("settings.log.autoSave.path"), pathPanel);

                JPanel patternPanel = new JPanel(new BorderLayout(4, 0));
                patternPanel.setOpaque(false);
                logAutoSavePatternField.setToolTipText(msg("settings.log.autoSave.pattern.tip"));
                logAutoSavePatternField.getEmptyText().setText(PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN);
                DocumentListener patternListener = new javax.swing.event.DocumentListener() {
                    @Override
                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        updateAutoSavePreview();
                    }

                    @Override
                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        updateAutoSavePreview();
                    }

                    @Override
                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        updateAutoSavePreview();
                    }
                };
                logAutoSavePatternField.getDocument().addDocumentListener(patternListener);
                patternPanel.add(logAutoSavePatternField, BorderLayout.CENTER);
                JButton resetButton = new JButton(msg("settings.log.autoSave.pattern.reset"));
                resetButton.setToolTipText(msg("settings.log.autoSave.pattern.reset.tip"));
                resetButton.addActionListener(e ->
                    logAutoSavePatternField.setText(PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN));
                patternPanel.add(resetButton, BorderLayout.EAST);
                addRow(p, g, msg("settings.log.autoSave.pattern"), patternPanel);

                logAutoSavePatternPreview.setToolTipText(msg("settings.log.autoSave.pattern.preview.tip"));
                addRow(p, g, "", logAutoSavePatternPreview);
            });

            // F-44-7：SqlSourceInterceptor 自动注入（源码位置捕获）
            sectionGroup(panel, gbc, "settings.log.tab.sourceCapture", (p2, g2) -> {
                autoInjectInterceptorCheck.setText(msg("settings.log.autoInjectInterceptor"));
                autoInjectInterceptorCheck.setToolTipText(msg("settings.log.autoInjectInterceptor.tip"));
                autoInjectInterceptorCheck.addActionListener(e -> {
                    if (autoInjectInterceptorCheck.isSelected()) {
                        // 触发注入
                        triggerInterceptorInjection();
                    }
                });
                addRow(p2, g2, "", autoInjectInterceptorCheck);
            });
        }

        // 组 5：界面与操作（默认展开）
        if (sections.contains(SECTION_UI)) {
            sectionGroup(panel, gbc, "settings.section.ui", (p, g) -> {
                gutterIconCheck.setText(msg("settings.showGutterIcon"));
                addRow(p, g, "", gutterIconCheck);
                joinDiagramNativeCheck.setText(msg("settings.joinDiagramNative"));
                joinDiagramNativeCheck.setToolTipText(msg("settings.joinDiagramNative.tip"));
                addRow(p, g, "", joinDiagramNativeCheck);
                languageCombo.setModel(new DefaultComboBoxModel<>(languageOptions()));
                LanguageOption lang = languageOptionByValue(settings.getState().getLanguage());
                languageCombo.setSelectedItem(lang != null ? lang : languageOptions()[0]);
                addRow(p, g, msg("settings.language"), languageCombo);
                shortcutEditor.updateTexts();
                addRow(p, g, msg("settings.shortcut"), shortcutEditor);
                reverseShortcutEditor.updateTexts();
                addRow(p, g, msg("settings.shortcut.sqlToJava"), reverseShortcutEditor);
            });
        }

        // 组 6：更新与诊断（默认折叠）
        if (sections.contains(SECTION_UPDATE_DIAGNOSTICS)) {
            sectionGroup(panel, gbc, "settings.section.updateDiagnostics", (p, g) -> {
                JPanel cachePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                cachePanel.setOpaque(false);
                cachePanel.add(cacheInfoLabel);
                clearCacheButton.setToolTipText(msg("settings.cache.tip"));
                clearCacheButton.addActionListener(e -> {
                    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                        PluginCacheManager.clearAll(project);
                    }
                    cacheInfoLabel.setText(msg("settings.cache.cleared"));
                });
                cachePanel.add(clearCacheButton);
                addRow(p, g, msg("settings.cache.label"), cachePanel);

                g.anchor = GridBagConstraints.NORTHWEST;
                updateSection.updateTexts();
                addRow(p, g, msg("settings.update.versionInfo"), updateSection);
                g.anchor = GridBagConstraints.WEST;

                JPanel buildPanel = new JPanel(new BorderLayout(8, 0));
                JBLabel buildLabel = new JBLabel();
                buildLabel.setText(PluginBuildInfo.displayText());
                buildLabel.setToolTipText(msg("settings.buildNumber.tip"));
                buildPanel.add(buildLabel, BorderLayout.CENTER);
                JButton copyButton = new JButton(msg("settings.buildNumber.copy"));
                copyButton.setEnabled(PluginBuildInfo.mpvBuild() != null);
                copyButton.addActionListener(e -> {
                    CopyPasteManager.getInstance().setContents(
                        new java.awt.datatransfer.StringSelection(PluginBuildInfo.displayText()));
                    String original = copyButton.getText();
                    copyButton.setText(msg("settings.buildNumber.copied"));
                    Timer restore = new Timer(1200, ev -> copyButton.setText(original));
                    restore.setRepeats(false);
                    restore.start();
                });
                buildPanel.add(copyButton, BorderLayout.EAST);
                addRow(p, g, msg("settings.buildNumber"), buildPanel);

                JPanel ioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                ioPanel.setOpaque(false);
                JButton importButton = new JButton(msg("settings.import"));
                importButton.setToolTipText(msg("settings.import.tip"));
                importButton.addActionListener(e -> importSettings());
                ioPanel.add(importButton);
                JButton exportButton = new JButton(msg("settings.export"));
                exportButton.setToolTipText(msg("settings.export.tip"));
                exportButton.addActionListener(e -> exportSettings());
                ioPanel.add(exportButton);
                addRow(p, g, msg("settings.pluginSettings"), ioPanel);
            });
        }

        // 底部 weighty 占位（内容顶置）
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), gbc);
    }

    private static void addRow(JPanel panel, GridBagConstraints gbc, String label, JComponent component) {
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        panel.add(new JBLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(component, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.gridy++;
    }

    /** 分隔线 + 内容面板（无边框、无折叠）。 */
    private static void sectionGroup(JPanel panel,
                                     GridBagConstraints gbc,
                                     String titleKey,
                                     BiConsumer<JPanel, GridBagConstraints> content) {
        JPanel contentPanel = new JPanel(new GridBagLayout());
        GridBagConstraints inner = new GridBagConstraints();
        inner.gridx = 0;
        inner.gridy = 0;
        inner.anchor = GridBagConstraints.WEST;
        inner.insets = new Insets(2, 6, 2, 6);
        content.accept(contentPanel, inner);

        // 计算内容区标签最大宽度，统一列宽
        GridBagLayout layout = (GridBagLayout) contentPanel.getLayout();
        int maxLabelWidth = 0;
        for (Component comp : contentPanel.getComponents()) {
            if (comp instanceof JBLabel) {
                maxLabelWidth = Math.max(maxLabelWidth, comp.getPreferredSize().width);
            }
        }
        if (maxLabelWidth > 0) {
            layout.columnWidths = new int[]{maxLabelWidth + 8, 0};
        }

        // 分隔线（仅横线，无边框）
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new TitledSeparator(MpSqlVisualizerBundle.message(titleKey)), gbc);
        gbc.gridy++;

        // 内容面板
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(contentPanel, gbc);
        gbc.gridy++;
    }

    /**
     * 快捷键内嵌编辑行（M14.1）：只读显示当前快捷键 + 「修改」按钮弹出录制对话框，
     * 捕获按键组合后回填（保存时由 {@link PluginSettingsPanel#apply()} 写入 Keymap）。
     * 不依赖 impl 包（KeyboardShortcutPanel 为包私有），全公开 API 实现。
     *
     * T-M41：actionId 参数化——同一组件服务多个 action（Convert to SQL / SQL→Java），
     * 冲突检测与保存写入均以各自 actionId 为准。
     */
    public class ShortcutEditor extends JPanel {

        private final String actionId;

        private KeyboardShortcut shortcut;

        /** M14.4：本次录制是否「强制使用」（冲突时抢占移除占用者；保存时消费后复位）。 */
        private boolean forceOnApply = false;

        private final JBTextField textField = new JBTextField(18);

        private final JButton editButton = new JButton(MpSqlVisualizerBundle.message("settings.shortcut.edit"));

        ShortcutEditor(String actionId) {
            super(new BorderLayout(6, 0));
            this.actionId = actionId;
            textField.setEditable(false);
            textField.setToolTipText(MpSqlVisualizerBundle.message("settings.shortcut.tip"));
            add(textField, BorderLayout.CENTER);
            add(editButton, BorderLayout.EAST);
            editButton.addActionListener(e -> captureShortcut());
        }

        public KeyboardShortcut getShortcut() {
            return shortcut;
        }

        public void setShortcut(KeyboardShortcut value) {
            this.shortcut = value;
            textField.setText(value != null ? KeymapUtil.getShortcutText(value) : "—");
        }

        public boolean isForceOnApply() {
            return forceOnApply;
        }

        public void setForceOnApply(boolean forceOnApply) {
            this.forceOnApply = forceOnApply;
        }

        /** M19.2：语言切换后刷新自身文案（tooltip/按钮；buildRows 每轮调用——字段实例文本构造时固化）。 */
        public void updateTexts() {
            textField.setToolTipText(MpSqlVisualizerBundle.message("settings.shortcut.tip"));
            editButton.setText(MpSqlVisualizerBundle.message("settings.shortcut.edit"));
        }

        /** 弹出模态录制对话框：聚焦输入框后按下组合键即完成，ESC 取消。 */
        private void captureShortcut() {
            JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), Dialog.ModalityType.APPLICATION_MODAL);
            dialog.setTitle(MpSqlVisualizerBundle.message("settings.shortcut.capture"));
            JBTextField capture = new JBTextField(20);
            capture.setFocusable(true);
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.setBorder(JBUI.Borders.empty(10));
            panel.add(new JBLabel(MpSqlVisualizerBundle.message("settings.shortcut.capture")), BorderLayout.NORTH);
            panel.add(capture, BorderLayout.CENTER);
            dialog.getContentPane().add(panel);
            dialog.pack();
            dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
            // M14.2 焦点修复：setVisible(true) 为阻塞调用，必须在显示前调度聚焦，
            // 否则输入框无焦点导致按键捕获不到（第二次修改不生效的根因）
            SwingUtilities.invokeLater(capture::requestFocusInWindow);
            capture.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE -> dialog.dispose();
                        case KeyEvent.VK_ENTER -> dialog.dispose();
                        // 纯修饰键忽略
                        case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META -> {
                        }
                        default -> {
                            KeyStroke stroke = KeyStroke.getKeyStrokeForEvent(e);
                            KeyboardShortcut newShortcut = new KeyboardShortcut(stroke, null);
                            // M14.2/14.4 冲突处理：仅冲突时弹窗，且「强制使用」为独立按钮（与普通确认区分）——
                            //  使用：绑定但不抢占（保留占用者，可能不生效）
                            //  强制使用：抢占移除占用者，保证始终生效
                            String conflict = conflictActionName(newShortcut);
                            if (conflict != null) {
                                String[] options = {
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.use"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.force"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.cancel"),
                                };
                                // 与原无 project 行为一致；返回选项索引 int（0=Yes/使用, 1=No/强制使用, 2=Cancel/取消）。
                                // T-M30：跨版本签名差异——showIdeaMessageDialog 已弃用，改用 MessageDialogBuilder.yesNoCancel
                                // （2024.3+ 可用）。guessWindowAndAsk() 返回 int：0=Yes(第一个按钮), 1=No(第二个), 2=Cancel(第三个)。
                                int choice = MessageDialogBuilder.yesNoCancel(
                                        MpSqlVisualizerBundle.message("settings.shortcut.conflict.title"),
                                        MpSqlVisualizerBundle.message("settings.shortcut.conflict", conflict))
                                    .yesText(options[0])
                                    .noText(options[1])
                                    .cancelText(options[2])
                                    .icon(Messages.getWarningIcon())
                                    .guessWindowAndAsk();
                                switch (choice) {
                                    case 0 -> { // 使用（普通）：不抢占
                                        forceOnApply = false;
                                        setShortcut(newShortcut);
                                        dialog.dispose();
                                    }
                                    case 1 -> { // 强制使用：抢占，保证生效
                                        forceOnApply = true;
                                        setShortcut(newShortcut);
                                        dialog.dispose();
                                    }
                                    default -> {
                                        // 取消
                                    }
                                }
                            } else {
                                forceOnApply = false;
                                setShortcut(newShortcut);
                                dialog.dispose();
                            }
                        }
                    }
                }
            });
            dialog.setVisible(true);
        }

        /** 若新快捷键已被其他 action 占用，返回占用者名称（可读 action 文本；环境异常兜底 null）。 */
        private String conflictActionName(KeyboardShortcut newShortcut) {
            try {
                var keymap = KeymapManager.getInstance().getActiveKeymap();
                for (String otherId : keymap.getActionIds()) {
                    if (Objects.equals(otherId, actionId)) continue;
                    for (Shortcut s : keymap.getShortcuts(otherId)) {
                        if (Objects.equals(s, newShortcut)) {
                            AnAction action = ActionManager.getInstance().getAction(otherId);
                            String text = action != null ? action.getTemplatePresentation().getText() : null;
                            return text != null ? text : otherId;
                        }
                    }
                }
                return null;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // 快捷键读写（M14.1：内嵌编辑，直接写当前 Keymap）
    // ------------------------------------------------------------------

    /** 指定 action 的当前键盘快捷键（activeKeymap 中第一个 KeyboardShortcut；环境异常兜底 null）。 */
    private static KeyboardShortcut currentShortcut(String actionId) {
        try {
            var keymap = KeymapManager.getInstance().getActiveKeymap();
            for (Shortcut s : keymap.getShortcuts(actionId)) {
                if (s instanceof KeyboardShortcut) return (KeyboardShortcut) s;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 应用快捷键（M14.3/14.4）：
     *  - 普通「使用」：仅绑定给本插件（移除自身旧绑定，不动其他 action）；
     *  - 「强制使用」：额外从**所有其他 action**（含 IDE 默认 keymap 继承绑定）移除该组合，保证独占始终生效。
     *
     * 抢占原理：IDEA keymap 支持"负绑定"——{@code Keymap.removeShortcut} 对继承自父级 keymap（$default）的
     * 快捷键会在当前（用户级）keymap 写入禁用覆盖，与 IDE Keymap 设置页"绑定冲突键时自动移除旧绑定"一致。
     */
    private static void applyShortcut(ShortcutEditor editor, String actionId) {
        try {
            var keymap = KeymapManager.getInstance().getActiveKeymap();
            // 1) 清理本 action 的旧键盘快捷键
            List<Shortcut> old = new java.util.ArrayList<>();
            for (Shortcut s : keymap.getShortcuts(actionId)) {
                if (s instanceof KeyboardShortcut) old.add(s);
            }
            for (Shortcut s : old) {
                keymap.removeShortcut(actionId, s);
            }
            // 2) 绑定新组合；「强制使用」时先抢占（从所有其他 action 移除，含默认继承绑定）
            KeyboardShortcut newShortcut = editor.getShortcut();
            if (newShortcut != null) {
                if (editor.isForceOnApply()) {
                    for (String other : keymap.getActionIds()) {
                        if (Objects.equals(other, actionId)) continue;
                        List<Shortcut> conflicts = new java.util.ArrayList<>();
                        for (Shortcut s : keymap.getShortcuts(other)) {
                            if (Objects.equals(s, newShortcut)) conflicts.add(s);
                        }
                        for (Shortcut s : conflicts) {
                            keymap.removeShortcut(other, s);
                        }
                    }
                }
                keymap.addShortcut(actionId, newShortcut);
                editor.setForceOnApply(false);
            }
        } catch (Throwable t) {
            LOG.warn("applyShortcut failed: actionId=" + actionId, t);
        }
    }

    /**
     * 语言切换后重建表单（设置页自身文案即时刷新；控件值从配置回读）。
     */
    public void rebuild() {
        rootPanel.removeAll();
        buildSections(rootPanel);
        reset();
        refreshCacheInfo();
        rootPanel.revalidate();
        rootPanel.repaint();
    }

    /**
     * M19：语言写穿入口（测试用）——选中语言值，触发与用户下拉选择**同一**写穿路径
     * （ActionListener → 立即写入配置 + 重建表单）。
     */
    public void selectLanguage(String value) {
        LanguageOption option = languageOptionByValue(value);
        if (option == null) return;
        // selectedItem 变化会同步触发 ActionListener，写穿与 rebuild 由监听统一处理
        languageCombo.setSelectedItem(option);
    }

    /** T-xx：反向转换形态选择入口（测试用）——与下拉选择同一路径（selectedItem 按 equals 匹配）。 */
    public void selectReverseFormat(String value) {
        ReverseFormatOption option = reverseFormatOptionByValue(value);
        if (option == null) return;
        reverseFormatCombo.setSelectedItem(option);
    }

    /** T-M41 测试访问器：SQL→Java 快捷键编辑行（断言行存在 / 修改检测 / apply 写入 Keymap）。 */
    public ShortcutEditor reverseShortcutEditorForTest() {
        return reverseShortcutEditor;
    }

    /** T-xx：启动自动监听复选框设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    public void setLogAutoCaptureCheck(boolean checked) {
        logAutoCaptureCheck.setSelected(checked);
    }

    /** 手动选择实体类复选框设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    public void setManualEntityCheck(boolean checked) {
        manualEntityCheck.setSelected(checked);
    }

    /** T-TenantLine：多租户注入开关设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    public void setInjectTenantLineCheck(boolean checked) {
        injectTenantLineCheck.setSelected(checked);
    }

    /** T-TenantLine：租户列名输入框设置入口（测试用）——与用户输入同一路径（isModified/apply 检测）。 */
    public void setTenantColumnName(String value) {
        tenantColumnNameField.setText(value);
    }

    /**
     * 刷新插件缓存信息（大小 + 条目数，所有打开项目汇总——含反查/解析/Wrapper 预览三类缓存；
     * 面板构造与清除按钮共用）。
     */
    private void refreshCacheInfo() {
        List<Project> projects = Arrays.asList(ProjectManager.getInstance().getOpenProjects());
        long total = PluginCacheManager.totalSizeBytes(projects);
        cacheInfoLabel.setText(MpSqlVisualizerBundle.message(
                "settings.cache.info",
                PluginCacheManager.formatSize(total),
                PluginCacheManager.entryCount(projects)));
    }

    /** 测试访问器：缓存信息文本（断言展示与清除后刷新）。 */
    public String cacheInfoForTest() {
        return cacheInfoLabel.getText();
    }

    /** 测试访问器：清除缓存按钮（断言存在与点击行为）。 */
    public JButton clearCacheButtonForTest() {
        return clearCacheButton;
    }

    // ------------------------------------------------------------------
    // F-44-7：SqlSourceInterceptor 自动注入
    // ------------------------------------------------------------------

    /** 触发拦截器注入（用户勾选开关后调用）。 */
    private void triggerInterceptorInjection() {
        Project project = ProjectManager.getInstance().getOpenProjects().length > 0
                ? ProjectManager.getInstance().getOpenProjects()[0]
                : null;
        if (project == null) {
            Messages.showWarningDialog(
                    (Component) null,
                    "请先打开一个项目，再启用源码捕获拦截器自动注入。",
                    "源码跳转配置");
            autoInjectInterceptorCheck.setSelected(false);
            return;
        }
        InjectionResult result = SqlSourceInterceptorAutoInjector.inject(project);
        if (result instanceof InjectionResult.Success) {
            Messages.showInfoMessage(
                    project,
                    ((InjectionResult.Success) result).getMessage(),
                    "源码跳转配置");
        } else if (result instanceof InjectionResult.AlreadyExists) {
            Messages.showInfoMessage(
                    project,
                    "配置已存在，无需重复生成。如需重新生成，请手动删除 config 包下的 SqlSourceInterceptor.java 和 SqlVisualizerConfig.java。",
                    "源码跳转配置");
            autoInjectInterceptorCheck.setSelected(false);
        } else if (result instanceof InjectionResult.UnsupportedProject) {
            Messages.showWarningDialog(
                    project,
                    "无法识别项目类型。请确保项目是 Spring Boot + MyBatis-Plus 项目。\n\n"
                            + "也可手动配置：参考 docs/instrumentation/SqlSourceInterceptor.kt",
                    "源码跳转配置");
            autoInjectInterceptorCheck.setSelected(false);
        } else if (result instanceof InjectionResult.CannotDetectStructure) {
            Messages.showWarningDialog(
                    project,
                    "无法检测项目结构。请确保项目存在 src/main/java 源码目录。",
                    "源码跳转配置");
            autoInjectInterceptorCheck.setSelected(false);
        }
    }

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

    /**
     * 根面板（Configurable.createComponent 返回）。
     */
    public JComponent getRoot() {
        return rootPanel;
    }

    /**
     * 表单值是否与配置不一致（含快捷键编辑状态）。
     *
     * M19.1：语言**写穿**后表单与配置一致，但语言变更尚未「应用」——`languagePending`
     * 使 `isModified()` 保持 true，Apply 按钮在写穿后仍可点击（平台 Apply 启用 = isModified()）。
     */
    public boolean isModified() {
        PluginSettings.State s = settings.getState();
        return languagePending
            || (sections.contains(SECTION_PREVIEW) && isModifiedPreview(s))
            || (sections.contains(SECTION_TABLE_MAPPING) && isModifiedTableMapping(s))
            || (sections.contains(SECTION_CODE_GEN) && isModifiedCodeGen(s))
            || (sections.contains(SECTION_SQL_LOG) && isModifiedSqlLog(s))
            || (sections.contains(SECTION_UI) && isModifiedUi(s))
            || (sections.contains(SECTION_UPDATE_DIAGNOSTICS)
                && updateSection.getAutoCheckCheck().isSelected() != s.getAutoCheckUpdate());
    }

    private boolean isModifiedPreview(PluginSettings.State s) {
        return !Objects.equals(dialectCombo.getSelectedItem(), s.getDialect())
            || replaceParamsCheck.isSelected() != s.getReplaceParams()
            || formatCheck.isSelected() != s.getFormatSql()
            || injectLogicDeleteCheck.isSelected() != s.getInjectLogicDelete()
            || !logicDeleteValueField.getText().trim().equals(s.getLogicDeleteValue())
            || injectTenantLineCheck.isSelected() != s.getInjectTenantLine()
            || !tenantColumnNameField.getText().trim().equals(s.getTenantColumnName())
            || !tenantColumnDefaultField.getText().trim().equals(s.getTenantColumnDefault())
            || injectTenantOnInsertCheck.isSelected() != s.getInjectTenantOnInsert()
            || dataPermissionHintCheck.isSelected() != s.getDataPermissionHint()
            || !dataPermissionMockConditionField.getText().trim().equals(s.getDataPermissionMockCondition())
            || jdbcSqlPreviewCheck.isSelected() != s.getJdbcSqlPreviewEnabled();
    }

    private boolean isModifiedTableMapping(PluginSettings.State s) {
        Object inference = inferenceCombo.getSelectedItem();
        return !tablePrefixField.getText().trim().equals(s.getTablePrefix())
            || !tableSuffixField.getText().trim().equals(s.getTableSuffix())
            || !Objects.equals(inference instanceof InferenceOption ? ((InferenceOption) inference).value : null,
                               s.getTableNameInference())
            || !ignoredFieldsField.getText().trim().equals(s.getIgnoredFields())
            || !removeTablePrefixField.getText().trim().equals(s.getRemoveTablePrefix())
            || !removeTableSuffixField.getText().trim().equals(s.getRemoveTableSuffix());
    }

    private boolean isModifiedCodeGen(PluginSettings.State s) {
        Object reverse = reverseFormatCombo.getSelectedItem();
        return !Objects.equals(reverse instanceof ReverseFormatOption ? ((ReverseFormatOption) reverse).value : null,
                               s.getReverseWrapperFormat())
            || manualEntityCheck.isSelected() != s.getReverseManualEntitySelection();
    }

    private boolean isModifiedSqlLog(PluginSettings.State s) {
        return !logSqlPrefixField.getText().trim().equals(s.getLogSqlPrefix())
            || !logParamPrefixField.getText().trim().equals(s.getLogParamPrefix())
            || logMixedPrefixCheck.isSelected() != s.getLogMixedPrefix()
            || logAutoCaptureCheck.isSelected() != s.getLogAutoCaptureOnStart()
            || logAutoOpenCheck.isSelected() != s.getLogAutoOpenOnCapture()
            || logStopOnToolWindowCloseCheck.isSelected() != s.getLogStopOnToolWindowClose()
            || logTimestampCheck.isSelected() != s.getLogTimestampEnabled()
            || !logTimestampFormatField.getText().trim().equals(s.getLogTimestampFormat())
            || logFormatSqlCheck.isSelected() != s.getLogFormatSql()
            || !logFilterKeywordsField.getText().trim().equals(s.getLogFilterKeywords())
            || !Objects.equals(parseLongOrNull(logDedupeField.getText().trim()), s.getLogDedupeWindowMs())
            || !Objects.equals(parseIntOrNull(logMaxParseField.getText().trim()), s.getLogMaxParseLength())
            || !Objects.equals(parseIntOrNull(logMaxCountField.getText().trim()), s.getLogMaxCount())
            || !logTimePrefixField.getText().trim().equals(s.getLogTimePrefix())
            || !Objects.equals(parseLongOrNull(slowQueryThresholdField.getText().trim()), s.getSlowQueryThresholdMs())
            || logShowDurationCheck.isSelected() != s.getShowDurationInLogList()
            || logHibernateEnabledCheck.isSelected() != s.getLogHibernateEnabled()
            || !logHibernateSqlPrefixField.getText().trim().equals(s.getLogHibernateSqlPrefix())
            || !logHibernateParamPrefixField.getText().trim().equals(s.getLogHibernateParamPrefix())
            || logJdbcEnabledCheck.isSelected() != s.getLogJdbcEnabled()
            || logAutoSaveCheck.isSelected() != s.getLogAutoSaveEnabled()
            || !logAutoSavePathField.getText().trim().equals(s.getLogAutoSavePath())
            || !logAutoSavePatternField.getText().trim().equals(s.getLogAutoSaveFileNamePattern())
            || autoInjectInterceptorCheck.isSelected() != s.getAutoInjectSqlSourceInterceptor();
    }

    private boolean isModifiedUi(PluginSettings.State s) {
        Object lang = languageCombo.getSelectedItem();
        return gutterIconCheck.isSelected() != s.getShowGutterIcon()
            || joinDiagramNativeCheck.isSelected() != s.getJoinDiagramNative()
            || !Objects.equals(lang instanceof LanguageOption ? ((LanguageOption) lang).value : null, s.getLanguage())
            || !Objects.equals(shortcutEditor.getShortcut(), currentShortcut(ConvertToSqlAction.ACTION_ID))
            || !Objects.equals(reverseShortcutEditor.getShortcut(), currentShortcut(ConvertSqlToJavaAction.ACTION_ID));
    }

    /**
     * 将表单值写回配置（Settings 点击 OK/Apply 时调用）；快捷键变化时写入 Keymap。
     *
     * @return 界面语言是否发生变化（true 时调用方应 {@link #rebuild()} 刷新设置页文案）
     */
    public boolean apply() {
        PluginSettings.State s = settings.getState();
        // M19.1：语言变更判定包含「待应用」标记——写穿后（combo 与 state 已一致）仍报告一次变更，
        // 触发调用方 rebuild；消费后清除标记（后续 apply 幂等）
        Object lang = languageCombo.getSelectedItem();
        boolean languageChanged = languagePending
            || !Objects.equals(lang instanceof LanguageOption ? ((LanguageOption) lang).value : null, s.getLanguage());

        if (sections.contains(SECTION_PREVIEW)) {
            Object dialect = dialectCombo.getSelectedItem();
            s.setDialect(dialect instanceof String ? (String) dialect : PluginSettings.DEFAULT_DIALECT);
            s.setReplaceParams(replaceParamsCheck.isSelected());
            s.setFormatSql(formatCheck.isSelected());
            s.setInjectLogicDelete(injectLogicDeleteCheck.isSelected());
            s.setLogicDeleteValue(logicDeleteValueField.getText().trim());
            s.setInjectTenantLine(injectTenantLineCheck.isSelected());
            s.setTenantColumnName(tenantColumnNameField.getText().trim());
            s.setTenantColumnDefault(tenantColumnDefaultField.getText().trim());
            s.setInjectTenantOnInsert(injectTenantOnInsertCheck.isSelected());
            s.setDataPermissionHint(dataPermissionHintCheck.isSelected());
            s.setDataPermissionMockCondition(dataPermissionMockConditionField.getText().trim());
            s.setJdbcSqlPreviewEnabled(jdbcSqlPreviewCheck.isSelected());
        }
        if (sections.contains(SECTION_TABLE_MAPPING)) {
            s.setTablePrefix(tablePrefixField.getText().trim());
            s.setTableSuffix(tableSuffixField.getText().trim());
            Object inference = inferenceCombo.getSelectedItem();
            s.setTableNameInference(inference instanceof InferenceOption
                ? ((InferenceOption) inference).value
                : PluginSettings.INFERENCE_SNAKE);
            s.setIgnoredFields(ignoredFieldsField.getText().trim());
            s.setRemoveTablePrefix(removeTablePrefixField.getText().trim());
            s.setRemoveTableSuffix(removeTableSuffixField.getText().trim());
        }
        if (sections.contains(SECTION_CODE_GEN)) {
            Object reverse = reverseFormatCombo.getSelectedItem();
            s.setReverseWrapperFormat(reverse instanceof ReverseFormatOption
                ? ((ReverseFormatOption) reverse).value
                : PluginSettings.REVERSE_AUTO);
            s.setReverseManualEntitySelection(manualEntityCheck.isSelected());
        }
        if (sections.contains(SECTION_SQL_LOG)) {
            s.setLogSqlPrefix(logSqlPrefixField.getText().trim());
            s.setLogParamPrefix(logParamPrefixField.getText().trim());
            s.setLogMixedPrefix(logMixedPrefixCheck.isSelected());
            s.setLogAutoCaptureOnStart(logAutoCaptureCheck.isSelected());
            s.setLogAutoOpenOnCapture(logAutoOpenCheck.isSelected());
            s.setLogStopOnToolWindowClose(logStopOnToolWindowCloseCheck.isSelected());
            s.setLogTimestampEnabled(logTimestampCheck.isSelected());
            s.setLogTimestampFormat(logTimestampFormatField.getText().trim());
            s.setLogFormatSql(logFormatSqlCheck.isSelected());
            s.setLogFilterKeywords(logFilterKeywordsField.getText().trim());
            Long dedupe = parseLongOrNull(logDedupeField.getText().trim());
            s.setLogDedupeWindowMs(dedupe != null ? dedupe : PluginSettings.DEFAULT_LOG_DEDUPE_WINDOW_MS);
            Integer maxParse = parseIntOrNull(logMaxParseField.getText().trim());
            s.setLogMaxParseLength(maxParse != null ? maxParse : PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH);
            Integer maxCount = parseIntOrNull(logMaxCountField.getText().trim());
            s.setLogMaxCount(maxCount != null ? maxCount : PluginSettings.DEFAULT_LOG_MAX_COUNT);
            s.setLogTimePrefix(logTimePrefixField.getText().trim());
            Long slowQuery = parseLongOrNull(slowQueryThresholdField.getText().trim());
            s.setSlowQueryThresholdMs(slowQuery != null ? slowQuery : PluginSettings.DEFAULT_SLOW_QUERY_THRESHOLD_MS);
            s.setShowDurationInLogList(logShowDurationCheck.isSelected());
            s.setLogHibernateEnabled(logHibernateEnabledCheck.isSelected());
            s.setLogHibernateSqlPrefix(logHibernateSqlPrefixField.getText().trim());
            s.setLogHibernateParamPrefix(logHibernateParamPrefixField.getText().trim());
            s.setLogJdbcEnabled(logJdbcEnabledCheck.isSelected());
            s.setLogAutoSaveEnabled(logAutoSaveCheck.isSelected());
            s.setLogAutoSavePath(logAutoSavePathField.getText().trim());
            String pattern = logAutoSavePatternField.getText().trim();
            s.setLogAutoSaveFileNamePattern(pattern.isEmpty()
                ? PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN
                : pattern);
            s.setAutoInjectSqlSourceInterceptor(autoInjectInterceptorCheck.isSelected());
        }
        if (sections.contains(SECTION_UI)) {
            s.setShowGutterIcon(gutterIconCheck.isSelected());
            s.setJoinDiagramNative(joinDiagramNativeCheck.isSelected());
            s.setLanguage(lang instanceof LanguageOption
                ? ((LanguageOption) lang).value
                : PluginSettings.DEFAULT_LANGUAGE);
            if (!Objects.equals(shortcutEditor.getShortcut(), currentShortcut(ConvertToSqlAction.ACTION_ID))) {
                applyShortcut(shortcutEditor, ConvertToSqlAction.ACTION_ID);
            }
            if (!Objects.equals(reverseShortcutEditor.getShortcut(), currentShortcut(ConvertSqlToJavaAction.ACTION_ID))) {
                applyShortcut(reverseShortcutEditor, ConvertSqlToJavaAction.ACTION_ID);
            }
        }
        if (sections.contains(SECTION_UPDATE_DIAGNOSTICS)) {
            s.setAutoCheckUpdate(updateSection.getAutoCheckCheck().isSelected());
        }
        languagePending = false;
        // 强制触发持久化：原地修改 state 属性后，框架可能不检测引用变化导致 XML 未写入（实证：
        // injectTenantLine=true 未持久化到 mp-sql-visualizer.xml，仅 showGutterIcon 等部分字段生效）。
        // 替换 state 引用使 XmlSerializer 检测到「状态变更」并在下一次持久化周期写入。
        LOG.info("PluginSettingsPanel.apply: state.after=" + s.getInjectTenantLine()
            + ", tenantColumn=" + s.getTenantColumnName() + ", calling loadState(s.copy())");
        settings.loadState(s.copy());
        // 发布设置变更通知（订阅方如 SqlLogPanel 据此即时刷新 UI）
        ApplicationManager.getApplication().getMessageBus()
            .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged();
        return languageChanged;
    }

    /**
     * 从配置加载表单值（Settings 打开/Reset 时调用）。
     */
    public void reset() {
        PluginSettings.State s = settings.getState();
        if (sections.contains(SECTION_PREVIEW)) {
            dialectCombo.setSelectedItem(s.getDialect());
            replaceParamsCheck.setSelected(s.getReplaceParams());
            formatCheck.setSelected(s.getFormatSql());
            injectLogicDeleteCheck.setSelected(s.getInjectLogicDelete());
            logicDeleteValueField.setText(s.getLogicDeleteValue());
            injectTenantLineCheck.setSelected(s.getInjectTenantLine());
            tenantColumnNameField.setText(s.getTenantColumnName());
            tenantColumnDefaultField.setText(s.getTenantColumnDefault());
            injectTenantOnInsertCheck.setSelected(s.getInjectTenantOnInsert());
            dataPermissionHintCheck.setSelected(s.getDataPermissionHint());
            dataPermissionMockConditionField.setText(s.getDataPermissionMockCondition());
            jdbcSqlPreviewCheck.setSelected(s.getJdbcSqlPreviewEnabled());
        }
        if (sections.contains(SECTION_TABLE_MAPPING)) {
            tablePrefixField.setText(s.getTablePrefix());
            tableSuffixField.setText(s.getTableSuffix());
            InferenceOption inferred = inferenceOptionByValue(s.getTableNameInference());
            inferenceCombo.setSelectedItem(inferred != null ? inferred : inferenceOptions()[0]);
            ignoredFieldsField.setText(s.getIgnoredFields());
            removeTablePrefixField.setText(s.getRemoveTablePrefix());
            removeTableSuffixField.setText(s.getRemoveTableSuffix());
        }
        if (sections.contains(SECTION_CODE_GEN)) {
            ReverseFormatOption reverse = reverseFormatOptionByValue(s.getReverseWrapperFormat());
            reverseFormatCombo.setSelectedItem(reverse != null ? reverse : reverseFormatOptions()[0]);
            manualEntityCheck.setSelected(s.getReverseManualEntitySelection());
        }
        if (sections.contains(SECTION_UI)) {
            gutterIconCheck.setSelected(s.getShowGutterIcon());
            joinDiagramNativeCheck.setSelected(s.getJoinDiagramNative());
            LanguageOption lang = languageOptionByValue(s.getLanguage());
            languageCombo.setSelectedItem(lang != null ? lang : languageOptions()[0]);
            shortcutEditor.setShortcut(currentShortcut(ConvertToSqlAction.ACTION_ID));
            reverseShortcutEditor.setShortcut(currentShortcut(ConvertSqlToJavaAction.ACTION_ID));
        }
        if (sections.contains(SECTION_UPDATE_DIAGNOSTICS)) {
            updateSection.resetState();
        }
        if (sections.contains(SECTION_SQL_LOG)) {
            logSqlPrefixField.setText(s.getLogSqlPrefix());
            logParamPrefixField.setText(s.getLogParamPrefix());
            logMixedPrefixCheck.setSelected(s.getLogMixedPrefix());
            logAutoCaptureCheck.setSelected(s.getLogAutoCaptureOnStart());
            logAutoOpenCheck.setSelected(s.getLogAutoOpenOnCapture());
            logStopOnToolWindowCloseCheck.setSelected(s.getLogStopOnToolWindowClose());
            logTimestampCheck.setSelected(s.getLogTimestampEnabled());
            logTimestampFormatField.setText(s.getLogTimestampFormat());
            logFormatSqlCheck.setSelected(s.getLogFormatSql());
            logFilterKeywordsField.setText(s.getLogFilterKeywords());
            logDedupeField.setText(String.valueOf(s.getLogDedupeWindowMs()));
            logMaxParseField.setText(String.valueOf(s.getLogMaxParseLength()));
            logMaxCountField.setText(String.valueOf(s.getLogMaxCount()));
            logTimePrefixField.setText(s.getLogTimePrefix());
            slowQueryThresholdField.setText(String.valueOf(s.getSlowQueryThresholdMs()));
            logShowDurationCheck.setSelected(s.getShowDurationInLogList());
            logHibernateEnabledCheck.setSelected(s.getLogHibernateEnabled());
            logHibernateSqlPrefixField.setText(s.getLogHibernateSqlPrefix());
            logHibernateParamPrefixField.setText(s.getLogHibernateParamPrefix());
            logJdbcEnabledCheck.setSelected(s.getLogJdbcEnabled());
            logAutoSaveCheck.setSelected(s.getLogAutoSaveEnabled());
            logAutoSavePathField.setText(s.getLogAutoSavePath());
            logAutoSavePatternField.setText(s.getLogAutoSaveFileNamePattern());
            boolean autoSaveEnabled = s.getLogAutoSaveEnabled();
            logAutoSavePathField.setEnabled(autoSaveEnabled);
            logAutoSaveBrowseButton.setEnabled(autoSaveEnabled);
            logAutoSavePatternField.setEnabled(autoSaveEnabled);
            updateAutoSavePreview();
            autoInjectInterceptorCheck.setSelected(s.getAutoInjectSqlSourceInterceptor());
        }
    }

    // ------------------------------------------------------------------
    // F-43：自动保存历史 SQL 辅助方法
    // ------------------------------------------------------------------

    private void browseAutoSavePath() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle(MpSqlVisualizerBundle.message("settings.log.autoSave.browse.tip"));
        VirtualFile baseDir = LocalFileSystem.getInstance()
            .findFileByIoFile(new File(System.getProperty("user.home")));
        VirtualFile dir = FileChooser.chooseFile(descriptor, null, baseDir);
        if (dir != null) {
            logAutoSavePathField.setText(dir.getPath());
        }
    }

    private void updateAutoSavePreview() {
        String pattern = logAutoSavePatternField.getText().trim();
        if (pattern.isEmpty()) {
            pattern = PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN;
        }
        String resolved = resolvePatternPreview(pattern);
        logAutoSavePatternPreview.setText(MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.preview", resolved));
    }

    /** 解析模板用于预览（复用 SqlLogAutoSaver 的占位符规则）。 */
    private static String resolvePatternPreview(String pattern) {
        long now = System.currentTimeMillis();
        Matcher matcher = PATTERN_PLACEHOLDER.matcher(pattern);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String formatStr = matcher.group(1);
            String replacement;
            try {
                String formatted = new SimpleDateFormat(formatStr, Locale.ENGLISH)
                    .format(new Date(now))
                    .replace(':', '-');
                replacement = formatted.equals(formatStr) ? matcher.group() : formatted;
            } catch (Exception e) {
                replacement = matcher.group();
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // O-5：设置导入/导出
    // ------------------------------------------------------------------

    /**
     * 从 JSON 文件导入设置（文件选择器 → 读取 → 覆盖当前全局配置 → 刷新表单）。
     */
    private void importSettings() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter(file -> "json".equalsIgnoreCase(file.getExtension()))
            .withTitle(MpSqlVisualizerBundle.message("settings.import"));
        VirtualFile file = FileChooser.chooseFile(descriptor, null, null);
        if (file == null) return;
        try {
            String text = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            PluginSettings.fromJson(text);
            reset();
            Messages.showInfoMessage(
                rootPanel,
                MpSqlVisualizerBundle.message("settings.import.success"),
                MpSqlVisualizerBundle.message("settings.import"));
        } catch (Throwable e) {
            Messages.showErrorDialog(
                rootPanel,
                MpSqlVisualizerBundle.message("settings.import.failed", e.getMessage() != null ? e.getMessage() : "unknown"),
                MpSqlVisualizerBundle.message("settings.import"));
        }
    }

    /**
     * 导出当前设置到 JSON 文件（文件选择器 → 写入）。
     */
    private void exportSettings() {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
            MpSqlVisualizerBundle.message("settings.export"),
            "JSON (*.json)",
            "json");
        VirtualFile baseDir = LocalFileSystem.getInstance()
            .findFileByIoFile(new File(System.getProperty("user.home")));
        VirtualFileWrapper saved = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, (Project) null)
            .save(baseDir, "mp-sql-visualizer-settings.json");
        if (saved != null) {
            try {
                Files.write(new File(saved.getFile().getAbsolutePath()).toPath(),
                    PluginSettings.toJson().getBytes(StandardCharsets.UTF_8));
                Messages.showInfoMessage(
                    rootPanel,
                    MpSqlVisualizerBundle.message("settings.export.success"),
                    MpSqlVisualizerBundle.message("settings.export"));
            } catch (Throwable e) {
                Messages.showErrorDialog(
                    rootPanel,
                    MpSqlVisualizerBundle.message("settings.export.failed", e.getMessage() != null ? e.getMessage() : "unknown"),
                    MpSqlVisualizerBundle.message("settings.export"));
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String msg(String key) {
        return MpSqlVisualizerBundle.message(key);
    }

    private static Long parseLongOrNull(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
