# =============================================================================
# check_competitor_watch.ps1 —— 竞品 Marketplace 更新哨兵（T-McpGuard / N-16）
#
# 拉 JetBrains Marketplace updates API 三个竞品插件的最近版本，对 changeNotes
# 做关键词扫描（preview / log / restore / mcp / validate / 校验 / 预览 / 还原），
# 命中打印告警块；无论命中与否都产出完整报告供人工巡检。
# 网络失败自动用本地代理 127.0.0.1:7890 重试一次（再失败标「获取失败」，不编数）。
#
# 竞品清单（第四轮调研报告 §8.3）：
#   10119 MyBatisX（540 万下载，待审新版盲区——重点盯守）
#   14522 CodeHelperPro 付费版（3.6.5 已加 validate_mybatis_mapper MCP 工具）
#   32766  MyBatis SQL Analyzer（${} 注入标注差异化细节来源）
#
# 日志：dev-support/verify-logs/competitor-watch-<时间戳>.log（追加，轮转保留 8 份）
#
# Windows 计划任务注册命令（唯一注册来源，任意 PowerShell 窗口执行一次；
# 系统重装或仓库迁移后重跑恢复，路径以实际仓库位置为准）：
#
#   $action   = New-ScheduledTaskAction -Execute "powershell.exe" `
#               -Argument '-NoProfile -ExecutionPolicy Bypass -File "F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-dev\dev-support\check_competitor_watch.ps1"' `
#               -WorkingDirectory "F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-dev"
#   $trigger  = New-ScheduledTaskTrigger -Daily -At 09:30
#   $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable
#   Register-ScheduledTask -TaskName "MPSqlVisualizer-CompetitorWatch" `
#       -Action $action -Trigger $trigger -Settings $settings `
#       -Description "每日竞品 Marketplace 更新关键词哨兵（MyBatisX/CodeHelperPro/Analyzer）；报告落 dev-support/verify-logs/competitor-watch-*.log" -Force
#
# 注册后核对：
#   Get-ScheduledTask -TaskName "MPSqlVisualizer-CompetitorWatch" | Get-ScheduledTaskInfo
#
# 告警只提示不阻塞：脚本任何失败 exit 0（计划任务不重试轰炸），结论看报告正文。
# =============================================================================

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$logDir  = Join-Path $PSScriptRoot "verify-logs"
$stamp   = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile = Join-Path $logDir "competitor-watch-$stamp.log"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-Log([string]$Message) {
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Message"
    Write-Host $line
    $line | Tee-Object -FilePath $logFile -Append | Out-Null
}

# 竞品：id → 名称
$watch = [ordered]@{
    10119 = "MyBatisX"
    14522 = "CodeHelperPro"
    32766 = "MyBatis SQL Analyzer"
}

# 关键词（英文按词边界匹配降噪，中文子串匹配）
$keywordsEn = @('preview', 'log', 'restore', 'mcp', 'validate')
$keywordsZh = @('校验', '预览', '还原')

function Match-Keywords([string]$Text) {
    if (-not $Text) { return @() }
    $hits = @()
    foreach ($kw in $keywordsEn) {
        if ($Text -match "(?i)\b$kw\b") { $hits += $kw }
    }
    foreach ($kw in $keywordsZh) {
        if ($Text.Contains($kw)) { $hits += $kw }
    }
    return $hits | Select-Object -Unique
}

function Strip-Html([string]$Html) {
    if (-not $Html) { return "" }
    return ($Html -replace '<[^>]+>', ' ' -replace '\s+', ' ').Trim()
}

# curl 直连拉取；失败用本地代理重试一次；再失败返回 $null（调用方标获取失败）
function Fetch-Updates([int]$PluginId) {
    $url = "https://plugins.jetbrains.com/api/plugins/$PluginId/updates?size=3"
    foreach ($proxy in @(@(), @('-x', '127.0.0.1:7890'))) {
        $args = @('-s', '--max-time', '30') + $proxy + @($url)
        $raw = & curl.exe @args 2>$null
        if ($LASTEXITCODE -eq 0 -and $raw) {
            try { return ($raw | ConvertFrom-Json) } catch { }
        }
    }
    return $null
}

Write-Log "=== 竞品更新哨兵开始（日志：$logFile）==="

$alarmCount = 0
foreach ($entry in $watch.GetEnumerator()) {
    $id = $entry.Key
    $name = $entry.Value
    Write-Log "---- $name (#$id) ----"
    $updates = Fetch-Updates $id
    if ($null -eq $updates) {
        Write-Log "获取失败（直连与代理均不可达），本轮跳过——不编数，人工复核：https://plugins.jetbrains.com/plugin/$id/changes"
        continue
    }
    if ($updates.Count -eq 0) {
        Write-Log "API 返回空列表（可能全部版本待审/下架）——人工复核上述页面"
        continue
    }
    foreach ($u in $updates) {
        $notes = Strip-Html $u.notes
        $hits = Match-Keywords $notes
        $date = if ($u.cdate) { ([DateTimeOffset]::FromUnixTimeMilliseconds([long]$u.cdate).LocalDateTime).ToString('yyyy-MM-dd') } else { "?" }
        if ($hits.Count -gt 0) {
            $script:alarmCount++
            $excerpt = if ($notes.Length -gt 220) { $notes.Substring(0, 220) + "..." } else { $notes }
            Write-Log ("⚠ 命中关键词 [{0}] — {1} v{2}（{3}）：{4}" -f ($hits -join ', '), $name, $u.version, $date, $excerpt)
        } else {
            Write-Log "未命中 — v$($u.version)（$date）"
        }
    }
}

# 日志轮转：仅保留最近 8 份 competitor-watch-*.log
$logs = @(Get-ChildItem -Path $logDir -Filter "competitor-watch-*.log" -File | Sort-Object LastWriteTime -Descending)
if ($logs.Count -gt 8) {
    $logs | Select-Object -Skip 8 | ForEach-Object {
        Remove-Item -Path $_.FullName -Force
        Write-Log "轮转删除旧日志：$($_.Name)"
    }
}

if ($alarmCount -gt 0) {
    Write-Log "=== 哨兵结束：$alarmCount 处关键词命中，详见上方告警块；对照第四轮报告 §9 风险预案评估是否需要响应 ==="
} else {
    Write-Log "=== 哨兵结束：无关键词命中 ==="
}
exit 0
