# =============================================================================
# load-dm8.ps1 —— 达梦 DM8 官方镜像 tar 导入脚本（T-DialectLabMatrix / T-DL-02）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File database-lab/load-dm8.ps1 -Tar <dm8 tar 路径>
#
# tar 来源（人工，实名注册）：https://eco.dameng.com/download/ 的 Docker 镜像条目，
# 形如 dm8_20250206_x86_rh6_rq_single.tar。达梦无 Docker Hub 官方镜像，本脚本不做下载。
# 导入后按提示把实际镜像名填进 docker-compose.dialects-cn.example.yml 的占位。
# =============================================================================
param(
    [Parameter(Mandatory = $true)]
    [string]$Tar
)

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

if (-not (Test-Path $Tar)) {
    Write-Host "找不到 tar 包：$Tar（先从 https://eco.dameng.com/download/ 下载 DM8 Docker 镜像）" -ForegroundColor Red
    exit 1
}

Write-Host "== docker load 导入达梦镜像（较大，需数分钟）..." -ForegroundColor Cyan
& docker load -i $Tar
if ($LASTEXITCODE -ne 0) {
    Write-Host "docker load 失败（exit=$LASTEXITCODE）——确认 tar 完整性与 Docker 运行状态" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "== 导入完成。下一步：" -ForegroundColor Green
Write-Host "1) 执行 docker images 找到刚载入的达梦镜像名（REPOSITORY:TAG，形如 dm8_single:dm8_20250206_x86_rh6_rq_single）"
Write-Host "2) 把镜像名填入 database-lab/docker-compose.dialects-cn.example.yml 的 dm8.image 占位"
Write-Host "3) docker compose -f database-lab/docker-compose.dialects-cn.example.yml up -d"
Write-Host "4) 按 database-lab/README.md「验证命令样例」跑 DialectDockerVerificationTest 外部实例模式（默认 SYSDBA/SYSDBA，端口 15236）"
exit 0
