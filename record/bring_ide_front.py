# -*- coding: utf-8 -*-
"""把覆盖在 IDE 上的 Chrome 最小化，再抬 IDE 到前台并验证。

用法: python bring_ide_front.py <ide_hwnd> <chrome_hwnd>
"""
import ctypes
import sys
import time
from ctypes import wintypes

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss

u32 = ctypes.windll.user32
k32 = ctypes.windll.kernel32

IDE = int(sys.argv[1])
CHROME = int(sys.argv[2]) if len(sys.argv) > 2 else 0

SW_MINIMIZE = 6
SW_RESTORE = 9

if CHROME:
    u32.ShowWindow(wintypes.HWND(CHROME), SW_MINIMIZE)
    time.sleep(0.8)

u32.ShowWindow(wintypes.HWND(IDE), SW_RESTORE)
time.sleep(0.4)
fg = u32.GetForegroundWindow()
fg_thread = u32.GetWindowThreadProcessId(wintypes.HWND(fg), None)
my_thread = k32.GetCurrentThreadId()
u32.AttachThreadInput(my_thread, fg_thread, True)
u32.SetForegroundWindow(wintypes.HWND(IDE))
u32.AttachThreadInput(my_thread, fg_thread, False)
u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint, ctypes.c_uint]
u32.SetWindowPos(ctypes.c_void_p(IDE), ctypes.c_void_p(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)
time.sleep(0.6)

buf = ctypes.create_unicode_buffer(128)
u32.GetWindowTextW(u32.GetForegroundWindow(), buf, 128)
print("fg now:", buf.value[:60])

with mss.mss() as sct:
    img = sct.grab({"left": 277, "top": 25, "width": 1370, "height": 984})
    mss.tools.to_png(img.rgb, img.size, output="record/perf/ide_front.png")
print("shot -> record/perf/ide_front.png")
