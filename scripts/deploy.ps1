param(
    [ValidateSet("dev", "staging", "prod")]
    [string]$Environment = "staging",
    [string]$Tag = "latest",
    [string]$Namespace = "DislikeTomato/AICaseTest",
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent)
)

# vP4: 多环境部署——从 GHCR 拉取指定 tag 并启动 compose。
# 前置：仓库根存在 .env 与 .env.<Environment>（从 .env.example 复制）。
$ErrorActionPreference = "Stop"
Set-Location $ProjectRoot

$envFile = Join-Path $ProjectRoot ".env.$Environment"
if (-not (Test-Path $envFile)) {
    throw "缺少环境文件: $envFile（请从 .env.example 复制）"
}

$namespace = $Namespace.ToLowerInvariant()
$env:IMAGE_BACKEND = "ghcr.io/$namespace/aicasetest-backend"
$env:IMAGE_FRONTEND = "ghcr.io/$namespace/aicasetest-frontend"
$env:IMAGE_TAG = $Tag
$env:PULL_POLICY = "always"

docker compose --env-file .env --env-file $envFile -f docker-compose.yml config --quiet
if ($LASTEXITCODE -ne 0) { throw "compose 配置校验失败" }

docker compose --env-file .env --env-file $envFile -f docker-compose.yml up -d
if ($LASTEXITCODE -ne 0) { throw "compose 启动失败" }

Write-Output "deployed environment=$Environment tag=$Tag images=$env:IMAGE_BACKEND,$env:IMAGE_FRONTEND"
