# -*- coding: utf-8 -*-
"""R07 code-completion 重录（T-DocsRefresh1023）：UserMapper.xml 的 from user 行尾
Enter 新行 → 键入 "join us" 自动弹出表名补全 → Enter 采纳 user → 定格 → undo 还原。

修订九改法：不再点击行坐标（goto 居中位置有 ±2 行漂移），改 goto_line(77) 把
光标落在 77 行行首后全键盘操作（End/Enter/键入），完全确定；鼠标仅做演示滑动。
前置：mybatis-plus-demo/UserMapper.xml 打开；无弹窗；系统 IME 为英文态。
"""
import sys
import time

from gifkit import Recorder, goto_line, move, pyautogui, wait_stable

REGION = (277, 25, 1370, 984)
OUT = "perf/r07_completion.mp4"


def main():
    goto_line(77)

    r = Recorder(REGION, OUT)
    time.sleep(1.2)
    r.start()
    time.sleep(0.5)

    move(640, 500, dur=0.4)              # 1. 光标滑入编辑区（演示轨迹，不点击）
    time.sleep(0.3)
    pyautogui.press('end')               # 2. 行尾
    time.sleep(0.4)
    pyautogui.press('enter')             # 3. 新行
    time.sleep(0.5)
    pyautogui.typewrite('join us', interval=0.09)  # 4. 边打边弹
    time.sleep(1.8)                      # 补全弹窗定格
    pyautogui.keyDown('return'); pyautogui.keyUp('return')  # 5. 采纳 user
    time.sleep(1.6)                      # 结果定格
    for _ in range(4):                   # 6. 撤回编辑（还原现场，循环无缝）
        pyautogui.hotkey('ctrl', 'z')
        time.sleep(0.4)
    time.sleep(0.6)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
