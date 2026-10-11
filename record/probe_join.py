# -*- coding: utf-8 -*-
"""打开 UserMapper.xml → 跳到 JOIN 语句行 → Alt+Shift+Q 开预览 → 截图。

用法: python probe_join.py [line] [out.png]
"""
import sys
import time
import ctypes

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui
import gifkit

LINE = sys.argv[1] if len(sys.argv) > 1 else "145"
OUT = sys.argv[2] if len(sys.argv) > 2 else "record/perf/join1.png"

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

# 1. 关掉可能开着的预览弹窗（底部「取消」）
pyautogui.click(1485, 828)
time.sleep(1.2)

# 2. 打开 UserMapper.xml
gifkit.open_file("UserMapper.xml")
time.sleep(2.5)

# 3. 跳到 JOIN 语句行
pyautogui.hotkey("ctrl", "g")
time.sleep(0.9)
pyautogui.typewrite(LINE, interval=0.08)
time.sleep(0.7)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(1.8)

# 4. 预览
pyautogui.hotkey("alt", "shift", "q")
time.sleep(3.0)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("join preview ->", OUT)
