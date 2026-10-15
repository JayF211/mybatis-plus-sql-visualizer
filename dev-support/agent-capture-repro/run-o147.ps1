param([string]$Label = 'initial', [string]$Tests = 'cn.song.mybatisplus.visualizer.log.O147RealAgentAcceptanceTest', [switch]$AgentTests)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $root
$env:JAVA_HOME = 'C:\Users\USER\.jdks\ms-17.0.19'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
python C:\Users\USER\.zcode\cli\parallel_tasks.py check
python C:\Users\USER\.zcode\cli\parallel_tasks.py register --name t-m19-agent-acceptance --sandbox MPSV-O147-Acceptance --hours 2 --note 'O147 real JVM targeted acceptance'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
try {
    $argsText = if ($AgentTests) { ':sql-visualizer-agent:test' } else {
        $filters = ($Tests -split ',' | ForEach-Object { '--tests ' + $_ }) -join ' '
        ':sql-visualizer-agent:agentFatJar :sql-visualizer-agent:childappClasses testFast ' + $filters
    }
    $command = '.\gradlew.bat ' + $argsText + ' -Pkotlin.compiler.execution.strategy=in-process --console=plain'
    & (Join-Path $root 'dev-support/gradle-gate.ps1') -MaxConcurrent 2 -WaitTimeoutSeconds 10 -Command $command
    $code = $LASTEXITCODE
    Write-Output "O147 label=$Label exit=$code"
    exit $code
} finally {
    python C:\Users\USER\.zcode\cli\parallel_tasks.py done --name t-m19-agent-acceptance
}
