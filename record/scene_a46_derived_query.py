# -*- coding: utf-8 -*-
"""★A46 派生查询深化（jpa-derived-query.gif）。

素材：`jpa-hibernate-demo` 的 `UserRepository.java`（demo 里已有全套派生方法，无需新增）。
成片三幕，各挑一个「形态差异明显」的派生方法开预览：
  ① `findTop10ByOrderByCreateTimeDesc`（L90）→ LIMIT；
  ② `findByUsernameLike`（L67）→ LIKE '%…%'；
  ③ `Page<UserRepository> findByDepartmentIdAndStatusOrderByCreateTimeDesc`（L119）→ 返回类型感知，
     出「count + 数据」两条 SQL。
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
sys.path.insert(0, HERE)

import pyautogui  # noqa: E402
import gifkit  # noqa: E402
from gifkit import Recorder  # noqa: E402
from nav import close_preview, force_en_us, goto_col, trigger_preview  # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a46_derived_query.mp4"

FILE = "UserRepository.java"
RESULT_ROW = (777, 342)
EDITOR_SPOT = (600, 500)

LINE_TOP, COL = 90, 22          # findTop10ByOrderByCreateTimeDesc
LINE_LIKE = 67                  # findByUsernameLike
LINE_PAGE = 119                 # Page<...> findByDepartmentIdAndStatus...


def set_clip(text):
    import tkinter
    r = tkinter.Tk()
    r.withdraw()
    r.clipboard_clear()
    r.clipboard_append(text)
    r.update()
    r.destroy()


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：开文件 + 光标压到第一个派生方法 ----
    close_preview()
    pyautogui.click(*EDITOR_SPOT); time.sleep(0.5)
    set_clip(FILE)
    pyautogui.hotkey("ctrl", "shift", "n"); time.sleep(1.4)
    pyautogui.hotkey("ctrl", "v"); time.sleep(1.6)
    pyautogui.press("enter"); time.sleep(1.4)
    pyautogui.press("enter"); time.sleep(2.4)
    set_clip("x")
    goto_col(LINE_TOP, COL)
    trigger_preview()

    r = Recorder(REGION, OUT)
    r.start()
    # ① Top10 → LIMIT
    pyautogui.moveTo(880, 430, duration=0.9); time.sleep(1.6)
    pyautogui.moveTo(880, 500, duration=0.8); time.sleep(1.4)
    # ② Like → LIKE '%…%'
    close_preview(wait=1.2)
    goto_col(LINE_LIKE, COL, wait=1.0)
    trigger_preview(wait=0.0); time.sleep(2.6)
    pyautogui.moveTo(880, 430, duration=0.8); time.sleep(1.4)
    # ③ Page → count + 数据 两条 SQL
    close_preview(wait=1.2)
    goto_col(LINE_PAGE, COL, wait=1.0)
    trigger_preview(wait=0.0); time.sleep(3.0)
    pyautogui.moveTo(880, 430, duration=0.8); time.sleep(1.2)
    pyautogui.moveTo(880, 620, duration=0.9); time.sleep(1.4)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
