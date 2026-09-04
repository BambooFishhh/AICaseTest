# 本地一次性重部署脚本：重建 backend/frontend 镜像并原参数重建容器。
# 用法：在仓库根执行  powershell -File scripts\redeploy-local.ps1 [-SkipBuild]
param([switch]$SkipBuild)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

$root = (Get-Location).Path
$net = "aicasetest_default"

# ========== 1. 构建镜像 ==========
if (-not $SkipBuild) {
    Write-Host "[1/3] build backend image..." -ForegroundColor Cyan
    docker build -t aicasetest-backend:local -f backend/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw "backend 镜像构建失败" }
    Write-Host "[1/3] build frontend image..." -ForegroundColor Cyan
    docker build -t aicasetest-frontend:local frontend
    if ($LASTEXITCODE -ne 0) { throw "frontend 镜像构建失败" }
}

# ========== 2. 运行时环境变量（与存量容器对齐） ==========
$envCommon = @(
    "TZ=Asia/Shanghai",
    "SPRING_PROFILES_ACTIVE=prod",
    "JAVA_OPTS=-Xms256m -Xmx2g -Dfile.encoding=UTF-8",
    "MYSQL_URL=jdbc:mysql://aicasetest-mysql:3306/aicasetest?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
    "MYSQL_USERNAME=aicasetest",
    "MYSQL_PASSWORD=aicasetest123",
    "REDIS_HOST=redis",
    "REDIS_PORT=6379",
    "REDIS_PASSWORD=aicasetest-redis",
    "APP_REDIS_ENABLED=true",
    "MILVUS_HOST=milvus",
    "MILVUS_PORT=19530",
    "MILVUS_USERNAME=root",
    "MILVUS_PASSWORD=aicasetest-milvus",
    "APP_MILVUS_ENABLED=true",
    "LLM_PROVIDER=openai",
    "LLM_BASE_URL=https://api.xiaomimimo.com/v1",
    "LLM_API_KEY=sk-cazi8bfqv0b9nnbnxxvgc7re9165d56fpy9lu4jb01jsjuza",
    "LLM_MODEL=mimo-v2.5-pro",
    "LLM_ENABLE_THINKING=false",
    "LLM_THINKING_ANALYSIS=false",
    "LLM_THINKING_GENERATION=false",
    "LLM_CONNECT_TIMEOUT_MS=30000",
    "LLM_READ_TIMEOUT_MS=600000",
    "LLM_RETRY_MAX_ATTEMPTS=3",
    "LLM_MAX_PROMPT_CHARS=500000",
    "LLM_CIRCUIT_FAILURE_THRESHOLD=5",
    "LLM_CIRCUIT_OPEN_SECONDS=30",
    "LLM_EMBEDDING_BASE_URL=https://llm-fua5iwoagy8hd5de.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
    "LLM_EMBEDDING_API_KEY=sk-ws-H.EEHMLER.Uo00.MEUCIQDFN984V4DxElPeBHm0oQBYL9IslmwxbdhWB6V0JWDjqgIgQece1FN7C0i16OF-UG6QTX90491tHOpAF_3QKeupU4Q",
    "LLM_EMBEDDING_MODEL=qwen3.7-text-embedding",
    "RAG_CONTEXT_TOPK=6",
    "RAG_FAILURE_TOPK=3",
    "RAG_MAX_QUERIES=12",
    "RAG_RRF_K=60",
    "RAG_CHUNK_SIZE=500",
    "RAG_CHUNK_OVERLAP=150",
    "EXECUTOR_LLM_CONCURRENCY=8",
    "EXECUTOR_PROJECT_ACQUIRE_TIMEOUT_MINUTES=30",
    "APP_ADMIN_PASSWORD=admin123",
    "APP_ENFORCE_SECURITY=false",
    "APP_JWT_SECRET=sktdb99fJdusVv2HHRjVlOOlkvi69Oh5K+ZG0rzdoxoVZMhPdD7PQ+vshxEjQ4y1",
    "APP_SSE_TIMEOUT_MINUTES=30",
    "APP_EXECUTION_BROWSER_DEVICE=iPhone 14",
    "APP_COPY_EXECUTE_REQUIRE_OPERATE=false",
    "APP_MCP_REQUEST_TIMEOUT_SECONDS=60",
    "APP_HA_DISPATCH_DELAY_MS=15000",
    "APP_HA_TASK_TTL_MINUTES=60",
    "APP_HA_TASK_LEASE_SECONDS=600",
    "MCP_BRIDGE_TOKEN=RdPwidiIFMU9nkhvq1rqmVwVooi1P6",
    "GIT_CLONE_DIR=/app/data/git-repos",
    "GIT_CLONE_TIMEOUT_SECONDS=600"
)

# ========== 3. 重建容器 ==========
Write-Host "[2/3] recreate backend A (:8000)..." -ForegroundColor Cyan
docker rm -f test-agent-backend | Out-Null
docker run -d --name test-agent-backend --network $net --network-alias backend --restart unless-stopped `
    -p 8000:8000 `
    -v "$root\outputs:/app/outputs" `
    -v "$root\data:/app/data" `
    -v "$root\projects\litemall:/app/projects/litemall-mall:ro" `
    $(foreach ($kv in $envCommon) { @("--env", $kv) }) `
    --env "MCP_BRIDGE_URL=http://backend:8000" `
    aicasetest-backend:local
if ($LASTEXITCODE -ne 0) { throw "backend A 启动失败" }

Write-Host "[2/3] recreate backend B (:8001)..." -ForegroundColor Cyan
docker rm -f test-agent-backend-b | Out-Null
docker run -d --name test-agent-backend-b --network $net `
    -p 8001:8000 `
    -v "$root\outputs:/app/outputs" `
    -v "$root\data:/app/data" `
    -v "$root\projects\litemall:/app/projects/litemall-mall:ro" `
    $(foreach ($kv in $envCommon) { @("--env", $kv) }) `
    --env "MCP_BRIDGE_URL=http://test-agent-backend-b:8000" `
    aicasetest-backend:local
if ($LASTEXITCODE -ne 0) { throw "backend B 启动失败" }

Write-Host "[2/3] recreate frontend (:80/:443)..." -ForegroundColor Cyan
docker rm -f test-agent-frontend | Out-Null
docker run -d --name test-agent-frontend --network $net --restart unless-stopped `
    -p 80:80 -p 443:443 `
    -v "$root\certs:/etc/nginx/certs" `
    aicasetest-frontend:local
if ($LASTEXITCODE -ne 0) { throw "frontend 启动失败" }

# ========== 4. 健康等待 ==========
Write-Host "[3/3] waiting health..." -ForegroundColor Cyan
$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 4
    $code = docker exec test-agent-backend curl -s -o /dev/null -w "%{http_code}" http://localhost:8000/actuator/health 2>$null
    if ($code -eq "200") { $ok = $true; break }
}
if (-not $ok) { Write-Warning "backend A 健康检查未通过，请查看日志: docker logs test-agent-backend" }
docker ps --filter "name=test-agent-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
