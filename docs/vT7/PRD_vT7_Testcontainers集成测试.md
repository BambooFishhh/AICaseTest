# vT7 PRD：Testcontainers 集成测试

## 1. 迭代背景与痛点

- Flyway V1/V2 与 JPA 只在 H2 上验证，MySQL 8 真实方言无自动化保障。
- `RedisRuntimeStore` 的 Lua 信号量、TTL、Hash 只在人工冒烟中验证。
- 集成测试需要真实中间件，但本地/CI 环境差异需要优雅跳过策略。

## 2. 范围（In / Out of scope）

### In scope

- Testcontainers MySQL：Flyway 迁移（V1/V2）+ JPA 读写。
- Testcontainers Redis：`RedisRuntimeStore` 标志/登录计数/信号量。
- `disabledWithoutDocker=true`：Docker 不可用时自动跳过。

### Out of scope

- Milvus 容器集成（体积大，后续）。
- H2→MySQL 迁移演练自动化（后续版本）。

## 3. 功能详情

### 3.1 依赖

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
```

### 3.2 MySQL 集成

- 启动 `mysql:8.0.29` 容器。
- `@DynamicPropertySource` 注入 JDBC。
- 断言 `flyway_schema_history` 有 V1/V2 成功记录。
- JPA 保存/查询 Project。

### 3.3 Redis 集成

- 启动 `redis:7-alpine` 容器。
- 手动构建 `StringRedisTemplate` + `RedisRuntimeStore`。
- 覆盖标志、登录计数/锁定、信号量。

## 4. 验收标准

1. 有 Docker 环境时集成测试真实运行。
2. 无 Docker 环境时自动跳过且构建成功。
3. `mvn test` 全量通过（34 run + 5 skipped 本地）。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 本地无 Docker 导致失败 | disabledWithoutDocker 自动跳过 |
| 容器镜像下载慢 | 使用本地已有镜像 tag |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- MySqlFlywayIntegrationTest
- RedisRuntimeStoreIntegrationTest
