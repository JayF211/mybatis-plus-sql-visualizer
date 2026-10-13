# -*- coding: utf-8 -*-
"""A8 列表显示模式（sql-list-display.gif）。

设置页「列表显示 → SQL 显示模式」从当前值切到下一个形态 → 确定 → SQL 日志列表外观随之变化。
"""
import os
import sys
import time
import ctypes

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
sys.path.insert(0, HERE)

import pyautogui  # noqa: E402
from gifkit import Recorder  # noqa: E402
from locate import sandbox_pid, windows  # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a8_list_display.mp4"


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    u32 = ctypes.windll.user32
    k32 = ctypes.windll.kernel32
    p = sandbox_pid()
    # 主窗口若被最小化先还原（**不要**在 take 前把它置前——设置对话框是独立窗口，
    # 置前主窗口会把它整块盖住，take 前半段就拍不到「改设置」那半）
    mw = next((h for h, pp, t, r in windows() if pp == p and 'mybatis-plus-sql-visualizer-demo' in t and 'Settings' not in t), None)
    if mw:
        u32.ShowWindow(ctypes.c_void_p(mw), 9)   # SW_RESTORE
        # 之前脚本的 ensure_foreground 给主窗口设过 TOPMOST，会一直压着设置对话框——撤掉
        u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                     ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
        u32.SetWindowPos(ctypes.c_void_p(mw), ctypes.c_void_p(-2), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)
        time.sleep(1.0)
    dlg = next((h for h, pp, t, r in windows() if pp == p and 'Settings' in t and (r[2] - r[0]) > 600), None)
    if dlg is None:
        raise RuntimeError("设置对话框未打开")
    r = next(rr for h, pp, t, rr in windows() if h == dlg)
    # 置前**对话框**（take 前半段拍的是它）
    fg = u32.GetForegroundWindow()
    ft = u32.GetWindowThreadProcessId(ctypes.c_void_p(fg), None)
    mt = k32.GetCurrentThreadId()
    u32.AttachThreadInput(mt, ft, True)
    u32.SetForegroundWindow(ctypes.c_void_p(dlg))
    u32.AttachThreadInput(mt, ft, False)
    time.sleep(0.5)
    # SetForegroundWindow 受前台锁限制常静默失败——补一次**真实点击对话框标题栏**取焦
    pyautogui.click(r[0] + 500, r[1] + 14); time.sleep(1.0)

    dd = (r[0] + 511, r[1] + 164)      # 「SQL 显示模式」下拉
    ok = (r[0] + 848, r[1] + 991)      # 确定

    rec = Recorder(REGION, OUT)
    rec.start()
    time.sleep(1.0)
    pyautogui.click(*dd); time.sleep(1.4)          # 打开下拉
    pyautogui.press('down'); time.sleep(0.5)
    pyautogui.press('enter'); time.sleep(1.6)      # 选下一项
    pyautogui.moveTo(r[0] + 700, r[1] + 400, duration=0.6); time.sleep(1.2)
    pyautogui.click(*ok); time.sleep(2.5)          # 确定 → 对话框关，列表按新形态重绘
    if mw:
        fg2 = u32.GetForegroundWindow(); ft2 = u32.GetWindowThreadProcessId(ctypes.c_void_p(fg2), None); mt2 = k32.GetCurrentThreadId()
        u32.AttachThreadInput(mt2, ft2, True); u32.SetForegroundWindow(ctypes.c_void_p(mw)); u32.AttachThreadInput(mt2, ft2, False)
        time.sleep(1.0)
    pyautogui.moveTo(1300, 380, duration=0.8); time.sleep(2.2)
    pyautogui.moveTo(1300, 560, duration=0.6); time.sleep(1.4)
    print("frames:", rec.stop())


if __name__ == "__main__":
    sys.exit(main())
