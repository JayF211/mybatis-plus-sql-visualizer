# -*- coding: utf-8 -*-
"""恢复 Chrome（录制背景）到全屏、再把 IDE 抬到它前面，最后截图确认。

录制环境里 Chrome 空白页是**故意的背景**，用来遮住录制区外的干扰——不要最小化它。

用法: python restore_chrome_front_ide.py <ide_hwnd> <chrome_hwnd>
"""
import ctypes
import os
import sys
import time
from ctypes import wintypes

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss

u32 = ctypes.windll.user32
k32 = ctypes.windll.kernel32

IDE = int(sys.argv[1])
CHROME = int(sys.argv[2]) if len(sys.argv) > 2 else 0

SW_RESTORE = 9
SW_MAXIMIZE = 3

if CHROME:
    u32.ShowWindow(wintypes.HWND(CHROME), SW_MAXIMIZE)
    time.sleep(0.8)

u32.ShowWindow(wintypes.HWND(IDE), SW_RESTORE)
time.sleep(0.3)
fg = u32.GetForegroundWindow()
fg_thread = u32.GetWindowThreadProcessId(wintypes.HWND(fg), None)
my_thread = k32.GetCurrentThreadId()
u32.AttachThreadInput(my_thread, fg_thread, True)
u32.SetForegroundWindow(wintypes.HWND(IDE))
u32.AttachThreadInput(my_thread, fg_thread, False)
u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint, ctypes.c_uint]
u32.SetWindowPos(ctypes.c_void_p(IDE), ctypes.c_void_p(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)
time.sleep(0.5)
# 撤掉 TOPMOST（留它在顶层会压住后续要弹的对话框/浮层观感）
u32.SetWindowPos(ctypes.c_void_p(IDE), ctypes.c_void_p(-2), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)
time.sleep(0.5)

buf = ctypes.create_unicode_buffer(128)
u32.GetWindowTextW(u32.GetForegroundWindow(), buf, 128)
print("fg now:", buf.value[:60])

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "perf", "ide_front2.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    mss.tools.to_png(img.rgb, img.size, output=out)
print("shot ->", out)
