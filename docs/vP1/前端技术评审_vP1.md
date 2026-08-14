# 前端技术评审 vP1：上线安全加固

> 版本 vP1，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 Nginx TLS

`frontend/nginx.conf`：

```nginx
listen 80;
listen 443 ssl;
ssl_certificate     /etc/nginx/certs/fullchain.pem;
ssl_certificate_key /etc/nginx/certs/privkey.pem;
ssl_protocols       TLSv1.2 TLSv1.3;
client_max_body_size 20m;
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
```

### 1.2 证书自动生成

`frontend/entrypoint.sh` 在 `/etc/nginx/certs` 缺少证书时生成自签证书；`frontend/Dockerfile` 安装 openssl 并切换 entrypoint，暴露 80/443。

### 1.3 Compose 与脚本

`docker-compose.yml` 暴露 443 并挂载 `./certs`；新增 `scripts/generate-self-signed-cert.ps1` 生成本地证书。

## 2. props/emit 变化

无。前端组件与页面零改动。

## 3. 数据流

不变：浏览器 → Nginx（80/443）→ `/api/` → backend:8000。

## 4. 向后兼容性

- 80 端口保留，HTTPS 与 HTTP 同 server 配置。
- 无证书时 entrypoint 自动生成自签证书，容器可启动；生产挂载正式证书覆盖。
- `.env.example` 增加证书相关变量说明，不影响本地开发。

## 5. 测试验证方案

- `npm run build` 成功。
- `docker compose config` 通过。
- 可选：`docker build frontend` + 启动容器验证 443 自签证书可访问。
