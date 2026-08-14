# vT2 PRD：服务层与集成测试

## 1. 迭代背景与痛点

- vT1 建立了基础组件测试（13 个），但安全（JWT）、工具类、JPA Repository 仍无测试。
- 安全是系统入口，JWT 解析/过滤器行为必须可回归。
- JPA 删除/查询等数据访问逻辑需要真实数据库方言验证（H2 集成测试）。

## 2. 范围（In / Out of scope）

### In scope

- JwtUtil 测试：签发、解析、无效/过期 token。
- JwtAuthFilter 测试：Bearer 认证成功、缺失/非法 token 不建认证上下文。
- JsonHelper 测试：Map/List 解析与容错。
- TestCaseVersionRepository H2 集成测试：保存、查询、按项目删除。

### Out of scope

- 前端测试（vT3）。
- 运维可观测（vT4）。

## 3. 功能详情

| 测试类 | 数量 | 覆盖点 |
|---|---|---|
| JwtUtilTest | 3 | token round-trip、非法 token、过期 token |
| JwtAuthFilterTest | 3 | 有效 Bearer、缺失 token、非法 token |
| JsonHelperTest | 3 | Map/List 解析与异常容错 |
| TestCaseVersionRepositoryTest | 1 | JPA 保存/查询/级联删除 |

## 4. 验收标准

1. `mvn test` 全量通过（23 个测试）。
2. 新增测试不依赖外部服务。
3. CHANGELOG / README 更新 vT2 状态。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 过期 token 测试边界 | 使用 0 小时过期构造确定性场景 |
| JPA 测试污染 | @DataJpaTest 自动回滚 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- 4 个测试类（10 个测试）
