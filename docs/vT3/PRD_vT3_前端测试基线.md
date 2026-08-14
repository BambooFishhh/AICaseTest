# vT3 PRD：前端测试基线

## 1. 迭代背景与痛点

- 前端 1.3 万+ 行 Vue 代码，但没有测试框架与任何测试。
- 工具函数（状态机文案翻译）与全局组件（ProgressTracker）缺少回归保障。
- CI 只构建不测试，前端逻辑变更无法自动发现回归。

## 2. 范围（In / Out of scope）

### In scope

- 引入 Vitest + Vue Test Utils + jsdom。
- 工具函数测试：`stateLabel`（中文翻译、未知 token、空值）。
- 组件测试：`ProgressTracker`（完成/运行状态渲染）。
- npm script `test` + CI `npm test`。

### Out of scope

- 运维可观测（vT4）。
- 全量组件/页面测试（后续 vT 系列扩展）。

## 3. 功能详情

### 3.1 依赖与配置

```json
{
  "devDependencies": {
    "@vue/test-utils": "^2.4.11",
    "jsdom": "^29.1.1",
    "vitest": "^4.1.10"
  }
}
```

`vitest.config.js`：jsdom 环境、`src/**/*.test.js`、`@` 别名。

### 3.2 测试用例

| 文件 | 数量 | 覆盖点 |
|---|---|---|
| src/utils/stateLabel.test.js | 3 | 中文翻译、未知 token、空值 |
| src/components/ProgressTracker.test.js | 2 | 完成/运行状态渲染 |

## 4. 验收标准

1. `npm test` 5 个测试通过。
2. `npm run build` 通过。
3. CI 前端 job 包含 `npm test`。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 组件依赖 Element Plus 无法挂载 | 测试中 stub `el-icon` |
| jsdom 环境较慢 | 仅组件/工具层轻量测试 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- vitest 配置 + 2 个测试文件
- package.json / CI 更新
