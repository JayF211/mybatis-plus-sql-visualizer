# -*- coding: utf-8 -*-
"""直接向 IDE 主窗口投递 WM_MOUSEWHEEL（绕开 OS 输入队列的合成路径）。

用法: python wheel_msg.py <hwnd> <notches> <sx> <sy> [out.png]   # notches>0 向上滚
"""
import sys
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

HWND = int(sys.argv[1])
NOTCHES = int(sys.argv[2])
SX = int(sys.argv[3])
SY = int(sys.argv[4])
OUT = sys.argv[5] if len(sys.argv) > 5 else ""
CTRL = len(sys.argv) > 6 and sys.argv[6].lower() in ("1", "ctrl", "true")

user32 = ctypes.windll.user32
WM_MOUSEWHEEL = 0x020A
WHEEL_DELTA = 120
MK_CONTROL = 0x0008

lparam = (SY << 16) | (SX & 0xFFFF)
for _ in range(abs(NOTCHES)):
    delta = WHEEL_DELTA if NOTCHES > 0 else -WHEEL_DELTA
    wparam = ((delta & 0xFFFF) << 16) | (MK_CONTROL if CTRL else 0)
    user32.PostMessageW(HWND, WM_MOUSEWHEEL, wparam, lparam)
    time.sleep(0.02)

time.sleep(1.0)
if OUT:
    import mss
    with mss.mss() as sct:
        img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
        mss.tools.to_png(img.rgb, img.size, output=OUT)
    print("posted", NOTCHES, "notches ->", OUT)
else:
    print("posted", NOTCHES, "notches")
