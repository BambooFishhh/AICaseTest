# PRD v1.6 — 高可用增强

**版本**: v1.6（迭代版本）
**基线**: v1.5（可视化增强）
**日期**: 2026-08-09
**迭代主题**: 错误详情返回 + 生成进度反馈 + 日志结构化

---

## 一、迭代背景

### 1.1 痛点分析

| 编号 | 痛点 | 现状 | 影响 |
|------|------|------|------|
| P1 | 生成失败后前端只收到"用例生成失败"，无错误详情 | `TestCaseService.runGenerate()` catch 后只 log，不存储 error message | 用户无法知道失败原因（API Key 错误？网络超时？JSON 解析失败？） |
| P2 | 生成过程中无进度反馈 | 前端只显示"正在生成测试用例，请稍候..." | 用户不知道卡在哪一步，不知道是否需要等待 |
| P3 | 日志为非结构化文本 | logback 默认格式，无 JSON 结构化 | 排查问题困难，无法接入 ELK |
| P4 | 并发生成无明确提示 | 状态检查存在但错误信息模糊 | 用户可能反复点击 |

### 1.2 v1.6 目标

1. **错误详情存储与返回**：Project 新增 `errorMessage` 字段，生成失败时存储异常信息，前端展示
2. **生成进度反馈**：Project 新增 `progress` 字段，生成过程中实时更新（如"正在生成第 2/5 个模块"）
3. **日志结构化**：logback-spring.xml 配置 JSON 格式日志
4. **并发提示优化**：triggerGenerate 返回更明确的错误信息

---

## 二、范围

### 2.1 In Scope

| 编号 | 改动 | 优先级 |
|------|------|--------|
| F1 | 后端：Project 新增 errorMessage + progress 字段 | P0 |
| F2 | 后端：TestCaseService 生成过程中更新 progress，失败时存储 errorMessage | P0 |
| F3 | 后端：ProjectDTO 透传 errorMessage + progress | P0 |
| F4 | 后端：logback-spring.xml 结构化日志配置 | P1 |
| F5 | 前端：生成失败时展示错误详情 | P0 |
| F6 | 前端：生成过程中展示进度信息 | P0 |
| F7 | 文档：PRD + 前后端技术评审 + CHANGELOG + README | P0 |

### 2.2 Out of Scope

- ❌ AI 执行（v2.0）
- ❌ H2 备份/容灾（未来）
- ❌ 分布式锁（当前单机不需要）

---

## 三、功能详述

### 3.1 错误详情存储（F1+F2+F3）

**Project entity 新增字段：**

```java
@Column(columnDefinition = "TEXT")
private String errorMessage;

@Column(name = "progress")
private String progress;
```

**TestCaseService.runGenerate() 增强：**

```java
// 生成过程中更新进度
updateProgress(projectId, "正在解析状态机...");

// 分模块生成时
updateProgress(projectId, "正在生成第 " + (i+1) + "/" + total + " 个模块: " + sm.getName());

// 失败时存储错误详情
catch (Exception e) {
    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    updateProjectError(projectId, "failed", errorMsg);
}
```

**ProjectDTO 透传：**

```java
private String errorMessage;
private String progress;
```

### 3.2 前端错误详情展示（F5）

轮询失败时，从 project 数据中读取 errorMessage 并展示：

```html
<el-alert
  v-if="generationError"
  :title="生成失败: ${generationError}"
  type="error"
  :closable="false"
  show-icon
/>
```

### 3.3 前端进度反馈（F6）

轮询过程中展示 progress：

```html
<el-alert
  v-if="pollingMessage"
  :title="pollingMessage"
  type="info"
  :closable="false"
  show-icon
/>
```

pollingMessage 从 project.progress 获取。

### 3.4 结构化日志（F4）

`logback-spring.xml` 配置 JSON 格式：

```xml
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeContext>true</includeContext>
      <includeMDC>true</includeMDC>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="JSON" />
  </root>
</configuration>
```

---

## 四、验收标准

| 编号 | 验收项 | 验证方式 |
|------|--------|----------|
| AC1 | 生成失败时 Project.errorMessage 有值 | API 验证 |
| AC2 | 前端展示错误详情 | 页面验证 |
| AC3 | 生成过程中 Project.progress 更新 | API 验证 |
| AC4 | 前端轮询展示进度 | 页面验证 |
| AC5 | 后端编译通过，前端构建通过 | 构建 |
| AC6 | 日志输出 JSON 格式 | 运行验证 |

---

## 五、交付物清单

- [ ] PRD v1.6
- [ ] 后端技术评审 v1.6
- [ ] 前端技术评审 v1.6
- [ ] 后端：Project + ProjectDTO + TestCaseService + ProjectService + logback
- [ ] 前端：TestCaseList.vue + project store
- [ ] CHANGELOG + README 更新
