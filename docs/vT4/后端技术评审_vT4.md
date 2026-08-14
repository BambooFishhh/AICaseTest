# 后端技术评审 vT4：运维与可观测基线

> 版本 vT4，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### 1.2 安全

- `/actuator/health` 加入 permitAll。
- `/actuator/prometheus`、`/actuator/metrics` 默认需认证。

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| backend/pom.xml | actuator + prometheus 依赖 |
| resources/application.yml | management 配置 |
| security/SecurityConfig.java | health 放行 |
| scripts/backup-v5.ps1 | 新增备份脚本 |

## 3. API 契约变化

- 新增 `/actuator/health`、`/actuator/prometheus` 等标准端点。

## 4. 向后兼容性

- 自定义 `/api/health` 保留，Actuator 为增量能力。

## 5. 测试验证方案

- `mvn test`。
- 启动后 `GET /actuator/health` 无认证 200；`GET /actuator/prometheus` 带认证返回指标。
