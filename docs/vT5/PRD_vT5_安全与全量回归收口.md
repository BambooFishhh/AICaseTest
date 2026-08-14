# vT5 PRD：安全与全量回归收口

## 1. 迭代背景与痛点

- 仓库存在 `.env`（含 API Key），需要基线保证其永不入库。
- 已跟踪文件缺少密钥/私钥扫描，敏感信息可能被误提交。
- vT1~vT4 建立了测试/CI/可观测基线，需要一个统一入口做最终收口。

## 2. 范围（In / Out of scope）

### In scope

- 新增 `scripts/security-check.ps1`：校验 `.env` 未被跟踪 + 扫描疑似密钥/私钥。
- `verify-v5-stack.ps1` 集成安全基线，输出全量回归汇总。
- README / CHANGELOG 收口 vT 系列状态。

### Out of scope

- 依赖漏洞扫描（OWASP 等，后续版本）。

## 3. 功能详情

### 3.1 安全基线

```powershell
.\scripts\security-check.ps1
```

- `.env` 被跟踪 → 失败。
- 扫描 `sk-*`、AWS AKIA、私钥、`api_key/secret/token` 疑似值 → 失败。

### 3.2 全量回归

`scripts/verify-v5-stack.ps1` 现在依次执行：

1. 后端 `mvn test`
2. 前端 `npm run build`
3. `docker compose config --quiet`
4. `security-check.ps1`
5. 可选 HTTP 健康检查

## 4. 验收标准

1. `verify-v5-stack.ps1` 全流程通过。
2. 安全基线能识别 `.env` 被跟踪与疑似密钥。
3. README / CHANGELOG 完成 vT1~vT5 收口。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 扫描误报 | 仅扫描已跟踪文本文件，>2MB 跳过 |
| 安全扫描遗漏 | 作为 CI/回归入口的一部分持续执行 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- `scripts/security-check.ps1`
- verify 脚本集成
