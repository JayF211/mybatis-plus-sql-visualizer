# -*- coding: utf-8 -*-
"""A11 EXPLAIN 真实执行计划（explain-plan.gif）20261012。

素材：`DeviceInfoServiceImpl#joinListEntity`（mybatis-plus-join-demo，car_device_info
      LEFT JOIN station_info 的 MPJ 链）。
流程：光标压链 token → 预览 → 点「执行计划」→ 选 `mpj_demo@localhost`（弹层第二行）
      → 等 EXPLAIN 提交原生数据库控制台执行 → 结构化计划表与静态性能规则同屏 → 凝视。

标定（20261012，IDE @ 277,25，整窗截图坐标 + (277,25)）：
  编辑器取焦 (600,500)｜预览浮窗体内（关窗）(760,400)
  「执行计划」按钮 screen (1295,251)（实测；旧值 (1300,253) 同点）
  数据源弹层第二行 mpj_demo@localhost screen (1276,237)
  结果计划表 screen y≈720，x 737~1517

坑（本轮实测，全部已修）：
  ① **原目标跑不通**：`UserServiceImpl#selectJoinList` 主表是 UserDO →
     `@TableName("\"user\"")`（达梦风格双引号，见同模块 schema-dm.sql）。MySQL 默认把 `"`
     当字符串字面量 → EXPLAIN 必报 `[42000][1064] ... near '"user" t' at line 11`。
     换成本脚本的 DeviceInfoServiceImpl 目标（表名都不是保留字）后一次通过。
     **这是 demo 侧的数据问题、不是插件缺陷**：插件忠实透传注解里的表名（SqlGenerator
     .renderTableName → dialect.quoteIfKeyword，MySQL 方言本用反引号；注解里已带引号故原样输出）。
  ② 打开文件必须**点结果行且扫掠**：Return 打不开「文件」搜索弹窗的结果行（JBR 吞键，
     见 probe_openfile.py），且行位在 region y 350~366 之间浮动。原脚本单点 (777,342)
     落在扫掠窗口之外 → 弹窗关不掉，后续 Ctrl+G 与 "49" 全被搜索框吃掉。
  ③ 落链 token 用 goto_col(行,列)：goto_text 的 Ctrl+F 路径实测没命中（光标停在 53:7），
     插件报「未在光标处找到可预览的 SQL 源」。
  ④ 数据源弹层行位随弹层摆位变：旧值 (860,251) 落在弹层之外（会点到后面的预览面板）。
  ⑤ 此前「执行中…」挂死的根因＝沙盒缺 IDE 密钥链（c.kdbx/c.pwd），已从真机拷入并经用户
     手工激活授权——本轮实测 EXPLAIN 正常出计划，未再挂死。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

from gifkit import Recorder
from nav import force_en_us, goto_col, shot, trigger_preview

REGION = (277, 25, 1370, 984)
OUT = "perf/a11_explain.mp4"

FILE_QUERY = "mpjdemo/DeviceInfoServiceImpl.java"
CHAIN_LINE = 110                             # .eq(DeviceInfoEntity::getShareSchemeId, 1)
CHAIN_COL = 18                               # 'eq' 首字符列（1 基）

EDITOR_SPOT = (600, 500)
CANCEL_XY = (1485, 828)
ROW_X = 777
ROW_YS = (350, 358, 366)
BTN_EXPLAIN = (1295, 251)
DS_MPJ_ROW = (1276, 237)
GAZE_A = (1100, 500)      # 预览里 SQL 区
GAZE_B = (1100, 620)
# 收尾凝视避开结果表的单元格/列头——悬停会弹 DataGrip 的列类型浮层（partitions: MEDIUMTEXT），
# 末帧会留一个气泡。改指下方原生控制台里真正执行的那条 EXPLAIN，再落到左侧面板空白处收尾。
GAZE_PLAN_A = (500, 880)   # 底部 mpsql-exec.sql 控制台
GAZE_PLAN_B = (577, 725)   # 左侧面板正中空白（远离控件与结果网格，无浮层）


def main():
    import pyautogui

    pyautogui.FAILSAFE = False
    pyautogui.PAUSE = 0
    force_en_us()

    # ---- prep（不入镜）：关预览/弹窗 → 开文件 → 光标压 token → 开好预览 ----
    pyautogui.click(*CANCEL_XY); time.sleep(1.0)
    pyautogui.keyDown('esc'); pyautogui.keyUp('esc'); time.sleep(0.6)
    pyautogui.click(*EDITOR_SPOT); time.sleep(0.4)
    pyautogui.hotkey("ctrl", "shift", "n"); time.sleep(1.4)
    pyautogui.hotkey("ctrl", "a"); time.sleep(0.5)
    force_en_us()
    pyautogui.typewrite(FILE_QUERY, interval=0.05); time.sleep(1.6)
    for y in ROW_YS:
        pyautogui.click(ROW_X, y); time.sleep(0.4)
    time.sleep(2.4)
    goto_col(CHAIN_LINE, CHAIN_COL)
    trigger_preview()
    # trigger 后把光标移离代码区：压在链 token 上会弹 documentation 悬浮窗挡预览画面
    #（take1 报废的直接原因）；SQL 面板中部安全。
    pyautogui.moveTo(1100, 480, duration=0.4)
    time.sleep(0.6)

    r = Recorder(REGION, OUT)
    r.start()

    # 1. 起手凝视：SQL 与静态性能规则警示同屏
    pyautogui.moveTo(*GAZE_A, duration=0.6)
    pyautogui.moveTo(*GAZE_B, duration=1.0)

    # 2. 点「执行计划」→ 弹数据源选择
    pyautogui.click(*BTN_EXPLAIN); time.sleep(2.2)

    # 3. 选 mpj_demo@localhost → 提交 EXPLAIN（首连可能 5~15s）
    pyautogui.click(*DS_MPJ_ROW)
    time.sleep(2.5)          # 先给「执行中…」一拍入镜
    time.sleep(10.0)         # 等结果渲染（本轮实测计划表在 16s 内到位）

    # 4. 凝视计划结果：结构化 EXPLAIN 与静态规则同屏对照
    pyautogui.moveTo(*GAZE_PLAN_A, duration=1.0)
    pyautogui.moveTo(*GAZE_PLAN_B, duration=1.0)
    time.sleep(0.8)

    shot("perf/a11_final.png")
    print("frames:", r.stop())


if __name__ == "__main__":
    sys.exit(main())
