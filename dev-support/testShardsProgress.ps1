# =============================================================================
# testShardsProgress.ps1 —— 全量门禁（testShards）实时进度包装
#
# 为什么需要：`gradlew testShards` 在 `--console=plain` 下只有任务开始/结束两行，
# 五个分片并发跑十几分钟期间终端没有任何读数，判断「跑到哪了 / 卡没卡」只能靠等。
#
# 用法（在你自己的终端里跑，进度就刷在同一个窗口）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support\testShardsProgress.ps1
#   ... -IntervalSeconds 5          # 刷新间隔（默认 10 秒）
#   ... -NoConfigCache:$false       # 需要复用配置缓存时关掉（默认关缓存，防分片清单被钉死）
#   ... -ExtraArgs "--tests", "..." # 追加任意 gradle 参数
#
# 输出形态（每 IntervalSeconds 刷一行）：
#   [03:12] 已跑 3m12s | 分片 2/5 完成 | 测试 3120 | 失败 0 | 正在: shard2 shard3 | 日志尾: > Task :testShard3
# 结束打印每个分片的 tests/failures 与失败用例清单，退出码 = gradle 退出码。
#
# 读数来源（都是既有产物，不额外埋点）：
#   - 分片完成：build/test-results/testShard<N>/ 下 TEST-*.xml 存在
#   - 计数：解析各 XML 根节点的 tests/failures/errors/skipped
#   - 正在跑哪个分片：日志里出现 `> Task :testShard<N>` 且该片尚未产出结果
# =============================================================================
param(
    [int]$IntervalSeconds = 10,
    [switch]$NoConfigCache = $true,
    [string[]]$ExtraArgs = @()
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $env:TEMP "mpsv-testshards-$stamp.log"
$errLog = Join-Path $env:TEMP "mpsv-testshards-$stamp.err.log"
$shardCount = 5

$gradleArgs = @('testShards', '--console=plain', '--continue')
if ($NoConfigCache) { $gradleArgs += '--no-configuration-cache' }
if ($ExtraArgs.Count -gt 0) { $gradleArgs += $ExtraArgs }

Write-Host "门禁日志：$log"
Write-Host "（另开窗口看原始输出：Get-Content '$log' -Wait -Tail 30）"
Write-Host ""

$started = Get-Date
$proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList $gradleArgs `
    -RedirectStandardOutput $log -RedirectStandardError $errLog -PassThru -NoNewWindow

function Get-ShardStats {
    param([int]$Index)
    $dir = Join-Path $root "build/test-results/testShard$Index"
    if (-not (Test-Path $dir)) { return $null }
    $files = @(Get-ChildItem -Path $dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { return $null }
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($f in $files) {
        try {
            [xml]$x = Get-Content -LiteralPath $f.FullName -ErrorAction Stop
            $tests += [int]$x.testsuite.tests
            $failures += [int]$x.testsuite.failures
            $errors += [int]$x.testsuite.errors
            $skipped += [int]$x.testsuite.skipped
        } catch { }
    }
    return [pscustomobject]@{ Tests = $tests; Failures = $failures; Errors = $errors; Skipped = $skipped; Classes = $files.Count }
}

function Get-LogTail {
    if (-not (Test-Path $log)) { return '' }
    try {
        $last = Get-Content -LiteralPath $log -Tail 1 -ErrorAction Stop
        if ($null -eq $last) { return '' }
        $line = ($last -join ' ').Trim()
        if ($line.Length -gt 70) { $line = $line.Substring(0, 70) + '…' }
        return $line
    } catch { return '' }
}

while (-not $proc.HasExited) {
    Start-Sleep -Seconds $IntervalSeconds
    $done = @(); $running = @(); $totalTests = 0; $totalFail = 0
    for ($i = 1; $i -le $shardCount; $i++) {
        $s = Get-ShardStats -Index $i
        if ($s) {
            $done += "shard$i"
            $totalTests += $s.Tests
            $totalFail += ($s.Failures + $s.Errors)
        }
    }
    if (Test-Path $log) {
        $text = Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue
        for ($i = 1; $i -le $shardCount; $i++) {
            if ("shard$i" -notin $done -and $text -and $text.Contains("> Task :testShard$i")) {
                $running += "shard$i"
            }
        }
    }
    $elapsed = (Get-Date) - $started
    $elapsedText = '{0:mm\:ss}' -f $elapsed
    $tail = Get-LogTail
    $line = "[{0}] 已跑 {1} | 分片 {2}/{3} 完成 | 测试 {4} | 失败 {5}" -f `
        (Get-Date -Format 'HH:mm'), $elapsedText, $done.Count, $shardCount, $totalTests, $totalFail
    if ($running.Count -gt 0) { $line += " | 正在: $($running -join ' ')" }
    if ($tail) { $line += " | 日志尾: $tail" }
    Write-Host $line
}

$exit = $proc.ExitCode
Write-Host ""
Write-Host "=== 门禁结束（退出码 $exit） ==="
$grandTests = 0; $grandFail = 0; $grandErr = 0; $grandSkip = 0
$failNames = New-Object System.Collections.Generic.List[string]
for ($i = 1; $i -le $shardCount; $i++) {
    $s = Get-ShardStats -Index $i
    if ($s) {
        Write-Host ("  shard{0}: tests={1} failures={2} errors={3} skipped={4} classes={5}" -f `
            $i, $s.Tests, $s.Failures, $s.Errors, $s.Skipped, $s.Classes)
        $grandTests += $s.Tests; $grandFail += $s.Failures; $grandErr += $s.Errors; $grandSkip += $s.Skipped
        $dir = Join-Path $root "build/test-results/testShard$i"
        Get-ChildItem -Path $dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
            try {
                [xml]$x = Get-Content -LiteralPath $_.FullName -ErrorAction Stop
                foreach ($tc in $x.testsuite.testcase) {
                    if ($tc.failure -or $tc.error) { $failNames.Add("$($tc.classname).$($tc.name)") }
                }
            } catch { }
        }
    } else {
        Write-Host ("  shard{0}: 无结果（未执行或产物缺失）" -f $i)
    }
}
Write-Host ("  合计: tests={0} failures={1} errors={2} skipped={3}" -f $grandTests, $grandFail, $grandErr, $grandSkip)
if ($failNames.Count -gt 0) {
    Write-Host "  失败用例："
    $failNames | Sort-Object -Unique | ForEach-Object { Write-Host "   - $_" }
}
Write-Host "  日志：$log"
exit $exit
