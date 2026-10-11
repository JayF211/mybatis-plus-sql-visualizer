# -*- coding: utf-8 -*-
"""点进目标控件 → Ctrl+A → 剪贴板粘贴替换文本 → 截图。

用法: python probe_replace.py <sx> <sy> <text> <out.png>
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

import gifkit

SX, SY = int(sys.argv[1]), int(sys.argv[2])
TEXT = sys.argv[3]
OUT = sys.argv[4] if len(sys.argv) > 4 else "record/perf/replace.png"

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

pyautogui.click(SX, SY)
time.sleep(0.6)
pyautogui.click(SX, SY)          # 子窗口首击可能被聚焦吞掉
time.sleep(0.6)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.5)
gifkit.paste(TEXT)
time.sleep(1.2)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("replaced ->", OUT)
