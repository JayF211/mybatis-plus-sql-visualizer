# -*- coding: utf-8 -*-
"""GIF 录制基建：脚本重放 + 帧稳定采样 + ffmpeg 管道录屏。

配套 20260909-脚本重放式录制-开发计划：
- 动作序列由本模块驱动（贝塞尔轨迹 + 紧凑间隔），不经 ZCode 逐步往返；
- wait_stable 以「画面稳定」为节奏锚点，消化 IDEA 偶发加载抖动（1s→3s）；
- 录屏为连续抓帧直写 ffmpeg 管道（15fps，无损 x264 中间产物），
  空白压缩在 assemble.py 后处理阶段完成。
"""
import ctypes
import math
import os
import random
import subprocess
import threading
import time

ctypes.windll.shcore.SetProcessDpiAwareness(2)  # 物理像素坐标，HighDIP 下 mss/pyautogui 一致

import pyautogui  # noqa: E402

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0  # 节奏由脚本显式控制

import mss  # noqa: E402
import numpy as np  # noqa: E402

CAP_FPS = 15

# 帧稳定采样参数（R0 标定基线，首条录制后可调）
STABLE_THR = 1.2      # 分块最大均值差 < THR 视为稳定（0-255 刻度）
STABLE_FRAMES = 8     # 稳定持续帧数（约 0.53s）
WAIT_CAP = 4.0        # 等待画面稳定的硬上限（覆盖 3s 偶发 + 余量）


def _sub_rgb(frame):
    """mss BGRA 帧 -> 4 倍降采样 RGB int16 数组（供 diff 计算）。"""
    h, w = frame.height, frame.width
    arr = np.frombuffer(frame.rgb, dtype=np.uint8).reshape(h, w, 3)
    return arr[::4, ::4, :].astype(np.int16)


def frame_diff(a, b):
    """两帧差异：16x16 分块均值取最大（小面积局部变化不被全帧平均稀释）。"""
    d = np.abs(a - b)
    h, w = d.shape[:2]
    gh, gw = h // 16, w // 16
    blocks = d[:gh * 16, :gw * 16].reshape(gh, 16, gw, 16, 3)
    return float(blocks.mean(axis=(1, 3, 4)).max())


def wait_stable(region, max_wait=WAIT_CAP, need=STABLE_FRAMES, thr=STABLE_THR,
                interval=1.0 / CAP_FPS):
    """等待画面稳定：相邻帧 diff < thr 连续 need 帧判定就绪。

    返回实际耗时；超时返回 -1（fallback 标记，调用方记录并跳过该分镜）。
    """
    with mss.mss() as s:
        mon = {"left": region[0], "top": region[1],
               "width": region[2], "height": region[3]}
        prev = _sub_rgb(s.grab(mon))
        stable = 0
        t0 = time.time()
        while time.time() - t0 < max_wait:
            time.sleep(interval)
            cur = _sub_rgb(s.grab(mon))
            d = frame_diff(prev, cur)
            if d < thr:
                stable += 1
            else:
                stable = 0
            prev = cur
            if stable >= need:
                return time.time() - t0
    return -1


def grab(region):
    """抓单帧返回降采样数组（标定/自检用）。"""
    with mss.mss() as s:
        mon = {"left": region[0], "top": region[1],
               "width": region[2], "height": region[3]}
        return _sub_rgb(s.grab(mon))


def shot(region, path):
    """抓单帧存 PNG（标定取坐标用）。"""
    import PIL.Image
    with mss.mss() as s:
        mon = {"left": region[0], "top": region[1],
               "width": region[2], "height": region[3]}
        PIL.Image.frombytes("RGB", (mon["width"], mon["height"]),
                            s.grab(mon).rgb).save(path)


# ---------------------------------------------------------------------------
# 鼠标/键盘：贝塞尔轨迹重放
# ---------------------------------------------------------------------------

def _bezier(p0, p1, p2, p3, t):
    mt = 1 - t
    x = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
    y = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
    return x, y


def _ease(t):
    """ease-in-out：起手慢、中间快、落点前减速。"""
    return t * t * (3 - 2 * t)


def move(x, y, dur=0.32):
    """贝塞尔轨迹移动到 (x, y)，中间点带微小随机抖动模拟人手。"""
    sx, sy = pyautogui.position()
    dx, dy = x - sx, y - sy
    c1 = (sx + dx * 0.25 + random.uniform(-38, 38), sy + dy * 0.12 + random.uniform(-26, 26))
    c2 = (sx + dx * 0.72 + random.uniform(-38, 38), sy + dy * 0.88 + random.uniform(-26, 26))
    n = max(14, int(dur * 65))
    t0 = time.time()
    for i in range(1, n + 1):
        t = _ease(i / n)
        jx = random.uniform(-1.2, 1.2)
        jy = random.uniform(-1.2, 1.2)
        bx, by = _bezier((sx, sy), c1, c2, (x, y), t)
        pyautogui.moveTo(int(bx + jx), int(by + jy))
        while time.time() - t0 < (i / n) * dur:
            time.sleep(0.002)


def click(x=None, y=None, move_dur=0.32, pauses=(0.25, 0.30)):
    """移动并左键单击。pauses=(移动前, 点击后) 的演示停顿。"""
    if x is not None:
        time.sleep(pauses[0])
        move(x, y, move_dur)
    pyautogui.click()
    time.sleep(pauses[1])


def double_click(x, y, move_dur=0.32):
    move(x, y, move_dur)
    time.sleep(0.06)
    pyautogui.doubleClick()


def right_click(x, y, move_dur=0.32):
    move(x, y, move_dur)
    time.sleep(0.06)
    pyautogui.rightClick()


def drag_select(x1, y1, x2, y2, dur=0.5):
    """贝塞尔移到起点 → 按下 → 移到终点 → 抬起（sql-to-java 选中文本用）。"""
    move(x1, y1, dur)
    time.sleep(0.1)
    pyautogui.mouseDown()
    # 拖拽轨迹：直线推进带抖动
    steps = 22
    for i in range(1, steps + 1):
        t = i / steps
        px = x1 + (x2 - x1) * t + random.uniform(-1, 1)
        pyy = y1 + (y2 - y1) * t + random.uniform(-1, 1)
        pyautogui.moveTo(int(px), int(pyy))
        time.sleep(dur / steps)
    pyautogui.mouseUp()
    time.sleep(0.15)


def keys(*combo):
    """快捷键组合，如 keys('alt', 'shift', 'j')。"""
    pyautogui.hotkey(*combo)
    time.sleep(0.3)


def typewrite(text, interval=0.05):
    pyautogui.write(text, interval=interval)
    time.sleep(0.3)


def paste(text):
    """剪贴板粘贴（绕开输入法，可靠写入多行文本）。"""
    import ctypes
    import ctypes.wintypes
    CF_UNICODETEXT = 13
    GMEM_MOVEABLE = 0x0002
    buf = text.encode("utf-16-le") + b"\x00\x00"
    k32 = ctypes.windll.kernel32
    u32 = ctypes.windll.user32
    k32.GlobalAlloc.restype = ctypes.c_void_p
    k32.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
    k32.GlobalLock.restype = ctypes.c_void_p
    k32.GlobalLock.argtypes = [ctypes.c_void_p]
    k32.GlobalUnlock.argtypes = [ctypes.c_void_p]
    u32.SetClipboardData.restype = ctypes.c_void_p
    u32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
    if not u32.OpenClipboard(0):
        raise OSError("OpenClipboard failed")
    try:
        u32.EmptyClipboard()
        h = k32.GlobalAlloc(GMEM_MOVEABLE, len(buf))
        p = k32.GlobalLock(h)
        ctypes.memmove(p, buf, len(buf))
        k32.GlobalUnlock(h)
        u32.SetClipboardData(CF_UNICODETEXT, h)
    finally:
        u32.CloseClipboard()
    pyautogui.hotkey("ctrl", "v")
    time.sleep(0.4)


# ---------------------------------------------------------------------------
# 录屏：mss 连续抓帧 -> ffmpeg 管道（无损中间产物）
# ---------------------------------------------------------------------------

class Recorder:
    def __init__(self, region, out_path, fps=CAP_FPS):
        self.region = region
        self.fps = fps
        self.out_path = out_path
        self._stop = threading.Event()
        self._thread = None
        self._proc = None
        self.frames = 0
        self._log = []  # (frame_idx, x, y, left_down) 区域相对坐标
        os.makedirs(os.path.dirname(out_path), exist_ok=True)

    def start(self):
        left, top, w, h = self.region
        assert w % 2 == 0 and h % 2 == 0, "录制区域宽高须为偶数"
        cmd = [
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "bgra",
            "-s", f"{w}x{h}", "-r", str(self.fps),
            "-i", "pipe:0", "-an",
            "-c:v", "libx264", "-preset", "ultrafast", "-qp", "0",
            self.out_path,
        ]
        self._proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self):
        user32 = ctypes.windll.user32
        left, top = self.region[0], self.region[1]

        class POINT(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

        pt = POINT()
        with mss.mss() as s:
            mon = {"left": left, "top": top,
                   "width": self.region[2], "height": self.region[3]}
            interval = 1.0 / self.fps
            while not self._stop.is_set():
                t0 = time.time()
                img = s.grab(mon)
                user32.GetCursorPos(ctypes.byref(pt))
                down = bool(user32.GetAsyncKeyState(0x01) & 0x8000)
                self._log.append((self.frames, pt.x - left, pt.y - top, down))
                try:
                    self._proc.stdin.write(img.bgra)
                    self.frames += 1
                except (BrokenPipeError, ValueError):
                    break
                dt = time.time() - t0
                if dt < interval:
                    time.sleep(interval - dt)

    def stop(self):
        self._stop.set()
        self._thread.join(timeout=5)
        self._proc.stdin.close()
        self._proc.wait(timeout=30)
        log_path = self.out_path + ".curlog"
        with open(log_path, "w") as f:
            for row in self._log:
                f.write("%d,%d,%d,%d\n" % row)
        return self.frames
