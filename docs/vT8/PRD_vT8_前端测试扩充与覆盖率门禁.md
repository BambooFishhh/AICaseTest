# vT8 PRD：前端测试扩充与覆盖率门禁

## 1. 迭代背景与痛点

- 前端测试只有 5 个，且未覆盖状态管理（auth store）。
- 前后端都没有覆盖率门禁，测试数量增长不等于质量增长。
- CI 只跑 `mvn test`，无法触发 JaCoCo 检查。

## 2. 范围（In / Out of scope）

### In scope

- 新增 auth store 测试（登录持久化、登出清理）。
- Vitest coverage（v8 provider）+ 全局阈值。
- 后端 JaCoCo 覆盖率检查（LINE/INSTRUCTION ≥ 5%）。
- CI backend 改为 `mvn -B verify` 触发 JaCoCo 检查。

### Out of scope

- 安全扫描与部署加固（vT9）。

## 3. 功能详情

### 3.1 前端测试

| 文件 | 覆盖点 |
|---|---|
| src/stores/auth.test.js | 登录持久化、登出清理、isAdmin |

### 3.2 前端覆盖率

```js
coverage: {
  provider: 'v8',
  thresholds: { lines: 2, functions: 2, statements: 2, branches: 0 }
}
```

### 3.3 后端覆盖率

- JaCoCo `prepare-agent` + `report` + `check`（LINE/INSTRUCTION ≥ 5%）。
- CI：`mvn -B verify`。

## 4. 验收标准

1. `npm test --coverage` 通过阈值。
2. `npm ci` 干净安装成功。
3. `mvn verify` BUILD SUCCESS，JaCoCo 检查通过。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 前端覆盖率过低导致门禁形同虚设 | 阈值先设为 2%，后续版本逐步提高 |
| verify 阶段更慢 | 仅多一次 JaCoCo 分析，可接受 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- auth store 测试（2 个）
- JaCoCo + Vitest coverage 配置
