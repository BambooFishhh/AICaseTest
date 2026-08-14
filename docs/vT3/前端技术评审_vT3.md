# 前端技术评审 vT3：前端测试基线

> 版本 vT3，一旦确定尽量不要轻易改动。

## 1. 变更点

- 新增 `vitest.config.js`。
- 新增 `src/utils/stateLabel.test.js`、`src/components/ProgressTracker.test.js`。
- `package.json` 增加 `test: vitest run`。
- CI frontend job 增加 `npm test`。

## 2. 数据流

```text
npm test
  └─ vitest run（jsdom）
        ├─ stateLabel.test.js（纯函数）
        └─ ProgressTracker.test.js（组件挂载）
```

## 3. 向后兼容性

- 仅新增测试与脚本，不影响构建产物。

## 4. 测试验证方案

- `npm test`：5 个测试通过。
- `npm run build`：构建通过。
