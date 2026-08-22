#!/usr/bin/env bash
# 本地清单测试：启动 Python 清单服务器（读 update/update.json 真实内容）→ 运行沙盒 IDE → IDE 关闭后自动停服务器
# 不影响 Gitee 用户（清单 URL 指向 localhost）
#
# 用法：bash dev-support/run-ide-local-manifest.sh [端口]
# 默认：端口 8919

set -euo pipefail

PORT="${1:-8919}"
MANIFEST_URL="http://localhost:${PORT}/update.json"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SERVER_LOG="${PROJECT_DIR}/.intellijPlatform/local-manifest-server.log"

mkdir -p "$(dirname "$SERVER_LOG")"

# 检查清单文件是否存在
if [ ! -f "${PROJECT_DIR}/update/update.json" ]; then
    echo "[local-错误] update/update.json 不存在，请先创建"
    exit 1
fi

echo "[local-manifest] 启动清单服务器（端口 ${PORT}，读取 update/update.json 真实内容）..."
python "${SCRIPT_DIR}/local-manifest-server.py" "$PORT" > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

# 等待服务器就绪
for i in $(seq 1 50); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "[local-错误] 服务器启动失败，查看 $SERVER_LOG"
        cat "$SERVER_LOG"
        exit 1
    fi
    if python -c "
import socket, sys
s = socket.socket()
try:
    s.connect(('127.0.0.1', $PORT)); s.close(); sys.exit(0)
except:
    sys.exit(1)
" 2>/dev/null; then
        break
    fi
    sleep 0.1
done

# 清理函数：IDE 关闭后停服务器
cleanup() {
    echo "[local-manifest] 停止清单服务器（PID $SERVER_PID）"
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "[local-manifest] 启动沙盒 IDE..."
(cd "$PROJECT_DIR" && ./gradlew runIde -Dmpvs.update.manifest.url="$MANIFEST_URL" --no-daemon)
