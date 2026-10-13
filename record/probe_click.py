# -*- coding: utf-8 -*-
"""点击健康探针：按屏幕坐标点一次，截 region 图供人工比对。

用法: python probe_click.py <sx> <sy> <out.png>
屏幕坐标 = region 坐标 + (277, 25)。
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

SX = int(sys.argv[1])
SY = int(sys.argv[2])
OUT = sys.argv[3] if len(sys.argv) > 3 else "record/perf/probe.png"

pyautogui.moveTo(SX, SY, duration=0.3)
time.sleep(0.15)
pyautogui.click(SX, SY)
time.sleep(0.9)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("clicked", SX, SY, "->", OUT)
