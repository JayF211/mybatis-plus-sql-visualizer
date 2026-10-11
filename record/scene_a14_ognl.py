# -*- coding: utf-8 -*-
"""A14 XML OGNL 表达式面板（xml-ognl.gif）20261011 终版：
前置：UserMapper.xml 已开、光标在 29 行 <where> 内、预览弹窗已开（点行内 + Alt+Shift+Q）。
流程：勾掉 age 条件（SQL 少 AND age >= ?）→ #username 参数框填 'tom'
→ LIKE concat('%', ?, '%') 实值替换。
标定（20261011 屏幕坐标，本次弹窗实测）：age 复选框 (367,425)；#username 输入框 (578,502)。每次弹窗打开位置须现标。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a14_ognl.mp4"

CHK_AGE = (367, 425)
IN_USERNAME = (578, 502)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    import gifkit

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 勾掉 age → SQL 少一个 AND
    pyautogui.click(*CHK_AGE)
    time.sleep(1.6)

    # 2. #username 填 'tom'
    pyautogui.click(*IN_USERNAME)
    time.sleep(0.5)
    gifkit.paste("'tom'")
    time.sleep(2.0)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
