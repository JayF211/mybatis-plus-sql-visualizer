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
import subprocess
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
SCROLL_NOTCHES = -18         # Ctrl+Home 钉首行=1 后滚 54 行 → 首行=55，表名行(L58)落在编辑区第 4 行。
#                              刻意不放第一行：锚点低一点（y≈165）菜单末项就到 y≈979，
#                              子菜单下方放不下 → **向上展开**，三项全在取景框内
#                              （锚点在 75 时菜单末项 889、子菜单向下展开，第三项 1054 出框）

ANCHOR = (790, 175)          # L58 注释里的 `car_device_info`（编辑区第一行；窗口标准位置）
WIN = (277, 25, 1370, 984)   # 标准几何（不推窗口出屏——用户要求 IDE 顶部完整入镜）
#                              锚点在第一行文字 y≈175 时，菜单末项 ≈175+814=989，取景框底 1009 ✓；
#                              子菜单下方放不下 → 向上展开，三项在 y≈891/924/957 ✓ 全在框内
ITEM_H = 41                  # 菜单单项高度（实测，仅注释用；扫描不依赖它）

# 子菜单所在横带：菜单右边缘 ≈ 锚点 x+368，子菜单面板 ≈ 锚点 x+378..+588
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


def chrome_hwnd():
    """找 Chrome 主窗口（类名 Chrome_WidgetWin_1）——录制的白色新标签页背景。"""
    u32 = ctypes.windll.user32
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _l):
        buf = ctypes.create_unicode_buffer(64)
        u32.GetClassNameW(h, buf, 64)
        if buf.value == "Chrome_WidgetWin_1" and u32.IsWindowVisible(h):
            r = ctypes.wintypes.RECT()
            u32.GetWindowRect(h, ctypes.byref(r))
            if r.right - r.left > 800:
                found.append(h)
        return True

    u32.EnumWindows(cb, 0)
    return found[0] if found else 0


def restore_backdrop(ide_hwnd):
    """把 Chrome（空白新标签页）恢复成全屏当背景，再把 IDE 抬到它前面。

    用户 2026-10-13 要求：录制背景用 Chrome 白页，避免桌面太乱干扰画面。
    """
    ch = chrome_hwnd()
    if not ch:
        print("⚠️ 没找到 Chrome 窗口，背景未恢复")
        return
    # 用户要求背景是 Chrome 的**白色新标签页**：先给 Chrome 开一个空白新标签，再最大化垫到 IDE 后面
    # SetForegroundWindow 会被前台锁拦下 → 走 AttachThreadInput（同 restore_chrome_front_ide.py）。
    # 不确认拿到焦点就按 Ctrl+T，会把新标签开进 IDE 里。
    u32 = ctypes.windll.user32
    k32 = ctypes.windll.kernel32
    fg = u32.GetForegroundWindow()
    fg_thread = u32.GetWindowThreadProcessId(ctypes.c_void_p(fg), None)
    my_thread = k32.GetCurrentThreadId()
    u32.AttachThreadInput(my_thread, fg_thread, True)
    u32.SetForegroundWindow(ctypes.c_void_p(ch))
    u32.AttachThreadInput(my_thread, fg_thread, False)
    time.sleep(0.8)
    if u32.GetForegroundWindow() != ch:
        print("⚠️ Chrome 未拿到焦点（fg=%s），跳过开新标签" % u32.GetForegroundWindow())
    else:
        pyautogui.hotkey('ctrl', 't')
        time.sleep(1.5)
    here = os.path.dirname(os.path.abspath(__file__))
    subprocess.run([sys.executable, os.path.join(here, "restore_chrome_front_ide.py"),
                    str(ide_hwnd), str(ch)], cwd=here, timeout=90)
    time.sleep(0.6)


def _grab(region):
    import mss
    with mss.mss() as sct:
        return np.asarray(sct.grab(region))[:, :, :3].astype(int)


SCAN_YMAX = 1020             # 扫描上限：再往下是任务栏，鼠标经过会把图标点亮 → 假变化
REF_X0 = ANCHOR[0] - 100       # 参照带起点（含菜单本身 + 右侧子菜单可能落位）
REF_W = 900


def _band_ref():
    """开菜单前取一条宽参照（含菜单本体与其右侧），后面按行/按列比对都用它。"""
    return _grab({"left": REF_X0, "top": 0, "width": REF_W, "height": 1080})


def _menu_right(ref):
    """量菜单面板的右边缘（列方向与参照不同的最大列）。"""
    row = _grab({"left": REF_X0, "top": ANCHOR[1] + 6, "width": REF_W, "height": 6})
    cols = np.abs(row - ref[ANCHOR[1] + 6:ANCHOR[1] + 12]).mean(axis=(0, 2))
    xs = np.where(cols > 8)[0]
    return REF_X0 + int(xs.max()) if len(xs) else ANCHOR[0] + 380


def _strip_x(menu_right):
    """取样带左端：必须**严格在菜单右边缘之外**（贴太近会把菜单本体算成「变化」，
    整条菜单高度都成了变化行 → 聚簇出来的「最后一项」落在菜单底部而非子菜单项上）。"""
    return int(menu_right + 12)


def _band_changed(ref, y, band_x):
    """悬停 y 处的菜单项时，子菜单带（子菜单面板所在横带）是否出现。

    **悬停后要等够**：子菜单渲染约 0.3~0.5s，只等 0.24s 会漏检——上一轮就是这样跳过末项、
    停在上面的 `Git` 上，点下去弹出了 Clone Repository 对话框。
    判据用**均值差**且阈值压到 8：实测末项子菜单带 mean≈13、同图噪声 2~5
    （逐点最大差判据不稳：状态栏/工具窗底色与菜单底色接近处只有 14）。
    """
    x = _strip_x(band_x)
    top = max(0, min(y - 60, 1080 - 120))          # 贴屏幕上下边时夹住（带高固定 120）
    cur = _grab({"left": x, "top": top, "width": 200, "height": 120})
    return np.abs(cur - ref[top:top + 120, x - REF_X0:x - REF_X0 + 200]).mean() > 8


def _row_changed(ref, y, band_x):
    x = _strip_x(band_x)
    y = max(0, min(y, 1080 - 6))
    cur = _grab({"left": x, "top": y, "width": 200, "height": 6})
    return np.abs(cur - ref[y:y + 6, x - REF_X0:x - REF_X0 + 200]).mean() > 8


def sub_item3_y(ref, parent_y, band_x):
    """悬停末项后，量子菜单**第三项**（抽样 10 行）的中心 y。

    子菜单相对末项向上还是向下展开，取决于下方还有没有空间（两种都实测出现过），
    所以位置必须现场量。做法：把带内「与参照不同」的行按间隙聚簇，最后一簇＝第三项文字行。
    """
    ys = [y for y in range(max(0, parent_y - 150), min(SCAN_YMAX, parent_y + 170), 5)
          if _row_changed(ref, y, band_x)]
    clusters = []
    for y in ys:
        if clusters and y - clusters[-1][-1] <= 20:
            clusters[-1].append(y)
        else:
            clusters.append([y])
    if not clusters:
        raise RuntimeError("悬停末项后没量到子菜单面板")
    return sum(clusters[-1]) / len(clusters[-1])


def find_last_item(ref, band_x, limit_y):
    """自下而上找末项：第一次「子菜单带变了」＝菜单最下面那个带子菜单的项。

    步长必须细（10px）：项高 ≈33~41px，粗步长会整格跳过末项
    （实测末项盒 914..950，从 1075 起按 41 步会落到 911 → 命中上一项 `Diagrams`）。
    返回末项**中心** y（命中点是末项下沿，减半项高）。
    """
    y = SCAN_YMAX
    while y > limit_y:
        pyautogui.moveTo(ANCHOR[0] + 60, y)
        time.sleep(0.55)
        if _band_changed(ref, y, band_x):
            return y - 16
        y -= 10
    raise RuntimeError("自下而上扫完也没等到子菜单出现——菜单没开？先看 shots/a15_pre.png")


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

    # 模态对话框护栏：沙盒里若还挂着别的窗口（Clone Repository / Settings / Commit…），
    # 整段 take 都会录进那个对话框——上一轮就是这样白录一条（Esc 关不掉模态框，
    # 得点它的 Cancel）。宁可在这里报错，也不带病出片。
    others = [t for h, p, t, r in windows() if p == ide_pid and h != hwnd]
    if others:
        raise RuntimeError("沙盒里还挂着模态窗口 %s ——先手工关掉（点它的 Cancel）再录" % others)

    close_preview(ide_pid)

    restore_backdrop(hwnd)          # Chrome 白页当背景（用户要求）

    u32 = ctypes.WinDLL("user32", use_last_error=True)
    u32.SetWindowPos.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
    u32.SetWindowPos(ctypes.c_void_p(hwnd), ctypes.c_void_p(0), WIN[0], WIN[1], WIN[2], WIN[3], 0x0004)
    time.sleep(1.2)
    rect = next(r for h, p, t, r in windows() if h == hwnd)
    top_clip = max(0, rect[1])
    bottom_clip = min(1080, rect[3])
    # 录制器要求宽高为偶数（否则 gifkit assert 挂）
    region = (rect[0], top_clip, rect[2] - rect[0], (bottom_clip - top_clip) // 2 * 2)
    global SCAN_YMAX
    SCAN_YMAX = min(1005, bottom_clip - 4)
    print("窗口 rect=%s region=%s 扫描上限=%d" % (rect, region, SCAN_YMAX))
    if rect[1] < 0 or rect[3] < 1000:
        raise RuntimeError("窗口位置不对（要求顶部完整、底边 >=1000，实际 %s）" % (rect,))

    # 光标压表名：Ctrl+Home 钉首行=1，再滚固定档数把 L58 送到编辑区第一行
    pyautogui.click(700, 300); time.sleep(0.5)
    pyautogui.hotkey('ctrl', 'home'); time.sleep(0.8)
    post_wheel(hwnd, SCROLL_NOTCHES)
    time.sleep(0.9)

    # ---- 开菜单、量末项与子菜单（不入镜）----
    ref = _band_ref()
    pyautogui.click(*ANCHOR, button='right'); time.sleep(1.6)
    menu_right = _menu_right(ref)
    band_x = menu_right            # 取样带：菜单右边缘之外
    click_x = menu_right + 90      # 点击 x：落在子菜单文字上
    last_y = find_last_item(ref, band_x, limit_y=ANCHOR[1] + 40)
    pyautogui.moveTo(ANCHOR[0] + 60, last_y, duration=0.5); time.sleep(1.5)   # 悬停末项 → 展开子菜单
    sub3_y = sub_item3_y(ref, last_y, band_x)
    print("菜单右边缘 x=%d（取样带起点 %d，点击 x %d）" % (menu_right, band_x + 12, click_x))
    print("末项 y=%.0f  子菜单第三项(抽样 10 行) y=%.0f  取景框底 %d" % (last_y, sub3_y, bottom_clip))
    import mss
    with mss.mss() as sct:
        mss.tools.to_png(sct.grab({"left": 0, "top": 0, "width": 1920, "height": 1080}).rgb,
                         (1920, 1080), output="shots/a15_pre.png")
    if last_y > bottom_clip - 8 or sub3_y > bottom_clip - 8:
        raise RuntimeError("末项 y=%.0f / 子菜单项 y=%.0f 超出取景框（底 %d）——锚点还得往上挪"
                           "（看 shots/a15_pre.png）" % (last_y, sub3_y, bottom_clip))
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.8)

    # ---- 录制 ----
    r = Recorder(region, OUT)
    r.start()
    time.sleep(0.6)
    pyautogui.click(*ANCHOR, button='right'); time.sleep(1.6)               # 弹菜单（同时落光标）
    pyautogui.moveTo(ANCHOR[0] + 60, last_y, duration=0.6); time.sleep(1.8)  # 悬停末项 → 子菜单
    pyautogui.click(click_x, sub3_y); time.sleep(2.6)                        # 点子菜单第三项（抽样 10 行）
    time.sleep(2.4)                                                          # 等控制台出结果
    pyautogui.moveTo(*GAZE, duration=0.7); time.sleep(1.6)
    pyautogui.moveTo(*PARK, duration=0.6); time.sleep(0.8)
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
