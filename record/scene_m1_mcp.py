# -*- coding: utf-8 -*-
"""M1 MCP 动图（20260919-1500 计划 §五 M1）：插件门控页 + 平台注册面 + 逐工具门控。

成品：public/demo/mcp.gif + mcp.png（首页第七卡），1280 宽 / 15fps。

分镜（录制段从「已就位的设置对话框」开始，省掉开窗与寻路，见 §录制段设计）：
1. 平台 Exposed Tools 页（已按 sqlv_ 过滤）静置 → 十个 Sql Visualizer* 分组全列
2. 关掉首个工具的 Enabled → 该行变灰（平台侧逐工具门控）
3. 打开回去
4. 左树点 MCP Tools → 插件门控页，滚到底（总开关 + 十项 + 全部禁用按钮）
5. 点「全部禁用」→ 十项全灰、按钮切「全部启用」、标题栏出现 Revert changes
6. 点「全部启用」→ 十项恢复
7. 左树点 Exposed Tools → 回到起点页，首尾帧一致

## 环境前提（prep 做掉，不进 GIF）

- 沙盒工程 = mybatis-plus-sql-visualizer-demo（`_find_ide_pid` 按窗口标题判定）
- 设置对话框尺寸固定 1125x875、不可缩放；MCP Tools 页内容比视口高约 46px，
  故必须滚到底——滚到底后总开关/十项/全部禁用按钮完整可见，仅页顶「点击前往」行被裁。
  **滚动位置不保证跨页面访问持久**，故每次都显式滚到底（拖滑块过轨道末端，钳位确定）。
- 左侧树由设置搜索框内容过滤；**该框内容同样不保证持久**，故每次显式设定。

## 两处沙盒输入限制（实测，已绕开）

1. **插件页的逐项复选框对合成点击基本不响应**：同一坐标点阵扫描 0/9 命中，
   而按钮、左树、输入框均可靠；平台页同语义的 Enabled 复选框 4/4 可靠。
   → 门控粒度这一拍落在平台页（分镜 2/3），插件页只用可靠的按钮（分镜 5/6）。
2. **总开关一关，十个工具复选框整体转禁用态**：调试期误点过它，之后整轮点击全部无效
   （表现为「点不动」）。→ prep 归一总开关，并在录制段复核它没被误动。

其余沿用 scene_t9_crudsql.py 的教训：合成点击落在未聚焦子窗口时首击会被「点击聚焦」
吞掉 → 点对话框内控件前先对标题栏做一次激活点击；每个关键状态出现后做像素自检。
"""
import sys
import time

import numpy as np
from PIL import Image as _Image

from gifkit import Recorder, click, move, paste, shot

REGION = (277, 25, 1370, 984)
OUT = "perf/m1_mcp.mp4"

# —— 对话框内坐标（region 相对，已标定）——
TITLE = (600, 76)               # 对话框标题栏（吃掉首击用）
SEARCH_SETTINGS = (261, 134)    # 设置搜索框（左树过滤源）
SEARCH_FILTER = "MCP"           # 过滤后左树呈紧凑四行（Exposed Tools / MCP Tools 相邻）
TREE_EXPOSED = (243, 416)       # 左树 Exposed Tools 行（仅在上述过滤态成立）
TREE_MCPTOOLS = (243, 479)      # 左树 MCP Tools 行（仅在上述过滤态成立）
SCROLL_FROM = (1230, 500)       # 页面滚动条滑块上一点
SCROLL_TO = (1230, 950)         # 拖过轨道末端 → 钳到最底（确定性）
BTN_ALL = (505, 805)            # 全部禁用 / 全部启用（同一位，文本随状态切换）
MASTER = (461, 205)             # 总开关
TOOL_X = 461                    # 十个工具复选框列（滚到底后的行位，步距 55）
TOOL_Y = [260, 315, 370, 425, 480, 535, 590, 645, 700, 755]
Y_EXPLAIN = TOOL_Y[2]           # sqlv_explain
SEARCH_TOOLS = (880, 345)       # 平台页 Search tools 输入框
FILTER = "sqlv_"                # 平台页过滤词
PLATFORM_ENABLED_X = 985        # 平台页 Enabled 复选框列
PLATFORM_FIRST_ROW_Y = 462      # 过滤 sqlv_ 后第一个工具行的 Enabled 复选框
NEUTRAL_CURSOR = (1000, 600)    # 起始/结束光标位（首尾帧一致用）

# 勾选框像素带（region 相对；下界 245 恰好排除 y=205 的总开关）
BOX_BAND = (452, 472, 245, 785)
MASTER_BAND = (452, 472, 194, 217)   # 仅总开关那一行


def _find_ide_pid():
    """按主窗口标题自动探测沙盒 IDE 进程 id。"""
    import ctypes
    import ctypes.wintypes as wt
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def cb(h, l):
        if u32.IsWindowVisible(h):
            n = ctypes.create_unicode_buffer(256)
            u32.GetWindowTextW(h, n, 256)
            c = ctypes.create_unicode_buffer(256)
            u32.GetClassNameW(h, c, 256)
            if "SunAwtFrame" in c.value and "mybatis-plus-sql-visualizer-demo" in n.value:
                pid = wt.DWORD()
                u32.GetWindowThreadProcessId(h, ctypes.byref(pid))
                found.append(pid.value)
        return True
    u32.EnumWindows(cb, 0)
    if not found:
        raise RuntimeError("sandbox IDE window not found")
    return found[0]


IDE_PID = _find_ide_pid()


def _hwnd(substr, cls):
    """按标题片段 + 窗口类找可见顶层窗，返回 hwnd（找不到返回 None）。"""
    import ctypes
    import ctypes.wintypes as wt
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def cb(h, l):
        if u32.IsWindowVisible(h):
            n = ctypes.create_unicode_buffer(300)
            u32.GetWindowTextW(h, n, 300)
            c = ctypes.create_unicode_buffer(128)
            u32.GetClassNameW(h, c, 128)
            pid = wt.DWORD()
            u32.GetWindowThreadProcessId(h, ctypes.byref(pid))
            if pid.value == IDE_PID and cls in c.value and substr in n.value:
                found.append(h)
        return True
    u32.EnumWindows(cb, 0)
    return found[0] if found else None


def _is_foreground(hwnd):
    import ctypes
    return ctypes.windll.user32.GetForegroundWindow() == hwnd


def focus(hwnd):
    """把指定顶层窗提到前台（线程输入挂接，绕开前台锁定）。已在前台则直接返回。"""
    import ctypes
    if _is_foreground(hwnd):
        return
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    tid_fg = u32.GetWindowThreadProcessId(u32.GetForegroundWindow(), None)
    tid_tg = u32.GetWindowThreadProcessId(hwnd, None)
    cur = k32.GetCurrentThreadId()
    u32.AttachThreadInput(cur, tid_fg, True)
    u32.AttachThreadInput(cur, tid_tg, True)
    u32.BringWindowToTop(hwnd)
    u32.SetForegroundWindow(hwnd)
    u32.AttachThreadInput(cur, tid_fg, False)
    u32.AttachThreadInput(cur, tid_tg, False)
    time.sleep(0.2)


def grab():
    import mss
    with mss.mss() as s:
        mon = {"left": REGION[0], "top": REGION[1],
               "width": REGION[2], "height": REGION[3]}
        return np.asarray(_Image.frombytes(
            "RGB", (REGION[2], REGION[3]), s.grab(mon).rgb), dtype=int)


def blue_boxes(img=None, band_spec=None):
    """统计给定像素带内的蓝色勾选块数量（未勾=灰描边，不计）。"""
    img = grab() if img is None else img
    x0, x1, y0, y1 = band_spec or BOX_BAND
    band = img[y0:y1, x0:x1]
    rr, gg, bb = band[:, :, 0], band[:, :, 1], band[:, :, 2]
    mask = (bb - rr > 40) & (bb > 120)
    ys = np.nonzero(mask)[0]
    if len(ys) == 0:
        return 0
    ys = np.sort(np.unique(ys))
    return 1 + int((np.diff(ys) > 15).sum())


def master_on(img=None):
    """总开关是否勾选。总开关一关，十个工具复选框整体转禁用、点击无效。"""
    return blue_boxes(img, MASTER_BAND) > 0


def platform_enabled_blue(y):
    """平台页某行 Enabled 复选框的蓝色像素数（勾选≈300+，未勾≈90）。"""
    img = grab()
    b = img[y - 9:y + 9, PLATFORM_ENABLED_X - 10:PLATFORM_ENABLED_X + 15]
    rr, gg, bb = b[:, :, 0], b[:, :, 1], b[:, :, 2]
    return int(((bb - rr > 40) & (bb > 120)).sum())


def platform_blue_blocks(y0, y1):
    """平台页 Enabled 列在给定纵向区间内的蓝色块个数（= 可见工具行数）。"""
    img = grab()
    col = img[y0:y1, PLATFORM_ENABLED_X - 10:PLATFORM_ENABLED_X + 15]
    rows = ((col[:, :, 2] - col[:, :, 0] > 40) & (col[:, :, 2] > 120)).sum(axis=1)
    ys = np.nonzero(rows)[0]
    if len(ys) == 0:
        return 0
    groups = 1
    for a, b in zip(ys[1:], ys[:-1]):
        if a - b > 15:
            groups += 1
    return groups


def bright_count(img, x0, x1, y0, y1, thr=140):
    patch = img[y0:y1, x0:x1]
    return int((patch.mean(axis=2) > thr).sum())


def act(x, y, wait=1.0):
    """点对话框内控件：必要时先聚焦+点标题栏吃掉首击，再点目标。"""
    dlg = _hwnd("Settings", "SunAwtDialog")
    if dlg and not _is_foreground(dlg):
        focus(dlg)
        click(REGION[0] + TITLE[0], REGION[1] + TITLE[1],
              move_dur=0.15, pauses=(0.08, 0.12))
        time.sleep(0.2)
    click(REGION[0] + x, REGION[1] + y, move_dur=0.18, pauses=(0.1, 0.15))
    time.sleep(wait)


def click_until(x, y, ok, tries=4, wait=0.9):
    """点一次 → 读勾选块数 → 未达预期就重试（沙盒内合成点击偶发不生效）。"""
    for _ in range(tries):
        act(x, y, wait=wait)
        n = blue_boxes()
        if ok(n):
            return n
    return None


def open_settings():
    import pyautogui
    frame = _hwnd("mybatis-plus-sql-visualizer-demo", "SunAwtFrame")
    if frame:
        focus(frame)
    pyautogui.hotkey('ctrl', 'alt', 's')
    time.sleep(2.2)


def close_settings():
    import pyautogui
    dlg = _hwnd("Settings", "SunAwtDialog")
    if dlg:
        focus(dlg)
        pyautogui.press('esc')
    time.sleep(1.2)


def set_settings_search(text):
    """显式设定设置搜索框内容，得到确定的左树布局（该内容不保证跨会话持久）。"""
    import pyautogui
    act(SEARCH_SETTINGS[0], SEARCH_SETTINGS[1], wait=0.25)
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.12)
    pyautogui.press('delete')
    time.sleep(0.15)
    paste(text)
    time.sleep(1.3)


def set_tool_filter(text):
    """平台页 Search tools：先清空再粘贴（该框内容跨页面访问持久，直接粘贴会变成追加）。"""
    import pyautogui
    act(SEARCH_TOOLS[0], SEARCH_TOOLS[1], wait=0.25)
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.12)
    pyautogui.press('delete')
    time.sleep(0.2)
    paste(text)
    time.sleep(1.5)


def scroll_page_bottom():
    """把设置页滚到最底（拖滑块过轨道末端，钳位确定）。"""
    import pyautogui
    dlg = _hwnd("Settings", "SunAwtDialog")
    if dlg:
        focus(dlg)
    pyautogui.moveTo(REGION[0] + SCROLL_FROM[0], REGION[1] + SCROLL_FROM[1], duration=0.2)
    pyautogui.mouseDown()
    time.sleep(0.15)
    pyautogui.moveTo(REGION[0] + SCROLL_TO[0], REGION[1] + SCROLL_TO[1], duration=0.35)
    time.sleep(0.15)
    pyautogui.mouseUp()
    time.sleep(0.7)


def verify_mcp_tools_page(where):
    """MCP Tools 页就位自检：底部「全部禁用/启用」按钮可见；返回勾选块数。

    该按钮文字亮度低于 bright_count 默认阈值，故此处用 thr=110。"""
    img = grab()
    n = blue_boxes(img)
    if bright_count(img, 460, 560, 797, 815, thr=110) < 15:
        raise RuntimeError("%s：底部全部禁用按钮不可见（页面滚动位置不符）" % where)
    return n


def prep():
    """就位到录制起点（不进 GIF）：插件页十项归一到全启用、总开关打开；
    设置停在 Exposed Tools + sqlv_ 过滤态，左树停在 MCP 过滤态。"""
    import pyautogui
    frame = _hwnd("mybatis-plus-sql-visualizer-demo", "SunAwtFrame")
    if not frame:
        raise RuntimeError("IDE 主窗口未找到")
    focus(frame)
    pyautogui.press('esc')
    time.sleep(1.2)
    open_settings()
    if not _hwnd("Settings", "SunAwtDialog"):
        raise RuntimeError("设置对话框未打开")
    set_settings_search(SEARCH_FILTER)

    # 1) 插件页：总开关 + 十项归一到全启用
    act(TREE_MCPTOOLS[0], TREE_MCPTOOLS[1], wait=1.2)
    scroll_page_bottom()
    if not master_on():
        if click_until(MASTER[0], MASTER[1], lambda _n: master_on(), tries=5) is None:
            raise RuntimeError("prep：总开关未能开启")
    n = verify_mcp_tools_page("prep")
    if n < 10:
        if click_until(BTN_ALL[0], BTN_ALL[1], lambda _n: blue_boxes() >= 10, tries=4) is None:
            raise RuntimeError("prep：十项未能归一到全启用")
        n = verify_mcp_tools_page("prep-normalize")
    if n != 10:
        raise RuntimeError("prep：十项未能归一到全启用（勾选块=%d）" % n)

    # 2) 平台页：过滤 sqlv_，停在列表顶部
    act(TREE_EXPOSED[0], TREE_EXPOSED[1], wait=1.2)
    set_tool_filter(FILTER)
    blocks = platform_blue_blocks(400, 880)
    if blocks < 8:
        raise RuntimeError("prep：Exposed Tools 过滤后行数异常（蓝色块=%d）" % blocks)

    # 3) 光标归位到首帧位置
    pyautogui.moveTo(REGION[0] + NEUTRAL_CURSOR[0], REGION[1] + NEUTRAL_CURSOR[1],
                     duration=0.2)
    time.sleep(0.5)
    shot(REGION, "perf/_m1_prep.png")
    print("prep ok: 插件页十项全启用 + 平台页已过滤（%d 行）" % blocks)


def main():
    """分镜：平台逐工具门控 → 插件门控页全部禁用/启用 → 回到起点页。"""
    import pyautogui
    prep()
    time.sleep(0.4)
    r = Recorder(REGION, OUT)
    time.sleep(0.8)
    r.start()
    time.sleep(0.6)                       # 起始定格：平台页十个 sqlv_ 分组

    # 1. 平台侧逐工具门控：关掉首个工具的 Enabled
    y = PLATFORM_FIRST_ROW_Y
    before = platform_enabled_blue(y)
    ok = False
    for _ in range(4):
        act(PLATFORM_ENABLED_X, y, wait=0.8)
        if abs(platform_enabled_blue(y) - before) > 50:
            ok = True
            break
    if not ok:
        r.stop()
        raise RuntimeError("平台页逐工具 Enabled 开关未响应")
    time.sleep(1.2)                       # 分镜 1 定格：该工具 Enabled 变灰

    # 2. 打开回去
    before = platform_enabled_blue(y)
    for _ in range(4):
        act(PLATFORM_ENABLED_X, y, wait=0.8)
        if abs(platform_enabled_blue(y) - before) > 50:
            break
    time.sleep(0.5)

    # 3. 左树点 MCP Tools → 插件门控页，滚到底
    act(TREE_MCPTOOLS[0], TREE_MCPTOOLS[1], wait=1.1)
    scroll_page_bottom()
    n = verify_mcp_tools_page("录制")
    if n != 10 or not master_on():
        r.stop()
        raise RuntimeError("插件门控页未就位（工具勾选块=%d, master=%s）" % (n, master_on()))
    time.sleep(0.6)                       # 分镜 3 定格：总开关+十项+全部禁用

    # 4. 全部禁用
    if click_until(BTN_ALL[0], BTN_ALL[1], lambda k: k <= 1) is None:
        r.stop()
        raise RuntimeError("全部禁用未生效")
    time.sleep(1.1)                       # 分镜 4 定格：十项全灰 + 按钮切「全部启用」

    # 5. 全部启用
    if click_until(BTN_ALL[0], BTN_ALL[1], lambda k: k >= 9) is None:
        r.stop()
        raise RuntimeError("全部启用未生效")
    time.sleep(1.1)                       # 分镜 5 定格：十项恢复

    if not master_on():
        r.stop()
        raise RuntimeError("录制途中总开关被意外关闭")

    # 6. 左树点 Exposed Tools → 回到起点页（过滤词仍在，列表即刻复现）
    act(TREE_EXPOSED[0], TREE_EXPOSED[1], wait=1.3)
    if platform_blue_blocks(400, 880) < 8:
        r.stop()
        raise RuntimeError("回到 Exposed Tools 后列表未复现")

    # 7. 光标归位，首尾帧一致
    pyautogui.moveTo(REGION[0] + NEUTRAL_CURSOR[0], REGION[1] + NEUTRAL_CURSOR[1],
                     duration=0.35)
    time.sleep(0.6)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
