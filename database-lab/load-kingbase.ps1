# =============================================================================
# load-kingbase.ps1 —— 人大金仓 KingbaseES 官方镜像 tar 导入脚本（T-DialectLabMatrix / T-DL-02）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File database-lab/load-kingbase.ps1 -Tar <kingbase tar 路径>
#
# tar 来源（人工，按 CPU 架构选包）：https://www.kingbase.com.cn/download.html 的
# Docker 镜像 tar 精简包（形如 kdb_x86_64_V009R001C002B0014.tar.gz，先解压出 tar 再导入）；
# 企业版需 License（https://www.kingbase.com.cn/sqwjxz/index.htm 免费申请开发授权）。
# 导入后按提示把实际镜像名填进 docker-compose.dialects-cn.example.yml 的占位。
# =============================================================================
param(
    [Parameter(Mandatory = $true)]
    [string]$Tar
)

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

if (-not (Test-Path $Tar)) {
    Write-Host "找不到 tar 包：$Tar（先从 https://www.kingbase.com.cn/download.html 下载 KingbaseES Docker 镜像；.tar.gz 需先解压出 .tar）" -ForegroundColor Red
    exit 1
}

Write-Host "== docker load 导入金仓镜像（较大，需数分钟）..." -ForegroundColor Cyan
& docker load -i $Tar
if ($LASTEXITCODE -ne 0) {
    Write-Host "docker load 失败（exit=$LASTEXITCODE）——确认 tar 完整性与 Docker 运行状态" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "== 导入完成。下一步：" -ForegroundColor Green
Write-Host "1) 执行 docker images 找到刚载入的金仓镜像名（REPOSITORY:TAG，形如 kingbase_v009r001c002b0014）"
Write-Host "2) 把镜像名填入 database-lab/docker-compose.dialects-cn.example.yml 的 kingbase.image 占位（企业版按需挂载 license.dat，见文件尾注释）"
Write-Host "3) docker compose -f database-lab/docker-compose.dialects-cn.example.yml up -d"
Write-Host "4) 按 database-lab/README.md「验证命令样例」跑 DialectDockerVerificationTest 外部实例模式（system 账户，端口 15433）"
exit 0
