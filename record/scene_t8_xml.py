# -*- coding: utf-8 -*-
"""T8 xml-preview（修订九构图 120%+13pt+树关）：selectUserByTrim 的 <select> 行
gutter 点击 → SQL 弹窗 → 取消勾选 <if roleId> 动态条件 → SQL 实时变化 → 关闭。

前置：mybatis-plus-demo/UserMapper.xml 打开并滚动到 line 74，无弹窗。
gutter 像素检测动态定位（取最接近视口中心者）；roleId 复选框为修订九重标。
13pt 下 SQL 全文开窗即可见，无需 Ctrl+End 滚动。
"""
import sys
import time

from gifkit import (
    Recorder, click, find_gutter_icons, goto_line, shot, wait_stable,
)

REGION = (277, 25, 1370, 984)
OUT = "perf/t8_xml.mp4"

CHK_ROLEID = (417, 378)  # 动态条件 roleId != null 复选框（修订九重标）
CANCEL = (1485, 827)     # 弹窗「取消」


def locate_gutter():
    """就位后现抓一帧，取最接近编辑区垂直中心的 gutter 图标。"""
    path = "perf/_pre_t8.png"
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(150, 700), gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found")
    center = (150 + 700) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("gutter icons:", icons, "-> picked", (y, x))
    return x + 277, y + 25


def main():
    goto_line(74)
    gutter = locate_gutter()

    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*gutter)                       # 1. gutter 点击
    w = wait_stable(REGION)
    print("popup stable wait:", w)
    time.sleep(1.4)

    click(*CHK_ROLEID, move_dur=0.3)     # 2. 取消勾选 <if roleId>
    time.sleep(2.0)                      # SQL 变化定格

    click(*CANCEL, move_dur=0.3)         # 3. 关闭
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
