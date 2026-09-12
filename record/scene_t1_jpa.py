# -*- coding: utf-8 -*-
"""T1 jpa-preview（修订九构图 120%+13pt+树关）：派生方法 findByUsernameOrEmail
gutter → SQL 弹窗定格 → 关闭 → goto @Query 行 → gutter → JPQL 转换定格 → 关闭 →
回跳 42 行（循环无缝）。

修订九改动：CANCEL 重标 (1485,827)；42 行图标改窄窗 y_range=(350,500) 直取
（13pt 视口内图标密度翻倍，旧「取最低簇」会误选下方方法）。
"""
import sys
import time

from gifkit import (
    Recorder, click, find_gutter_icons, goto_line, open_file, shot,
    wait_stable,
)

REGION = (277, 25, 1370, 984)
OUT = "perf/t1_jpa.mp4"

CANCEL = (1485, 827)     # 弹窗「取消」


def locate_gutter_derived():
    """42 行派生方法：goto 居中后窄窗直取（修订九）。"""
    path = "perf/_pre_t1.png"
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(350, 500), gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found (derived)")
    y, x = icons[0]
    print("derived icons:", icons, "-> picked", (y, x))
    return x + 277, y + 25


def locate_gutter_center():
    """@Query 行：goto 居中后取最接近视口中心的图标。"""
    path = "perf/_mid_t1.png"
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(150, 700), gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found (mid)")
    center = (150 + 700) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("mid icons:", icons, "-> picked", (y, x))
    return x + 277, y + 25


def main():
    open_file("jpa-hibernate-demo/UserRepository")
    goto_line(42)
    gutter1 = locate_gutter_derived()

    r = Recorder(REGION, OUT)
    time.sleep(1.0)
    r.start()
    time.sleep(0.4)

    click(*gutter1, pauses=(0.25, 0.25))    # 1. 派生方法 gutter
    w = wait_stable(REGION)
    print("popup1 stable wait:", w)
    time.sleep(1.6)

    click(*CANCEL, move_dur=0.28)           # 2. 关闭
    time.sleep(0.5)

    goto_line(147)                          # 3. 场景内跳 @Query（对录屏不可见检测）
    gutter2 = locate_gutter_center()
    time.sleep(0.3)

    click(*gutter2, pauses=(0.25, 0.25))    # 4. @Query gutter
    w = wait_stable(REGION)
    print("popup2 stable wait:", w)
    time.sleep(1.5)

    click(*CANCEL, move_dur=0.28)           # 5. 关闭
    time.sleep(0.5)

    goto_line(42)                           # 6. 回跳起点（循环无缝）
    time.sleep(0.7)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
