# 后端技术评审 vP1：上线安全加固

> 版本 vP1，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 生产安全门禁 `ProductionGuard`

```java
@Profile("prod")
@Component
public class ProductionGuard implements CommandLineRunner {
    @Override
    public void run(String... args) {
        // JWT Secret < 32 或默认值；管理员密码 < 12 或 admin123 → 违规
        // enforce=true 抛异常，false 仅 ERROR 日志
    }
}
```

### 1.2 上传二次校验 `UploadGuard`

```java
public void assertSize(MultipartFile file, String label) {
    if (file.getSize() > maxUploadBytes) {
        throw BusinessException.invalidParam(label + " 文件过大，最大 " + mb + "MB");
    }
}
```

落点：`ProjectService.uploadPrdPdf`、`TestCaseService.importTestCases`、`TestCaseService.importFromXmind`。

### 1.3 URL 抓取加固

`PrdAgent.fetchUrl`：协议白名单、IP 私网/回环/链路本地校验、10s 超时、2MB 响应体上限、最多 3 次手动重定向并逐跳校验。

### 1.4 Milvus 鉴权

`MilvusService` 读取 `app.milvus.username/password`，非空时使用 `ConnectParam.Builder.withAuthorization(username, password)`。

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| config/ProductionGuard.java | 新增：prod 密钥/密码强制校验 |
| common/UploadGuard.java | 新增：上传大小校验 |
| agent/PrdAgent.java | URL 抓取加固 |
| service/MilvusService.java | Milvus 账号连接 |
| service/ProjectService.java | PDF 上传校验 |
| service/TestCaseService.java | JSON/XMind 上传校验 |
| resources/application.yml | multipart 限制 + upload/security 配置 |
| resources/application-prod.yml | prod 强制开关 + Milvus 账号 |
| test/config/ProductionGuardTest.java | 新增 |
| test/common/UploadGuardTest.java | 新增 |

## 3. API 契约变化

无新增/删除端点；超限上传返回既有 `40001` 业务错误。

## 4. 向后兼容性

- 开发默认 `APP_ENFORCE_SECURITY=false`，行为不变。
- prod 使用默认弱密钥会启动失败（有意行为），可显式设置 `APP_ENFORCE_SECURITY=false` 降级。
- Milvus 未配置账号时不发送鉴权元数据，兼容关闭鉴权的旧环境。

## 5. 测试验证方案

- `ProductionGuardTest`：默认密钥/密码拒绝；强密钥/密码通过。
- `UploadGuardTest`：20MB 边界拒绝/接受。
- `mvn compile` + 定向 `mvn test`；`docker compose config` 校验。
