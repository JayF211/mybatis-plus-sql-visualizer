# -*- coding: utf-8 -*-
"""S1 同款拖拽：单段长时长 moveTo（不做多步插值）。

用法: python probe_drag2.py <sx> <sy1> <sy2> <out.png>
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

SX = int(sys.argv[1])
Y1 = int(sys.argv[2])
Y2 = int(sys.argv[3])
OUT = sys.argv[4] if len(sys.argv) > 4 else "record/perf/drag2.png"

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

pyautogui.moveTo(SX, Y1, duration=0.2)
time.sleep(0.15)
pyautogui.mouseDown()
time.sleep(0.15)
pyautogui.moveTo(SX, Y2, duration=0.4)
time.sleep(0.15)
pyautogui.mouseUp()
time.sleep(0.8)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("dragged", SX, Y1, "->", Y2, "->", OUT)
