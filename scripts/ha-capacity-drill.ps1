param(
    [string]$BackendUrl = "http://localhost:8000",
    [string]$Token = ""
)

# v6.9: 高可用容量/阈值演练入口。
$ErrorActionPreference = "Stop"

Write-Host "== HA Capacity Drill =="
Write-Host "Task lease seconds:  $(Get-Content .env | Where-Object { $_ -match '^APP_HA_TASK_LEASE_SECONDS=' } | Select-Object -First 1)"
Write-Host "Task TTL minutes:    $(Get-Content .env | Where-Object { $_ -match '^APP_HA_TASK_TTL_MINUTES=' } | Select-Object -First 1)"
Write-Host "MCP timeout seconds: $(Get-Content .env | Where-Object { $_ -match '^APP_MCP_REQUEST_TIMEOUT_SECONDS=' } | Select-Object -First 1)"

if (-not [string]::IsNullOrWhiteSpace($Token)) {
    $headers = @{ Authorization = "Bearer $Token" }
    $stats = Invoke-RestMethod -Uri "$BackendUrl/api/tasks/stats" -Headers $headers -TimeoutSec 10
    Write-Host "Stats: $($stats | ConvertTo-Json -Compress)"
} else {
    Write-Host "未提供 Token，跳过 stats 校验。"
}

Write-Host @"
建议阈值基线：
- 队列 QUEUED/RUNNING 单池不超过 80% core+max。
- LLM 熔断默认 5 次/30s，失败率告警 20%。
- DLQ 大于 0 即触发告警。
- 任务 TTL 默认 60m，分析/生成长任务按需放宽。
"@
