param(
    [string]$ProjectRoot = "E:\java_project\AICaseTest"
)

# vT5: security baseline - sensitive file tracking and secret scanning
$ErrorActionPreference = "Continue"
Set-Location $ProjectRoot
$failures = @()

# 1. .env must not be tracked by git
$trackedEnv = git ls-files .env
if ($trackedEnv) {
    $failures += ".env is tracked by git, commit forbidden"
}

# 2. scan tracked files for obvious secrets / private keys
$patterns = @(
    'sk-[A-Za-z0-9]{20,}',
    'AKIA[0-9A-Z]{16}',
    '-----BEGIN (RSA|EC|OPENSSH) PRIVATE KEY-----',
    '(?i)(api[_-]?key|secret|token)\s*[:=]\s*["'']?[A-Za-z0-9_-]{24,}'
)
$regex = ($patterns | ForEach-Object { "($_)" }) -join '|'
$raw = git ls-files -z
$files = $raw -split "`0" | Where-Object { $_ }
foreach ($file in $files) {
    if ($file -match '/src/test/' -or $file -match '\.test\.js$') {
        continue
    }
    $item = Get-Item -LiteralPath $file -ErrorAction SilentlyContinue
    if (-not $item -or $item.Length -gt 2MB) { continue }
    $content = Get-Content -LiteralPath $file -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $content) { continue }
    # 跳过 ${ENV:default} 占位符，避免把配置默认值当密钥
    $content = $content -replace '\$\{[^}]*\}', ''
    if ($content -match $regex) {
        $failures += "possible secret detected: $file"
    }
}

if ($failures.Count -gt 0) {
    Write-Output ("security baseline failed:`n" + ($failures -join "`n"))
    exit 1
}
Write-Output "security check OK"
