# -*- coding: utf-8 -*-
"""A27 捕获诊断与授权（capture-diagnostics.gif）20261013。

素材：插件工具窗（`cn.song.mybatisplus.visualizer.log`，右侧停靠）的 **SQL 日志页签空态**：
      `暂无可捕获的 SQL。` + 三档下一步（点「捕捉」并运行带 SQL 日志的控制台 / 从「日志转 SQL」
      页签手动粘贴还原 / 设置页开启增强捕获以拿参数实参与精确耗时）+ 末尾
      「打开设置（定位到增强捕获）」链接。
成片一幕：空态指引凝视 → 点「打开设置（定位到增强捕获）」→ 设置页**跳到增强捕获分组**
      （状态行/开关入镜，即 A27 的另一半）→ 收尾。

前置：**工具窗需已打开**（右侧停靠、SQL 日志页签）。它是主窗口的一部分、不是独立窗口，
无法用 EnumWindows 校验；本场景假定已开（若关掉了，先手动/动作打开再录）。

标定（20261013，IDE @ 277,25）：
  空态指引文本   screen (1150,300)
  「打开设置（定位到增强捕获）」链接  screen (1390,562)   ← 实测 region 1030-1190, y 537
  设置对话框内容 screen (1000,400)
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder                                                # noqa: E402
from locate import ensure_foreground, sandbox_pid, windows                  # noqa: E402
from nav import force_en_us                                                 # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a27_capture_diagnostics.mp4"

GAZE_EMPTY = (1150, 300)
LINK_SETTINGS = (1390, 562)   # 实测：链接文字在 region 1030-1190, y 537
GAZE_SETTINGS = (1000, 400)
PARK = (600, 700)


def main():
    import pyautogui
    pyautogui.FAILSAFE = False; pyautogui.PAUSE = 0
    force_en_us()

    ide_pid = sandbox_pid()
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    print("沙盒 pid=%s 已置前" % ide_pid)

    # prep（不入镜）：清掉可能开着的弹窗/预览
    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.5)

    r = Recorder(REGION, OUT)
    r.start()
    pyautogui.moveTo(*GAZE_EMPTY, duration=0.7); time.sleep(1.6)   # 空态指引
    pyautogui.click(*LINK_SETTINGS); time.sleep(2.5)               # 点「打开设置（定位到增强捕获）」
    pyautogui.moveTo(*GAZE_SETTINGS, duration=0.8); time.sleep(1.8)  # 设置页增强捕获分组
    pyautogui.moveTo(1000, 500, duration=0.6); time.sleep(1.2)
    pyautogui.moveTo(*PARK, duration=0.6); time.sleep(0.8)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
