#!/bin/bash
# 手动运行 IntelliJ Plugin Verifier（绕过被沙盒文件锁阻塞的 gradle buildPlugin）
VERIFIER="C:/Users/USER/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/1.409/f35af25eb3d5cc4f8dabe0b7a9c340527980f6c/verifier-cli-1.409-all.jar"
PLUGIN_DIR="F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer/dev-support/verify-target"
IDE_243="C:/Users/USER/.gradle/caches/9.7.0/transforms/cfd9113040487d86d63d9022472046cb/transformed/ideaIU-2024.3.7.1-win"
IDE_253="C:/Users/USER/.gradle/caches/9.7.0/transforms/99985a92ea912855260eaa386f9b0240/transformed/idea-2025.3.5-win"
IDE_262="C:/Users/USER/.gradle/caches/9.7.0/transforms/af228bcb143270d170c2bd6debbba1c3/transformed/idea-2026.2.1-win"

JAVA="C:/Users/USER/.jdks/ms-17.0.19/bin/java.exe"

"$JAVA" \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 \
  -jar "$VERIFIER" check-plugin "$PLUGIN_DIR" "$IDE_243" "$IDE_253" "$IDE_262"
echo "EXIT_CODE=$?"
