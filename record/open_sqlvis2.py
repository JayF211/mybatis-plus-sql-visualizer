# -*- coding: utf-8 -*-
"""Find Action → 激活 SQL Visualizer 工具窗（Enter 用 keyDown/keyUp，绕 JBR 吞键）。"""
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

REGION = {"left": 277, "top": 25, "width": 1370, "height": 984}

pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.4)
pyautogui.typewrite("SQL Visualizer", interval=0.05)
time.sleep(1.4)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(2.2)

with mss.mss() as sct:
    img = sct.grab(REGION)
    mss.tools.to_png(img.rgb, img.size, output="perf/ov3.png")
print("done")
