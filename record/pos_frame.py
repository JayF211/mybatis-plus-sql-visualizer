# -*- coding: utf-8 -*-
"""录制前置：把沙盒 IDE 主窗体定位到标准录制区（物理像素）。

用法：
  python pos_frame.py <pid>                 列出该 pid 的顶层可见窗体
  python pos_frame.py <pid> L T W H         把主窗体（最大面积者）移动到 L,T 并设为 W,H

gifkit 一样先 SetProcessDpiAwareness(2)，MoveWindow 全程物理像素，
与 mss 抓屏坐标系一致（REGION=(277,25,1370,984)）。
"""
import ctypes


def main():
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
    user32 = ctypes.windll.user32
    pid = int(__import__("sys").argv[1])
    args = __import__("sys").argv[2:]

    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(hwnd, _):
        owner = ctypes.wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))  # 进程ID走出参（返回值是线程ID）
        if owner.value != pid or not user32.IsWindowVisible(hwnd):
            return True
        rect = ctypes.wintypes.RECT()
        if not user32.GetWindowRect(hwnd, ctypes.byref(rect)):
            return True
        w, h = rect.right - rect.left, rect.bottom - rect.top
        if w < 200 or h < 200:      # 跳过小浮层（tooltip/弹窗），只看主窗体候选
            return True
        found.append((hwnd, rect.left, rect.top, w, h))
        return True

    ctypes.windll.user32.EnumWindows(cb, None)
    if not found:
        print("no visible window for pid", pid)
        return
    for hwnd, l, t, w, h in found:
        print(f"hwnd=0x{hwnd:X} ({l},{t}) {w}x{h}")
    if len(args) != 4:
        return
    l, t, w, h = map(int, args)
    hwnd = max(found, key=lambda r: r[3] * r[4])[0]
    ok = user32.MoveWindow(hwnd, l, t, w, h, True)
    print("moved" if ok else "MoveWindow FAILED", "->", (l, t, w, h))


if __name__ == "__main__":
    import ctypes.wintypes  # noqa: F401  RECT 依赖
    main()
