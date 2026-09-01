package cn.song.mybatisplus.visualizer.xml;

/**
 * bind 视图：变量名 + 表达式（面板覆盖值输入，空 = 自动求值）。
 *
 * @param name       变量名
 * @param expression 表达式
 */
public record XmlBindView(String name, String expression) {
}
