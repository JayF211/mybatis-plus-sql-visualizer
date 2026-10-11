# -*- coding: utf-8 -*-
"""右键探针：在屏幕坐标右键并截图（用于量右键菜单项位置）。

用法: python probe_rclick.py <sx> <sy> <out.png> [wait_secs]
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

SX = int(sys.argv[1])
SY = int(sys.argv[2])
OUT = sys.argv[3] if len(sys.argv) > 3 else "record/perf/rclick.png"
WAIT = float(sys.argv[4]) if len(sys.argv) > 4 else 1.4

pyautogui.moveTo(SX, SY, duration=0.25)
time.sleep(0.2)
pyautogui.click(SX, SY, button="right")
time.sleep(WAIT)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("right-clicked", SX, SY, "->", OUT)
