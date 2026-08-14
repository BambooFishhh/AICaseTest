# vP1 PRD：上线安全加固

> 版本 vP1，一旦确定尽量不要轻易改动。迭代范围：TLS、密码/密钥强制、DB/Redis/Milvus 访问控制、文件上传与 URL 抓取加固。

## 1. 迭代背景与痛点

- Nginx 仅 HTTP，生产传输明文，缺少 TLS。
- 生产可使用默认 JWT Secret / 默认管理员密码启动，无强制门禁。
- MySQL/Redis/Milvus 端口直接暴露在宿主所有网卡，且 Milvus 默认无鉴权。
- 上传仅依赖 Nginx 10MB 限制，后端缺少文件大小二次校验。
- URL 抓取仅做基础抓取，存在访问内网/回环地址、超时过长、响应过大等风险。

## 2. 范围（In / Out of scope）

### In scope

- Nginx 增加 HTTPS（443），支持挂载正式证书；缺失证书时自动生成自签证书。
- 自签证书生成脚本（开发/内网快速验证）。
- 生产 profile 强制校验 JWT Secret 与管理员密码，默认值/弱值阻断启动，可开关降级为告警。
- MySQL/Redis/Milvus 宿主端口仅绑定 127.0.0.1；Redis 强制 AUTH + protected-mode；Milvus 开启鉴权并支持账号连接。
- 后端上传文件大小二次校验（PRD PDF、JSON/XMind 导入），Nginx 与 Spring multipart 同步限制 20MB。
- URL 抓取加固：仅 http/https、禁止内网/回环/链路本地地址、超时、最大响应体、重定向限制。

### Out of scope

- 正式 CA 证书申请与管理（由部署方提供证书）。
- 用户密码强度策略改造、审计日志（归后续迭代）。

## 3. 功能详情

### 3.1 TLS

- `frontend/nginx.conf`：同一 server 同时监听 80/443，443 使用 `/etc/nginx/certs/fullchain.pem` 与 `privkey.pem`。
- `frontend/entrypoint.sh`：证书缺失时自动生成自签证书，保证容器可启动；生产用 bind mount 覆盖正式证书。
- `scripts/generate-self-signed-cert.ps1`：本机 openssl 或 Docker alpine/openssl 生成证书。
- `docker-compose.yml`：frontend 暴露 80/443，挂载 `./certs`。

### 3.2 密码/密钥强制

- 新增 `ProductionGuard`（仅 prod profile）：
  - `APP_JWT_SECRET` 为空、等于默认值或长度 < 32 判为违规。
  - `APP_ADMIN_PASSWORD` 为空、等于 `admin123` 或长度 < 12 判为违规。
  - `APP_ENFORCE_SECURITY=true`（prod 默认）时启动抛异常阻断；`false` 时仅 ERROR 日志告警。
  - `LLM_API_KEY` 为空时 WARN 日志提示。

### 3.3 DB/Redis/Milvus 访问控制

- MySQL：宿主端口绑定 `127.0.0.1`，容器间走内部网络；使用专用账号而非 root 连接；补充 utf8mb4/连接数参数。
- Redis：AUTH + `protected-mode yes`，宿主端口绑定 `127.0.0.1`。
- Milvus：启用 `COMMON_SECURITY_AUTHORIZATIONENABLED`，root 口令由 `MILVUS_ROOT_PASSWORD` 注入；后端 `MilvusService` 支持 `MILVUS_USERNAME/MILVUS_PASSWORD` 连接。

### 3.4 文件上传加固

- Nginx `client_max_body_size 20m`。
- Spring `spring.servlet.multipart.max-file-size/max-request-size` 默认 20MB。
- 新增 `UploadGuard`，在 `ProjectService.uploadPrdPdf`、`TestCaseService.importTestCases`、`importFromXmind` 做二次校验。

### 3.5 URL 抓取加固

- `PrdAgent.fetchUrl` 仅允许 http/https。
- 解析主机 IP 后禁止 any/loopback/link-local/site-local 地址，防 SSRF。
- 超时 10s、最大响应体 2MB、手动跟随最多 3 次重定向并逐跳校验。

## 4. 验收标准

1. `mvn test`（至少新增的 ProductionGuard/UploadGuard 测试）通过。
2. `mvn compile` BUILD SUCCESS。
3. `npm run build` 成功。
4. `docker compose config` 校验通过。
5. 上传超过 20MB 返回业务错误。
6. prod 启动使用默认 JWT/管理员密码时抛出明确错误。
7. URL 抓取 `localhost`/内网地址返回业务错误。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 自签证书浏览器告警 | 仅用于开发/内网，生产使用正式证书 |
| 生产强制校验导致启动失败 | 明确错误信息；允许 `APP_ENFORCE_SECURITY=false` 降级告警 |
| Milvus 鉴权配置兼容性 | 通过环境变量开关 `MILVUS_AUTH_ENABLED`，可回退关闭 |
| DNS rebinding 绕过 SSRF 校验 | 逐跳重定向校验 + 文档提示，后续可引入代理校验 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- `ProductionGuard`、`UploadGuard`、`MilvusService` 鉴权
- Nginx TLS、entrypoint 自签证书、证书生成脚本、compose 访问控制
- 上传与 URL 抓取加固及单元测试
