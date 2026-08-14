# vT9 PRD：安全扫描与部署加固

## 1. 迭代背景与痛点

- 缺少 `.env.example`，新环境配置靠 README 猜测。
- nginx 无安全响应头、登录限流与上传限制。
- Redis 默认无 AUTH；CI 无密钥扫描/依赖审计/镜像构建。
- 缺少运维手册，部署/备份/恢复/排障没有统一入口。

## 2. 范围（In / Out of scope）

### In scope

- `.env.example` 配置模板。
- nginx：安全头、`/api/auth/` 限流、`client_max_body_size`、gzip。
- Redis AUTH + 后端连接密码。
- CI：gitleaks、`npm audit --omit=dev`、Docker 镜像构建校验。
- `docs/运维手册.md`。

### Out of scope

- Kubernetes/Helm、TLS 证书管理（后续）。

## 3. 功能详情

### 3.1 配置模板

`cp .env.example .env` 后按需修改，覆盖 LLM/MySQL/Redis/Milvus/App 全部变量。

### 3.2 Nginx 加固

- `X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`、`X-XSS-Protection`
- 登录限流：`limit_req_zone ... rate=5r/s`，`/api/auth/` burst=10
- `client_max_body_size 10m`；gzip 压缩

### 3.3 Redis AUTH

- compose：`redis-server --requirepass ${REDIS_PASSWORD:-aicasetest-redis}`
- backend：注入 `REDIS_PASSWORD`，`spring.data.redis.password` 自动读取

### 3.4 CI

| job | 内容 |
|---|---|
| security | gitleaks + npm audit（生产依赖，high 及以上失败） |
| docker | 构建 backend/frontend 镜像（push=false 校验） |

### 3.5 运维手册

覆盖部署、升级、备份/恢复、监控、排障、安全默认配置。

## 4. 验收标准

1. `.env.example` 覆盖全部运行变量。
2. `docker compose config` 通过。
3. nginx 配置包含安全头与限流。
4. Redis 启动需密码；后端连接配置同步。
5. CI 包含 security 与 docker job。
6. 运维手册可指导部署与排障。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| npm audit 在镜像源 405 | CI 使用默认 npm registry |
| Redis AUTH 导致旧部署连不上 | 密码通过 compose 变量统一注入 |
| gitleaks 误报 | .env.example 仅放占位值 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- .env.example、nginx、compose、CI
- docs/运维手册.md
