# -*- coding: utf-8 -*-
"""打开指定文件 → 跳行 → Alt+Shift+Q 预览 → 截图（一条龙，避开页签点击）。

用法: python probe_open_preview.py <file> <line> <out.png>
"""
import sys
import time
import ctypes

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

FILE = sys.argv[1]
LINE = sys.argv[2]
OUT = sys.argv[3] if len(sys.argv) > 3 else "record/perf/op1.png"

RESULT_ROW = (777, 350)   # 「文件」搜索弹窗首个结果行（20261011 标定）

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

# 关掉可能开着的预览弹窗
pyautogui.click(1485, 828)
time.sleep(1.0)

# 打开文件
pyautogui.hotkey("ctrl", "shift", "n")
time.sleep(1.4)
pyautogui.hotkey("ctrl", "a")
time.sleep(0.4)
pyautogui.typewrite(FILE, interval=0.05)
time.sleep(1.6)
# 键盘路径：↓ 把选中落到结果列表首行，再 Return（goto 已验证 keyDown/keyUp('return') 可用）；
# 结果行的屏幕落点在不同会话间漂得厉害，点行不可靠。
pyautogui.press("down")
time.sleep(0.5)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(2.4)

# 跳行
pyautogui.hotkey("ctrl", "g")
time.sleep(0.9)
pyautogui.typewrite(LINE, interval=0.08)
time.sleep(0.7)
pyautogui.keyDown("return")
pyautogui.keyUp("return")
time.sleep(1.6)

# 预览
pyautogui.hotkey("alt", "shift", "q")
time.sleep(3.5)

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output=OUT)
print("open+preview", FILE, LINE, "->", OUT)
