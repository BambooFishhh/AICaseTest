# 前端技术评审 vP5：压测与容量

> 版本 vP5，一旦确定尽量不要轻易改动。

## 1. 变更点

- 前端 `src/` 无源码变更。
- 新增 `loadtest/k6/smoke.js`、`loadtest/k6/load.js`、`loadtest/k6/README.md`。
- 新增 `scripts/pagination-baseline.ps1` 用于验证后端分页索引。

## 2. props/emit 变化

无。

## 3. 数据流

无变化；k6 作为外部负载注入，不修改前端应用。

## 4. 向后兼容性

- 压测脚本只依赖 HTTP API，不影响前端构建与运行。

## 5. 测试验证方案

- `npm run build` 回归。
- `docker compose config`。
