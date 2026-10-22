# -*- coding: utf-8 -*-
"""R04 XML 动态 SQL 预览重录（T-DocsRefresh1023）。

**不再点 gutter**：gutter 图标 y 位置随滚动位置漂移，且条带里混着编辑器折叠箭头
（x≈117 不是插件图标）——实测三次都选错行。改用快捷键 `Alt+Shift+Q`：在 mapper XML 里
光标不在语句内时弹出「选择要预览的 SQL」候选框，按 id 精确选目标语句，比像素定位确定。

分镜（约 11 秒）：
1. 光标在 updateUserByXml 上，`Alt+Shift+Q` → 候选框列出六条语句，停 1.3s
2. 选 updateUserByXml → 预览面板打开（UPDATE + SET 段条件），停 1.5s
3. 取消 email 条件 → SET 段少一行，停 1.2s
4. 取消 status 条件 → SET 段再少一行，停 1.2s
5. 点蓝色主行动「复制 SQL 并关闭」→ 面板关闭、剪贴板通知，停 0.9s
6. 回跳到起始行，首尾同态

前置（prep）：UserMapper.xml 打开、goto(74)、无弹窗。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui
from gifkit import Recorder, click, goto_line
from robot import find_components

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r04_xml.mp4")

OX, OY = REGION[0], REGION[1]


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


# 「选择要预览的 SQL」候选框里 updateUserByXml 那一行（region 相对，实测）
POPUP_UPDATE_ROW = (500, 464)
POPUP_OK = (563, 646)
# 动态条件复选框（updateUserByXml 三枚：email / status / 其它）
COND_CHECKS = [(140, 334), (140, 364)]
COPY_AND_CLOSE = (1115, 806)


def main():
    goto_line(74)
    time.sleep(0.8)

    rec = Recorder(REGION, OUT)
    time.sleep(1.0)
    rec.start()
    try:
        time.sleep(0.5)
        pyautogui.hotkey("alt", "shift", "q")     # 1. 唤起候选框
        time.sleep(1.3)

        click(*abs_pt(POPUP_UPDATE_ROW), move_dur=0.3, pauses=(0.2, 0.6))  # 2. 选中 updateUserByXml 行
        click(*abs_pt(POPUP_OK), move_dur=0.25, pauses=(0.15, 1.6))        #    点 OK 确认

        click(*abs_pt(COND_CHECKS[0]), move_dur=0.28, pauses=(0.2, 1.2))   # 3. 取消 email
        click(*abs_pt(COND_CHECKS[1]), move_dur=0.28, pauses=(0.2, 1.2))   # 4. 取消 status

        click(*abs_pt(COPY_AND_CLOSE), move_dur=0.3)                       # 5. 复制 SQL 并关闭
        time.sleep(0.9)

        # 6. 首尾同态：Ctrl+Shift+Backspace 回上次编辑位置（不弹 goto 框，避免末帧多一个对话框）
        pyautogui.hotkey("ctrl", "shift", "backspace")
        time.sleep(1.0)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
