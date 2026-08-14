# 后端技术评审 vT8：前端测试扩充与覆盖率门禁

> 版本 vT8，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 JaCoCo

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    ...
</plugin>
```

- `prepare-agent`：测试时收集覆盖率。
- `report`：test 阶段生成报告。
- `check`：verify 阶段校验 LINE/INSTRUCTION ≥ 5%。

### 1.2 CI

`backend job` 从 `mvn -B test` 改为 `mvn -B verify`。

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| backend/pom.xml | jacoco 插件 |
| .github/workflows/ci.yml | verify 代替 test |

## 3. API 契约变化

无。

## 4. 向后兼容性

- 构建产物新增 jacoco 报告目录，不影响运行。

## 5. 测试验证方案

- `mvn verify`：测试 + JaCoCo 检查全部通过。
