# -*- coding: utf-8 -*-
"""A13 执行 SQL（临时 .sql + 结果）20261012 v5。

v1~v4 的返工各自暴露一个坑，全部已固化进 locate/verify 标准件：
  ① 弹窗按钮行**目测**写死偏低 48px（连点三次落空）→ `bright_band_y` 实测；
  ② 按钮 x 写死（右对齐按钮随弹窗宽度变）→ `bright_band_x` 取亮簇；
  ③ `window_rect(pid=None)` 会匹配到**别人的同尺寸窗口**（撞上 ZCode 的 ZCodeTpsFooter）→ 限定沙盒 PID；
  ④ **IDE 不在前台 → 键盘快捷键送不到**（开文件无反应、预览不出，且不报错）→ `ensure_foreground` 置顶+取焦+校验。

素材：`UserServiceImpl#selectJoinList`（mpjdemo，`user` LEFT JOIN `user_address` RIGHT JOIN `area`）。
它正是当初报 `[42000][1064] near '"user"'` 的那条——IDE 数据源补上 ANSI_QUOTES 后应能跑通，
**这张片子本身就是那个修复的验证**。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import Recorder                                              # noqa: E402
from locate import (bright_band_x, bright_band_y, ensure_foreground,     # noqa: E402
                    grab as locate_grab, pid_by_cmdline, window_rect, windows)
from nav import force_en_us, goto_col, trigger_preview                   # noqa: E402
from verify import Guard                                                 # noqa: E402

REGION = (277, 25, 1370, 984)
OUT = "perf/a13_execute_sql.mp4"

FILE_QUERY = "mpjdemo/UserServiceImpl.java"
FILE_TITLE_KEY = "UserServiceImpl"
CHAIN_LINE, CHAIN_COL = 49, 18          # .like(AddressDO::getTel, "1")

POP_CANCEL = (1489, 781)
ROW_X = 777                    # 结果行点击的 x（弹窗左半，落在首条结果文字上）
ROW_TEXT_X = (290, 560)        # 首条结果文字所在列带（剖面判据用）
ROW_Y_RANGE = (290, 420)       # 结果列表所在行带（查询框下方）
MAVEN_HIDE = (1622, 103)       # Maven 工具窗面板头部的「—」

OVERLAY_WATCH = (1290, 520, 60, 200)    # 预览右缘深色边距（浅底浮层盖上来会明显变亮）
CLICK_WATCH = (740, 620, 500, 190)      # 点「执行 SQL」后会长出结果网格的地方
PARK = (577, 725)
GAZE_SQL = (1100, 500)


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
    g = Guard()

    # ---- 前置：沙盒 PID + 前台（键盘输入的前提）----
    ide_pid = pid_by_cmdline("MPSV-GifFresh")
    if ide_pid is None:
        raise RuntimeError("未找到沙盒 IDE 进程（命令行应含 MPSV-GifFresh）")
    hwnd, title0 = ide_hwnd_and_title(ide_pid)
    if not ensure_foreground(hwnd):
        raise RuntimeError("无法把沙盒 IDE 弄到前台（当前前台句柄 != %s）——键盘快捷键会送不到" % hwnd)
    print("沙盒 pid=%s hwnd=%s 已置前，起始标题=%s" % (ide_pid, hwnd, title0[:60]))

    # ---- prep（不入镜）----
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.8)
    pyautogui.click(*POP_CANCEL); time.sleep(1.0)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.5)
    pyautogui.click(*MAVEN_HIDE); time.sleep(1.2)      # 收起 Maven 工具窗，恢复录制版式
    pyautogui.click(600, 500); time.sleep(0.5)

    pyautogui.hotkey("ctrl", "shift", "n"); time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.5)
    force_en_us()
    pyautogui.typewrite(FILE_QUERY, interval=0.05); time.sleep(1.8)
    # **键盘路径开文件**（放弃点结果行）：`↓` 把选中落到结果列表首行 → Return。
    # 坐标这条路我调了三轮（行带剖面 / 连点两次 / 落点上移）都不稳：Go-to-File 弹窗位置随会话变，
    # 且剖面会把查询框与首条结果并成一段，落点差几像素就擦空。键盘路径与坐标无关；
    # 本轮首条结果正是目标文件（抓帧确认过）。Return 用 keyDown/keyUp——JBR 会吞 press('enter')（坑表 7）。
    pyautogui.press("down"); time.sleep(0.6)
    pyautogui.keyDown("return"); pyautogui.keyUp("return"); time.sleep(2.6)
    # 用窗口标题核对目标文件真的打开了（标题形如 '… – UserServiceImpl.java [module]'）
    _, title1 = ide_hwnd_and_title(ide_pid)
    if FILE_TITLE_KEY not in title1:
        raise RuntimeError("目标文件未打开（标题仍是 %s）——开文件这一步没生效" % title1[:70])
    print("目标文件已打开：", title1[:70])

    pyautogui.moveTo(300, 300, duration=0.3); time.sleep(0.5)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.8)
    goto_col(CHAIN_LINE, CHAIN_COL)
    trigger_preview()
    pyautogui.moveTo(*PARK, duration=0.4); time.sleep(1.0)

    # ---- 开录前自检：预览右缘必须是深色（浅底文档浮层盖上来会变亮）----
    mean = float(locate_grab(OVERLAY_WATCH).mean())
    print("预览右缘灰度均值 = %.1f（干净态 ~26）" % mean)
    if mean > 60:
        raise AssertionError("开录前画面不干净：预览右缘变亮（%.1f）——文档浮层可能盖住了预览" % mean)

    # ---- 定位按钮：y 用剖面找按钮行，x 取该行最左亮簇 ----
    main_rect = window_rect(pid=ide_pid, pick="largest")
    popup = window_rect(pid=ide_pid, pick="smallest", min_size=(600, 400))
    if popup is None or popup == main_rect:
        raise RuntimeError("预览弹窗未打开（限定 PID 后只匹配到主窗口 %s）" % (main_rect,))
    pw, ph = popup[2] - popup[0], popup[3] - popup[1]
    img = locate_grab((popup[0], popup[1], pw, ph))
    rx0 = int(pw * 0.45)
    band = bright_band_y(img, rx0, pw, 0, ph, thr=45.0)
    if band is None:
        raise RuntimeError("按钮行未找到（右半幅无亮行带）")
    row0, row1 = band
    clusters = bright_band_x(img, max(0, row0 - 4), min(ph, row1 + 5), rx0, pw,
                             thr=45.0, min_gap=14, min_w=8)
    if not clusters:
        raise RuntimeError("按钮行上未找到亮簇")
    btn = (popup[0] + (clusters[0][0] + clusters[0][1]) // 2, popup[1] + (row0 + row1) // 2)
    print("预览弹窗=%s(%dx%d) 按钮行相对 y=%s 亮簇=%s → 点最左簇=%s"
          % (popup, pw, ph, (row0, row1), clusters[:4], btn))

    # ---- 录制 ----
    r = Recorder(REGION, OUT)
    r.start()
    pyautogui.moveTo(*GAZE_SQL, duration=0.6); time.sleep(1.2)
    g.after_click(btn, region=CLICK_WATCH, wait=1.2, retries=1,
                  msg="点『执行 SQL』后预览下半部无变化（落空）")
    time.sleep(2.5)
    time.sleep(10.0)
    pyautogui.moveTo(*PARK, duration=0.8); time.sleep(1.2)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
