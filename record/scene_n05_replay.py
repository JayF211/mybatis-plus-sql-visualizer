# -*- coding: utf-8 -*-
"""N05「导出回放脚本」新增录制（T-DocsRefresh1023 / R36）。

分镜（约 8 秒）：
1. 预览面板打开（MPJ 三表 JOIN），停 1.2s
2. 「更多」→「导出回放脚本」→ 导出对话框（事务包裹勾选项），停 1.6s
3. 勾选「事务包裹（BEGIN … COMMIT）」→ 说明行变化，停 1.2s
4. 点「导出」→ 保存框（已填默认名），停 1.4s
5. 取消保存框回预览，停 0.8s

前置（prep）：mpjdemo UserServiceImpl.java 打开、goto(40)、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui

from gifkit import (Recorder, click, find_gutter_icons, goto_line, shot,
                    wait_stable)
from robot import find_components

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "n05_replay.mp4")

OX, OY = REGION[0], REGION[1]
MORE = (1223, 208)
ITEM_REPLAY = (1233, 364)
WRAP_CHECK = (430, 458)      # 事务包裹复选框（导出对话框内，实测）
OK_BTN = (842, 558)          # 对话框 OK（实测）


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def has(xp):
    return len(find_components(xp)) > 0


def locate_gutter():
    path = os.path.join(BASE, "perf", "_pre_n05.png")
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(100, 860), gap=12)
    icons = [i for i in icons if 88 <= i[1] <= 130]
    if not icons:
        raise RuntimeError(f"gutter icon not found: {icons}")
    center = (100 + 860) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    return x + OX, y + OY


def main():
    # prep：预览必须已打开（由调用方先开好）。脚本内不再自己开——
    # gutter 图标位置随 goto 漂移，实测会导致预览未打开而整段白录。
    if not has("//div[@accessiblename='SQL Visualizer - SQL 预览']"):
        raise RuntimeError("前置未满足：预览未打开。请先手工开好预览再跑本脚本")

    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.8)
        # 清掉可能残留的菜单：点预览标题栏空白处（Escape 会连预览一起关掉）
        click(*abs_pt((600, 161)), move_dur=0.2, pauses=(0.1, 0.6))

        click(*abs_pt(MORE), move_dur=0.3, pauses=(0.2, 1.2))           # 更多
        click(*abs_pt(ITEM_REPLAY), move_dur=0.25, pauses=(0.15, 1.8))  # 导出回放脚本
        if not has("//div[@accessiblename='导出回放脚本']"):
            raise RuntimeError("导出回放脚本对话框未打开，中止")

        click(*abs_pt(WRAP_CHECK), move_dur=0.3, pauses=(0.15, 1.2))    # 取消/勾选事务包裹
        click(*abs_pt(WRAP_CHECK), move_dur=0.3, pauses=(0.15, 1.4))    # 再勾回（演示说明行联动）

        click(*abs_pt(OK_BTN), move_dur=0.3, pauses=(0.15, 2.0))        # OK → 保存框
        pyautogui.press("escape")                                        # 取消保存框
        time.sleep(1.0)
        click(*abs_pt((1185, 806)), move_dur=0.3)                        # 关闭预览
        time.sleep(0.9)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
