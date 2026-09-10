# -*- coding: utf-8 -*-
"""T8 xml-preview：selectUserByTrim 的 <select> 行 gutter 点击 → SQL 弹窗 →
Ctrl+End 露出 WHERE → 取消勾选 <if roleId> 动态条件 → SQL 实时变化 → 关闭。

T-GifRerecord 重构：现场就位（open_file/goto_line/gutter 定位）全部搬进本脚本、
Recorder.start 之前动态执行——prep 与录制分离时编辑器滚动/焦点会漂移
（实证：两次 take 因状态漂移录成空场）。gutter 坐标用 find_gutter_icons
像素检测动态取，不再用写死的屏幕坐标。
"""
import sys
import time

from gifkit import (
    Recorder, click, find_gutter_icons, goto_line, keys,
    open_file, shot, wait_stable,
)

REGION = (277, 25, 1370, 984)
OUT = "perf/t8_xml.mp4"

SQL_AREA = (1050, 500)   # SQL 面板聚焦（屏幕坐标）
CHK_ROLEID = (424, 447)  # 动态条件 roleId != null 复选框（xml 弹窗行距与 wrapper 不同，实测重标）
CANCEL = (1480, 822)     # 弹窗「取消」


def locate_gutter():
    """就位后现抓一帧，取最接近编辑区垂直中心的 gutter 图标（goto 居中即目标行）。"""
    path = "perf/_pre_t8.png"
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(150, 700))
    if not icons:
        raise RuntimeError("gutter icon not found")
    center = (150 + 700) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("gutter icons:", icons, "-> picked", (y, x))
    return x + 277, y + 25


def main():
    open_file("mybatis-plus-demo/UserMapper.xml")
    goto_line(74)
    gutter = locate_gutter()

    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*gutter)                       # 1. gutter 点击
    w = wait_stable(REGION)
    print("popup stable wait:", w)
    time.sleep(1.3)

    click(*SQL_AREA, move_dur=0.28)      # 2. 聚焦 SQL 面板滚到 WHERE
    keys("ctrl", "end")
    time.sleep(0.9)

    click(*CHK_ROLEID, move_dur=0.3)     # 3. 取消勾选 <if roleId>
    time.sleep(2.0)                      # SQL 变化定格

    click(*CANCEL, move_dur=0.3)         # 4. 关闭
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
