#!/bin/bash
# 发布前全量验证（三版本 243+253+262，约 14min）
# 用法：bash dev-support/verify-full.sh
set -e
echo "🔍 Running plugin verification (full mode - 3 versions, ~14min)..."
./gradlew verifyPlugin --no-daemon
