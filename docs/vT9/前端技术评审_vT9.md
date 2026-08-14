# 前端技术评审 vT9：安全扫描与部署加固

> 版本 vT9，一旦确定尽量不要轻易改动。

## 1. 变更点

- `frontend/nginx.conf`：安全响应头、登录限流、上传限制、gzip。
- CI：npm audit（生产依赖）与 Docker 镜像构建校验。

## 2. 向后兼容性

- 新增头与限流不影响现有页面；上传文件需 ≤10MB。

## 3. 测试验证方案

- `npm run build`。
- `docker compose config`。
