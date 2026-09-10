# -*- coding: utf-8 -*-
"""T2 batch-operation：saveBatch 调用行 gutter 点击 → 批量参数面板 →
取消勾选 age 列 → INSERT SQL 实时收缩 → 关闭。

T-GifRerecord 重构（同 scene_t8）：现场就位 + 复选框标定全部在录制前动态执行——
先开一次弹窗用 find_blue_checkboxes 定位 toInsert.age 行，关掉后再开录。
"""
import sys
import time

from gifkit import (
    Recorder, click, find_blue_checkboxes, find_gutter_icons, goto_line,
    shot, wait_stable,
)

REGION = (277, 25, 1370, 984)
OUT = "perf/t2_batch.mp4"

CANCEL = (1480, 822)     # 弹窗「取消」


def locate_gutter():
    path = "perf/_pre_t2.png"
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(150, 700), gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found")
    y, x = icons[0]  # 上方簇 = line 211 saveBatch（212 行还有另一个图标，勿选）
    print("gutter icons:", icons, "-> picked", (y, x))
    return x + 277, y + 25


def calibrate_age_checkbox(gutter):
    """开一次弹窗，检测第3行复选框（toInsert.id/password/age/...），关掉。"""
    click(*gutter, move_dur=0.35)
    w = wait_stable(REGION)
    print("calib popup stable:", w)
    time.sleep(1.2)
    path = "perf/_cal_t2.png"
    shot(REGION, path)
    rows = find_blue_checkboxes(path, strip_x=(135, 168), y_range=(370, 770))
    print("checkbox rows:", rows, "-> screen:", [(y + 25, x + 277) for y, x in rows])
    if len(rows) < 3:
        click(*CANCEL, move_dur=0.3)
        raise RuntimeError("less than 3 checkbox rows (expect id/password/age/...)")
    ay, ax = rows[1]  # id 列灰勾（不计数），蓝色第2行 = toInsert.age
    click(*CANCEL, move_dur=0.3)
    time.sleep(1.0)
    return ax + 277, ay + 25


def main():
    goto_line(211)
    gutter = locate_gutter()
    chk_age = calibrate_age_checkbox(gutter)
    print("chk_age:", chk_age)

    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    click(*gutter)                       # 1. 点 saveBatch gutter 图标
    w = wait_stable(REGION)
    print("popup stable wait:", w)
    time.sleep(1.4)                      # 批量面板定格

    click(*chk_age, move_dur=0.35)       # 2. 取消勾选 age 列
    time.sleep(2.0)                      # SQL 收缩定格

    click(*CANCEL, move_dur=0.3)         # 3. 关闭
    time.sleep(0.9)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
