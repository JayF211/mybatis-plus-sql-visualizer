package cn.song.mybatisplus.visualizer.model;

/**
 * 生成器信息公共接口（T-J14）：name 即 @GeneratedValue(generator = "...") 的引用键，
 * 同时作为同名生成器去重键——类级 repeatable 直接标注、容器注解、字段级三处扫描后按 name 归并，
 * 先见者优先。
 */
public interface GeneratorInfo {
    String getName();
}
