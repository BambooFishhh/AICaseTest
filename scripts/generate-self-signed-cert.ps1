param(
    [string]$CertDir = (Join-Path (Split-Path $PSScriptRoot -Parent) "certs"),
    [string]$CommonName = "aicasetest.local"
)

# vP1: 生成本地自签证书，供 docker-compose 的 frontend 443 使用。
# 生产环境请使用正式 CA 证书替换 certs/fullchain.pem 与 certs/privkey.pem。

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $CertDir | Out-Null

$fullchain = Join-Path $CertDir "fullchain.pem"
$privkey = Join-Path $CertDir "privkey.pem"

if ((Test-Path $fullchain) -and (Test-Path $privkey)) {
    Write-Host "证书已存在，跳过生成: $CertDir"
    exit 0
}

$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if ($openssl) {
    & $openssl.Source req -x509 -nodes -newkey rsa:2048 -days 365 `
        -keyout $privkey -out $fullchain -subj "/CN=$CommonName"
    if ($LASTEXITCODE -ne 0) { throw "openssl 生成证书失败" }
} else {
    # 本机无 openssl 时回退到 Docker（alpine/openssl 镜像，首次会拉取）
    docker run --rm -v "${CertDir}:/certs" alpine/openssl `
        req -x509 -nodes -newkey rsa:2048 -days 365 `
        -keyout /certs/privkey.pem -out /certs/fullchain.pem -subj "/CN=$CommonName"
    if ($LASTEXITCODE -ne 0) { throw "Docker openssl 生成证书失败" }
}

Write-Host "已生成自签证书:"
Write-Host "  $fullchain"
Write-Host "  $privkey"
