# -*- coding: utf-8 -*-
"""坐标定位三件套：不靠目测。

设计依据（2026-10-12 大范围录制实测）：本轮所有「点空 / 点错」都出在**目测坐标**上——
弹窗按钮行目测偏低 48px（连点三次全落空）、开文件扫掠的余点点进代码区、点错树节点；
而三种「按证据算」的定位法**一次就对**。本模块把它们固化：

  - `window_rect()`   —— 对话框/浮窗不是进程主窗口，按 PID/标题用 EnumWindows 取矩形；
  - `button_row_y()`  —— 弹窗底部按钮行恒在窗口底边上方约 39px（弹窗高度随内容变，目测必错）；
  - `bright_band_y()` —— 像素剖面：逐行取均值找最亮连续段（按钮行/工具条这类亮文字带）。

坐标系：全部是**屏幕物理像素**，与 `pyautogui` 的点击坐标同一坐标系（本模块已声明 DPI 感知）。
"""
import ctypes
from ctypes import wintypes

import mss
import numpy as np
import PIL.Image

ctypes.windll.shcore.SetProcessDpiAwareness(2)
_u32 = ctypes.windll.user32
_ENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)


def windows():
    """枚举可见顶层窗口 → `[(hwnd, pid, title, (l, t, r, b))]`。

    对话框/浮窗**不是进程主窗口**——`Get-Process.MainWindowHandle` 看不到它们，
    只能用 EnumWindows + `GetWindowThreadProcessId` 按 PID 过滤。
    """
    out = []

    def _cb(hwnd, _lparam):
        if not _u32.IsWindowVisible(hwnd):
            return True
        n = _u32.GetWindowTextLengthW(hwnd)
        if n == 0:
            return True
        buf = ctypes.create_unicode_buffer(n + 1)
        _u32.GetWindowTextW(hwnd, buf, n + 1)
        pid = wintypes.DWORD(0)
        _u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        r = wintypes.RECT()
        _u32.GetWindowRect(hwnd, ctypes.byref(r))
        out.append((int(hwnd), int(pid.value), buf.value, (r.left, r.top, r.right, r.bottom)))
        return True

    _u32.EnumWindows(_ENUMPROC(_cb), 0)
    return out


def window_rect(title=None, pid=None, min_size=(300, 300), max_size=(4000, 3000), pick="largest"):
    """按标题关键字（子串、大小写不敏感）或 PID 取窗口屏幕矩形 `(l, t, r, b)`；找不到返回 None。

    同一 PID 下常同时有主窗口与对话框：主窗口大、对话框小。
    `pick` 取 `"largest"`（主窗口）或 `"smallest"`（对话框）；`min_size`/`max_size` 按宽高过滤，
    用来把浮窗、提示气泡这类小窗排除掉。

    ```python
    rect = window_rect(pid=ide_pid, pick="smallest", min_size=(800, 600))  # 设置对话框
    ```
    """
    cands = []
    for hwnd, wpid, wtitle, rect in windows():
        w, h = rect[2] - rect[0], rect[3] - rect[1]
        if pid is not None and wpid != pid:
            continue
        if title is not None and title.lower() not in wtitle.lower():
            continue
        if not (min_size[0] <= w <= max_size[0] and min_size[1] <= h <= max_size[1]):
            continue
        cands.append(rect)
    if not cands:
        return None
    key = (lambda r: (r[2] - r[0]) * (r[3] - r[1]))
    return max(cands, key=key) if pick == "largest" else min(cands, key=key)


def button_row_y(rect, offset=39):
    """弹窗底部按钮行的屏幕 y ＝ 窗口底边 − offset。

    **为什么不能目测**：弹窗高度随内容变（SQL 预览面板这类），按钮行位置随之浮动；
    本轮目测写死的值偏低 48px，连点三次全落空。`offset=39` 为实测口径（A13/A29 两次吻合）。
    取到 `window_rect(...)` 后即可算出按钮行，再用 `bright_band_y()` 复核。
    """
    return rect[3] - offset


def grab(region, path=None):
    """抓 `region=(left, top, width, height)` → 灰度 numpy 数组；给了 `path` 同时存 PNG。"""
    with mss.mss() as sct:
        img = sct.grab({'left': region[0], 'top': region[1],
                        'width': region[2], 'height': region[3]})
        if path:
            mss.tools.to_png(img.rgb, img.size, output=path)
    arr = np.frombuffer(img.rgb, dtype=np.uint8)
    arr = arr.reshape(img.size[1], img.size[0], len(arr) // (img.size[0] * img.size[1]))
    return arr[:, :, :3].mean(axis=2)


def bright_band_y(src, x0, x1, y0, y1, thr=35.0, min_len=4):
    """像素剖面定位：在 `x0..x1` 列、`y0..y1` 行内逐行取灰度均值，返回**最亮连续段**的 `(y_start, y_end)`。

    `src` 传灰度数组（`grab()`）或 PNG 路径。用于「文字比背景亮」的水平条带——
    实测按钮文字行均值 47~60、深色面板背景 26，故默认阈值 35 能干净分开；
    暗色主题下**别用「空白≈0」的直觉设阈值**（收起态本底噪声就有 13）。

    找不到亮行返回 `None`。
    """
    a = np.asarray(PIL.Image.open(src).convert('L'), dtype=float) if isinstance(src, str) else src
    prof = a[y0:y1, x0:x1].mean(axis=1)
    bright = prof > thr
    best, start = None, None
    for i, on in enumerate(bright):
        if on and start is None:
            start = i
        if (not on or i == len(bright) - 1) and start is not None:
            end = i - 1 if not on else i
            if best is None or (end - start) > (best[1] - best[0]):
                best = (start, end)
            start = None
    if best is None or best[1] - best[0] + 1 < min_len:
        return None
    return y0 + best[0], y0 + best[1]


def button_click_point(rect, x_in_window, offset=39):
    """弹窗按钮的屏幕点击点：`x_in_window` 传窗口相对 x（按钮中心），y 由 `button_row_y()` 算。"""
    return rect[0] + x_in_window, button_row_y(rect, offset)


if __name__ == '__main__':
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == 'windows':
        for hwnd, pid, title, rect in windows():
            print('%-8d %-8d %-52s %s' % (hwnd, pid, title[:50], rect))
    else:
        print(__doc__)
