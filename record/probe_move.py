# -*- coding: utf-8 -*-
"""悬停探针：把鼠标移到屏幕坐标并截图（不点击），用于找气泡上的 ✕。

用法: python probe_move.py <sx> <sy> <out.png>
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

SX = int(sys.argv[1])
SY = int(sys.argv[2])
OUT = sys.argv[3] if len(sys.argv) > 3 else "record/perf/move.png"

pyautogui.moveTo(SX, SY, duration=0.25)
time.sleep(1.4)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("hovered", SX, SY, "->", OUT)
