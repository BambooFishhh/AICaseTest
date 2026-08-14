# 后端技术评审 vT6：服务层与接口安全测试

> 版本 vT6，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 MCP 开关

`McpClientManager` 增加 `app.mcp.enabled`，关闭时跳过 `@PostConstruct` 子进程启动。

### 1.2 安全漏洞修复

```java
Map<String, Object> body = new LinkedHashMap<>();
body.put("code", 401);
body.put("message", "未登录或登录已过期");
body.put("data", null);
```

### 1.3 测试

- `SecurityApiTest`（@SpringBootTest + MockMvc，H2 内存库，MCP/Milvus/Redis 关闭）
- `ProjectServiceTest`（Mockito 级联清理）

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| security/SecurityConfig.java | 401 响应 NPE 修复 |
| mcp/McpClientManager.java | app.mcp.enabled 开关 |
| resources/application.yml | mcp.enabled 配置 |
| test/SecurityApiTest.java | 新增 5 个测试 |
| test/service/ProjectServiceTest.java | 新增 1 个测试 |

## 3. API 契约变化

无。

## 4. 向后兼容性

- `app.mcp.enabled` 默认 true，生产行为不变。

## 5. 测试验证方案

- `mvn test`：29 个测试全部通过。
