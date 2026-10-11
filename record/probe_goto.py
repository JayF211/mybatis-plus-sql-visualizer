# -*- coding: utf-8 -*-
"""切到 UserServiceImpl.java 并 goto 指定行，截图（用于定位 wrapper 链行落点）。"""
import ctypes
import sys
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

LINE = sys.argv[1] if len(sys.argv) > 1 else "110"
TAB = (412, 102)          # 编辑器页签 UserServiceImpl.java
OUT = sys.argv[2] if len(sys.argv) > 2 else "record/perf/pv1.png"

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

pyautogui.click(*TAB)
time.sleep(1.4)
pyautogui.hotkey("ctrl", "g")
time.sleep(0.9)
pyautogui.typewrite(LINE, interval=0.08)
time.sleep(0.7)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(1.6)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("goto", LINE, "->", OUT)
