param(
    [string]$ProjectRoot = "E:\java_project\AICaseTest",
    [string]$JdkHome = "C:\Users\DislikeTomato\.jdks\ms-17.0.18",
    [string]$HealthUrl = ""
)

# vT5: full regression entry - backend tests + frontend build + compose config + security baseline
$ErrorActionPreference = "Continue"
Set-Location $ProjectRoot

if (-not (Test-Path $JdkHome)) {
    Write-Error "JDK 17 not found: $JdkHome"
}
$env:JAVA_HOME = $JdkHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "==> Backend tests"
Push-Location "$ProjectRoot\backend"
try {
    $output = & mvn test -s ../maven-settings.xml 2>&1
    $output | Select-Object -Last 8
    if ($LASTEXITCODE -ne 0) {
        Write-Error "backend tests failed"
        exit 1
    }
} finally {
    Pop-Location
}

Write-Host "==> Frontend build"
Push-Location "$ProjectRoot\frontend"
try {
    $output = & npm run build 2>&1
    $output | Select-Object -Last 6
    if ($LASTEXITCODE -ne 0) {
        Write-Error "frontend build failed"
        exit 1
    }
} finally {
    Pop-Location
}

Write-Host "==> Docker compose config"
$output = & docker compose config --quiet 2>&1
$output
if ($LASTEXITCODE -ne 0) {
    Write-Error "docker compose config validation failed"
    exit 1
}

Write-Host "==> Security baseline"
$output = & "$ProjectRoot\scripts\security-check.ps1" -ProjectRoot $ProjectRoot 2>&1
$output
if ($LASTEXITCODE -ne 0) {
    Write-Error "security baseline check failed"
    exit 1
}

if ($HealthUrl) {
    Write-Host "==> Health check: $HealthUrl"
    $resp = Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 10
    $resp | ConvertTo-Json -Depth 4
}

Write-Host "==> vT5 full regression completed"
