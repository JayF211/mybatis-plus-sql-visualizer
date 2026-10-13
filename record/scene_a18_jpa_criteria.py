# -*- coding: utf-8 -*-
"""A18 JPA Criteria 链预览（jpa-criteria.gif）20261012。

素材：`JpaQueryServiceImpl#criteriaGrouped`（jpa-hibernate-demo，L124）——CriteriaBuilder
      组合条件：`cb.like` / `cb.between` / `cb.in` / `cb.isNull` / `cb.not` + `cb.and/or`，
      终结在 `cq.where(...)`（L135）。
锚点：**`cq.where(...)` 调用行**（实测 L135 列 12 可唤起预览；`cb.like` 那类中间调用不认）。
成片一幕：take 内唤起预览（Criteria 代码 → SQL 同屏）→ 凝视 WHERE 段（like/between/in/isNull/not
      逐条落在 SQL 上）→ 凝视线上的 `#username/#ageStart/#ageEnd/#statuses` 参数框 → 收尾。

为什么把「唤起预览」放进 take：裸屏捕获不含光标，纯凝视 take 会被静段裁剪吃光；
本张要展示的正是「Criteria 调用行 → 预览出 SQL」这一步。

标定（20261012，IDE @ 277,25，弹窗 (367,166,1557,868)）：
  WHERE 段凝视      screen (900,600) / (900,700)
  左栏参数框凝视    screen (500,700)
  左栏空白收尾      screen (577,760)
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder                                                # noqa: E402
from locate import close_preview, ensure_foreground, sandbox_pid, windows   # noqa: E402
from nav import force_en_us, goto_col, trigger_preview                      # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a18_jpa_criteria.mp4"

FILE_QUERY = "JpaQueryServiceImpl.java"
FILE_TITLE_KEY = "JpaQueryServiceImpl"
CHAIN_LINE, CHAIN_COL = 135, 12          # cq.where(cb.and(...))
ROW_X = 600
FILE_ROW_YS = (341, 333, 349, 357)

GAZE_WHERE_A = (900, 600)
GAZE_WHERE_B = (900, 700)
GAZE_PARAMS = (500, 700)
PARK = (577, 760)


def ide_hwnd_and_title(pid):
    for h, p, t, r in windows():
        if p == pid and (r[2] - r[0]) > 1200:
            return h, t
    return None, ""


def main():
    import pyautogui

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    ide_pid = sandbox_pid()
    hwnd, _ = ide_hwnd_and_title(ide_pid)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")
    print("沙盒 pid=%s 已置前" % ide_pid)

    # ---- prep（不入镜）：开文件 + 光标压到 cq.where(...)，预览留给 take 内唤起 ----
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.7)
    print("关旧预览:", close_preview(ide_pid))
    pyautogui.click(600, 500); time.sleep(0.5)
    pyautogui.hotkey("ctrl", "shift", "n"); time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.4)
    force_en_us()
    pyautogui.typewrite(FILE_QUERY, interval=0.05); time.sleep(1.8)
    for y in FILE_ROW_YS:
        pyautogui.click(ROW_X, y); time.sleep(1.3)
        if FILE_TITLE_KEY in ide_hwnd_and_title(ide_pid)[1]:
            break
    time.sleep(1.6)
    t1 = ide_hwnd_and_title(ide_pid)[1]
    if FILE_TITLE_KEY not in t1:
        raise RuntimeError("开文件失败：标题仍为 %s" % t1[:70])
    print("开文件后标题 = %s" % t1[:62])
    goto_col(CHAIN_LINE, CHAIN_COL)
    pyautogui.moveTo(*PARK, duration=0.4); time.sleep(0.6)

    # ---- 录制 ----
    r = Recorder(REGION, OUT)
    r.start()
    pyautogui.moveTo(900, 500, duration=0.5); time.sleep(0.4)
    trigger_preview(wait=1.2)               # Criteria 调用 → 预览出 SQL（真实 UI 变化）
    time.sleep(1.8)
    pyautogui.moveTo(*GAZE_WHERE_A, duration=0.7); time.sleep(1.1)
    pyautogui.moveTo(*GAZE_WHERE_B, duration=0.7); time.sleep(1.1)
    pyautogui.moveTo(*GAZE_PARAMS, duration=0.7); time.sleep(1.1)
    pyautogui.moveTo(*GAZE_WHERE_A, duration=0.7); time.sleep(1.1)
    pyautogui.moveTo(*PARK, duration=0.6); time.sleep(0.8)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
