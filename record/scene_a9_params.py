# -*- coding: utf-8 -*-
"""A9 参数回填/动态条件勾选（wrapper-params.gif）20261011：
预览弹窗已开（prep：Alt+Shift+Q 在 38 行 eq 链上唤起）→
勾掉 username 条件（WHERE 少一条件）→ username 参数框键入 'tom'（条件回来并替换实值）
→ status 框填 1 → 短停收尾。
标定（20261011 屏幕坐标，弹窗 @ (100,148)）：username 复选框 (367,540)；
username 输入框 (599,541)；status 输入框 (599,606)。
坑：输入用 gifkit.paste 绕 IME；对话框首击可能被吞（连点两次无害——复选框是 toggle，两次=原态）。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a9_params.mp4"

CHK_USER = (367, 540)
IN_USER = (599, 541)
IN_STATUS = (599, 606)


def main():
    import ctypes
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    import gifkit

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 勾掉 username 条件
    pyautogui.click(*CHK_USER)
    time.sleep(1.4)

    # 2. 填 username 参数（'tom'）
    pyautogui.click(*IN_USER)
    time.sleep(0.4)
    gifkit.paste("'tom'")
    time.sleep(1.6)

    # 3. 填 status 参数（1）
    pyautogui.click(*IN_STATUS)
    time.sleep(0.4)
    gifkit.paste("1")
    time.sleep(1.8)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
