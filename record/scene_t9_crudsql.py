# -*- coding: utf-8 -*-
"""T9 CRUD SQL 生成（20260914-0805 计划）：实体侧编辑器右键单主线。

分镜（约 13 秒）：
1. 光标移至 User 类名行，右键呼出上下文菜单，停 0.5s（Generate CRUD SQL 可见）
2. 点击 Generate CRUD SQL → 候选浮层六项双行渲染，停 1s
3. 粘贴过滤 ins 收窄候选 → Enter 选定 INSERT，停 1s
4. 预览窗口 INSERT SQL 与列勾选面板（自增主键 id 默认不勾），停 2s
5. 取消勾选 password 列 → SQL 对应列实时收缩，停 1s
6. 关闭预览回到编辑器，首尾帧一致循环不跳

教训固化（20260914 夜录）：
- 合成点击落在未聚焦子窗口（预览对话框/浮层）时，第一击会被「点击聚焦」吞掉
  → 点对话框按钮前先对其标题栏做一次激活点击；
- paste('ins') 而非 typewrite：速度搜索过滤完全绕开 IME 组合态；
- 每个关键 UI 状态出现后做像素自检，不满足即重试/中止，杜绝盲飞。
"""
import sys
import time

import numpy as np
from PIL import Image as _Image

from gifkit import (Recorder, click, goto_line, move, paste, right_click,
                    shot, wait_stable)

REGION = (277, 25, 1370, 984)
OUT = "perf/t9_crudsql.mp4"

CLASS_LINE = 27                 # public class User implements Serializable {
SCROLL_DOWN = 4                 # goto_line 居中后 Ctrl+Down 次数
CLASS_CLICK = (590, 339)        # 类名行右键点（screen）
MENU_CRUD = (650, 949)          # Generate CRUD SQL 菜单项（菜单锚点 y+610）
FILTER = "ins"                  # 速度搜索过滤词（唯一命中 user · INSERT）
PREVIEW_TITLE = (660, 166)      # 预览对话框标题栏（激活点击用）
PREVIEW_CANCEL = (1484, 858)    # 预览取消按钮
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


IDE_PID = _find_ide_pid()       # 沙盒 IDE 进程（runIde java）


def bright_count(img, x0, x1, y0, y1, thr=140):
    """区域相对坐标内亮色像素数（检测浅色文字/控件）。"""
    patch = img[y0:y1, x0:x1]
    return int((patch.mean(axis=2) > thr).sum())


def preview_visible():
    """预览对话框是否存在：枚举 IDE 进程可见的 SunAwtDialog（JDialog 模态无关）。"""
    import ctypes
    import ctypes.wintypes as wt
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def cb(h, l):
        if u32.IsWindowVisible(h):
            c = ctypes.create_unicode_buffer(64)
            u32.GetClassNameW(h, c, 64)
            pid = wt.DWORD()
            u32.GetWindowThreadProcessId(h, ctypes.byref(pid))
            if "SunAwtDialog" in c.value and pid.value == IDE_PID:
                found.append(h)
        return True
    u32.EnumWindows(cb, 0)
    return bool(found)


def popup_visible(img):
    """候选浮层标题「选择要预览的 SQL」文字检测。"""
    return bright_count(img, 615, 760, 315, 336, thr=160) > 30


def close_preview_if_open():
    """预览对话框若开着：激活标题栏（第一击被吞）+ Escape 关闭。"""
    import pyautogui
    for _ in range(3):
        if not preview_visible():
            return True
        click(*PREVIEW_TITLE, move_dur=0.25, pauses=(0.15, 0.2))
        click(*PREVIEW_TITLE, move_dur=0.2, pauses=(0.1, 0.3))
        pyautogui.press('esc')
        time.sleep(1.0)
    return not preview_visible()


def _grab_rgb(region):
    import mss
    with mss.mss() as s:
        mon = {"left": region[0], "top": region[1],
               "width": region[2], "height": region[3]}
        return s.grab(mon).rgb


def ensure_editor_focus():
    click(1000, 600, move_dur=0.25, pauses=(0.15, 0.3))


def prep():
    """就位：关闭可能残留的预览 → 定位类名行 → 光标落点。"""
    ensure_editor_focus()
    if not close_preview_if_open():
        raise RuntimeError("preview dialog cannot be closed")
    goto_line(CLASS_LINE)
    import pyautogui
    for _ in range(SCROLL_DOWN):
        pyautogui.hotkey('ctrl', 'down')
        time.sleep(0.3)
    time.sleep(0.5)
    click(*CLASS_CLICK, move_dur=0.3, pauses=(0.2, 0.3))
    shot(REGION, "perf/_t9_prep.png")
    print("prep done")


def main():
    prep()
    time.sleep(0.5)
    r = Recorder(REGION, OUT)
    time.sleep(1.0)
    r.start()
    time.sleep(0.5)

    # 1. 右键呼出上下文菜单
    move(*CLASS_CLICK, dur=0.35)
    right_click(*CLASS_CLICK, move_dur=0.01)
    time.sleep(0.9)

    # 2. 点击 Generate CRUD SQL → 候选浮层（像素自检 + 一次重试）
    click(*MENU_CRUD, move_dur=0.35, pauses=(0.2, 0.4))
    img = np.asarray(_Image.frombytes(
        "RGB", (REGION[2], REGION[3]), _grab_rgb(REGION)), dtype=int)
    if not popup_visible(img):
        print("popup missing, retry menu click")
        click(*MENU_CRUD, move_dur=0.3, pauses=(0.2, 0.4))
        time.sleep(1.2)
        img = np.asarray(_Image.frombytes(
            "RGB", (REGION[2], REGION[3]), _grab_rgb(REGION)), dtype=int)
        if not popup_visible(img):
            r.stop()
            raise RuntimeError("candidate popup never appeared")
    wait_stable(REGION, max_wait=2.5, need=5)
    time.sleep(1.0)                       # 分镜 2 停 1s

    # 3. 粘贴过滤 ins → Enter 选 INSERT（粘贴绕开 IME）
    paste(FILTER)
    time.sleep(0.9)
    import pyautogui
    pyautogui.keyDown('return')
    time.sleep(0.08)
    pyautogui.keyUp('return')
    wait_stable(REGION, max_wait=3.0, need=5)
    time.sleep(2.6)                       # 分镜 3 停 1s + 预览窗落位动画

    # 4. 预览窗定格（分镜 4 共停 2s）
    time.sleep(1.6)

    # 5. 取消勾选一列（第一个已勾列；双次扫描一致才点击，防面板动画漂移）
    def scan_checkbox():
        img = np.asarray(_Image.frombytes(
            "RGB", (REGION[2], REGION[3]), _grab_rgb(REGION)), dtype=int)
        band_x0, band_y0 = 130, 340          # 只扫勾选框列，跳过 id 行
        band = img[band_y0:800, band_x0:172]
        rr, gg, bb = band[:, :, 0], band[:, :, 1], band[:, :, 2]
        mask = (bb - rr > 40) & (bb > 120)
        ys, xs = np.nonzero(mask)
        if len(ys) < 30:
            return None
        order = np.argsort(ys)
        ys, xs = ys[order], xs[order]
        cy, cx = [ys[0]], [xs[0]]
        for yv, xv in zip(ys[1:], xs[1:]):
            if yv - cy[-1] > 20:
                break
            cy.append(yv)
            cx.append(xv)
        return (int(np.mean(cx)) + band_x0 + REGION[0],
                int(np.mean(cy)) + band_y0 + REGION[1])

    pos1 = scan_checkbox()
    time.sleep(1.2)
    pos2 = scan_checkbox()
    if pos1 and pos2 and abs(pos1[0] - pos2[0]) < 12 and abs(pos1[1] - pos2[1]) < 12:
        print("checkbox stable at", pos2)
        click(*pos2, move_dur=0.35, pauses=(0.2, 0.3))
        time.sleep(1.0)                   # SQL 实时收缩定格 1s
    else:
        print("WARN: checkbox position unstable", pos1, pos2, ", skip uncheck")

    # 6. 关闭预览：激活标题 + Escape，光标回起点，首尾帧一致
    click(*PREVIEW_TITLE, move_dur=0.3, pauses=(0.15, 0.2))
    click(*PREVIEW_TITLE, move_dur=0.2, pauses=(0.1, 0.3))
    import pyautogui
    pyautogui.press('esc')
    move(*CLASS_CLICK, dur=0.4)
    time.sleep(0.6)

    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
