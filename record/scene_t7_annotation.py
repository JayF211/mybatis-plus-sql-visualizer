# -*- coding: utf-8 -*-
"""T7 annotation-preview（修订九构图 120%+13pt+树关）：@Select script 方法行 gutter →
SQL 弹窗 → 参数回填 username/status → SQL 实时回填定格 → 关闭。

前置：mybatis-plus-demo/UserMapper.java 打开并滚动到 line 33（@Select("<script>")），
无弹窗。gutter 图标像素检测动态定位；弹窗内控件坐标为修订九重标。
"""
import sys
import time

from gifkit import (
    Recorder, click, find_gutter_icons, goto_line, shot, typewrite,
    wait_stable,
)

REGION = (277, 25, 1370, 984)
OUT = "perf/t7_annotation.mp4"

IN_USERNAME = (593, 465)   # 参数 #username 输入框（修订九重标）
IN_STATUS = (593, 523)     # 参数 #status 输入框
CANCEL = (1485, 827)       # 弹窗「取消」


def locate_gutter():
    path = "perf/_pre_t7.png"
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(150, 700), gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found")
    center = (150 + 700) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("icons:", icons, "-> picked", (y, x))
    return x + 277, y + 25


def main():
    goto_line(33)
    gutter = locate_gutter()

    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*gutter)                        # 1. gutter 点击
    w = wait_stable(REGION)
    print("popup stable wait:", w)
    time.sleep(1.3)                       # 弹窗定格

    click(*IN_USERNAME, move_dur=0.3)     # 2. 回填 username
    typewrite("song", interval=0.06)
    time.sleep(0.5)

    click(*IN_STATUS, move_dur=0.3)       # 3. 回填 status
    typewrite("1", interval=0.06)
    time.sleep(2.0)                       # SQL 回填效果定格

    click(*CANCEL, move_dur=0.3)          # 4. 关闭
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
