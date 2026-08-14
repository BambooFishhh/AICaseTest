# 后端技术评审 vT5：安全与全量回归收口

> 版本 vT5，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 安全脚本

`scripts/security-check.ps1`：

- `git ls-files --error-unmatch .env` 校验敏感文件未被跟踪。
- 正则扫描 `sk-*` / `AKIA*` / 私钥 / `api_key|secret|token` 疑似值。

### 1.2 回归入口

`verify-v5-stack.ps1` 增加安全基线步骤与汇总输出。

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| scripts/security-check.ps1 | 新增 |
| scripts/verify-v5-stack.ps1 | 集成安全基线 |

## 3. API 契约变化

无。

## 4. 向后兼容性

- 验证脚本为增量增强。

## 5. 测试验证方案

- 运行 `verify-v5-stack.ps1` 全流程。
