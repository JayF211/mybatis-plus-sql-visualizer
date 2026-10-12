# -*- coding: utf-8 -*-
"""拖拽 SQL Visualizer 工具窗左边界，恢复录制基线宽度。

用法: python drag_border.py <from_sx> <to_sx> <y>
默认：从当前窄版左缘拖回 region x=668（screen 945）。
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

FX = int(sys.argv[1]) if len(sys.argv) > 1 else 1257
TX = int(sys.argv[2]) if len(sys.argv) > 2 else 945
Y = int(sys.argv[3]) if len(sys.argv) > 3 else 325

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

pyautogui.moveTo(FX, Y, duration=0.3)
time.sleep(0.25)
pyautogui.mouseDown()
time.sleep(0.25)
pyautogui.moveTo((FX + TX) // 2, Y, duration=0.35)
pyautogui.moveTo(TX, Y, duration=0.35)
time.sleep(0.35)
pyautogui.mouseUp()
time.sleep(1.2)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output="perf/widen.png")
print("dragged", FX, "->", TX, "at y", Y)
