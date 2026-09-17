#!/bin/bash
# 手动运行 IntelliJ Plugin Verifier（绕过被沙盒文件锁阻塞的 gradle buildPlugin）
VERIFIER="C:/Users/USER/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/1.409/f35af25eb3d5cc4f8dabe0b7a9c340527980f6c/verifier-cli-1.409-all.jar"
PLUGIN_DIR="F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer/dev-support/verify-target"
IDE_243="C:/Users/USER/.gradle/caches/9.7.0/transforms/cfd9113040487d86d63d9022472046cb/transformed/ideaIU-2024.3.7.1-win"
IDE_253="C:/Users/USER/.gradle/caches/9.7.0/transforms/99985a92ea912855260eaa386f9b0240/transformed/idea-2025.3.5-win"
# T-VerifyIde2623：动态发现替代写死 hash 目录（caches 版本号/transform hash 每次换版本都变）
IDE_262=$(ls -d C:/Users/USER/.gradle/caches/*/transforms/*/transformed/idea-2026.2.3-win 2>/dev/null | head -1)

JAVA="C:/Users/USER/.jdks/ms-17.0.19/bin/java.exe"

"$JAVA" \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 \
  -jar "$VERIFIER" check-plugin "$PLUGIN_DIR" "$IDE_243" "$IDE_253" "$IDE_262"
echo "EXIT_CODE=$?"
