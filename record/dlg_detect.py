# -*- coding: utf-8 -*-
"""探测 SQL 预览弹窗的位置（亮度突变扫描）。
弹窗内部比周围变暗的 IDE 背景亮；沿 x=650 纵向扫找上边缘，沿 y=top+20 横向扫找左边缘。
"""
import numpy as np
from gifkit import shot


def find_dialog():
    tmp = "perf/_dlg_detect.png"
    shot((277, 25, 1370, 984), tmp)
    import PIL.Image
    img = np.asarray(PIL.Image.open(tmp).convert("RGB"), dtype=int)
    # 纵向：x=650 列，y 40~500 找第一个亮度跳变（>7）
    col = img[:, 648:653, :].mean(axis=(1, 2))
    top = None
    for y in range(40, 500):
        if col[y + 1] - col[y] > 7 and col[y + 3] - col[y] > 7:
            top = y + 1
            break
    if top is None:
        return None
    # 横向：y=top+20 行，x 250~900 找左边缘
    row = img[top + 18:top + 23, :, :].mean(axis=(0, 2))
    left = None
    for x in range(250, 900):
        if row[x + 1] - row[x] > 7 and row[x + 3] - row[x] > 7:
            left = x + 1
            break
    return (left, top)


if __name__ == "__main__":
    print(find_dialog())
