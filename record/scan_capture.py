# -*- coding: utf-8 -*-
"""扩展九宫格扫捕捉按钮热区。"""
import ctypes
import time
import urllib.request

ctypes.windll.shcore.SetProcessDpiAwareness(2)
import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0
import mss
import numpy as np


def has_entries():
    with mss.mss() as s:
        img = s.grab({"left": 1000, "top": 230, "width": 340, "height": 100})
        arr = np.frombuffer(img.rgb, dtype=np.uint8).reshape(100, 340, 3).astype(int)
        r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
        return int(((b - r > 40) & (b > 120)).sum())


base = (999, 190)
for dx, dy in [(-20, 0), (20, 0), (0, -20), (0, 20), (-16, -12), (16, -12), (-16, 12), (16, 12), (-18, 0), (18, 0)]:
    pyautogui.click(base[0] + dx, base[1] + dy)
    time.sleep(1.2)
    urllib.request.urlopen("http://localhost:8081/user/list", timeout=5)
    time.sleep(1.5)
    n = has_entries()
    print(f"({base[0]+dx},{base[1]+dy}) -> {n}")
    if n > 40:
        print("CAPTURED")
        break
