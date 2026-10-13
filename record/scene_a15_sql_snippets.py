# -*- coding: utf-8 -*-
"""A15 SQL 快捷模板（sql-snippets.gif）20261013 定稿。

素材：`mybatis-plus-join-demo/src/main/resources/db/schema.sql:59` `CREATE TABLE `car_device_info``
      —— 光标压在该表名标识符上；该表在**当前绑定库 `mpj_demo`** 里真实存在，
      所以三条模板都能执行成功（原计划的 `t_order` 在 `mp_demo`，用它必报 1146）。

成片一幕：光标压在表名上 → **鼠标右键**弹编辑器菜单 → 菜单**最后一项**是
「SQL 快捷模板 ▸」（`plugin.xml:472-475` 以 `anchor="last"` 挂到 `EditorPopupMenu`）→
悬停展开子菜单（EXPLAIN 选中表 / COUNT 选中表 / 抽样 10 行）→ 点「抽样 10 行」→
生成 `SELECT * FROM car_device_info LIMIT 10` 并在原生 Database 控制台执行出结果。

坐标标定（20261013，窗口 277,25 1370x984；scene 内按窗口实际 top 动态折算 dy）：
  表名标识符（L59，-3 档滚动后）  screen (557,177)   ← 右键点它（右键同时落光标＝菜单锚点）
  菜单末项「SQL 快捷模板」        screen (700,993)   ← 悬停即展开子菜单
  子菜单三项                      screen (1000, 925)EXPLAIN / (1000,962)COUNT / (1000,1000)抽样 10 行

本轮踩坑（前十二轮探针收敛，全记在案）：
  ① 菜单高约 814px 且**底边被屏幕夹住**（top = min(锚点 y, 1080-814≈266)）→
     锚点必须在 y≤195，末项才落进取景框；锚点是**光标行**（鼠标位置不影响，v8 反证）。
  ② 编辑器顶部约 40px 是**粘性行（sticky lines）**，右键全被它吃掉（会弹出
     Configure Sticky Lines… 菜单）→ 本场景依赖 prep 里已对 SQL 关掉粘性行。
  ③ `apps` 键开菜单时光标会漂（v7 状态栏 78:8）→ 改用**右键直接点在 token 上**，一次到位。
  ④ `pyautogui.scroll` 的合成滚轮 **IDE 不吃**，必须 PostMessage(WM_MOUSEWHEEL)（同 SQL 日志列表）。
  ⑤ 窗口上移 25px（top 25→0）后菜单/子菜单各多出 25px 余量，且取景框不再触到任务栏。
  ⑥ 文本输入一律走剪贴板 `gifkit.paste()`（typewrite 会被微软拼音组字吃掉）。
"""
import ctypes
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pyautogui  # noqa: E402

from gifkit import Recorder  # noqa: E402
from locate import close_preview, ensure_foreground, sandbox_pid, windows  # noqa: E402
from nav import force_en_us  # noqa: E402

OUT = "perf/a15_sql_snippets.mp4"
SEL_LINE, SEL_COL = 59, 15
SCROLL_NOTCHES = -19         # Ctrl+Home 钉首行=1 后滚 57 行 → 首行=58、表名行(L59)落在第二行 y≈144

# 屏幕坐标（窗口已移到 277,0 且高度改 1030；见 prep）
TOKEN = (557, 144)           # `car_device_info`（L59）
MENU_LAST = (700, 1003)      # 末项「SQL 快捷模板 ▸」
SUB_SAMPLE = (1000, 998)     # 子菜单「抽样 10 行」
GAZE = (1000, 800)           # 控制台出结果后的凝视点
PARK = (620, 700)


def post_wheel(hwnd, notches, sx=1000, sy=400):
    """PostMessage(WM_MOUSEWHEEL)：本环境唯一能让 IDE 滚动的机制。

    落点必须在**编辑器内**——Services 面板占了窗口下半，y=700 会落到面板上、滚不动。
    """
    user32 = ctypes.windll.user32
    lp = (sy << 16) | (sx & 0xFFFF)
    for _ in range(abs(notches)):
        delta = 120 if notches > 0 else -120
        user32.PostMessageW(hwnd, 0x020A, ((delta & 0xFFFF) << 16), lp)
        time.sleep(0.03)


def _unused_shift(pt, dy):
    return (pt[0], pt[1] + dy)


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    ide_pid = sandbox_pid()
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 500), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")

    # ---- prep（不入镜）----
    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.4)
    close_preview(ide_pid)

    # 窗口移到 277,0 并加高到 1030：菜单高约 859px（末项 y≈1003），984 高的取景框装不下，
    # 1030 正好装下且仍不触任务栏（任务栏顶 ≈1032）
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
    u32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(0), 277, 0, 1370, 1030, 0x0004)
    time.sleep(1.2)
    rect = next(r for h, p, t, r in windows() if h == hwnd)
    region = (rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
    print("窗口 rect=%s region=%s" % (rect, region))
    if rect[1] != 0 or rect[3] < 1030:
        raise RuntimeError("窗口未能移到 277,0 并加高到 1030（实际 %s）" % (rect,))

    # 打开 Services：模板执行走原生 Database 控制台，结果要在画面里
    # （快捷键 Alt+8；别点左侧工具窗条图标——那里 y=930 是 Git）
    pyautogui.hotkey('alt', '8'); time.sleep(1.8)

    # 光标压表名：Ctrl+Home 钉首行=1，再滚固定档数（1 档 = 3 行）把 L59 送到编辑区第二行。
    # 不能用「goto_col 居中 + 相对滚动」——Services 开合会改编辑器高度，居中点跟着漂
    # （v13 就是这样把右键落到了 L54 `VARCHAR` 上，子菜单三项全灰）。
    pyautogui.click(700, 300); time.sleep(0.5)
    pyautogui.hotkey('ctrl', 'home'); time.sleep(0.8)
    post_wheel(hwnd, SCROLL_NOTCHES)
    time.sleep(0.9)

    # ---- 录制 ----
    r = Recorder(region, OUT)
    r.start()
    time.sleep(0.6)
    pyautogui.click(*TOKEN, button='right'); time.sleep(1.6)     # 弹菜单（同时落光标）
    pyautogui.moveTo(*MENU_LAST, duration=0.6); time.sleep(1.8)  # 悬停末项 → 子菜单
    pyautogui.click(*SUB_SAMPLE); time.sleep(2.6)                # 点「抽样 10 行」
    time.sleep(2.4)                                              # 等控制台出结果
    pyautogui.moveTo(*GAZE, duration=0.7); time.sleep(1.6)
    pyautogui.moveTo(*PARK, duration=0.6); time.sleep(0.8)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
