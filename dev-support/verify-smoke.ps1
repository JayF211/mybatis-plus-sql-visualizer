# 日常开发快速兼容冒烟（仅 243，约 1.5min）
# 用法：powershell -ExecutionPolicy Bypass -File dev-support/verify-smoke.ps1
Write-Host "🔍 Running plugin verification (smoke mode - 2024.3 only, ~1.5min)..."
& ./gradlew verifyPlugin -PverifyIdes=smoke --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
