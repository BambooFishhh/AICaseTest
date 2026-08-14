# 后端技术评审 vT1：测试与运维基线

> 版本 vT1，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 新增测试

| 测试类 | 说明 |
|---|---|
| runtime/MemoryRuntimeStoreTest | 内存运行态基础行为 |
| queue/MemoryTaskQueueStoreTest | 队列计数与幂等 |
| service/LoginAttemptServiceTest | 登录防爆破阈值与锁定 |

### 1.2 CI

```yaml
  compose:
    name: Docker compose config
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Validate compose
        run: docker compose config --quiet
```

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| backend/src/test/java/com/testagent/runtime/MemoryRuntimeStoreTest.java | 新增 |
| backend/src/test/java/com/testagent/queue/MemoryTaskQueueStoreTest.java | 新增 |
| backend/src/test/java/com/testagent/service/LoginAttemptServiceTest.java | 新增 |
| .github/workflows/ci.yml | 新增 compose job |

## 3. API 契约变化

无。

## 4. 向后兼容性

- 纯测试与 CI 变更，不影响运行时代码。

## 5. 测试验证方案

- `mvn test`：13 个测试全部通过。
