# PRD v9.3 — litemall 实测回归修复（断言误判 / 编号乱序 / 面包屑口径）

> 版本 v9.3，一旦确定尽量不要轻易改动。基线 v9.2。范围：litemall 实测暴露的三处回归修复。两后端一前端。

## 一、背景与痛点

- **断言误判 failed**：收藏页断言 `页面显示'共 N 件收藏'，且N为实际商品列表数量`——引号短语明明命中（共 1 件收藏）仍 failed。根因两层：① 「且N为实际商品列表数量」是对占位符的**语义限定（元描述）**，被抽成中文核心短语做 3-gram 匹配必然落空；② Java 正则 `\b` 是 Unicode 感知的——"且N为"中 N 两侧都是汉字形不成词边界，`\b[A-Z]\b` 检测不出嵌在中文里的占位符；
- **编号乱序**：列表按 module 分组渲染，编号 projectSeq 按生成产出序分配，LLM 按覆盖端点产出导致同模块用例编号被打散（组内 1,2,13,14）观感乱序；
- **面包屑口径不一**：执行结果面包屑上一级是「项目详情」且可点，页面返回按钮却指向用例页。

## 二、范围与验收

| # | 内容 | 验收 |
|---|---|---|
| 1 | 断言：占位符元描述子句剔除（stripPlaceholderClauses）+ 子句占位符检测改显式 lookaround | ExecutionAssertTest 回归用例 |
| 2 | 编号：各落库路径收口时按模块归组重编 projectSeq | TestCasePersistenceResequenceTest + 存量 SQL 重编 |
| 3 | 面包屑：执行结果/批次结果上一级改「测试用例」 | 路由 meta + crumbLink 映射 |

## 三、功能细节

- **stripPlaceholderClauses**：含占位符（独立大写字母/花括号变量）的子句从中文段/英文 token 提取中剔除——引号短语由占位符正则独立权威校验（页面无该形态照样 failed），无占位符子句保持原字面校验强度；顺带修复 `{orderId}` 变量名被抽成英文 token 要求页面出现 "orderId" 的同类误判。
- **CLAUSE_PLACEHOLDER**：`(?<![a-zA-Z0-9])[A-Z](?![a-zA-Z0-9])|\{[^}]*\}`——前后非 ASCII 字母数字即算独立占位符，允许紧贴中文（"且N为"可检出）。
- **resequenceProjectSeq**（TestCasePersistenceService）：模块按首次出现排序、组内保持原相对顺序，重编后组内编号连续；挂在 replaceAll/追加/JSON 导入/XMind 导入/复制到项目 五个落库收口；id 为稳定主键不受影响；存量数据用同规则 SQL 一次性重编。
- **面包屑**：执行结果/批次结果 meta 改 `['项目列表','测试用例',...]`，App.vue crumbLink 补「测试用例」映射。

## 四、验收标准

1. ExecutionAssertTest 19 例绿（含实测回归用例：元描述子句不再误判、无占位符子句保持强度）；全量 542/542 绿。
2. litemall 项目刷新后编号按模块连续（1-4/5-7/8-13/14-16/17）。

## 五、交付物清单

service/ExecutionAssert、TestCasePersistenceService、TestCaseService；frontend/src/router/index.js、App.vue；TestCasePersistenceResequenceTest、ExecutionAssertTest 扩展。
