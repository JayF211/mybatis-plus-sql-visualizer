# -*- coding: utf-8 -*-
"""A5 状态转换探针：一次跑完四次转换并各截一图，供拼图核对。

序列：Esc 清场 → 单击 #1（浮层？）→ Esc（浮层关？）→ 双击 #1（弹窗？）→ Esc（弹窗关？）
输出 perf/tn*.png，之后可用 ffmpeg tile 拼一张。
"""
import ctypes
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)

import mss
import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

ENTRY1 = (1277, 250)
REGION = {"left": 277, "top": 25, "width": 1370, "height": 984}


def shot(tag):
    with mss.mss() as sct:
        img = sct.grab(REGION)
        mss.tools.to_png(img.rgb, img.size, output=f"perf/tn{tag}.png")
    print("shot", tag)


def main():
    pyautogui.press("escape")
    time.sleep(1.2)
    shot("1_after_esc")

    # 单击 → 轻量浮层
    pyautogui.click(*ENTRY1)
    time.sleep(2.4)
    shot("2_after_click")

    # Esc → 浮层是否可关
    pyautogui.press("escape")
    time.sleep(1.4)
    shot("3_after_esc2")

    # 双击 → 完整弹窗
    pyautogui.doubleClick(*ENTRY1)
    time.sleep(2.6)
    shot("4_after_dclick")

    # Esc → 弹窗是否可关
    pyautogui.press("escape")
    time.sleep(1.6)
    shot("5_after_esc3")


if __name__ == "__main__":
    main()
