param(
    [string]$ProjectRoot = "E:\java_project\AICaseTest",
    [string]$BackupRoot = "",
    [string]$MysqlContainer = "",
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "root",
    [string]$MysqlDatabase = "aicasetest"
)

# vT4: 数据/输出文件备份 + 可选 MySQL dump
$ErrorActionPreference = "Stop"
Set-Location $ProjectRoot
if (-not $BackupRoot) {
    $BackupRoot = Join-Path $ProjectRoot "backups"
}
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$target = Join-Path $BackupRoot ("app-backup-" + $stamp)
New-Item -ItemType Directory -Force -Path $target | Out-Null

if (Test-Path "data") {
    Copy-Item -Recurse -Force "data" (Join-Path $target "data")
}
if (Test-Path "outputs") {
    Copy-Item -Recurse -Force "outputs" (Join-Path $target "outputs")
}
if ($MysqlContainer) {
    docker exec $MysqlContainer sh -c "MYSQL_PWD='$MysqlPassword' mysqldump -u$MysqlUser --single-transaction $MysqlDatabase" |
        Set-Content -Encoding UTF8 (Join-Path $target "mysql.sql")
}

Write-Output "backup created: $target"
