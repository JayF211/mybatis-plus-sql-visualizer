# -*- coding: utf-8 -*-
"""A15 SQL 快捷模板（sql-snippets.gif）20261013 定稿 v3。

素材：`mybatis-plus-join-demo/src/main/resources/db/schema.sql`，绑定库 `mpj_demo@localhost`
      （Database 工具窗里 `mpj_demo` 已导入并选中）。

成片一幕：光标压在表名上 → **鼠标右键**弹编辑器菜单 → 菜单**最后一项**「SQL 快捷模板 ▸」
（`plugin.xml:472-475` 以 `anchor="last"` 挂到 `EditorPopupMenu`）→ 悬停展开子菜单
（EXPLAIN 选中表 / COUNT 选中表 / 抽样 10 行）→ 点「抽样 10 行」→ 生成
`SELECT * FROM car_device_info LIMIT 10` 并在原生 Database 控制台执行出结果。

## 末项 y **运行时找**（本轮返工的根因，别再写死）

v13 take 把末项写死成 (700,1003)，结果点到了**状态栏的路径**上，弹出同级文件
`schema-dm.sql`/`schema.sql` 的列表——那次菜单比标定时**多了一项**（≈41px），
末项实际在 y≈1046。菜单项数随上下文变（`Run 'schema.sql'`/`Attach Data Source` 这类会进出），
所以末项位置必须现场量。

量法：菜单右侧那条带（子菜单出现的位置）**开菜单前先取一张参照**，然后自下而上逐点悬停，
第一次「该带与参照不同」＝悬停到了**最下面那个带子菜单的项**＝末项「SQL 快捷模板」。
用「与开菜单前的参照比」而不是比颜色，是因为状态栏底色与菜单底色相同、纯颜色判据会误判
（实测：按底色扫会一路扫到状态栏里）。

## 为什么点 L58 注释里的表名（而不是 L59 的 CREATE TABLE）

菜单高约 **900px**（22 项 × ≈41px，实测自 take 帧），底边被**屏幕**夹住
（top = min(锚点 y, 1080-菜单高)）——锚点是**光标行**（鼠标位置不影响，v8 反证）。
取景框最多到 y≈1030（再往下是任务栏），所以 **锚点 ≤ 114**：菜单末项 ≈ 锚点 + 902。
编辑区首行 y≈114（上面是标签页行），故表名 token 必须落在**编辑区第一行**。
滚轮 1 档 = 3 行，从 Ctrl+Home 的首行=1 出发只有「行号 ≡1 (mod 3)」的行能当首行，
本文件里符合的只有 L58 注释行——它正好写着 `car_device_info`（注释里也是合法 token：
插件的光标扫描是纯词边界扫描，不认语言也不认注释）。L59 要当首行需滚 19.33 档，整数档做不到。

## 其它硬结论（前十四轮探针收敛，详见 probe_a15_menu*.py）

  · 编辑器顶部约 40px 是**粘性行**，右键会被它吃掉（弹它自己的菜单）→ 已对 SQL 关掉
  · `apps` 键开菜单时光标会漂（v7 状态栏 78:8）→ 改用右键直接点 token，一次到位
  · `pyautogui.scroll` 的合成滚轮 IDE 不吃，必须 PostMessage(WM_MOUSEWHEEL)；
    落点必须在编辑器内（Services 占了窗口下半时 y=700 会落到面板上滚不动）
  · 定位不要用「goto_col 居中 + 相对滚动」：Services 开合会改编辑器高度、居中点跟着漂
    （v13 take 因此把右键落到 L54 `VARCHAR`，子菜单三项全灰）→ 用 Ctrl+Home + 固定档数
  · 窗口要移到 277,0 并加高到 1030（984 高的取景框装不下菜单末项）
"""
import ctypes
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402
import pyautogui  # noqa: E402

from gifkit import Recorder  # noqa: E402
from locate import close_preview, ensure_foreground, sandbox_pid, windows  # noqa: E402
from nav import force_en_us  # noqa: E402

OUT = "perf/a15_sql_snippets.mp4"
SCROLL_NOTCHES = -19         # Ctrl+Home 钉首行=1 后滚 57 行 → 首行=58（表名 token 落在编辑区第一行）

ANCHOR = (790, 114)          # L58 注释里的 `car_device_info`（编辑区第一行）
WIN = (277, 0, 1370, 1030)   # 窗口：上移 25px + 加高到 1030（避开任务栏、装得下菜单）
ITEM_H = 41                  # 菜单单项高度（实测）

# 子菜单所在横带：菜单右边缘 ≈ 锚点 x+368，子菜单面板 ≈ 锚点 x+378..+588
SUB_CX = ANCHOR[0] + 460     # 子菜单文字带中心（也是「抽样 10 行」的点击 x）
SUB_DY = (-83, -43, -5)      # 子菜单三项相对末项中心的纵向偏移（实测：末项在最下）
GAZE = (1000, 700)
PARK = (620, 700)


def post_wheel(hwnd, notches, sx=1000, sy=400):
    """PostMessage(WM_MOUSEWHEEL)：本环境唯一能让 IDE 滚动的机制。"""
    user32 = ctypes.windll.user32
    lp = (sy << 16) | (sx & 0xFFFF)
    for _ in range(abs(notches)):
        delta = 120 if notches > 0 else -120
        user32.PostMessageW(hwnd, 0x020A, ((delta & 0xFFFF) << 16), lp)
        time.sleep(0.03)


def _grab(region):
    import mss
    with mss.mss() as sct:
        return np.asarray(sct.grab(region))[:, :, :3].astype(int)


def _band_ref():
    """开菜单前取「子菜单带」的参照（整列，逐 y 比对用）。"""
    return _grab({"left": SUB_CX - 60, "top": 0, "width": 120, "height": 1080})


def _band_changed(ref, y):
    """悬停 y 处的菜单项时，子菜单带是否出现了。

    子菜单三项在「末项中心 -83/-43/-5」，故取样带取 [y-100, y+20]（120px 高）。
    判据用**均值差**（实测：子菜单出现时 mean≈38，同图内噪声≈5；单点最大差在
    状态栏/工具窗底色与菜单底色接近处只有 14，逐点判据不稳）。
    """
    cur = _grab({"left": SUB_CX - 60, "top": _band_top(y), "width": 120, "height": 120})
    top = _band_top(y)
    return np.abs(cur - ref[top:top + 120]).mean() > 15


def _band_top(y):
    """取样带顶端：以 y-100 为基准，贴屏幕上下边时夹住（带高固定 120）。"""
    return max(0, min(y - 100, 1080 - 120))


def find_last_item(ref, limit_y):
    """自下而上找末项：第一次「子菜单带变了」＝菜单最下面那个带子菜单的项。

    两段扫：粗扫步长=项高（快），命中后在其下方一项内细扫（步长 8）取最靠下的命中点。
    起点压在屏幕底部（菜单底边最多到屏幕底，再往下没有菜单）。
    """
    def hit(y):
        pyautogui.moveTo(ANCHOR[0] + 60, y)
        time.sleep(0.24)
        return _band_changed(ref, y)

    coarse = None
    y = 1075
    while y > limit_y:
        if hit(y):
            coarse = y
            break
        y -= ITEM_H
    if coarse is None:
        raise RuntimeError("自下而上扫完也没等到子菜单出现——菜单没开？先看 shots/a15_pre.png")

    y = min(1075, coarse + ITEM_H)
    while y >= coarse - 2:
        if hit(y):
            return y
        y -= 8
    return coarse


def main():
    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    ide_pid = sandbox_pid()
    hwnd = next(h for h, p, t, r in windows() if p == ide_pid and (r[2] - r[0]) > 1200)
    if not ensure_foreground(hwnd, tries=6, click_xy=(700, 300), wait=0.7):
        raise RuntimeError("沙盒 IDE 未置前")

    # ---- prep（不入镜）----
    for _ in range(3):
        pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.4)
    close_preview(ide_pid)

    u32 = ctypes.WinDLL("user32", use_last_error=True)
    u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
    u32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(0), WIN[0], WIN[1], WIN[2], WIN[3], 0x0004)
    time.sleep(1.2)
    rect = next(r for h, p, t, r in windows() if h == hwnd)
    region = (rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
    print("窗口 rect=%s region=%s" % (rect, region))
    if rect[1] != 0 or rect[3] < WIN[3]:
        raise RuntimeError("窗口未能移到 277,0 并加高到 %d（实际 %s）" % (WIN[3], rect))

    # 光标压表名：Ctrl+Home 钉首行=1，再滚固定档数把 L58 送到编辑区第一行
    pyautogui.click(700, 300); time.sleep(0.5)
    pyautogui.hotkey('ctrl', 'home'); time.sleep(0.8)
    post_wheel(hwnd, SCROLL_NOTCHES)
    time.sleep(0.9)

    # ---- 开菜单、找末项（不入镜）----
    ref = _band_ref()
    pyautogui.click(*ANCHOR, button='right'); time.sleep(1.6)
    last_y = find_last_item(ref, limit_y=ANCHOR[1] + 40)
    print("末项 y=%d（取景框底 %d）" % (last_y, WIN[3]))
    import mss
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab({"left": 0, "top": 0, "width": 1920, "height": 1080}).rgb,
                         (1920, 1080), output="shots/a15_pre.png")
    if last_y > WIN[3] - 8:
        raise RuntimeError("末项 y=%d 超出取景框（%d）——锚点还得往上挪（看 shots/a15_pre.png）"
                           % (last_y, WIN[3]))
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.8)

    # ---- 录制 ----
    r = Recorder(region, OUT)
    r.start()
    time.sleep(0.6)
    pyautogui.click(*ANCHOR, button='right'); time.sleep(1.6)               # 弹菜单（同时落光标）
    pyautogui.moveTo(ANCHOR[0] + 60, last_y, duration=0.6); time.sleep(1.8)  # 悬停末项 → 子菜单
    pyautogui.click(SUB_CX, last_y + SUB_DY[2]); time.sleep(2.6)             # 点「抽样 10 行」
    time.sleep(2.4)                                                          # 等控制台出结果
    pyautogui.moveTo(*GAZE, duration=0.7); time.sleep(1.6)
    pyautogui.moveTo(*PARK, duration=0.6); time.sleep(0.8)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
