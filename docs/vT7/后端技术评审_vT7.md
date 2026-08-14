# 后端技术评审 vT7：Testcontainers 集成测试

> 版本 vT7，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 测试类

| 测试类 | 说明 |
|---|---|
| MySqlFlywayIntegrationTest | MySQL 8 + Flyway V1/V2 + JPA 读写 |
| RedisRuntimeStoreIntegrationTest | Redis 7 + RuntimeStore 真实读写 |

### 1.2 跳过策略

`@Testcontainers(disabledWithoutDocker = true)`：Docker 不可用时整个测试类跳过。

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| backend/pom.xml | testcontainers junit-jupiter/mysql |
| test/MySqlFlywayIntegrationTest.java | 新增 2 个测试 |
| test/runtime/RedisRuntimeStoreIntegrationTest.java | 新增 3 个测试 |

## 3. API 契约变化

无。

## 4. 向后兼容性

- 仅新增测试与依赖（test scope）。

## 5. 测试验证方案

- 有 Docker：`mvn test` 运行容器测试。
- 无 Docker：自动跳过，构建仍成功。
