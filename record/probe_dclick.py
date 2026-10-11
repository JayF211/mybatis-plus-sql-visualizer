# -*- coding: utf-8 -*-
"""双击探针：双击屏幕坐标后截图，并可选取等候秒数。

用法: python probe_dclick.py <sx> <sy> <out.png> [wait_secs]
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

SX = int(sys.argv[1])
SY = int(sys.argv[2])
OUT = sys.argv[3] if len(sys.argv) > 3 else "record/perf/dclick.png"
WAIT = float(sys.argv[4]) if len(sys.argv) > 4 else 2.2

pyautogui.moveTo(SX, SY, duration=0.25)
time.sleep(0.15)
pyautogui.doubleClick(SX, SY)
time.sleep(WAIT)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("double-clicked", SX, SY, "->", OUT)
