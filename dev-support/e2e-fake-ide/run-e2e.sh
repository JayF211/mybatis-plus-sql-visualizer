#!/usr/bin/env bash
# 端到端验证驱动：假 IDE 探针（FakeIde） + 真实 Spring Boot/MPJ 应用（demo 工程）。
#
# 一条命令复现全部读数：
#   bash dev-support/e2e-fake-ide/run-e2e.sh
#
# 产物：
#   logs/probe.log    探针全量日志（帧级：时刻/命令/摘要 + 断言 PASS/FAIL）
#   logs/app.log      应用 stdout+stderr（agent 的 System.err 诊断打点在此）
#   logs/run-meta.txt 本次运行的环境与参数快照
#
# 退出码：0 = 探针全部断言通过；非 0 = 有断言失败（详见 logs/probe.log 的 SUMMARY 段）。
#
# 可用环境变量覆盖：AGENT_JAR / DEMO_DIR / MVN / PROBE_PORT / APP_PORT / APP_URL_BASE

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

# MSYS 路径（/f/code/...）Java 进程认不出来（会被当成相对路径）——凡是要交给 java 的路径
# 一律先转成 Windows 形式；cygpath 缺失时原样返回（纯 Windows shell 下本来就是对的形式）。
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

AGENT_JAR="${AGENT_JAR:-$REPO_ROOT/sub-projects/sql-visualizer-agent/build/dist/mps-sql-agent.jar}"
DEMO_DIR="${DEMO_DIR:-F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo/mybatis-plus-join-demo}"
MVN="${MVN:-/d/HuanJing/Maven/maven-mvnd-1.0.3/mvn/bin/mvn}"
PROBE_PORT="${PROBE_PORT:-53999}"
APP_PORT="${APP_PORT:-8082}"
LOGLEVEL="${LOGLEVEL:-info}"

LOGS="$HERE/evidence"
PROBE_LOG="$LOGS/probe.log"
APP_LOG="$LOGS/app.log"
META="$LOGS/run-meta.txt"
PROBE_CONSOLE="$LOGS/probe.console.log"

mkdir -p "$LOGS"
: > "$PROBE_LOG"
: > "$APP_LOG"

echo "=== T-AgentE2EFakeIde 端到端验证 ==="

# ── 1) 探针编译 ────────────────────────────────────────────────
echo "[1/6] 编译假 IDE 探针"
mkdir -p "$HERE/build"
javac -encoding UTF-8 -d "$HERE/build" "$HERE/FakeIde.java" || { echo "探针编译失败"; exit 3; }

# ── 2) agent 产物检查 ─────────────────────────────────────────
if [ ! -f "$AGENT_JAR" ]; then
  echo "缺少 agent jar：$AGENT_JAR"
  echo "请先构建：cd $REPO_ROOT && ./gradlew :sql-visualizer-agent:agentFatJar \\"
  echo "            -Pkotlin.compiler.execution.strategy=in-process"
  exit 3
fi
echo "      agent jar: $(ls -la "$AGENT_JAR" | awk '{print $5" bytes  "$6" "$7" "$8}')"

# ── 3) 应用 classpath / 编译 ──────────────────────────────────
CP_FILE="$DEMO_DIR/target/e2e-cp.txt"
if [ ! -f "$CP_FILE" ]; then
  echo "[2/6] 生成 demo classpath（$CP_FILE）"
  ( cd "$DEMO_DIR" && "$MVN" -q -o dependency:build-classpath -Dmdep.outputFile=target/e2e-cp.txt ) || {
    echo "classpath 生成失败（离线模式；确认 ~/.m2 已有依赖）"; exit 3; }
fi
if [ ! -d "$DEMO_DIR/target/classes" ]; then
  echo "编译 demo 工程"
  ( cd "$DEMO_DIR" && "$MVN" -q -o compile ) || { echo "demo 编译失败"; exit 3; }
fi
APP_CP="$(winpath "$DEMO_DIR/target/classes");$(cat "$CP_FILE")"

# ── 4) 起探针（必须先于应用：agent 在 premain 阶段回连） ──────
echo "[3/6] 启动假 IDE 探针 127.0.0.1:$PROBE_PORT"
MSYS2_ARG_CONV_EXCL='*' java -cp "$(winpath "$HERE/build")" FakeIde \
  --port "$PROBE_PORT" --script "$(winpath "$HERE/script-e2e.txt")" --log "$(winpath "$PROBE_LOG")" \
  > "$PROBE_CONSOLE" 2>&1 &
PROBE_PID=$!

for i in $(seq 1 30); do
  grep -q "LISTENING" "$PROBE_LOG" 2>/dev/null && break
  sleep 0.5
done
if ! grep -q "LISTENING" "$PROBE_LOG" 2>/dev/null; then
  echo "探针未就绪，日志尾部："; tail -20 "$PROBE_LOG"
  kill "$PROBE_PID" 2>/dev/null
  exit 3
fi
echo "      探针已监听（pid=$PROBE_PID）"

# ── 5) 起真实应用（-javaagent 注入，logLevel=info 打开定位器诊断） ──
echo "[4/6] 启动 demo 应用（javaagent 注入）"
{
  echo "date=$(date -Iseconds)"
  echo "probe_port=$PROBE_PORT app_port=$APP_PORT loglevel=$LOGLEVEL"
  echo "agent_jar=$AGENT_JAR"
  echo "agent_jar_sha256=$(sha256sum "$AGENT_JAR" | awk '{print $1}')"
  echo "demo_dir=$DEMO_DIR"
  echo "java=$(java -version 2>&1 | head -1)"
  echo "demo_git_head=$(cd "$DEMO_DIR" && git rev-parse HEAD 2>/dev/null)"
  echo "repo_git_head=$(cd "$REPO_ROOT" && git rev-parse HEAD)"
} > "$META"

MSYS2_ARG_CONV_EXCL='*' java \
  "-javaagent:$(winpath "$AGENT_JAR")=port=$PROBE_PORT;logLevel=$LOGLEVEL" \
  -Dfile.encoding=UTF-8 \
  -cp "$APP_CP" \
  cn.song.mpjdemo.MybatisPlusJoinDemoApplication \
  > "$APP_LOG" 2>&1 &
APP_PID=$!
echo "      应用已启动（pid=$APP_PID），日志：$APP_LOG"

# ── 6) 等探针跑完脚本（最多 300s，防挂死） ───────────────────
echo "[5/6] 等探针脚本执行完毕（握手/停采/恢复/脱敏/收口）"
PROBE_RC=1
for i in $(seq 1 300); do
  if ! kill -0 "$PROBE_PID" 2>/dev/null; then
    wait "$PROBE_PID"
    PROBE_RC=$?
    break
  fi
  sleep 1
done
if kill -0 "$PROBE_PID" 2>/dev/null; then
  echo "      探针超时未结束（300s），强制终止"
  kill "$PROBE_PID" 2>/dev/null
  PROBE_RC=124
fi
echo "      探针退出码 = $PROBE_RC"

# 给应用一点时间把最后一批诊断打点刷进日志
sleep 2
echo "[6/6] 关闭应用（pid=$APP_PID）"
kill "$APP_PID" 2>/dev/null
sleep 1
kill -9 "$APP_PID" 2>/dev/null
# MSYS 的 PID 与 Windows PID 不同域：kill 无效时按监听端口兜底收口
if netstat -ano 2>/dev/null | grep -q ":$APP_PORT .*LISTENING"; then
  WIN_PID="$(netstat -ano 2>/dev/null | grep ":$APP_PORT .*LISTENING" | head -1 | awk '{print $NF}')"
  if [ -n "${WIN_PID:-}" ]; then
    echo "      端口 $APP_PORT 仍在监听，taskkill Windows PID=$WIN_PID"
    taskkill //F //PID "$WIN_PID" >/dev/null 2>&1
  fi
fi

echo
echo "=== 产物 ==="
echo "  探针日志（帧级 + 断言）: $PROBE_LOG"
echo "  应用日志（agent 打点） : $APP_LOG"
echo "  运行元数据            : $META"
echo
echo "=== 探针 SUMMARY ==="
sed -n '/──────── SUMMARY ────────/,/─────────────────────────/p' "$PROBE_LOG"
echo
echo "=== 应用侧定位器诊断（entryKey 取证）==="
grep -a -E "T-SourceJumpFunnel|T-AgentSourceLocateStale|T-AgentConfigChannel" "$APP_LOG" | head -40
echo
echo "退出码 = $PROBE_RC"
exit "$PROBE_RC"
