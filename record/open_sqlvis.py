# -*- coding: utf-8 -*-
"""用 Find Action 重新激活 SQL Visualizer 工具窗，并截两图（输入后 / 回车后）。"""
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

REGION = {"left": 277, "top": 25, "width": 1370, "height": 984}


def shot(tag):
    with mss.mss() as sct:
        img = sct.grab(REGION)
        mss.tools.to_png(img.rgb, img.size, output=f"perf/ov{tag}.png")
    print("shot", tag)


pyautogui.hotkey("ctrl", "shift", "a")
time.sleep(1.4)
pyautogui.typewrite("SQL Visualizer", interval=0.05)
time.sleep(1.4)
shot("1_typed")
pyautogui.press("enter")
time.sleep(2.0)
shot("2_entered")
