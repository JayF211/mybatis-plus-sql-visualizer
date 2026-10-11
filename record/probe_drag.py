# -*- coding: utf-8 -*-
"""拖拽探针：按住起点拖到终点后截图。

用法: python probe_drag.py <x1> <y1> <x2> <y2> <out.png> [after_wait]
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

X1, Y1 = int(sys.argv[1]), int(sys.argv[2])
X2, Y2 = int(sys.argv[3]), int(sys.argv[4])
OUT = sys.argv[5] if len(sys.argv) > 5 else "record/perf/drag.png"
WAIT = float(sys.argv[6]) if len(sys.argv) > 6 else 1.2

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

pyautogui.moveTo(X1, Y1, duration=0.25)
time.sleep(0.25)
pyautogui.mouseDown()
time.sleep(0.25)
steps = 8
for i in range(1, steps + 1):
    pyautogui.moveTo(X1 + (X2 - X1) * i // steps, Y1 + (Y2 - Y1) * i // steps, duration=0.06)
time.sleep(0.3)
pyautogui.mouseUp()
time.sleep(WAIT)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("dragged", (X1, Y1), "->", (X2, Y2), "->", OUT)
