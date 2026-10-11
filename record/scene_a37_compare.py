# -*- coding: utf-8 -*-
"""A37 历史两条对比（history-compare.gif）20261011 v2：
历史二级列表：prep 已点选 #2（内联预览展开、#1 位置不动）→ Ctrl+点选 #1 →
右键 #1 → 菜单「对比选中两条」→ 差异对比对话框（标题含结构指纹结论）。
标定（20261011 屏幕坐标）：#1 (1110,310)（点 #2 后位置不变）；
菜单项相对右键点：对比选中两条 (+60,+181)。
坑：单击条目会内联展开预览、把下方条目挤出视区——先点下面的行再 Ctrl+点上面的行。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a37_compare.mp4"

ROW1 = (1110, 310)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. Ctrl+点选 #1（#2 已在 prep 中选中）
    pyautogui.keyDown('ctrl')
    time.sleep(0.1)
    pyautogui.click(*ROW1)
    time.sleep(0.35)
    pyautogui.keyUp('ctrl')
    time.sleep(0.8)

    # 2. 右键 #1 → 对比选中两条
    pyautogui.rightClick(*ROW1)
    time.sleep(1.4)
    pyautogui.click(ROW1[0] + 60, ROW1[1] + 181)
    time.sleep(2.6)  # 差异对话框落位

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
