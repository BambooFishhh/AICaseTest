# vT6 PRD：服务层与接口安全测试

## 1. 迭代背景与痛点

- 安全规则（401/403/登录锁定）只有单元级 JWT 测试，没有真实 API 层验证。
- 项目删除级联清理是 v5.6 高影响逻辑，但没有回归测试。
- 全量 Spring 上下文测试会拉起 MCP 子进程，需要可控开关。

## 2. 范围（In / Out of scope）

### In scope

- `app.mcp.enabled` 测试开关，默认 true，测试置 false。
- `SecurityApiTest`：健康公开、401、ADMIN 403、登录 5 次锁定 429。
- `ProjectServiceTest`：删除项目级联清理验证。
- 修复 401 响应 `Map.of(..., null)` NPE 漏洞。

### Out of scope

- Testcontainers 集成测试（vT7）。

## 3. 功能详情

### 3.1 测试开关

```yaml
app:
  mcp:
    enabled: ${APP_MCP_ENABLED:true}
```

`McpClientManager.start()` 在关闭时跳过子进程启动。

### 3.2 接口安全测试

| 场景 | 期望 |
|---|---|
| GET /api/health | 200 |
| GET /api/settings（无 token） | 401 JSON，不抛异常 |
| GET /api/settings（USER） | 403 |
| GET /api/admin/data/health（无 token） | 401 |
| 登录失败 5 次后第 6 次 | 429 |

### 3.3 漏洞修复

`SecurityConfig` 401 响应改用 `LinkedHashMap` 写入 `data:null`，修复 `Map.of` 禁止 null 导致的 NPE。

## 4. 验收标准

1. `mvn test` 29 个测试全部通过。
2. 401/403/429 行为在 API 层可回归。
3. 生产默认 MCP 行为不变。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 全上下文测试慢 | 仅一个 @SpringBootTest 类 |
| MCP 子进程污染测试 | app.mcp.enabled=false |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- SecurityApiTest、ProjectServiceTest
- SecurityConfig 401 修复
