# -*- coding: utf-8 -*-
"""R05 JPA 派生查询预览重录（T-DocsRefresh1023）：派生方法 gutter → 预览 → 复制并关闭。

相对 20260912 版（scene_t1）的差异：
- 收尾点蓝色主行动「复制 SQL 并关闭」而非「取消」；
- 只保留一个主场景（派生方法），旧片双弹窗（派生 + @Query）在 11 秒里信息过载；
- gutter 只要主条带（x<105），避开编辑器折叠箭头。

前置（prep）：jpa-hibernate-demo 的 UserRepository.java 打开、goto(53)、无弹窗。
"""
import os
import sys
import time

import pyautogui

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gifkit import (Recorder, click, find_gutter_icons, goto_line, shot,
                    wait_stable)

REGION = (277, 25, 1370, 984)
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "perf", "r05_jpa.mp4")

OX, OY = REGION[0], REGION[1]
COPY_AND_CLOSE = (1115, 806)


def abs_pt(p):
    return (p[0] + OX, p[1] + OY)


def locate_gutter():
    path = os.path.join(BASE, "perf", "_pre_r05.png")
    shot(REGION, path)
    icons = find_gutter_icons(path, y_range=(100, 860), gap=12)
    icons = [i for i in icons if 95 <= i[1] <= 130]   # gutter 条带（JPA 文件在 x≈110）
    if not icons:
        raise RuntimeError("gutter icon not found")
    center = (100 + 860) / 2
    y, x = min(icons, key=lambda p: abs(p[0] - center))
    print("icons:", icons, "-> picked", (y, x))
    return x + OX, y + OY


def main():
    goto_line(53)                              # findByUsernameOrEmail
    gutter = locate_gutter()

    rec = Recorder(REGION, OUT)
    time.sleep(1.2)
    rec.start()
    try:
        time.sleep(0.5)
        click(*gutter, pauses=(0.25, 0.3))     # 1. 派生方法 gutter
        wait_stable(REGION)
        time.sleep(1.8)                        # 预览定格（实体别名展开可见）

        click(*abs_pt(COPY_AND_CLOSE), move_dur=0.3)   # 2. 复制 SQL 并关闭
        time.sleep(0.9)

        # 3. 不跳转：关窗后光标仍在 53 行，与首帧同态（跳转会引入新的滚动/弹窗差异）
        time.sleep(1.2)
    finally:
        rec.stop()
        print(f"frames={rec.frames} out={OUT}")


if __name__ == "__main__":
    main()
