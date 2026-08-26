# v8.9.6(12.7): 短请求并发压测（PowerShell 多 Job 聚合版——k6 镜像不可用时的替代）
# 每个 worker 为独立 PowerShell 进程，顺序请求固定次数，父进程聚合 P50/P95/失败数与并发 RPS。
# 用法：
#   .\perf\load-shortrequests.ps1 -Username perf-smoke -Password 'xxx' -Workers 8 -PerWorker 150
param(
    [string]$BaseUrl = "http://127.0.0.1:8000",
    [string]$Username = "admin",
    [string]$Password = "",
    [int]$Workers = 8,
    [int]$PerWorker = 150
)

$ErrorActionPreference = 'Stop'
if (-not $Password) {
    $line = Select-String -Path (Join-Path $PSScriptRoot "..\.env") -Pattern "^APP_ADMIN_PASSWORD=(.+)$"
    if ($line) { $Password = $line.Matches[0].Groups[1].Value.Trim() }
}

$login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post `
    -ContentType "application/json" `
    -Body (@{ username = $Username; password = $Password } | ConvertTo-Json) -TimeoutSec 15
$token = $login.data.token
if (-not $token) { throw "登录失败" }

$workerScript = {
    param($BaseUrl, $token, $count)
    $dur = New-Object System.Collections.Generic.List[long]
    $fail = 0
    for ($i = 0; $i -lt $count; $i++) {
        $url = if ($i % 2 -eq 0) { "$BaseUrl/api/health" } else { "$BaseUrl/api/projects" }
        $t = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            if ($i % 2 -eq 0) {
                Invoke-RestMethod -Uri $url -TimeoutSec 10 | Out-Null
            } else {
                Invoke-RestMethod -Uri $url -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 10 | Out-Null
            }
            $dur.Add($t.ElapsedMilliseconds)
        } catch {
            $fail++
        }
    }
    @{ Durations = $dur; Failures = $fail }
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$jobs = @()
for ($w = 0; $w -lt $Workers; $w++) {
    $jobs += Start-Job -ScriptBlock $workerScript -ArgumentList $BaseUrl, $token, $PerWorker
}
$all = New-Object System.Collections.Generic.List[long]
$totalFail = 0
foreach ($j in $jobs) {
    $r = Receive-Job -Job $j -Wait
    foreach ($v in @($r.Durations)) { $all.Add([long]$v) }
    $totalFail += [int]$r.Failures
    Remove-Job -Job $j
}
$elapsed = $sw.Elapsed.TotalSeconds

$sorted = $all | Sort-Object
$p50 = $sorted[[int][Math]::Floor($sorted.Count * 0.5)]
$p95 = $sorted[[int][Math]::Floor($sorted.Count * 0.95)]
$max = $sorted[-1]
$rps = [Math]::Round($all.Count / $elapsed, 1)

[pscustomobject]@{
    Workers   = $Workers
    PerWorker = $PerWorker
    Requests  = $all.Count
    Failures  = $totalFail
    ElapsedS  = [Math]::Round($elapsed, 1)
    RPS       = $rps
    P50ms     = $p50
    P95ms     = $p95
    MaxMs     = $max
} | Format-List
