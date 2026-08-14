# 前端技术评审 vP4：发布流水线

> 版本 vP4，一旦确定尽量不要轻易改动。

## 1. 变更点

- 前端 `src/` 无源码变更。
- publish workflow 构建并推送 `aicasetest-frontend` 到 GHCR。
- compose frontend 支持 `IMAGE_FRONTEND/IMAGE_TAG/PULL_POLICY`。

## 2. props/emit 变化

无。

## 3. 数据流

无变化；仅镜像来源从本地 build 变为 GHCR pull。

## 4. 向后兼容性

- 本地开发/构建仍走 Dockerfile，不需要 GHCR 登录。
- 生产部署需 `docker login ghcr.io` 或由 Actions 注入 token。

## 5. 测试验证方案

- `npm run build` 回归。
- `docker compose config`。
