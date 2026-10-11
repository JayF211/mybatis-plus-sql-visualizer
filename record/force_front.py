# -*- coding: utf-8 -*-
"""强制把 IDE 抬到前台并保持 TOPMOST（干完活用 force_back 撤）。"""
import ctypes
import sys
import time
from ctypes import wintypes

ctypes.windll.shcore.SetProcessDpiAwareness(2)
u32 = ctypes.windll.user32
k32 = ctypes.windll.kernel32

IDE_HWND = int(sys.argv[1]) if len(sys.argv) > 1 else 983416

ide = wintypes.HWND(IDE_HWND)
fg = u32.GetForegroundWindow()
fg_thread = u32.GetWindowThreadProcessId(wintypes.HWND(fg), None)
my_thread = k32.GetCurrentThreadId()
u32.AttachThreadInput(my_thread, fg_thread, True)
u32.SetForegroundWindow(ide)
u32.AttachThreadInput(my_thread, fg_thread, False)
u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
u32.SetWindowPos(ctypes.c_void_p(IDE_HWND), ctypes.c_void_p(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)
time.sleep(0.4)
fg = u32.GetForegroundWindow()
buf = ctypes.create_unicode_buffer(128)
u32.GetWindowTextW(fg, buf, 128)
print('fg now:', buf.value[:60])
