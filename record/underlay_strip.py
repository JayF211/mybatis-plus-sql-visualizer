# -*- coding: utf-8 -*-
"""录制辅助：**置顶**黑条，盖住沙盒 IDE 窗口下沿之外的那一条（Windows 任务栏所在带）。

用途：A15 的右键菜单高约 814px、被**屏幕**底边夹住（末项恒在 y≈1032），
取景框必须向下延伸到 y≈1075 才拍得到末项与子菜单；而那条带里是任务栏（很花）。
本窗置顶 + 晚于任务栏创建 → 压住任务栏；菜单弹窗又是更晚创建的置顶窗 → 仍压在它之上。

用法：python underlay_strip.py [top] [height]     默认 1009 / 71
"""
import ctypes
import sys
import time

import tkinter as tk

X, W = 277, 1370
Y = int(sys.argv[1]) if len(sys.argv) > 1 else 1009
H = int(sys.argv[2]) if len(sys.argv) > 2 else 71


def main():
    root = tk.Tk()
    root.geometry(f"{W}x{H}+{X}+{Y}")
    root.overrideredirect(True)
    root.config(bg="black")
    root.attributes("-topmost", True)
    root.update_idletasks()
    me = ctypes.windll.user32.GetParent(root.winfo_id()) or root.winfo_id()
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
    u32.SetWindowPos(ctypes.c_void_p(me), ctypes.c_void_p(-1),   # HWND_TOPMOST
                     0, 0, 0, 0, 0x0001 | 0x0002)                # NOSIZE | NOMOVE
    print("strip hwnd:", me, "rect:", X, Y, W, H, flush=True)
    while True:
        time.sleep(3600)
        root.update()


if __name__ == "__main__":
    sys.exit(main())
