# -*- coding: utf-8 -*-
"""Ctrl+Shift+N 打开文件（Return 用 keyDown/keyUp 绕 JBR 吞键）+ 截图。

用法: python probe_openfile.py <name> <out.png>
"""
import sys
import time
import ctypes

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

NAME = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else "record/perf/of1.png"

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

pyautogui.press("escape")
time.sleep(0.8)
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.4)
pyautogui.hotkey("ctrl", "a")          # 清掉搜索框里可能残留的旧查询
time.sleep(0.5)
pyautogui.typewrite(NAME, interval=0.05)
time.sleep(1.6)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(2.4)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("opened", NAME, "->", OUT)
