param(
    [ValidateSet("dev", "staging", "prod")]
    [string]$Environment = "staging",
    [string]$PreviousTag = "",
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent)
)

# vP4: 应用回滚——部署上一个镜像 tag。
# 数据库结构回滚不自动执行，需按运维手册使用备份恢复。
$ErrorActionPreference = "Stop"
if (-not $PreviousTag) {
    throw "请指定 -PreviousTag（例如上一个大版本 tag）"
}

& (Join-Path $PSScriptRoot "deploy.ps1") `
    -Environment $Environment -Tag $PreviousTag -ProjectRoot $ProjectRoot
if ($LASTEXITCODE -ne 0) { throw "回滚部署失败" }

Write-Output "rollback triggered: environment=$Environment previousTag=$PreviousTag"
