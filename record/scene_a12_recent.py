# -*- coding: utf-8 -*-
"""A12 最近执行记录（recent-executions.gif）20261011：
编辑器 gutter 图标右键 → 菜单（↓+Enter 选「最近执行记录」）→ 对话框（左列表 + 右编辑器）
→ Ctrl+点选两条多选 → 右键弹菜单。IDE 保持 TOPMOST（force_front.py 已置）。
标定（20261011 屏幕坐标）：对话框锚定 gutter @ (399,293)，列表行中心 (677,475)/(677,570)——图内坐标须 +277/+25。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a12_recent.mp4"

ICON = (399, 293)
ROW1 = (677, 475)
ROW2 = (677, 570)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. gutter 图标右键 → 菜单 → ↓ Enter 选「最近执行记录」
    pyautogui.rightClick(*ICON)
    time.sleep(1.2)
    pyautogui.press('down'); time.sleep(0.35)
    pyautogui.keyDown('return'); time.sleep(0.08); pyautogui.keyUp('return')
    time.sleep(2.3)

    # 2. Ctrl+点选两条 → 已选 N 条
    pyautogui.keyDown('ctrl')
    time.sleep(0.1)
    pyautogui.click(*ROW1); time.sleep(0.4)
    pyautogui.click(*ROW2); time.sleep(0.4)
    pyautogui.keyUp('ctrl')
    time.sleep(0.8)

    # 3. 右键选中行 → 菜单
    pyautogui.rightClick(*ROW1)
    time.sleep(1.5)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
