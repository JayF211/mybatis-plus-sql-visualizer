# 发布前全量验证（三版本 243+253+262，约 14min）
# 用法：powershell -ExecutionPolicy Bypass -File dev-support/verify-full.ps1
Write-Host "🔍 Running plugin verification (full mode - 3 versions, ~14min)..."
& ./gradlew verifyPlugin --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
