# -*- coding: utf-8 -*-
"""R03 注解 SQL 预览重录（T-DocsRefresh1023）：@Select script 行 gutter → 预览 → 参数回填 → 复制并关闭。

相对 20260912 版（scene_t7）的差异：
- 底部按钮行改四枚，收尾点蓝色主行动「复制 SQL 并关闭」（旧片是点「取消」）；
- 参数框坐标按当前弹窗实测重标；
- gutter 图标仍走像素检测（组件树看不见 gutter）。

前置（prep）：UserMapper.java 打开、goto(33)、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import (Recorder, click, find_gutter_icons, goto_line, shot,
                    typewrite, wait_stable)
from robot import click_component, find_components

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r03_annotation.mp4")

OX, OY = REGION[0], REGION[1]


def abs_pt(p):
    """region 相对坐标 → 屏幕绝对坐标（gifkit.click 收绝对坐标）。"""
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def locate_gutter(y_range=(100, 860)):
    path = os.path.join(BASE, "perf", "_pre_r03.png")
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=y_range, gap=12)
    # 只要 gutter 主条带（x≈95）：x≈117 是编辑器折叠箭头，不是插件图标
    icons = [i for i in icons if i[1] < 105]
    if not icons:
        raise RuntimeError("gutter icon not found")
    center = (y_range[0] + y_range[1]) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("icons:", icons, "-> picked", (y, x))
    return x + OX, y + OY


def param_fields():
    """参数输入框坐标（region 相对）：弹窗内 robot 组件树看不到，按当前布局实测标定。"""
    return [(303, 436), (303, 484)]


def main():
    goto_line(37)
    gutter = locate_gutter()

    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.5)
        click(*gutter)                       # 1. gutter 点开预览
        wait_stable(REGION)
        time.sleep(1.3)                      # 弹窗定格

        fields = param_fields()
        click(*abs_pt(fields[0]), move_dur=0.3)
        typewrite("song", interval=0.06)
        time.sleep(0.5)
        click(*abs_pt(fields[1]), move_dur=0.3)
        typewrite("1", interval=0.06)
        time.sleep(1.8)                      # SQL 回填定格

        # 3. 复制 SQL 并关闭（新 UI 蓝色主行动）
        click(*abs_pt((1115, 806)), move_dur=0.3)
        time.sleep(0.9)
        # 收尾回跳到起始行，保证首尾同态（录制指南第 18 条）
        goto_line(37)
        time.sleep(0.9)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
