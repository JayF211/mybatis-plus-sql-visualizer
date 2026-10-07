# 日常开发快速兼容冒烟（仅 243，权威门禁 verifyGate，约 1.5min）
# T-RC-02：原跑 verifyPlugin -PverifyIdes=smoke（已降级为报告生成器），改挂 verifySmoke（= verifyGate 冒烟）。
# 用法：powershell -ExecutionPolicy Bypass -File dev-support/verify-smoke.ps1
Write-Host "🔍 Running plugin verification (smoke mode - 2024.3 only, authoritative verifyGate, ~1.5min)..."
& ./gradlew verifySmoke --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
