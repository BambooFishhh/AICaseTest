param(
    [string]$Time = "03:00",
    [string]$TaskName = "AICaseTest MySQL Backup"
)

# vP2: 注册 Windows 计划任务，每日执行 scripts/mysql-backup.ps1。
$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "mysql-backup.ps1"
$taskRun = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`""

& schtasks /Create /F /SC DAILY /ST $Time /TN $TaskName /TR $taskRun
if ($LASTEXITCODE -ne 0) {
    throw "计划任务创建失败"
}
Write-Output "scheduled task created: $TaskName @ $Time"
