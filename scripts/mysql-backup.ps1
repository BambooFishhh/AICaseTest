param(
    [string]$Container = "aicasetest-mysql",
    [string]$User = "root",
    [string]$Password = "",
    [string]$Database = "aicasetest",
    [string]$BackupRoot = "",
    [int]$KeepDays = 14
)

# vP2: MySQL 备份（通过 Docker exec 使用容器内 mysqldump，避免本机依赖）。
# 可配合 schedule-backup.ps1 注册 Windows 计划任务实现每日调度。
$ErrorActionPreference = "Stop"

if (-not $BackupRoot) {
    $BackupRoot = Join-Path (Split-Path $PSScriptRoot -Parent) "backups\mysql"
}
New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$file = Join-Path $BackupRoot ("aicasetest_" + $stamp + ".sql")
$remote = "/tmp/aicasetest_backup.sql"

try {
    docker exec $Container sh -c "MYSQL_PWD='$Password' mysqldump -u$User --single-transaction --routines --triggers $Database > $remote"
    if ($LASTEXITCODE -ne 0) { throw "mysqldump 失败" }
    docker cp "${Container}:${remote}" $file
    if ($LASTEXITCODE -ne 0) { throw "docker cp 备份文件失败" }
    Write-Output "backup created: $file"
} finally {
    docker exec $Container sh -c "rm -f $remote" | Out-Null
}

# 按保留天数轮转旧备份
$cutoff = (Get-Date).AddDays(-$KeepDays)
Get-ChildItem -Path $BackupRoot -Filter "aicasetest_*.sql" |
    Where-Object { $_.LastWriteTime -lt $cutoff } |
    Remove-Item -Force
