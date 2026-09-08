package cn.song.mybatisplus.visualizer.log;

import com.intellij.ui.SimpleTextAttributes;

/**
 * 着色文本片段（T-LogListFreeze-04）：列表条目渲染数据单元——文本 + 属性，供
 * {@link com.intellij.ui.SimpleColoredComponent#append} 直接消费。
 * 取代原 HTML <code>&lt;span&gt;</code> 通道：渲染成本从「BasicHTML 解析 + CSS 属性解析 + 文档布局」
 * 降为轻量文本测量，文本原样保留（无 HTML 转义面，注入场景不复存在）。
 */
public record SqlTextFragment(String text, SimpleTextAttributes attributes) {
}
