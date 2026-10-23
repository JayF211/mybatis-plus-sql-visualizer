# T-ReleasePerf8 S1 helper: verifier home 下 loaded-plugins 目录快照与轮间 diff。
# 背景：marketplace 在线门禁的下载税取证需要回答两个问题——
#   ① 一轮之后哪些文件是新的（mtime/build 号推进了多少）→ snapshot 模式轮前轮后各跑一次 + diff 模式比对；
#   ② 两轮之间 build 号集合的复用率 → diff 模式输出「新增/消失/共用」三段计数与字节量。
# 用法：
#   snapshot: powershell -File verify-net-probe.ps1 snapshot -Dir <loaded-plugins 目录> -Out <清单文件>
#   diff:     powershell -File verify-net-probe.ps1 diff -Before <旧清单> -After <新清单>
# 清单格式：制表符分隔 name<TAB>bytes<TAB>mtimeTicks，只列文件不递归（loaded-plugins 是平目录）。
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Mode,
    [string]$Dir,
    [string]$Out,
    [string]$Before,
    [string]$After
)
if ($Mode -eq "snapshot") {
    if (-not $Dir -or -not $Out) { throw "snapshot 需要 -Dir 与 -Out" }
    $dirInfo = Get-Item -LiteralPath $Dir -ErrorAction SilentlyContinue
    if ($null -eq $dirInfo) {
        "EMPTY" | Out-File -FilePath $Out -Encoding utf8
        exit 0
    }
    Get-ChildItem -LiteralPath $Dir -File | ForEach-Object {
        "$($_.Name)`t$($_.Length)`t$($_.LastWriteTimeUtc.Ticks)"
    } | Out-File -FilePath $Out -Encoding utf8
    exit 0
}
if ($Mode -eq "diff") {
    if (-not $Before -or -not $After) { throw "diff 需要 -Before 与 -After" }
    function Read-Manifest($path) {
        $m = @{}
        if (Test-Path -LiteralPath $path) {
            Get-Content -LiteralPath $path | ForEach-Object {
                $p = $_ -split "`t"
                if ($p.Count -ge 3) { $m[$p[0]] = [long]$p[1] }
            }
        }
        return $m
    }
    $b = Read-Manifest $Before
    $a = Read-Manifest $After
    $added = $a.Keys | Where-Object { -not $b.ContainsKey($_) }
    $removed = $b.Keys | Where-Object { -not $a.ContainsKey($_) }
    $kept = $a.Keys | Where-Object { $b.ContainsKey($_) }
    $addedBytes = ($added | ForEach-Object { $a[$_] } | Measure-Object -Sum).Sum
    $removedBytes = ($removed | ForEach-Object { $b[$_] } | Measure-Object -Sum).Sum
    $keptBytes = ($kept | ForEach-Object { $a[$_] } | Measure-Object -Sum).Sum
    "added=$($added.Count)/$([math]::Round($addedBytes/1MB,1))MB removed=$($removed.Count)/$([math]::Round($removedBytes/1MB,1))MB kept=$($kept.Count)/$([math]::Round($keptBytes/1MB,1))MB total=$($a.Count)/$([math]::Round(($a.Values | Measure-Object -Sum).Sum/1MB,1))MB"
    $added | Sort-Object | ForEach-Object { "ADD  $_" }
    $removed | Sort-Object | ForEach-Object { "DEL  $_" }
    exit 0
}
throw "未知模式 $Mode（snapshot | diff）"
