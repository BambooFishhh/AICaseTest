param(
    [string]$Container = "aicasetest-mysql",
    [string]$User = "root",
    [string]$Password = "",
    [string]$Database = "aicasetest",
    [string]$BackupFile = ""
)

# vP4: 从备份恢复 MySQL 生产库（破坏性操作，回滚/容灾时使用）。
$ErrorActionPreference = "Stop"
if (-not $BackupFile -or -not (Test-Path $BackupFile)) {
    throw "请指定有效的 -BackupFile"
}

$remote = "/tmp/aicasetest_restore.sql"
try {
    docker cp $BackupFile "${Container}:${remote}"
    docker exec $Container sh -c "MYSQL_PWD='$Password' mysql -u$User $Database < $remote"
    if ($LASTEXITCODE -ne 0) { throw "MySQL 恢复失败" }
    Write-Output "restored database=$Database backup=$BackupFile"
} finally {
    docker exec $Container sh -c "rm -f $remote" | Out-Null
}
