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

# 预览弹窗是独立窗口，开着时 Ctrl+Shift+N 到不了 IDE（实测：不关弹窗则搜索弹窗根本不出现）
pyautogui.click(1485, 828)
time.sleep(1.0)
pyautogui.press("escape")
time.sleep(0.8)
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.4)
pyautogui.hotkey("ctrl", "a")          # 清掉搜索框里可能残留的旧查询
time.sleep(0.5)
pyautogui.typewrite(NAME, interval=0.05)
time.sleep(1.6)
# Return 打不开「文件」搜索弹窗的结果行（JBR 吞键），必须点行。
# 行落点 region y 在 317~325 之间浮动（随弹窗摆位），故做两点小扫掠：命中即开，
# 落空的那一下只会点在编辑器空白处（无害）。
for y in (350, 358, 366):
    pyautogui.click(777, y)
    time.sleep(0.4)
time.sleep(2.4)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("opened", NAME, "->", OUT)
