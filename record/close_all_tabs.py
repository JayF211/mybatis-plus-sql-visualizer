# -*- coding: utf-8 -*-
"""关掉当前所有编辑器页签（Ctrl+F4 连按，空编辑区时按下无害）。"""
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

# 先关掉可能开着的预览弹窗（独立窗口会吞快捷键）
pyautogui.click(1485, 828)
time.sleep(1.0)

for _ in range(10):
    pyautogui.hotkey("ctrl", "f4")
    time.sleep(0.35)

print("closed all tabs")
