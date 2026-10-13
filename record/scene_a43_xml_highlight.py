# -*- coding: utf-8 -*-
"""A43 mapper XML 语法高亮注入（xml-syntax-highlight.gif）。

素材：`mybatis-plus-demo/src/main/resources/mapper/OrderMapper.xml`（含 `<select>` / `${ew.customSqlSegment}` /
`#{orderNo}` / `<if test=…>` 等），插件给 mapper XML 注入的关键字 + 参数占位着色（总开关在
设置 → UI and Actions「mapper XML 语法高亮」）。

成片：打开该 XML → 从上往下滚过几条语句，展示关键字与 `#{}`/`${}` 占位的着色。
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
from locate import sandbox_pid, windows, ensure_foreground  # noqa: E402
from nav import force_en_us  # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a43_xml_highlight.mp4"


def set_clip(text):
    import tkinter
    r = tkinter.Tk()
    r.withdraw()
    r.clipboard_clear()
    r.clipboard_append(text)
    r.update()
    r.destroy()


def wheel(hwnd, notches, sx=700, sy=400):
    u32 = ctypes.windll.user32
    lp = (sy << 16) | (sx & 0xFFFF)
    for _ in range(abs(notches)):
        d = 120 if notches > 0 else -120
        u32.PostMessageW(hwnd, 0x020A, ((d & 0xFFFF) << 16), lp)
        time.sleep(0.03)


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    p = sandbox_pid()
    main_h = next(h for h, pp, t, r in windows() if pp == p and (r[2] - r[0]) > 1200)
    if not ensure_foreground(main_h, tries=8, click_xy=(700, 300), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")

    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.35)

    # 打开 OrderMapper.xml（剪贴板粘贴，绕开中文 IME）
    set_clip("OrderMapper.xml")
    pyautogui.hotkey('ctrl', 'shift', 'n')
    time.sleep(1.3)
    pyautogui.hotkey('ctrl', 'v')
    time.sleep(1.6)
    pyautogui.press('enter')
    time.sleep(1.3)
    pyautogui.press('enter')
    time.sleep(2.2)
    set_clip("x")

    t = next((x for h, pp, x, r in windows() if pp == p and (r[2] - r[0]) > 1200), '')
    if 'OrderMapper.xml' not in t:
        raise RuntimeError("未打开 OrderMapper.xml：%s" % t[:70])

    pyautogui.click(700, 300); time.sleep(0.5)
    pyautogui.hotkey('ctrl', 'home'); time.sleep(0.9)

    r = Recorder(REGION, OUT)
    r.start()
    time.sleep(1.0)
    wheel(main_h, -6); time.sleep(1.4)     # 滚过第 1~2 条语句
    wheel(main_h, -6); time.sleep(1.6)     # 滚到 where/if 那条
    wheel(main_h, -5); time.sleep(1.4)     # 再往下
    pyautogui.moveTo(1150, 700, duration=0.7); time.sleep(1.2)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
