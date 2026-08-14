param(
    [string]$Container = "aicasetest-mysql",
    [string]$User = "root",
    [string]$Password = "",
    [int]$MysqlPort = 3308,
    [string]$StagingDatabase = "aicasetest_flyway_drill",
    [string]$FlywayImage = "flyway/flyway:10-alpine",
    [string]$MigrationsDir = ""
)

# vP4: Flyway staging 演练——在临时库完整执行迁移，校验 schema history 后清理。
$ErrorActionPreference = "Stop"
if (-not $MigrationsDir) {
    $MigrationsDir = Join-Path (Split-Path $PSScriptRoot -Parent) "backend\src\main\resources\db\migration\mysql"
}

$url = "jdbc:mysql://host.docker.internal:$MysqlPort/$StagingDatabase?useSSL=false&allowPublicKeyRetrieval=true"

try {
    docker exec $Container sh -c "MYSQL_PWD='$Password' mysql -u$User -e 'DROP DATABASE IF EXISTS $StagingDatabase; CREATE DATABASE $StagingDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'"
    if ($LASTEXITCODE -ne 0) { throw "创建演练库失败" }

    docker run --rm --add-host=host.docker.internal:host-gateway `
        -v "${MigrationsDir}:/flyway/sql:ro" `
        $FlywayImage `
        "-url=$url" "-user=$User" "-password=$Password" "-locations=filesystem:/flyway/sql" migrate
    if ($LASTEXITCODE -ne 0) { throw "Flyway migrate 演练失败" }

    $history = docker exec $Container sh -c "MYSQL_PWD='$Password' mysql -N -u$User -e \"SELECT COUNT(*) FROM $StagingDatabase.flyway_schema_history\""
    $count = [int]($history | Select-Object -Last 1)
    if ($count -le 0) { throw "Flyway 演练校验失败：schema history 为空" }
    Write-Output "flyway staging drill OK: migrations=$count"
} finally {
    docker exec $Container sh -c "MYSQL_PWD='$Password' mysql -u$User -e 'DROP DATABASE IF EXISTS $StagingDatabase'" | Out-Null
}
