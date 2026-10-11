"""沙盒 IDE 导航助手：跳行并回读状态栏校验落点（Goto Line 对话框会残留上次输入，必须先全选清空）。"""
import ctypes, time, pyautogui, mss

ctypes.windll.shcore.SetProcessDpiAwareness(2)
pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

WIN = {'left': 277, 'top': 25, 'width': 1370, 'height': 984}
SB  = {'left': 277, 'top': 25 + 944, 'width': 1370, 'height': 40}   # 状态栏条


def shot(path, region=WIN):
    with mss.mss() as sct:
        img = sct.grab(region)
        mss.tools.to_png(img.rgb, img.size, output=path)


def goto(line, wait=1.2, verify_shot=None):
    """Ctrl+G 跳行。goto 对话框残留上次输入 -> 先 Ctrl+A 清空再输入。"""
    pyautogui.hotkey('ctrl', 'g')
    time.sleep(0.7)
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.25)
    pyautogui.typewrite(str(line), interval=0.06)
    time.sleep(0.4)
    pyautogui.keyDown('return'); pyautogui.keyUp('return')
    time.sleep(wait)
    if verify_shot:
        shot(verify_shot, SB)


def force_en_us():
    """Alt+Shift 会触 Windows 输入法切换；输入前强制回 en-US，避免 typewrite 被 IME 吃掉。"""
    try:
        hkl = ctypes.windll.user32.LoadKeyboardLayoutW('00000409', 1)
        ctypes.windll.user32.ActivateKeyboardLayout(hkl, 0)
    except Exception:
        pass


def trigger_preview(wait=3.5, use_find_action=False):
    """打开 SQL 预览。默认 Alt+Shift+Q；use_find_action=True 走 Ctrl+Shift+A（文本输入更稳）。"""
    force_en_us()
    if use_find_action:
        pyautogui.hotkey('ctrl', 'shift', 'a')
        time.sleep(0.9)
        pyautogui.typewrite('Convert to SQL', interval=0.05)
        time.sleep(0.9)
        pyautogui.keyDown('return'); pyautogui.keyUp('return')
    else:
        pyautogui.hotkey('alt', 'shift', 'q')
    time.sleep(wait)


def goto_token(line, words=1):
    """跳行并把光标落到**首个 token** 上。

    插件 WrapperLocator 要求光标在 token 上；落行首缩进（col 1）会 anchor=null
    只弹「未在光标处找到可预览的 SQL 源」通知。Ctrl+Right 从缩进跳到首 token 起点。
    """
    goto(line)
    pyautogui.keyDown('home'); pyautogui.keyUp('home'); time.sleep(0.2)
    for _ in range(words):
        pyautogui.keyDown('ctrl'); pyautogui.keyDown('right')
        pyautogui.keyUp('right'); pyautogui.keyUp('ctrl')
        time.sleep(0.15)
    time.sleep(0.3)


def goto_col(line, col, wait=1.0):
    """跳行并把光标精确移到第 col 列（1 基）——落在指定 token 上。

    比 goto_token 可靠：列号由源码文本算出，不受缩进/词长影响。
    """
    goto(line)
    pyautogui.keyDown('home'); pyautogui.keyUp('home'); time.sleep(0.2)
    for _ in range(max(0, col - 1)):
        pyautogui.keyDown('right'); pyautogui.keyUp('right')
    time.sleep(wait)
