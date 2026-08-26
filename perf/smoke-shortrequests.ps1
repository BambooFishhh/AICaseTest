# v8.9.5(12.7): 短请求容量冒烟（顺序版——单连接串行，客户端为 PowerShell Invoke-RestMethod，
# RPS 含客户端开销，仅作相对基线；更高并发负载建议用 k6：perf/k6/short-requests.js）
# 用法：
#   .\perf\smoke-shortrequests.ps1 -Username perf-smoke -Password 'xxx' -Requests 300
param(
    [string]$BaseUrl = "http://127.0.0.1:8000",
    [string]$Username = "admin",
    [string]$Password = "",
    [int]$Requests = 300
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

$durations = New-Object System.Collections.Generic.List[long]
$failures = 0
for ($i = 0; $i -lt $Requests; $i++) {
    $url = if ($i % 2 -eq 0) { "$BaseUrl/api/health" } else { "$BaseUrl/api/projects" }
    $t = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        if ($i % 2 -eq 0) {
            Invoke-RestMethod -Uri $url -TimeoutSec 10 | Out-Null
        } else {
            Invoke-RestMethod -Uri $url -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 10 | Out-Null
        }
        $durations.Add($t.ElapsedMilliseconds)
    } catch {
        $failures++
        $t.Stop()
    }
}

$sorted = $durations | Sort-Object
$p50 = $sorted[[int][Math]::Floor($sorted.Count * 0.5)]
$p95 = $sorted[[int][Math]::Floor($sorted.Count * 0.95)]
$max = $sorted[-1]
$rps = [Math]::Round($durations.Count / (($sorted | Measure-Object -Sum).Sum / 1000.0), 1)

[pscustomobject]@{
    Requests   = $durations.Count
    Failures   = $failures
    AvgMs      = [Math]::Round(($sorted | Measure-Object -Average).Average, 1)
    P50ms      = $p50
    P95ms      = $p95
    MaxMs      = $max
    SeqRPS     = $rps
} | Format-List
