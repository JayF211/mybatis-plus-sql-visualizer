#!/bin/bash
# 日常开发快速兼容冒烟（仅 243，约 1.5min）
# 用法：bash dev-support/verify-smoke.sh
set -e
echo "🔍 Running plugin verification (smoke mode - 2024.3 only, ~1.5min)..."
./gradlew verifyPlugin -PverifyIdes=smoke --no-daemon
