package cn.song.mybatisplus.visualizer.config;

import java.util.Set;

/** SQL 日志子页面（前缀/自动捕获/时间戳/格式化/过滤/去重/最大条数/耗时/慢查询/Hibernate/Jdbc/自动保存） */
public class SqlLogConfigurable extends SectionConfigurable {
    public SqlLogConfigurable() {
        super(Set.of("sqlLog"), "settings.section.sqlLog");
    }
}
