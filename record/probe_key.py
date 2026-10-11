# -*- coding: utf-8 -*-
"""按键探针：发一个按键序列，截图供人工比对。

用法: python probe_key.py escape out.png
      python probe_key.py "alt+f4" out.png
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

COMBO = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else "record/perf/keyprobe.png"

if "+" in COMBO:
    parts = COMBO.split("+")
    pyautogui.hotkey(*parts)
else:
    pyautogui.press(COMBO)
time.sleep(1.1)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("sent", COMBO, "->", OUT)
