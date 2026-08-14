# 前端技术评审 vT8：前端测试扩充与覆盖率门禁

> 版本 vT8，一旦确定尽量不要轻易改动。

## 1. 变更点

- 新增 `src/stores/auth.test.js`（2 个测试）。
- `vitest.config.js` 增加 v8 coverage 与阈值。
- `package.json` 增加 `test:coverage` 脚本。

## 2. 数据流

```text
npm test --coverage
  └─ vitest run --coverage
        ├─ 单元测试
        └─ 覆盖率阈值检查
```

## 3. 向后兼容性

- 仅新增测试与脚本。

## 4. 测试验证方案

- `npm ci` 干净安装。
- `npm test -- --coverage` 通过阈值。
- `npm run build` 成功。
