param(
    [string]$ProjectRoot = "E:\java_project\AICaseTest",
    [string]$JdkHome = "C:\Users\DislikeTomato\.jdks\ms-17.0.18",
    [string]$HealthUrl = ""
)

# v5.5: 全量回归脚本 —— 后端编译/测试 + 前端构建 + compose 配置校验 + 可选健康检查
$ErrorActionPreference = "Stop"
Set-Location $ProjectRoot

if (-not (Test-Path $JdkHome)) {
    Write-Error "JDK 17 未找到: $JdkHome"
}
$env:JAVA_HOME = $JdkHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "==> 后端编译 + 测试"
Push-Location "$ProjectRoot\backend"
try {
    mvn test -s ../maven-settings.xml 2>&1 | Select-Object -Last 8
} finally {
    Pop-Location
}

Write-Host "==> 前端构建"
Push-Location "$ProjectRoot\frontend"
try {
    npm run build 2>&1 | Select-Object -Last 6
} finally {
    Pop-Location
}

Write-Host "==> docker compose 配置校验"
docker compose config --quiet
if ($LASTEXITCODE -ne 0) {
    Write-Error "docker compose config 校验失败"
}

if ($HealthUrl) {
    Write-Host "==> 健康检查: $HealthUrl"
    $resp = Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 10
    $resp | ConvertTo-Json -Depth 4
}

Write-Host "==> v5.5 全量回归完成"
