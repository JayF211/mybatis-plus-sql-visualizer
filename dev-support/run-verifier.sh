#!/bin/bash
# 手动运行 IntelliJ Plugin Verifier（绕过被沙盒文件锁阻塞的 gradle buildPlugin）
#
# 本机特有值不再写死（2026-10-10 T-LocalEnvOverride）。取值优先级：
#   1) .local/local.env（存在则先 source；键名见 .local/local.env.example）
#   2) 环境变量 MPSV_*
#   3) 动态发现（$HOME/.gradle caches）或 $JAVA_HOME / PATH
# 任一必需项缺失都给出可读报错并退出，不做静默降级。

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# ---- 可选的本机覆盖 ----------------------------------------------------
if [ -f "$PROJECT_DIR/.local/local.env" ]; then
    # shellcheck disable=SC1091
    . "$PROJECT_DIR/.local/local.env"
fi

MPSV_VERIFIER_JAR="${MPSV_VERIFIER_JAR:-}"
MPSV_JAVA="${MPSV_JAVA:-}"
MPSV_IDE_243="${MPSV_IDE_243:-}"
MPSV_IDE_253="${MPSV_IDE_253:-}"
MPSV_IDE_262="${MPSV_IDE_262:-}"
# 代理：不设则用本机默认；显式设空串＝关闭代理
MPSV_PROXY_HOST="${MPSV_PROXY_HOST-127.0.0.1}"
MPSV_PROXY_PORT="${MPSV_PROXY_PORT-7890}"

GRADLE_HOME_DIR="${HOME}/.gradle"

# ---- 动态发现 ----------------------------------------------------------
discover_verifier() {
    ls -d "$GRADLE_HOME_DIR"/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/*/*/verifier-cli-*-all.jar 2>/dev/null | sort -V | tail -1
}
# $1 = IDE 目录名（如 ideaIU-2024.3.7.1-win）
discover_ide() {
    local hit
    hit="$(ls -d "$GRADLE_HOME_DIR"/caches/*/transforms/*/transformed/"$1" 2>/dev/null | head -1)"
    printf '%s' "${hit%/}"
}

VERIFIER="${MPSV_VERIFIER_JAR:-$(discover_verifier)}"
IDE_243="${MPSV_IDE_243:-$(discover_ide 'ideaIU-2024.3.7.1-win')}"
IDE_253="${MPSV_IDE_253:-$(discover_ide 'idea-2025.3.5-win')}"
IDE_262="${MPSV_IDE_262:-$(discover_ide 'idea-2026.2.3-win')}"

if [ -n "$MPSV_JAVA" ]; then
    JAVA="$MPSV_JAVA"
elif [ -n "${JAVA_HOME:-}" ]; then
    # JAVA_HOME 常是反斜杠的 Windows 路径，统一成正斜杠再拼 bin/java
    JAVA="${JAVA_HOME//\\//}/bin/java"
else
    JAVA="$(command -v java || true)"
fi

PLUGIN_DIR="$PROJECT_DIR/dev-support/verify-target"

# ---- 前置检查（缺失即报错，不静默降级） --------------------------------
fail=0
check_file() {
    if [ -z "$2" ] || [ ! -e "$2" ]; then
        echo "[run-verifier] 缺少 $1: ${2:-<空>}" >&2
        fail=1
    fi
}
check_file "verifier jar（设 MPSV_VERIFIER_JAR，或检查 gradle caches）" "$VERIFIER"
check_file "IDE 243 发行版（设 MPSV_IDE_243）" "$IDE_243"
check_file "IDE 253 发行版（设 MPSV_IDE_253）" "$IDE_253"
check_file "IDE 262 发行版（设 MPSV_IDE_262）" "$IDE_262"
check_file "java（设 MPSV_JAVA 或 JAVA_HOME）" "$JAVA"
check_file "被测插件目录（先跑 buildPlugin 产出）" "$PLUGIN_DIR"
if [ "$fail" -ne 0 ]; then
    echo "[run-verifier] 复制 .local/local.env.example 为 .local/local.env 可覆盖以上路径。" >&2
    exit 1
fi

PROXY_ARGS=()
if [ -n "$MPSV_PROXY_HOST" ] && [ -n "$MPSV_PROXY_PORT" ]; then
    PROXY_ARGS=(-Dhttp.proxyHost="$MPSV_PROXY_HOST" -Dhttp.proxyPort="$MPSV_PROXY_PORT"
                -Dhttps.proxyHost="$MPSV_PROXY_HOST" -Dhttps.proxyPort="$MPSV_PROXY_PORT")
fi

# 报告落点：verifier 把报告写到「当前工作目录」下的 verification-<时间戳>/——直接在仓库根跑
# 会持续污染根目录（T-LocalEnvOverride 2026-10-10 实测产生一个）。改为在已 gitignore 的
# dev-support/verify-logs/ 下执行（-jar 与插件目录都是绝对路径，切 CWD 安全）。
REPORT_DIR="$PROJECT_DIR/dev-support/verify-logs"
mkdir -p "$REPORT_DIR"
(
  cd "$REPORT_DIR" || exit 1
  "$JAVA" ${PROXY_ARGS[@]+"${PROXY_ARGS[@]}"} \
    -jar "$VERIFIER" check-plugin "$PLUGIN_DIR" "$IDE_243" "$IDE_253" "$IDE_262"
)
echo "EXIT_CODE=$?"
