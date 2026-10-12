# -*- coding: utf-8 -*-
"""A9 参数回填/动态条件勾选（wrapper-params.gif）20261011 终版：
前置：预览弹窗已开（点 37 行行内 + Alt+Shift+Q）、username 已取消勾选（status/age 保留）。
流程：勾回 username（WHERE 回来 username = ?）→ username 参数框键入 'tom'
→ SQL 里 ? 替换为 'tom' → status 参数框填 1 → WHERE status = 1。
标定（20261011 屏幕坐标，弹窗 @ (100,275) 附近）：username 复选框 (411,410)；
username 输入框 (578,410)；status 输入框 (578,475)。
坑：弹窗内复选框热区即图标中心（新会话健康态）；输入用 gifkit.paste 绕 IME。
"""
import sys
import time

from gifkit import Recorder

REGION = (277, 25, 1370, 984)
OUT = "perf/a9_params.mp4"

CHK_USER = (411, 410)
IN_USER = (578, 410)
IN_STATUS = (578, 475)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    import gifkit

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(0.4)

    # 1. 勾回 username → WHERE 回来一个条件
    pyautogui.click(*CHK_USER)
    time.sleep(1.6)

    # 2. username 参数框填 'tom' → ? 替换为实值
    pyautogui.click(*IN_USER)
    time.sleep(0.5)
    gifkit.paste("'tom'")
    time.sleep(1.8)

    # 3. status 参数框填 1
    pyautogui.click(*IN_STATUS)
    time.sleep(0.5)
    gifkit.paste("1")
    time.sleep(2.0)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
