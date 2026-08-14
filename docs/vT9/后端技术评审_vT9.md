# 后端技术评审 vT9：安全扫描与部署加固

> 版本 vT9，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 Redis AUTH

```yaml
command: ["redis-server", "--requirepass", "${REDIS_PASSWORD:-aicasetest-redis}"]
```

后端通过 `REDIS_PASSWORD` 环境变量连接。

### 1.2 CI

- `security` job：gitleaks + `npm audit --omit=dev --audit-level=high`
- `docker` job：build-push-action 构建前后端镜像（push=false）

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| .env.example | 新增 |
| frontend/nginx.conf | 安全头/限流/上传限制/gzip |
| docker-compose.yml | Redis AUTH + backend REDIS_PASSWORD |
| .github/workflows/ci.yml | security/docker job |
| docs/运维手册.md | 新增 |

## 3. API 契约变化

无。

## 4. 向后兼容性

- Redis 密码默认值与原部署一致（`aicasetest-redis`），旧 compose 需同步。

## 5. 测试验证方案

- `docker compose config`。
- 本地 `npm audit` 与安全扫描脚本。
