# -*- coding: utf-8 -*-
"""T2 batch-operation（修订九构图 120%+13pt+树关）：saveBatch 调用行 gutter →
批量参数面板 → 取消勾选 age 列 → INSERT SQL 实时收缩 → 关闭。

前置：mybatis-plus-demo/UserServiceImpl.java 打开并滚动到 line 211（saveBatch），
无弹窗。gutter 检测限 y_range=(350,650)：只含 211/212 两簇，避开上方 205 行的
执行图标（修订九 13pt 下更多行进入视口）；蓝色复选框第 2 行 = toInsert.age。
"""
import sys
import time

from gifkit import (
    Recorder, click, find_blue_checkboxes, find_gutter_icons, goto_line,
    shot, wait_stable,
)

REGION = (277, 25, 1370, 984)
OUT = "perf/t2_batch.mp4"

CANCEL = (1485, 827)     # 弹窗「取消」


def locate_gutter():
    path = "perf/_pre_t2.png"
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(350, 650), gap=12)
    if not icons:
        raise RuntimeError("gutter icon not found")
    y, x = icons[0]  # 上簇 = line 211 saveBatch（下簇是 212 updateBatchById）
    print("gutter icons:", icons, "-> picked", (y, x))
    return x + 277, y + 25


def calibrate_age_checkbox(gutter):
    """开一次弹窗，检测第2个蓝色复选框行（toInsert.age），关掉。"""
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
        raise RuntimeError("less than 3 checkbox rows (expect password/age/...)")
    ay, ax = rows[1]  # 蓝色第2行 = toInsert.age（第1行 password）
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
