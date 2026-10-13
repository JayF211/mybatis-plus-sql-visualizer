# -*- coding: utf-8 -*-
"""A15 探针 v5：把表名滚到编辑区**最上一行**（菜单从点击点向下展开 → 末项 y≈点击+818，
子菜单又从末项**向上**展开 → 两者都落在取景框内）。

v2/v3/v4 结论：菜单高约 847px，被**屏幕**底边夹住（top=1080-847≈233，末项恒 y≈1050），
所以「点低处让菜单上翻」没用；必须**点高处**让菜单向下展开。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mss  # noqa: E402
import pyautogui  # noqa: E402

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

from locate import close_preview, ensure_foreground, sandbox_pid, windows  # noqa: E402
from nav import force_en_us, goto_col  # noqa: E402

NOTCHES = int(sys.argv[1]) if len(sys.argv) > 1 else 5
CROP = {"left": 277, "top": 100, "width": 620, "height": 910}   # 左半编辑区（行号 + 文本）


def main():
    ide_pid = sandbox_pid()
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    force_en_us()

    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.4)
    close_preview(ide_pid)
    pyautogui.click(1420, 908); time.sleep(0.5)

    pyautogui.click(600, 400); time.sleep(0.4)
    goto_col(59, 15)
    time.sleep(0.5)

    # 滚轮挪到编辑区右侧空白（x=1000 处无文字，不会触发悬停文档弹层）
    pyautogui.moveTo(1000, 700, duration=0.2); time.sleep(0.2)
    pyautogui.scroll(-NOTCHES)          # 负数 = 向下滚 → 内容上移 → 第 59 行往顶部走
    time.sleep(0.9)

    with mss.mss() as sct:
        sct.compact = False
        mss.tools.to_png(sct.grab(CROP).rgb, (CROP["width"], CROP["height"]),
                         output="shots/p5_crop_%d.png" % NOTCHES)
    print("notches=%d -> shots/p5_crop_%d.png" % (NOTCHES, NOTCHES))


if __name__ == "__main__":
    sys.exit(main())
