# -*- coding: utf-8 -*-
"""T4 wrapper-preview（修订九构图 120%+13pt+树关）：LambdaQueryWrapper 链 gutter →
SQL 弹窗 → 取消勾选 status 布尔重载条件 → SQL 实时重渲染 → 关闭。导出宽 1440。

前置：mybatis-plus-demo/UserServiceImpl.java 打开并滚动到 listByLambdaQuery
（line 35-41），无弹窗。gutter 像素检测动态定位；弹窗控件为修订九重标。
13pt 下 SQL 全文开窗即可见，无需 Ctrl+End 滚动。
"""
import sys
import time

from gifkit import (
    Recorder, click, find_gutter_icons, goto_line, shot, wait_stable,
)

REGION = (277, 25, 1370, 984)
OUT = "perf/t4_wrapper.mp4"

CHK_STATUS = (417, 399)   # 动态条件 status = ? 复选框（修订九重标）
CANCEL = (1485, 827)      # 弹窗「取消」


def locate_gutter():
    path = "perf/_pre_t4.png"
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(150, 700), gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found")
    center = (150 + 700) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("icons:", icons, "-> picked", (y, x))
    return x + 277, y + 25


def main():
    goto_line(36)
    gutter = locate_gutter()

    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*gutter)                       # 1. gutter 点击
    w = wait_stable(REGION)
    print("popup stable wait:", w)
    time.sleep(1.4)                      # 弹窗定格

    click(*CHK_STATUS, move_dur=0.3)     # 2. 取消勾选 status
    time.sleep(1.8)                      # 重渲染定格

    click(*CANCEL, move_dur=0.3)         # 3. 关闭
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
