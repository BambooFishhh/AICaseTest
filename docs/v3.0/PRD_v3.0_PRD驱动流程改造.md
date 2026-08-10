# PRD v3.0 — PRD 驱动流程改造

**版本**: v3.0
**基线**: v2.9
**日期**: 2026-08-10
**主题**: 将产品流程从"代码驱动"改为"PRD 驱动"——PRD 为用例生成主线（必须），代码路径降级为可选上下文

---

## 1. 背景与痛点

当前产品流程是"代码驱动"：创建项目时强制要求填代码路径（`sourcePath` 必填 + 后端校验路径存在），PRD 反而是项目详情页里的可选补充。这与"PRD 是用例生成的主线、代码是上下文"的产品定位不符：

1. **创建门槛错配**：用户可能只有 PRD 还没拉代码，但当前不填代码路径无法创建项目（前端 `sourcePath` required + 后端 `@NotBlank` + 路径存在校验）。
2. **主次颠倒**：项目详情页操作区把"开始分析"（代码分析）放首位，PRD 面板被压在下方，视觉上代码是主线、PRD 是配角。
3. **生成校验缺失**：`runGenerate` 不校验 PRD 和代码分析是否都为空，两者皆空时会生成空用例或抛模糊异常。

后端生成链路（OrchestratorAgent / TestGeneratorAgent）其实已支持"纯 PRD 驱动"（v1.10 实现），卡点仅在前端表单和后端创建校验。

## 2. 范围

### In Scope（本迭代做）

- 后端：`CreateProjectRequest` 移除 `sourcePath` 的 `@NotBlank`，改为可选
- 后端：`ProjectService.createProject` 在 `sourcePath` 为空时跳过路径存在校验
- 后端：`TestCaseService.runGenerate` 前置校验——PRD 和代码分析结果都为空时抛明确异常
- 前端：`ProjectCreate.vue` 表单 `sourcePath` 改为非必填；来源类型增加"无代码（纯 PRD）"选项
- 前端：`ProjectDetail.vue` 调整布局——PRD 面板上提到操作区上方；操作区"开始分析"按钮在无 `sourcePath` 时禁用并提示

### Out of Scope（本迭代不做）

- 不改动 PrdPanel 组件本身（已有文本/PDF/URL 三种接入方式）
- 不改动 OrchestratorAgent / TestGeneratorAgent 生成逻辑（已支持纯 PRD 驱动）
- 不在创建项目表单内嵌 PRD 编辑器（PRD 仍在详情页 PrdPanel 编辑，降低创建门槛）

## 3. 功能详情

### 3.1 创建项目表单改造（ProjectCreate.vue）

| 字段 | 改造前 | 改造后 |
|------|--------|--------|
| 项目名称 | 必填 | 必填（不变） |
| 来源类型 | 本地路径 / Git 地址 | 本地路径 / Git 地址 / **无代码（纯 PRD）** |
| 项目路径 | 必填 | **可选**（选"无代码"时隐藏） |

选"无代码（纯 PRD）"时隐藏项目路径输入框，创建后直接进入详情页编辑 PRD。

### 3.2 项目详情页布局调整（ProjectDetail.vue）

调整前顺序：基本信息 → 操作区（开始分析/生成用例/...）→ PRD 面板

调整后顺序：基本信息 → **PRD 面板** → 操作区（生成用例为主，开始分析标注可选）

操作区按钮调整：
- "生成用例"作为主操作（type=primary），排在首位
- "开始分析"标注为可选上下文：无 `sourcePath` 时禁用，tooltip 提示"未配置代码路径，可跳过直接用 PRD 生成用例"

### 3.3 后端创建校验放宽（ProjectService）

```java
// 改造前
File path = new File(req.getSourcePath());
if (!path.exists()) {
    throw BusinessException.pathNotFound("源码路径不存在: " + req.getSourcePath());
}

// 改造后：sourcePath 为空时跳过校验
if (req.getSourcePath() != null && !req.getSourcePath().isBlank()) {
    File path = new File(req.getSourcePath());
    if (!path.exists()) {
        throw BusinessException.pathNotFound("源码路径不存在: " + req.getSourcePath());
    }
}
```

### 3.4 生成用例前置校验（TestCaseService.runGenerate）

在调用 `orchestratorAgent.generate` 前，校验 PRD 和代码分析结果是否都为空：

```java
// PRD 和代码分析都为空时阻止生成
String prdContent = project.getPrdContent();
CodeAnalysis analysis = codeAnalysisRepository.findByProjectId(projectId).orElse(null);
boolean hasPrd = prdContent != null && !prdContent.isBlank();
boolean hasAnalysis = analysis != null && "completed".equals(analysis.getStatus());
if (!hasPrd && !hasAnalysis) {
    throw new IllegalStateException("请先输入 PRD 或完成代码分析，至少需要一项才能生成用例");
}
```

## 4. 验收标准

- [x] 创建项目时不填代码路径可成功创建（选"无代码（纯 PRD）"）
- [x] 创建项目时填了代码路径仍校验路径存在（向后兼容）
- [x] 项目详情页 PRD 面板显示在操作区上方
- [x] 无代码路径时"开始分析"按钮禁用并有提示
- [x] PRD 和代码分析都为空时点"生成用例"返回明确错误提示
- [x] 只有 PRD（无代码分析）时能成功生成用例
- [x] 后端 `mvn compile` + 前端 `npm run build` 通过

## 5. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 历史项目无 PRD 也无代码分析，点生成用例会被新校验拦截 | 校验提示明确，引导用户先输入 PRD |
| 无代码路径时"开始分析"按钮禁用，用户困惑 | tooltip 说明"代码分析为可选上下文，可直接用 PRD 生成" |

## 6. 交付物清单

- [ ] `docs/v3.0/PRD_v3.0_PRD驱动流程改造.md`
- [ ] `docs/v3.0/后端技术评审_v3.0.md`
- [ ] `docs/v3.0/前端技术评审_v3.0.md`
- [ ] 后端代码改动（CreateProjectRequest + ProjectService + TestCaseService）
- [ ] 前端代码改动（ProjectCreate.vue + ProjectDetail.vue）
- [ ] CHANGELOG + README 更新
