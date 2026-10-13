# -*- coding: utf-8 -*-
"""滚轮探针：在指定屏幕坐标滚轮并截图。

用法: python probe_scroll.py <sx> <sy> <amount> <out.png>   # amount>0 向上滚
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

SX = int(sys.argv[1])
SY = int(sys.argv[2])
AMT = int(sys.argv[3])
OUT = sys.argv[4] if len(sys.argv) > 4 else "record/perf/scroll.png"

pyautogui.moveTo(SX, SY, duration=0.25)
time.sleep(0.3)
pyautogui.scroll(AMT)
time.sleep(1.2)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("scrolled", AMT, "at", SX, SY, "->", OUT)
