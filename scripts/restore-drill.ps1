param(
    [string]$Container = "aicasetest-mysql",
    [string]$User = "root",
    [string]$Password = "",
    [string]$Database = "aicasetest",
    [string]$BackupFile = "",
    [string]$DrillDatabase = "aicasetest_drill"
)

# vP2: 恢复演练——把最近备份恢复到临时库，校验表数量后删除，不触碰生产库。
$ErrorActionPreference = "Stop"

if (-not $BackupFile) {
    $backupRoot = Join-Path (Split-Path $PSScriptRoot -Parent) "backups\mysql"
    $BackupFile = Get-ChildItem -Path $backupRoot -Filter "aicasetest_*.sql" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $BackupFile -or -not (Test-Path $BackupFile)) {
    throw "未找到可用的 MySQL 备份文件"
}

$remote = "/tmp/aicasetest_restore_drill.sql"
try {
    docker cp $BackupFile "${Container}:${remote}"
    docker exec $Container sh -c "MYSQL_PWD='$Password' mysql -u$User -e 'DROP DATABASE IF EXISTS $DrillDatabase; CREATE DATABASE $DrillDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'"
    if ($LASTEXITCODE -ne 0) { throw "创建演练库失败" }
    docker exec $Container sh -c "MYSQL_PWD='$Password' mysql -u$User $DrillDatabase < $remote"
    if ($LASTEXITCODE -ne 0) { throw "恢复演练失败" }

    $tableCountRaw = docker exec $Container sh -c "MYSQL_PWD='$Password' mysql -N -u$User -e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DrillDatabase'\""
    $tableCount = [int]($tableCountRaw | Select-Object -Last 1)
    if ($tableCount -le 0) {
        throw "恢复演练校验失败：演练库表数量为 0"
    }
    Write-Output "restore drill OK: backup=$BackupFile tables=$tableCount"
} finally {
    docker exec $Container sh -c "MYSQL_PWD='$Password' mysql -u$User -e 'DROP DATABASE IF EXISTS $DrillDatabase'" | Out-Null
    docker exec $Container sh -c "rm -f $remote" | Out-Null
}
