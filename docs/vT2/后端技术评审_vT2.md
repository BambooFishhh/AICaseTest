# 后端技术评审 vT2：服务层与集成测试

> 版本 vT2，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 测试类

| 测试类 | 说明 |
|---|---|
| security/JwtUtilTest | 直接构造 JwtUtil，验证签发/解析/校验 |
| security/JwtAuthFilterTest | MockHttpServletRequest + Mockito UserRepository |
| dto/JsonHelperTest | 解析与容错 |
| repository/TestCaseVersionRepositoryTest | @DataJpaTest + H2 |

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| backend/src/test/java/com/testagent/security/JwtUtilTest.java | 新增 |
| backend/src/test/java/com/testagent/security/JwtAuthFilterTest.java | 新增 |
| backend/src/test/java/com/testagent/dto/JsonHelperTest.java | 新增 |
| backend/src/test/java/com/testagent/repository/TestCaseVersionRepositoryTest.java | 新增 |

## 3. API 契约变化

无。

## 4. 向后兼容性

- 纯测试新增，不影响运行时代码。

## 5. 测试验证方案

- `mvn test`：23 个测试全部通过。
