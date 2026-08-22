param(
    [string]$BackendUrl = "http://localhost:8000"
)

# v6.9: 高可用故障演练入口。先做只读检查，再输出需要人工/CI 执行的故障注入清单。
$ErrorActionPreference = "Stop"

Write-Host "== HA Fault Drill (只读检查) =="
try {
    $health = Invoke-RestMethod -Uri "$BackendUrl/api/health" -TimeoutSec 10
    Write-Host "Health: $($health.data.status)"
} catch {
    Write-Host "Health: FAILED - $($_.Exception.Message)"
    exit 1
}

docker compose ps backend frontend

Write-Host @"
== 故障注入清单 ==
1. LLM 5xx/超时：临时配置 LLM_BASE_URL 指向不可用端点，生成任务应失败/降级，agent_task 终态正确。
2. 工具挂起：将 APP_MCP_REQUEST_TIMEOUT_SECONDS 调小，screenshot/status 应 TOOL_TIMEOUT 后幂等重试。
3. kill -9 后端：docker compose kill backend；重启后 RUNNING 任务应转 NEEDS_REVIEW，可在任务中心重试。
4. Redis 宕机：docker compose stop redis；调度降级为 DB 轮询，运行态回退内存（仅单实例）。
5. 取消/断线：执行中取消批次，agent_task 应 CANCELLED 且执行记录同步。
"@
