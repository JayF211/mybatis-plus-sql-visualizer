# push-all.ps1 —— 双仓推送：Gitee（主仓）+ GitHub（镜像）
#
# 为什么需要它：本仓双平台同步，Gitee 是主仓、GitHub 是镜像。手工双推要记三件事——
#   ① GitHub 端必须挂本地代理（127.0.0.1:7890），Gitee 端不需要；
#   ② GitHub 端必须 `-c credential.helper=` 置空，否则 GCM 会弹 GUI 抢凭据并常驻不退；
#   ③ 令牌从 .local/github-token.json 读，不进命令行历史。
# 本脚本把这三点固定下来，并保证一端失败不阻断另一端（不用 && 串联，见 AGENTS.md 收口纪律）。
#
# 用法（在仓库根或任意子目录执行均可）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/push-all.ps1
#   powershell ... -File dev-support/push-all.ps1 -All          # master + dev + feature 三支
#   powershell ... -File dev-support/push-all.ps1 -Tags         # 附带推送全部 tag
#   powershell ... -File dev-support/push-all.ps1 -DryRun       # 只打印将执行的命令
#   powershell ... -File dev-support/push-all.ps1 -GiteeOnly    # 只推主仓（网络受限时）
#
# 退出码：0 = 两端都成功（或 DryRun）；1 = 至少一端失败。失败端逐条列在末尾摘要里。

[CmdletBinding()]
param(
    [switch]$All,
    [switch]$Tags,
    [switch]$DryRun,
    [switch]$GiteeOnly,
    [string]$Branch,
    [int]$ProxyPort = 7890
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) { Write-Host "    OK   $Message" -ForegroundColor Green }
function Write-Fail([string]$Message) { Write-Host "    FAIL $Message" -ForegroundColor Red }
function Write-Note([string]$Message) { Write-Host "    ..   $Message" -ForegroundColor DarkGray }

# ---- 定位仓库根（兼容从任意子目录调用）----
$repoRoot = (git -C $PSScriptRoot rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Fail "无法定位 git 仓库根：$PSScriptRoot 不在任何 git 工作树内"
    exit 1
}
$repoRoot = $repoRoot.Trim()
Write-Note "仓库根：$repoRoot"

# ---- 待推分支 ----
$currentBranch = (git -C $repoRoot rev-parse --abbrev-ref HEAD).Trim()
if ($currentBranch -eq 'HEAD') {
    Write-Fail "当前处于 detached HEAD，请先切到分支再推"
    exit 1
}

$branches = @()
if ($Branch) {
    $branches = @($Branch)
} elseif ($All) {
    $branches = @('master', 'dev', 'feature/kotlin-to-java')
    # 只推实际存在的分支
    $existing = @(git -C $repoRoot for-each-ref --format='%(refname:short)' refs/heads)
    $branches = @($branches | Where-Object { $existing -contains $_ })
} else {
    $branches = @($currentBranch)
}
Write-Note "待推分支：$($branches -join ', ')"

# ---- GitHub 令牌 ----
$tokenFile = Join-Path $repoRoot '.local/github-token.json'
$ghToken = $null
if (Test-Path $tokenFile) {
    try {
        $cfg = Get-Content $tokenFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $ghToken = $cfg.github_personal_access_token
        $ghOwner = if ($cfg.owner) { $cfg.owner } else { 'SongRenShuo' }
        $ghRepo = if ($cfg.repo) { $cfg.repo } else { 'mybatis-plus-sql-visualizer' }
    } catch {
        Write-Fail "读取 $tokenFile 失败：$($_.Exception.Message)"
    }
} else {
    Write-Note "未找到 $tokenFile（GitHub 端将跳过）"
}

# ---- 推送实现：一端失败不影响另一端 ----
$results = @()

function Invoke-Push {
    param(
        [string]$Name,
        [string[]]$GitArgs
    )
    # 令牌脱敏：DryRun 与日志都不得出现明文（URL 里的 x-access-token:<token>@ 段替换为 ***）
    $display = "git " + (($GitArgs -join ' ') -replace 'x-access-token:[^@]+@', 'x-access-token:***@')
    if ($DryRun) {
        Write-Note "[$Name] $display"
        return @{ Name = $Name; Ok = $true; Detail = 'dry-run' }
    }
    Write-Note "[$Name] $display"
    # 令牌走环境变量注入的 URL 里，这里屏蔽输出避免落日志
    $output = & git -C $repoRoot @GitArgs 2>&1
    $code = $LASTEXITCODE
    if ($code -eq 0) {
        Write-Ok "$Name 推送成功"
        return @{ Name = $Name; Ok = $true; Detail = 'ok' }
    }
    Write-Fail "$Name 推送失败（exit $code）"
    $output | Select-Object -Last 6 | ForEach-Object { Write-Host "         $_" -ForegroundColor DarkYellow }
    return @{ Name = $Name; Ok = $false; Detail = ($output | Select-Object -Last 3) -join ' / ' }
}

# ---- 1. Gitee（主仓）----
Write-Step "推送 Gitee（主仓）"
foreach ($b in $branches) {
    $results += Invoke-Push -Name "Gitee:$b" -GitArgs @('push', 'origin', "${b}:${b}")
}
if ($Tags) {
    $results += Invoke-Push -Name 'Gitee:tags' -GitArgs @('push', 'origin', '--tags')
}

# ---- 2. GitHub（镜像）----
if ($GiteeOnly) {
    Write-Step "跳过 GitHub（-GiteeOnly）"
} elseif (-not $ghToken) {
    Write-Step "跳过 GitHub（无令牌）"
    Write-Note "创建 $tokenFile，内容形如：{ ""github_personal_access_token"": ""gho_..."", ""owner"": ""SongRenShuo"", ""repo"": ""mybatis-plus-sql-visualizer"" }"
} else {
    Write-Step "推送 GitHub（镜像）"
    $ghUrl = "https://x-access-token:${ghToken}@github.com/${ghOwner}/${ghRepo}.git"
    foreach ($b in $branches) {
        $results += Invoke-Push -Name "GitHub:$b" -GitArgs @(
            '-c', "http.proxy=http://127.0.0.1:$ProxyPort",
            '-c', 'credential.helper=',
            'push', $ghUrl, "${b}:${b}"
        )
    }
    if ($Tags) {
        $results += Invoke-Push -Name 'GitHub:tags' -GitArgs @(
            '-c', "http.proxy=http://127.0.0.1:$ProxyPort",
            '-c', 'credential.helper=',
            'push', $ghUrl, '--tags'
        )
    }
}

# ---- 摘要 ----
Write-Step "摘要"
$failed = @($results | Where-Object { -not $_.Ok })
foreach ($r in $results) {
    if ($r.Ok) { Write-Ok $r.Name } else { Write-Fail "$($r.Name) —— $($r.Detail)" }
}

if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "有 $($failed.Count) 项失败。常见原因：" -ForegroundColor Yellow
    Write-Host "  - GitHub 端 000 / exit 35：本地代理未启动或抖动，确认 127.0.0.1:$ProxyPort 可用；直连通常也能通（去掉 -c http.proxy 试一次）" -ForegroundColor DarkGray
    Write-Host "  - GitHub 端认证失败：令牌过期或 scope 不含 repo，重新取令牌写入 .local/github-token.json" -ForegroundColor DarkGray
    Write-Host "  - Gitee 端失败：检查 origin 远端与网络（Gitee 不走代理）" -ForegroundColor DarkGray
    exit 1
}

Write-Host ""
Write-Ok "双仓推送完成"
exit 0
