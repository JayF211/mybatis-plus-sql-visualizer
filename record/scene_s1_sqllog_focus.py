# -*- coding: utf-8 -*-
"""S1 SQL 日志「聚焦页签」动图（20260919-1500 计划 §五 S1）。

成品：public/demo/sql-log-focus.gif + sql-log-focus.png（features/sql-log.md「聚焦页签」节），
1280 宽 / 15fps。封面 sql-log.gif 不动。

分镜（约 11 秒）：
1. SQL 日志列表静置：多条条目，每条带耗时/方言/**调用点**（如 `5ms [MySQL] [OrderController.java:38]`）
2. 右键某条 → 上下文菜单 → 点「聚焦调用方方法」
3. 面板顶部出现聚焦页签 `聚焦：OrderController#list ×`，列表收缩为该调用点区间内的条目，
   并显示「N 条不匹配聚焦范围的条目已隐藏」
4. 单击该条目 → 轻量预览浮窗（SQL 词法高亮）
5. 点浮窗「复制」→ 图标闪变对号
6. 关闭浮窗 → 关闭聚焦页签 → 列表复原，首尾帧一致

## 前置（prep 做掉，不进 GIF）——**这一步是本张最容易踩的地方**

「聚焦调用方方法」要求条目**带调用点**，而调用点只有**字节码增强通道**（agent）能给。
实测（2026-09-20）两条硬约束：

1. **agent 会话只随应用启动建立一次**，之后不会自动重连：把面板「捕捉」关掉会连带断掉
   会话（日志 `pushCaptureActive: active=false → disabled=true` 后紧接在线态 true→false），
   再打开捕捉时 `sessions=0`，此后捕获的条目**没有调用点**，聚焦菜单项变灰不可点。
2. **会话会在挂载后约 45~80 秒自行掉线**（日志 `增强捕获在线态切换: true -> false`，
   `AgentSession - Socket closed`），应用进程本身并未退出（`netstat` 端口仍在、pid 不变）。

故 prep 的顺序是**应用重启 → 立刻开捕捉 → 立刻触发查询**，把带调用点的条目抢在窗口内录进列表；
条目一旦入库就带调用点，后续聚焦不受会话掉线影响。

另：demo 应用若含 DevTools，改源码会触发自动重启，新 JVM 不带 agent → 会话同样掉线（本轮踩过）。

## 其他标定

- 面板宽度：2026-09-20 由 330px 拉宽到 505px（左边界 795），使轻量预览浮窗（560px 宽、
  锚定面板左缘）完整落入录制区；拉窄则浮窗右缘会被窗口裁掉。
- 列表按**时间升序**排序，新条目在下方 → prep 把列表滚到底再录。
- 右键菜单各项纵坐标随点击点平移（本轮点击 y=360 时「聚焦调用方方法」在 y=554）。
"""
import sys
import time

import numpy as np
import pyautogui

import scene_m1_mcp as base          # 复用 focus / _hwnd / grab / bright_count / paste
from gifkit import Recorder, click, move, right_click, shot

REGION = (277, 25, 1370, 984)
OUT = "perf/s1_sqllog_focus.mp4"

# —— 面板内坐标（region 相对，2026-09-20 拉宽后标定）——
CAPTURE = (834, 181)        # 捕捉 / 停止（红=捕捉中，绿=已停止）
CLEAR = (868, 181)          # 清空列表
LIST_SB = 1293              # 列表滚动条 x
ENTRY_RC = (1000, 360)      # 右键目标条目（需带调用点）
MENU_FOCUS = (1095, 554)    # 右键菜单「聚焦调用方方法」
FOCUS_TAB_X = (1046, 271)   # 聚焦页签的 × 关闭
PREVIEW_COPY = (1214, 455)  # 预览浮窗「复制」
PREVIEW_CLOSE = (1310, 456)  # 预览浮窗「关闭」
NEUTRAL_CURSOR = (500, 500)

# 聚焦页签行（判在否）：只扫「聚焦：」+页签文字区 x815~990——
# 同行右侧「排序：」下拉在 x1000+，y 与页签行重叠，扫宽了会假阳性
FOCUS_ROW = (815, 990, 256, 290)
ENTRY_ROW = (860, 1300, 330, 350)     # 首条目标题行（判列表非空）


def _cap_state():
    """捕捉按钮颜色：红=捕捉中，绿=已停止。"""
    img = base.grab()
    px = img[CAPTURE[1], CAPTURE[0]]
    return int(px[0]) > int(px[1]) + 20


def _panel_wnd():
    """面板所在的 IDE 主窗（录制时前台窗口）。"""
    return base._hwnd("mybatis-plus-sql-visualizer-demo", "SunAwtFrame")


def _popup_wnd():
    """轻量预览浮窗：本进程的 SunAwtWindow，且尺寸为特征值 560x367。

    不能只按窗口类匹配——通知气泡等也是 SunAwtWindow，会假阳性导致「以为浮窗开着、
    去点一个不存在的 ×」（本轮踩过：_popup_rect 读到别的窗口）。"""
    import ctypes
    import ctypes.wintypes as wt
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def cb(h, l):
        if u32.IsWindowVisible(h):
            c = ctypes.create_unicode_buffer(128)
            u32.GetClassNameW(h, c, 128)
            pid = wt.DWORD()
            u32.GetWindowThreadProcessId(h, ctypes.byref(pid))
            if pid.value == base.IDE_PID and "SunAwtWindow" in c.value:
                r = wt.RECT()
                u32.GetWindowRect(h, ctypes.byref(r))
                if 520 <= r.right - r.left <= 600 and 330 <= r.bottom - r.top <= 400:
                    found.append(h)
        return True
    u32.EnumWindows(cb, 0)
    return found[0] if found else None


def _popup_rect():
    """浮窗屏幕矩形 (l, t, w, h)。浮窗纵坐标随条目位置变，按钮坐标必须按矩形算——
    写死坐标会随浮窗上下漂移而点空（本轮踩过：× 写在 456 而浮窗实际在 412）。"""
    import ctypes
    import ctypes.wintypes as wt
    h = _popup_wnd()
    if not h:
        return None
    r = wt.RECT()
    ctypes.windll.user32.GetWindowRect(h, ctypes.byref(r))
    return (r.left, r.top, r.right - r.left, r.bottom - r.top)


def _entry_clusters():
    """列表区（y320~600）内亮文字行的簇数：聚焦生效时只剩 1 条，复原后多条。

    不用「聚焦行有无文字」判据——页签行 y≈271 与「排序」行 y≈276 重叠，
    无页签时列表首条也会顶到该高度，按行扫描会假阳性（本轮实测踩过）。"""
    img = base.grab()
    sub = img[320:600, 800:1290]
    b = (sub.mean(axis=2) > 110).sum(axis=1)
    rows = [i for i, v in enumerate(b) if v > 5]
    if not rows:
        return 0
    groups = 1
    for a, c in zip(rows[1:], rows[:-1]):
        if a - c > 6:
            groups += 1
    return groups


def _list_nonempty():
    img = base.grab()
    x0, x1, y0, y1 = ENTRY_ROW
    return base.bright_count(img, x0, x1, y0, y1, thr=110) > 40


def _focus_tab_close_xy():
    """聚焦页签 × 的位置：页签宽度随方法名长度变，必须动态定位。

    聚焦生效时「排序」行会被页签行取代，故该行最右侧的亮簇就是 ×（本轮把坐标写死在
    页签文字上，点了三次都没关掉）。找不到返回 None。"""
    img = base.grab()
    sub = img[260:286, 800:1295]
    mask = (sub.mean(axis=2) > 100)
    cols = mask.sum(axis=0)
    xs = [800 + i for i, v in enumerate(cols) if v > 0]
    if not xs:
        return None
    groups = []
    for x in xs:
        if groups and x - groups[-1][-1] <= 10:
            groups[-1].append(x)
        else:
            groups.append([x])
    g = groups[-1]
    return (int(sum(g) / len(g)), 272)


def _click(x, y, wait=1.0):
    """面板内单击：先聚焦 IDE 主窗，再点（合成点击首击可能被「点击聚焦」吞掉）。"""
    w = _panel_wnd()
    if w:
        base.focus(w)
    click(REGION[0] + x, REGION[1] + y, move_dur=0.22, pauses=(0.15, 0.2))
    time.sleep(wait)


def _close_popup():
    """关闭可能残留的轻量预览浮窗：它是独立顶层窗，Esc 前必须先聚焦它本身，
    否则 Esc 落到主窗、浮窗留着挡住列表右键（本轮踩过：浮窗未关 → 右键落在浮窗上）。"""
    for i in range(4):
        rc = _popup_rect()
        if not rc:
            return True
        l, t, w, h = rc
        if i % 2 == 0:                       # 先试点浮窗外部（多数浮窗失焦即关）
            pyautogui.moveTo(REGION[0] + 400, REGION[1] + 500, duration=0.25)
            pyautogui.click()
        else:                                # 再试点浮窗右上角 ×
            base.focus(_popup_wnd())
            pyautogui.moveTo(l + 500, t + 26, duration=0.25)
            pyautogui.click()
        time.sleep(1.0)
    return not _popup_wnd()


def _scroll_list_bottom():
    pyautogui.moveTo(REGION[0] + LIST_SB, REGION[1] + 300, duration=0.2)
    pyautogui.mouseDown()
    time.sleep(0.15)
    pyautogui.moveTo(REGION[0] + LIST_SB, REGION[1] + 600, duration=0.4)
    time.sleep(0.15)
    pyautogui.mouseUp()
    time.sleep(0.8)


def _app_up(timeout=150):
    import urllib.request
    for _ in range(timeout // 5):
        try:
            urllib.request.urlopen("http://localhost:8081/user/list", timeout=3).read()
            return True
        except Exception:
            time.sleep(5)
    return False


def _trigger_queries(n=4):
    import urllib.request
    paths = ["/user/list", "/user/page?pageNum=1&pageSize=5", "/order/list", "/order/sum"]
    for p in paths[:n]:
        try:
            urllib.request.urlopen("http://localhost:8081" + p, timeout=5).read()
        except Exception as e:
            print("query err", p, e)
        time.sleep(0.9)


def _menu_visible():
    """右键菜单是否弹出（判据：菜单区域出现成片亮文字）。"""
    img = base.grab()
    return base.bright_count(img, 1030, 1235, 375, 600, thr=120) > 120


def _menu_focus_enabled():
    """右键菜单里「聚焦调用方方法」是否可用（亮=可用，灰=条目无调用点）。

    实测（2026-09-20）该行亮像素约 350（禁用时为 0），故阈值取 60；带子取
    y540~570 只覆盖该项（上一项「跳转到 Java」在 525，下一项「按请求聚焦」在 583）。
    首版阈值写 40、带子取 546~564，可用态只有 ~29 像素 → 假阴性误判「无调用点」。"""
    img = base.grab()
    return base.bright_count(img, 1030, 1200, 540, 570, thr=120) > 60


def prep():
    """就位到录制起点（不进 GIF）：重启应用 → 抢窗口内捕捉带调用点的条目 → 归零面板状态。"""
    w = _panel_wnd()
    if not w:
        raise RuntimeError("IDE 主窗未找到")
    base.focus(w)
    pyautogui.press('esc')
    time.sleep(1.0)
    _close_popup()

    # 1) 重启应用，让字节码增强 agent 重新挂载（会话不会自动重连）
    _click(118, 691, wait=2.5)                     # Run 窗「停止」
    time.sleep(2.0)
    import subprocess
    raw = subprocess.run(["netstat", "-ano"], capture_output=True).stdout or b""
    net = raw.decode("utf-8", errors="ignore")
    for line in net.splitlines():
        if "LISTENING" in line and ":8081" in line:
            pid = line.split()[-1]
            subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True)
            time.sleep(2)
            break
    _click(88, 691, wait=1.5)                      # Run 窗「重跑」
    if not _app_up():
        raise RuntimeError("demo 应用未起来（8081 不通）")
    time.sleep(2)

    # 2) 立刻开捕捉（此刻 agent 在线），立刻触发查询 → 条目带调用点
    if not _cap_state():
        _click(*CAPTURE, wait=0.8)
    if not _cap_state():
        raise RuntimeError("捕捉未能开启")
    _click(*CLEAR, wait=0.8)
    _trigger_queries()
    # 给控制台解析留足落库时间：条目若在录制开头还在陆续入库，首尾列表内容不一致，接缝必超标
    time.sleep(7.0)

    # 3) 滚到底（新条目在下方），自检条目非空
    _close_popup()
    _scroll_list_bottom()
    if not _list_nonempty():
        raise RuntimeError("列表为空：捕捉未产出条目")
    # 先点中目标条目：录制段末尾会点它开预览并留下选中高亮，起点没有该高亮会让接缝超标
    _click(ENTRY_RC[0], 340, wait=0.8)
    _close_popup()
    # 末态是「条目已选中 + 焦点在编辑器」（预览点选后焦点被移出列表），起点停成同一态
    pyautogui.moveTo(REGION[0] + NEUTRAL_CURSOR[0], REGION[1] + NEUTRAL_CURSOR[1],
                     duration=0.3)
    pyautogui.click()
    time.sleep(0.5)

    # 3.5) 清掉可能残留的聚焦页签（上次录制留下的），保证起点是未聚焦列表
    xy = _focus_tab_close_xy()
    if xy:
        _click(xy[0], xy[1], wait=1.2)
        xy = _focus_tab_close_xy()
        if xy:
            _click(xy[0], xy[1], wait=1.2)

    # 4) 自检条目带调用点：右键菜单里「聚焦调用方方法」应为可用态
    #    浮窗/气泡可能盖住列表导致右键落空 → 关浮窗重试，菜单没弹出也算一次失败
    ok = False
    for i in range(4):
        _close_popup()
        right_click(REGION[0] + ENTRY_RC[0], REGION[1] + ENTRY_RC[1], move_dur=0.25)
        time.sleep(1.5)
        if _menu_visible():
            ok = _menu_focus_enabled()
            shot(REGION, "perf/_s1_prep_menu.png")
            print("  诊断: 第 %d 次右键 popup=%s clusters=%d menu_ok=%s" % (
                i + 1, bool(_popup_wnd()), _entry_clusters(), ok))
            pyautogui.press('esc')
            time.sleep(0.7)
            if ok:
                break
        else:
            print("  诊断: 第 %d 次右键菜单未弹出（疑似被浮窗盖住），重试" % (i + 1))
            pyautogui.press('esc')
            time.sleep(0.7)
    if not ok:
        raise RuntimeError("条目无调用点或菜单打不开——agent 会话未在捕捉窗口内生效")
    print("prep ok: 列表非空且「聚焦调用方方法」可用")


def main():
    prep()
    time.sleep(0.4)
    r = Recorder(REGION, OUT)
    time.sleep(0.8)
    r.start()
    print("  起始条目簇:", _entry_clusters())
    time.sleep(0.6)                       # 起始定格：列表 + 调用点

    # 1. 右键 → 聚焦调用方方法
    right_click(REGION[0] + ENTRY_RC[0], REGION[1] + ENTRY_RC[1], move_dur=0.3)
    time.sleep(0.9)
    before = _entry_clusters()
    _click(*MENU_FOCUS, wait=1.6)
    after = _entry_clusters()
    if not (after < before):
        r.stop()
        raise RuntimeError("聚焦未生效（条目簇 %d -> %d）" % (before, after))
    time.sleep(0.7)                       # 分镜 3 定格：页签 + 收缩 + 隐藏计数

    # 2. 单击条目 → 轻量预览浮窗
    _click(ENTRY_RC[0], 340, wait=1.1)
    if not _popup_wnd():
        r.stop()
        raise RuntimeError("轻量预览浮窗未出现")
    time.sleep(0.8)

    # 3. 复制 → 对号（按浮窗矩形算按钮位置）
    rc = _popup_rect()
    if rc:
        l, t, w, h = rc
        pyautogui.moveTo(l + 404, t + 24, duration=0.3)
        pyautogui.click()
        time.sleep(0.8)
    else:
        _click(*PREVIEW_COPY, wait=0.8)
    time.sleep(0.6)

    # 4. 关浮窗（Esc，比点 × 稳）→ 关聚焦页签 → 列表复原
    #    × 与「排序」下拉在同一带（y≈271 vs 276），只点一次、给足渲染时间，避免误开下拉
    pyautogui.press('esc')
    time.sleep(0.6)
    if _popup_wnd():
        pyautogui.press('esc')
        time.sleep(0.5)
    n0 = _entry_clusters()
    xy = _focus_tab_close_xy()
    if xy:
        _click(xy[0], xy[1], wait=1.2)
    if _entry_clusters() <= n0:           # 未复原 → 按新位置再点一次
        xy = _focus_tab_close_xy()
        if xy:
            _click(xy[0], xy[1], wait=1.2)
    time.sleep(0.5)

    # 5. 复原列表滚动位置 + 清条目选中态
    #    （起点是未选中列表；预览那一下会给条目留下蓝色选中高亮，不清掉接缝会超标）
    _scroll_list_bottom()
    pyautogui.moveTo(REGION[0] + NEUTRAL_CURSOR[0], REGION[1] + NEUTRAL_CURSOR[1],
                     duration=0.3)
    pyautogui.click()
    time.sleep(0.5)

    # 6. 光标归位，首尾帧一致（给足时长，压缩器按尾部 cap 保留的帧要含落位后的静止）
    pyautogui.moveTo(REGION[0] + NEUTRAL_CURSOR[0], REGION[1] + NEUTRAL_CURSOR[1],
                     duration=0.6)
    time.sleep(1.3)
    print("  结束条目簇:", _entry_clusters())
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
