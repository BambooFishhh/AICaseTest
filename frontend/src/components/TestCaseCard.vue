<template>
  <!--
    测试用例卡片组件
    - 创建模式（mode=create）：自带 el-dialog 包裹（visible 受控）
    - 查看模式（mode=view）：仅渲染内容，由父级 el-dialog 承载（避免嵌套 dialog）
    业务逻辑、字段、事件全部保留：
    - 查看 / 编辑 / 创建
    - 结构化步骤、关联接口、执行提示、测试数据、状态机引用
    - 上一条 / 下一条 / 历史版本 / 执行 / 删除
  -->

  <!-- ============ 创建模式：自带 el-dialog（仅编辑表单，因为 editMode 初始为 true） ============ -->
  <el-dialog
    v-if="isStandalone"
    :model-value="visible"
    :title="dialogTitle"
    width="780px"
    :show-close="true"
    :close-on-click-modal="false"
    :destroy-on-close="false"
    class="case-card-dialog"
    @close="handleClose"
  >
    <div class="case-card is-edit">
      <!-- 编辑模式表单（与查看模式分支内联一致，避免抽组件） -->
      <div class="case-edit">
        <el-form :model="formData" label-width="100px" label-position="right">
          <div class="form-grid">
            <el-form-item label="用例ID">
              <el-input :model-value="formData.id" disabled placeholder="自动生成" />
            </el-form-item>
            <el-form-item label="标题" required>
              <el-input v-model="formData.title" placeholder="请输入用例标题" />
            </el-form-item>
          </div>

          <div class="form-grid form-grid-3">
            <el-form-item label="所属模块">
              <el-input v-model="formData.module" placeholder="如：订单/支付" />
            </el-form-item>
            <el-form-item label="用例类型">
              <el-select v-model="formData.type" placeholder="请选择" style="width: 100%">
                <el-option label="正向用例" value="positive" />
                <el-option label="负向用例" value="negative" />
                <el-option label="边界值用例" value="boundary" />
                <el-option label="数据驱动用例" value="data" />
              </el-select>
            </el-form-item>
            <el-form-item label="优先级">
              <el-select v-model="formData.priority" placeholder="请选择" style="width: 100%">
                <el-option label="P0 (最高)" value="P0" />
                <el-option label="P1 (高)" value="P1" />
                <el-option label="P2 (中)" value="P2" />
                <el-option label="P3 (低)" value="P3" />
              </el-select>
            </el-form-item>
          </div>

          <div class="form-grid">
            <el-form-item label="来源">
              <el-input v-model="formData.source" placeholder="如：PRD / 代码分析" />
            </el-form-item>
            <el-form-item label="置信度">
              <el-input-number
                v-model="confidenceInput"
                :min="0"
                :max="1"
                :step="0.05"
                :precision="2"
                style="width: 100%"
              />
            </el-form-item>
          </div>

          <section class="edit-section">
            <header class="edit-section-head">
              <span class="edit-section-title">前置条件</span>
              <el-button text type="primary" :icon="Plus" size="small" @click="addItem('preconditions')">
                添加
              </el-button>
            </header>
            <div
              v-for="(item, idx) in formData.preconditions"
              :key="'pre-c-' + idx"
              class="edit-row"
            >
              <span class="edit-row-index">{{ idx + 1 }}</span>
              <el-input
                v-model="formData.preconditions[idx]"
                type="textarea"
                :autosize="{ minRows: 1, maxRows: 3 }"
                placeholder="请输入前置条件"
              />
              <el-button
                text
                type="danger"
                :icon="Delete"
                @click="removeItem('preconditions', idx)"
              />
            </div>
          </section>

          <section class="edit-section">
            <header class="edit-section-head">
              <span class="edit-section-title">测试步骤</span>
              <el-button text type="primary" :icon="Plus" size="small" @click="addItem('steps')">
                添加
              </el-button>
            </header>
            <div
              v-for="(step, idx) in formData.steps"
              :key="'step-c-' + idx"
              class="edit-row"
            >
              <span class="edit-row-index">{{ idx + 1 }}</span>
              <el-input
                v-model="formData.steps[idx]"
                type="textarea"
                :autosize="{ minRows: 1, maxRows: 3 }"
                placeholder="请输入测试步骤"
              />
              <el-button
                text
                type="danger"
                :icon="Delete"
                @click="removeItem('steps', idx)"
              />
            </div>
          </section>

          <section class="edit-section">
            <header class="edit-section-head">
              <span class="edit-section-title">预期结果</span>
              <el-button text type="primary" :icon="Plus" size="small" @click="addItem('expectedResults')">
                添加
              </el-button>
            </header>
            <div
              v-for="(result, idx) in formData.expectedResults"
              :key="'exp-c-' + idx"
              class="edit-row"
            >
              <span class="edit-row-index">{{ idx + 1 }}</span>
              <el-input
                v-model="formData.expectedResults[idx]"
                type="textarea"
                :autosize="{ minRows: 1, maxRows: 3 }"
                placeholder="请输入预期结果"
              />
              <el-button
                text
                type="danger"
                :icon="Delete"
                @click="removeItem('expectedResults', idx)"
              />
            </div>
          </section>

          <section class="edit-section">
            <header class="edit-section-head">
              <span class="edit-section-title">结构化步骤（可执行）</span>
              <el-button text type="primary" :icon="Plus" size="small" @click="addStructuredStep">
                添加
              </el-button>
            </header>
            <div
              v-for="(step, idx) in formData.structuredSteps"
              :key="'ss-c-' + idx"
              class="structured-edit-card"
            >
              <div class="structured-edit-head">
                <span class="structured-edit-index">步骤 {{ idx + 1 }}</span>
                <el-button
                  text
                  type="danger"
                  :icon="Delete"
                  size="small"
                  @click="removeStructuredStep(idx)"
                />
              </div>
              <el-row :gutter="12">
                <el-col :span="6">
                  <el-select v-model="step.type" placeholder="步骤类型" size="small" style="width: 100%">
                    <el-option label="接口调用" value="api_call" />
                    <el-option label="界面操作" value="ui_action" />
                    <el-option label="状态断言" value="state_assert" />
                    <el-option label="人工" value="manual" />
                  </el-select>
                </el-col>
                <el-col :span="18">
                  <el-input v-model="step.action" placeholder="动作描述" size="small" />
                </el-col>
              </el-row>
              <el-input
                v-model="step.target"
                placeholder="操作目标，如 POST /api/order/create"
                size="small"
                class="mt-2"
              />
              <el-input
                v-model="step.expected"
                placeholder="预期结果"
                size="small"
                class="mt-2"
              />
            </div>
          </section>
        </el-form>
      </div>
    </div>

    <template #footer>
      <div class="card-footer">
        <div class="footer-right">
          <el-button @click="cancelEdit">取消</el-button>
          <el-button type="primary" :icon="Check" @click="handleSave">
            {{ mode === 'create' ? '创建' : '保存' }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>

  <!-- ============ 查看模式：直接渲染内容（被父级 el-dialog 承载） ============ -->
  <div v-else class="case-card" :class="{ 'is-edit': editMode }">
    <!-- 查看模式 -->
    <div v-if="!editMode" class="case-view">
      <!-- 头部信息卡 -->
      <section class="info-card">
        <div class="info-card-head">
          <div class="info-title-block">
            <h3 class="info-title">{{ testCase.title || '未命名用例' }}</h3>
            <div class="info-id">ID: {{ testCase.id || '-' }}</div>
          </div>
          <div class="info-tags">
            <el-tag v-if="testCase.type" :type="getTypeTagType(testCase.type)" size="small" effect="light">
              {{ typeLabel(testCase.type) }}
            </el-tag>
            <el-tag v-if="testCase.priority" :type="getPriorityTagType(testCase.priority)" size="small" effect="light">
              {{ testCase.priority }}
            </el-tag>
            <el-tag v-if="testCase.executionStatus" :type="getExecutionStatusTagType(testCase.executionStatus)" size="small" effect="light">
              {{ getExecutionStatusLabel(testCase.executionStatus) }}
            </el-tag>
            <el-tag v-if="testCase.source" type="info" size="small" effect="plain">
              {{ testCase.source }}
            </el-tag>
          </div>
        </div>

        <!-- 元信息网格 -->
        <div class="info-grid">
          <div class="info-cell">
            <div class="info-label">所属模块</div>
            <div class="info-value">{{ testCase.module || '-' }}</div>
          </div>
          <div class="info-cell">
            <div class="info-label">置信度</div>
            <div class="info-value">
              <el-progress
                v-if="typeof testCase.confidence === 'number'"
                :percentage="Math.round(testCase.confidence * 100)"
                :status="getConfidenceStatus(testCase.confidence)"
                :stroke-width="10"
              />
              <span v-else class="text-muted">-</span>
            </div>
          </div>
          <div class="info-cell">
            <div class="info-label">质量评分</div>
            <div class="info-value">
              <el-progress
                v-if="testCase.qualityScore > 0"
                :percentage="testCase.qualityScore"
                :color="qualityColor(testCase.qualityScore)"
                :stroke-width="10"
              />
              <span v-else class="text-muted">未评分</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 前置条件 -->
      <section class="block">
        <header class="block-head">
          <div class="block-title">
            <el-icon :size="16"><List /></el-icon>
            <span>前置条件</span>
            <span class="block-count">{{ (testCase.preconditions || []).length }}</span>
          </div>
        </header>
        <ol v-if="testCase.preconditions && testCase.preconditions.length" class="numbered-list">
          <li v-for="(item, idx) in testCase.preconditions" :key="'pre-' + idx">{{ item }}</li>
        </ol>
        <el-empty v-else description="无前置条件" :image-size="60" />
      </section>

      <!-- 测试步骤 -->
      <section class="block">
        <header class="block-head">
          <div class="block-title">
            <el-icon :size="16"><Tickets /></el-icon>
            <span>测试步骤</span>
            <span class="block-count">{{ stepsTotal }}</span>
          </div>
        </header>

        <!-- 结构化步骤（优先展示） -->
        <div v-if="hasStructuredSteps" class="structured-steps">
          <article
            v-for="step in testCase.structuredSteps"
            :key="'sstep-' + step.order"
            class="step-card"
          >
            <div class="step-card-head">
              <span class="step-order">{{ step.order }}</span>
              <span class="step-action">{{ step.action }}</span>
              <el-tag
                v-if="step.type"
                :type="getStepTypeTagType(step.type)"
                size="small"
                effect="light"
              >
                {{ getStepTypeLabel(step.type) }}
              </el-tag>
            </div>
            <div class="step-card-body">
              <div v-if="step.target" class="step-row">
                <span class="step-label">目标</span>
                <code class="step-code">{{ step.target }}</code>
              </div>
              <div v-if="step.expected" class="step-row">
                <span class="step-label">预期</span>
                <span class="step-text">{{ step.expected }}</span>
              </div>
              <div v-if="hasStepData(step.data)" class="step-row">
                <span class="step-label">数据</span>
                <code class="step-code">{{ JSON.stringify(step.data) }}</code>
              </div>
            </div>
          </article>
        </div>

        <!-- 回退：纯文本步骤 -->
        <ol v-else-if="testCase.steps && testCase.steps.length" class="numbered-list">
          <li v-for="(step, idx) in testCase.steps" :key="'step-' + idx">{{ step }}</li>
        </ol>
        <el-empty v-else description="无测试步骤" :image-size="60" />
      </section>

      <!-- 预期结果 -->
      <section class="block">
        <header class="block-head">
          <div class="block-title">
            <el-icon :size="16"><CircleCheck /></el-icon>
            <span>预期结果</span>
            <span class="block-count">{{ (testCase.expectedResults || []).length }}</span>
          </div>
        </header>
        <ol
          v-if="testCase.expectedResults && testCase.expectedResults.length"
          class="numbered-list"
        >
          <li v-for="(result, idx) in testCase.expectedResults" :key="'exp-' + idx">{{ result }}</li>
        </ol>
        <el-empty v-else description="无预期结果" :image-size="60" />
      </section>

      <!-- 关联接口 -->
      <section v-if="hasApiEndpoints" class="block">
        <header class="block-head">
          <div class="block-title">
            <el-icon :size="16"><Connection /></el-icon>
            <span>关联接口</span>
            <span class="block-count">{{ (testCase.apiEndpoints || []).length }}</span>
          </div>
        </header>
        <div class="api-list">
          <span
            v-for="(ep, i) in testCase.apiEndpoints"
            :key="'api-' + i"
            class="api-pill"
            :class="getMethodClass(ep.method)"
          >
            <strong>{{ (ep.method || '').toUpperCase() }}</strong>
            <span class="api-path">{{ ep.path }}</span>
          </span>
        </div>
      </section>

      <!-- 执行提示 -->
      <section v-if="hasExecutionHints" class="block">
        <header class="block-head">
          <div class="block-title">
            <el-icon :size="16"><InfoFilled /></el-icon>
            <span>执行提示</span>
          </div>
        </header>
        <el-alert
          :type="getApproachAlertType(testCase.executionHints.approach)"
          :closable="false"
          show-icon
        >
          <template #title>
            推荐执行方式: {{ getApproachLabel(testCase.executionHints.approach) }}
          </template>
          <div v-if="testCase.executionHints.notes" class="hint-notes">
            {{ testCase.executionHints.notes }}
          </div>
        </el-alert>
      </section>

      <!-- 测试数据 -->
      <section v-if="hasTestData" class="block">
        <header class="block-head">
          <div class="block-title">
            <el-icon :size="16"><Coin /></el-icon>
            <span>测试数据</span>
          </div>
        </header>
        <div class="data-grid">
          <div v-for="(val, key) in testCase.testData" :key="'td-' + key" class="data-cell">
            <div class="data-key">{{ key }}</div>
            <div class="data-val">{{ typeof val === 'object' ? JSON.stringify(val) : val }}</div>
          </div>
        </div>
      </section>

      <!-- 状态机引用 -->
      <section class="block">
        <header class="block-head">
          <div class="block-title">
            <el-icon :size="16"><Share /></el-icon>
            <span>状态机引用</span>
          </div>
        </header>
        <div v-if="hasStateMachineRef" class="state-machine-wrap">
          <StateMachineViewer
            :states="testCase.stateMachineRef.states || []"
            :transitions="testCase.stateMachineRef.transitions || []"
            :forbidden-transitions="testCase.stateMachineRef.forbiddenTransitions || []"
          />
        </div>
        <el-alert
          v-else
          type="info"
          :closable="false"
          title="该用例未关联状态机"
          show-icon
        />
      </section>
    </div>

    <!-- 编辑模式 -->
    <div v-else class="case-edit">
      <el-form :model="formData" label-width="100px" label-position="right">
        <div class="form-grid">
          <el-form-item label="用例ID">
            <el-input :model-value="formData.id" disabled placeholder="自动生成" />
          </el-form-item>
          <el-form-item label="标题" required>
            <el-input v-model="formData.title" placeholder="请输入用例标题" />
          </el-form-item>
        </div>

        <div class="form-grid form-grid-3">
          <el-form-item label="所属模块">
            <el-input v-model="formData.module" placeholder="如：订单/支付" />
          </el-form-item>
          <el-form-item label="用例类型">
            <el-select v-model="formData.type" placeholder="请选择" style="width: 100%">
              <el-option label="正向用例" value="positive" />
              <el-option label="负向用例" value="negative" />
              <el-option label="边界值用例" value="boundary" />
              <el-option label="数据驱动用例" value="data" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="formData.priority" placeholder="请选择" style="width: 100%">
              <el-option label="P0 (最高)" value="P0" />
              <el-option label="P1 (高)" value="P1" />
              <el-option label="P2 (中)" value="P2" />
              <el-option label="P3 (低)" value="P3" />
            </el-select>
          </el-form-item>
        </div>

        <div class="form-grid">
          <el-form-item label="来源">
            <el-input v-model="formData.source" placeholder="如：PRD / 代码分析" />
          </el-form-item>
          <el-form-item label="置信度">
            <el-input-number
              v-model="confidenceInput"
              :min="0"
              :max="1"
              :step="0.05"
              :precision="2"
              style="width: 100%"
            />
          </el-form-item>
        </div>

        <!-- 前置条件 -->
        <section class="edit-section">
          <header class="edit-section-head">
            <span class="edit-section-title">前置条件</span>
            <el-button text type="primary" :icon="Plus" size="small" @click="addItem('preconditions')">
              添加
            </el-button>
          </header>
          <div
            v-for="(item, idx) in formData.preconditions"
            :key="'pre-edit-' + idx"
            class="edit-row"
          >
            <span class="edit-row-index">{{ idx + 1 }}</span>
            <el-input
              v-model="formData.preconditions[idx]"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 3 }"
              placeholder="请输入前置条件"
            />
            <el-button
              text
              type="danger"
              :icon="Delete"
              @click="removeItem('preconditions', idx)"
            />
          </div>
        </section>

        <!-- 测试步骤 -->
        <section class="edit-section">
          <header class="edit-section-head">
            <span class="edit-section-title">测试步骤</span>
            <el-button text type="primary" :icon="Plus" size="small" @click="addItem('steps')">
              添加
            </el-button>
          </header>
          <div
            v-for="(step, idx) in formData.steps"
            :key="'step-edit-' + idx"
            class="edit-row"
          >
            <span class="edit-row-index">{{ idx + 1 }}</span>
            <el-input
              v-model="formData.steps[idx]"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 3 }"
              placeholder="请输入测试步骤"
            />
            <el-button
              text
              type="danger"
              :icon="Delete"
              @click="removeItem('steps', idx)"
            />
          </div>
        </section>

        <!-- 预期结果 -->
        <section class="edit-section">
          <header class="edit-section-head">
            <span class="edit-section-title">预期结果</span>
            <el-button text type="primary" :icon="Plus" size="small" @click="addItem('expectedResults')">
              添加
            </el-button>
          </header>
          <div
            v-for="(result, idx) in formData.expectedResults"
            :key="'exp-edit-' + idx"
            class="edit-row"
          >
            <span class="edit-row-index">{{ idx + 1 }}</span>
            <el-input
              v-model="formData.expectedResults[idx]"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 3 }"
              placeholder="请输入预期结果"
            />
            <el-button
              text
              type="danger"
              :icon="Delete"
              @click="removeItem('expectedResults', idx)"
            />
          </div>
        </section>

        <!-- 结构化步骤 -->
        <section class="edit-section">
          <header class="edit-section-head">
            <span class="edit-section-title">结构化步骤（可执行）</span>
            <el-button text type="primary" :icon="Plus" size="small" @click="addStructuredStep">
              添加
            </el-button>
          </header>
          <div
            v-for="(step, idx) in formData.structuredSteps"
            :key="'ss-' + idx"
            class="structured-edit-card"
          >
            <div class="structured-edit-head">
              <span class="structured-edit-index">步骤 {{ idx + 1 }}</span>
              <el-button
                text
                type="danger"
                :icon="Delete"
                size="small"
                @click="removeStructuredStep(idx)"
              />
            </div>
            <el-row :gutter="12">
              <el-col :span="6">
                <el-select v-model="step.type" placeholder="步骤类型" size="small" style="width: 100%">
                  <el-option label="接口调用" value="api_call" />
                  <el-option label="界面操作" value="ui_action" />
                  <el-option label="状态断言" value="state_assert" />
                  <el-option label="人工" value="manual" />
                </el-select>
              </el-col>
              <el-col :span="18">
                <el-input v-model="step.action" placeholder="动作描述" size="small" />
              </el-col>
            </el-row>
            <el-input
              v-model="step.target"
              placeholder="操作目标，如 POST /api/order/create"
              size="small"
              class="mt-2"
            />
            <el-input
              v-model="step.expected"
              placeholder="预期结果"
              size="small"
              class="mt-2"
            />
          </div>
        </section>
      </el-form>
    </div>

    <!-- 底部操作区（查看模式时由父级 dialog 显示 footer） -->
    <div class="card-footer card-footer-inline">
      <template v-if="!editMode">
        <div class="footer-left">
          <el-button text :icon="ArrowLeft" :disabled="!canGoPrev" @click="goPrev">上一条</el-button>
          <el-button text @click="goNext" :disabled="!canGoNext">
            下一条<el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
          <el-button text :icon="Clock" @click="emit('versions')">历史版本</el-button>
        </div>
        <div class="footer-right">
          <el-button @click="handleClose">关闭</el-button>
          <el-button v-if="editable" type="primary" :icon="EditPen" @click="enterEditMode">
            编辑
          </el-button>
          <el-button v-if="testCase && testCase.id" text :icon="Clock" @click="openExecutions">
            执行记录
          </el-button>
          <el-button type="success" :icon="VideoPlay" @click="openExecuteDialog">执行</el-button>
          <el-button type="danger" plain :icon="Delete" @click="handleDelete">删除</el-button>
        </div>
      </template>
      <template v-else>
        <div class="footer-right">
          <el-button @click="cancelEdit">取消</el-button>
          <el-button type="primary" :icon="Check" @click="handleSave">
            {{ mode === 'create' ? '创建' : '保存' }}
          </el-button>
        </div>
      </template>
    </div>
  </div>

  <!-- =================== 执行测试用例对话框 =================== -->
  <el-dialog
    v-model="executeDialogVisible"
    title="执行测试用例"
    width="480px"
    append-to-body
  >
    <el-form label-width="100px">
      <el-form-item label="用例标题">
        <span class="exec-case-title">{{ testCase.title || '-' }}</span>
      </el-form-item>
      <el-form-item label="待测页面URL">
        <el-input
          v-model="targetUrl"
          placeholder="http://localhost:5173"
          clearable
        />
      </el-form-item>
      <el-form-item label="执行模式">
        <el-radio-group v-model="executeMode">
          <el-radio value="agent">Agent 模式</el-radio>
          <el-radio value="programmatic">程序化模式</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="executeDialogVisible = false">取消</el-button>
      <el-button
        type="success"
        :icon="VideoPlay"
        :loading="executing"
        @click="confirmExecute"
      >
        确认执行
      </el-button>
    </template>
  </el-dialog>

  <!-- v5.10: 单条用例执行记录 -->
  <el-dialog v-model="executionsVisible" title="执行记录" width="780px" append-to-body>
    <el-table
      v-loading="loadingExecutions"
      :data="caseExecutions"
      empty-text="该用例暂无执行记录"
      @row-click="goExecution"
    >
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="executionStatusType(row.status)" size="small" effect="light">
            {{ executionStatusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="模式" width="100">
        <template #default="{ row }">{{ row.mode === 'agent' ? 'Agent' : '程序化' }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">{{ execDuration(row) }}</template>
      </el-table-column>
      <el-table-column label="开始时间" width="180">
        <template #default="{ row }">{{ formatExecTime(row.startTime) }}</template>
      </el-table-column>
      <el-table-column label="摘要" min-width="200" show-overflow-tooltip prop="summary" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="goExecution(row)">查看详情</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<script setup>
/**
 * 测试用例卡片组件
 * 同时承载查看 / 编辑 / 创建三种模式
 * 业务字段、事件、执行流程与旧版完全一致
 *
 * - mode=create：isStandalone=true，渲染独立 el-dialog
 * - mode=view：isStandalone=false，仅渲染内容（被父级 el-dialog 承载）
 */
import { ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus, Delete, EditPen, Check, Clock, VideoPlay,
  List, Tickets, CircleCheck, Connection, InfoFilled,
  Coin, Share, ArrowLeft, ArrowRight
} from '@element-plus/icons-vue'
import StateMachineViewer from './StateMachineViewer.vue'
import { executeTestCase, getExecutions } from '@/api/execution'

const props = defineProps({
  testCase: { type: Object, default: () => ({}) },
  editable: { type: Boolean, default: false },
  visible: { type: Boolean, default: false },
  // v3.12: 项目默认执行 URL（可选，自动带入执行对话框）
  defaultTargetUrl: { type: String, default: '' },
  canGoPrev: { type: Boolean, default: false },
  canGoNext: { type: Boolean, default: false },
  mode: { type: String, default: 'view' }
})

const emit = defineEmits(['save', 'close', 'delete', 'prev', 'next', 'versions', 'create', 'executed'])

const route = useRoute()
const router = useRouter()
const projectId = route.params.id

// 是否独立弹窗模式（创建模式）
const isStandalone = computed(() => props.mode === 'create')

// 编辑模式状态
const editMode = ref(props.mode === 'create')

// 执行相关状态
const executeDialogVisible = ref(false)
const targetUrl = ref('http://localhost:5173')
const executing = ref(false)
const executeMode = ref('agent')
// v5.9: 单条用例执行记录
const executionsVisible = ref(false)
const caseExecutions = ref([])
const loadingExecutions = ref(false)

// 表单数据
const formData = reactive({
  id: '',
  title: '',
  module: '',
  type: '',
  priority: '',
  preconditions: [],
  steps: [],
  expectedResults: [],
  structuredSteps: [],
  stateMachineRef: null,
  source: '',
  confidence: 0
})

const confidenceInput = ref(0)

// 对话框标题
const dialogTitle = computed(() => {
  if (props.mode === 'create') return '新增用例'
  const id = props.testCase?.id || ''
  const title = props.testCase?.title || ''
  if (editMode.value) return `编辑用例${id ? ' - ' + id : ''}`
  return [id, title].filter(Boolean).join(' - ') || '用例详情'
})

// 状态机引用
const hasStateMachineRef = computed(() => {
  const sm = props.testCase?.stateMachineRef
  if (!sm) return false
  return (sm.states && sm.states.length > 0) || (sm.transitions && sm.transitions.length > 0)
})

// 步骤总数（结构化优先）
const stepsTotal = computed(() => {
  if (hasStructuredSteps.value) return (props.testCase.structuredSteps || []).length
  return (props.testCase.steps || []).length
})

// ========== 类型映射 ==========
const typeLabel = (type) => {
  const map = {
    positive: '正向用例',
    negative: '负向用例',
    boundary: '边界值用例',
    data: '数据驱动用例'
  }
  return map[type] || type || '-'
}
const getTypeTagType = (type) => {
  const map = { positive: 'success', negative: 'danger', boundary: 'warning', data: 'info' }
  return map[type] || 'info'
}
const getPriorityTagType = (priority) => {
  const map = { P0: 'danger', P1: 'warning', P2: 'primary', P3: 'info' }
  return map[priority] || 'info'
}
const getConfidenceStatus = (confidence) => {
  if (confidence >= 0.8) return 'success'
  if (confidence >= 0.5) return 'warning'
  return 'exception'
}
const getStepTypeLabel = (type) => {
  const map = { api_call: '接口调用', ui_action: '界面操作', state_assert: '状态断言', manual: '人工' }
  return map[type] || type || '-'
}
const getStepTypeTagType = (type) => {
  const map = { api_call: 'success', ui_action: 'warning', state_assert: 'primary', manual: 'info' }
  return map[type] || 'info'
}
const getMethodClass = (method) => {
  const m = (method || '').toUpperCase()
  if (m === 'GET') return 'method-get'
  if (m === 'POST') return 'method-post'
  if (m === 'PUT' || m === 'DELETE') return 'method-put'
  return 'method-other'
}
const getApproachLabel = (approach) => {
  const map = { api_call: '接口调用', browser: '浏览器操作', manual: '人工执行' }
  return map[approach] || approach || '-'
}
const getApproachAlertType = (approach) => {
  const map = { api_call: 'success', browser: 'warning', manual: 'info' }
  return map[approach] || 'info'
}
const getExecutionStatusLabel = (status) => {
  const map = {
    not_executed: '未执行',
    running: '执行中',
    passed: '通过',
    failed: '失败',
    blocked: '阻塞'
  }
  return map[status] || '未执行'
}
const getExecutionStatusTagType = (status) => {
  const map = {
    passed: 'success',
    failed: 'danger',
    running: 'warning',
    blocked: 'info',
    not_executed: 'info'
  }
  return map[status] || 'info'
}
const qualityColor = (score) => {
  if (score >= 80) return '#10b981'
  if (score >= 50) return '#f59e0b'
  return '#ef4444'
}

// ========== 结构化字段计算属性 ==========
const hasStructuredSteps = computed(() =>
  Array.isArray(props.testCase?.structuredSteps) && props.testCase.structuredSteps.length > 0
)
const hasApiEndpoints = computed(() =>
  Array.isArray(props.testCase?.apiEndpoints) && props.testCase.apiEndpoints.length > 0
)
const hasExecutionHints = computed(() => {
  const h = props.testCase?.executionHints
  return h && typeof h === 'object' && h.approach
})
const hasTestData = computed(() => {
  const d = props.testCase?.testData
  return d && typeof d === 'object' && Object.keys(d).length > 0
})
const hasStepData = (data) => data && typeof data === 'object' && Object.keys(data).length > 0

// ========== 编辑相关方法 ==========
const enterEditMode = () => {
  const tc = props.testCase || {}
  formData.id = tc.id || ''
  formData.title = tc.title || ''
  formData.module = tc.module || ''
  formData.type = tc.type || ''
  formData.priority = tc.priority || ''
  formData.preconditions = Array.isArray(tc.preconditions) ? [...tc.preconditions] : []
  formData.steps = Array.isArray(tc.steps) ? [...tc.steps] : []
  formData.expectedResults = Array.isArray(tc.expectedResults) ? [...tc.expectedResults] : []
  formData.structuredSteps = Array.isArray(tc.structuredSteps)
    ? tc.structuredSteps.map((s) => ({ ...s, data: s.data || {} }))
    : []
  formData.stateMachineRef = tc.stateMachineRef || null
  formData.source = tc.source || ''
  formData.confidence = typeof tc.confidence === 'number' ? tc.confidence : 0
  confidenceInput.value = formData.confidence
  editMode.value = true
}

const cancelEdit = () => {
  if (props.mode === 'create') {
    handleClose()
    return
  }
  editMode.value = false
}

const addItem = (field) => formData[field].push('')
const removeItem = (field, idx) => formData[field].splice(idx, 1)

const addStructuredStep = () => {
  formData.structuredSteps.push({
    order: formData.structuredSteps.length + 1,
    action: '',
    target: '',
    expected: '',
    data: {},
    type: 'api_call'
  })
}
const removeStructuredStep = (idx) => {
  formData.structuredSteps.splice(idx, 1)
  formData.structuredSteps.forEach((s, i) => { s.order = i + 1 })
}

// 删除
const handleDelete = async () => {
  try {
    await ElMessageBox.confirm('确定要删除该用例吗？此操作不可撤销。', '确认删除', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    emit('delete', props.testCase.id)
  } catch {
    // 取消
  }
}

// 导航
const goPrev = () => emit('prev')
const goNext = () => emit('next')

// 执行测试用例
const openExecuteDialog = () => {
  targetUrl.value = props.testCase?.executionHints?.targetUrl || props.defaultTargetUrl || 'http://localhost:5173'
  executeMode.value = 'agent'
  executeDialogVisible.value = true
}

const confirmExecute = async () => {
  if (!targetUrl.value || !targetUrl.value.trim()) {
    ElMessage.warning('请输入待测页面URL')
    return
  }
  if (!props.testCase?.id) {
    ElMessage.warning('用例ID不存在，无法执行')
    return
  }
  executing.value = true
  try {
    const res = await executeTestCase(projectId, props.testCase.id, targetUrl.value.trim(), executeMode.value)
    const eid = res.data?.executionId
    emit('executed', props.testCase.id)
    executeDialogVisible.value = false
    if (eid) router.push(`/projects/${projectId}/executions/${eid}`)
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    executing.value = false
  }
}

async function openExecutions() {
  if (!props.testCase?.id) return
  executionsVisible.value = true
  loadingExecutions.value = true
  try {
    const res = await getExecutions(projectId, { page: 1, pageSize: 20, testCaseId: props.testCase.id })
    caseExecutions.value = res.data?.items || res.data?.executions || res.data?.records || res.data || []
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    loadingExecutions.value = false
  }
}

function goExecution(row) {
  if (row?.id) router.push(`/projects/${projectId}/executions/${row.id}`)
}

function executionStatusType(status) {
  return { passed: 'success', failed: 'danger', running: 'warning', pending: 'info', cancelled: 'info' }[status] || 'info'
}

function executionStatusLabel(status) {
  return { passed: '通过', failed: '失败', running: '执行中', pending: '排队中', cancelled: '已取消' }[status] || status || '-'
}

function formatExecTime(time) {
  if (!time) return '-'
  const d = new Date(time)
  if (isNaN(d.getTime())) return '-'
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function execDuration(row) {
  if (!row?.startTime) return '-'
  const start = new Date(row.startTime)
  const end = row.endTime ? new Date(row.endTime) : new Date()
  const diff = end - start
  if (isNaN(diff) || diff < 0) return '-'
  const seconds = Math.floor(diff / 1000)
  if (seconds < 60) return `${seconds} 秒`
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}

// 保存
const handleSave = () => {
  if (!formData.title) {
    ElMessage.warning('请填写用例标题')
    return
  }
  formData.confidence = confidenceInput.value
  const updated = {
    ...props.testCase,
    id: formData.id,
    title: formData.title,
    module: formData.module,
    type: formData.type,
    priority: formData.priority,
    preconditions: formData.preconditions.filter((s) => s.trim() !== ''),
    steps: formData.steps.filter((s) => s.trim() !== ''),
    expectedResults: formData.expectedResults.filter((s) => s.trim() !== ''),
    structuredSteps: formData.structuredSteps.filter((s) => s.action.trim() !== ''),
    stateMachineRef: formData.stateMachineRef,
    source: formData.source,
    confidence: formData.confidence
  }
  if (props.mode === 'create') {
    emit('create', updated)
  } else {
    emit('save', updated)
  }
  editMode.value = false
}

const handleClose = () => {
  editMode.value = false
  emit('close')
}

watch(() => props.visible, (val) => {
  if (!val) editMode.value = false
})
</script>

<style scoped>
/* ========== 主容器 ========== */
.case-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
  box-sizing: border-box;
}

/* ========== 头部信息卡 ========== */
.info-card {
  background: var(--bg-surface, #fff);
  border: 1px solid var(--border-light, #e5e7eb);
  border-radius: 10px;
  padding: 16px 18px;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.04) 0%, rgba(255, 255, 255, 0) 100%);
}
.info-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.info-title-block { flex: 1 1 200px; min-width: 200px; }
.info-title {
  margin: 0 0 4px 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary, #1f2937);
  line-height: 1.4;
  word-break: break-word;
}
.info-id {
  font-size: 12px;
  color: var(--text-tertiary, #9ca3af);
  font-family: 'Consolas', 'Monaco', monospace;
  word-break: break-all;
}
.info-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  flex-shrink: 0;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.info-cell {
  background: rgba(255, 255, 255, 0.6);
  border: 1px solid var(--border-light, #f0f0f0);
  border-radius: 8px;
  padding: 10px 12px;
  min-width: 0;
  overflow: hidden;
}
.info-label {
  font-size: 12px;
  color: var(--text-tertiary, #9ca3af);
  margin-bottom: 4px;
}
.info-value {
  font-size: 14px;
  color: var(--text-primary, #1f2937);
  font-weight: 500;
  word-break: break-word;
}
.info-value :deep(.el-progress) {
  width: 100%;
  max-width: 200px;
}
.info-value :deep(.el-progress-bar) {
  width: 100%;
  flex: 1;
}

/* ========== 通用区块 ========== */
.block {
  background: var(--bg-surface, #fff);
  border: 1px solid var(--border-light, #e5e7eb);
  border-radius: 10px;
  padding: 14px 16px;
  overflow: hidden;
}
.block-head { margin-bottom: 10px; }
.block-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #1f2937);
  flex-wrap: wrap;
}
.block-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  background: var(--bg-tag, #f3f4f6);
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
  font-weight: 500;
  border-radius: 11px;
}

/* ========== 编号列表 ========== */
.numbered-list {
  margin: 0;
  padding-left: 22px;
  line-height: 1.9;
  color: var(--text-primary, #1f2937);
  word-break: break-word;
}
.numbered-list li { margin-bottom: 6px; }

/* ========== 结构化步骤卡 ========== */
.structured-steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.step-card {
  border: 1px solid var(--border-light, #e5e7eb);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--bg-subtle, #fafafa);
  transition: all 0.2s ease;
  overflow: hidden;
}
.step-card:hover {
  border-color: var(--primary-light, #c7d2fe);
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.08);
}
.step-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.step-order {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  background: var(--primary, #6366f1);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  border-radius: 50%;
  flex-shrink: 0;
}
.step-action {
  flex: 1 1 200px;
  font-weight: 600;
  color: var(--text-primary, #1f2937);
  word-break: break-word;
  min-width: 0;
}
.step-card-body { padding-left: 4px; }
.step-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 13px;
  line-height: 1.6;
  flex-wrap: wrap;
}
.step-label {
  flex-shrink: 0;
  color: var(--text-tertiary, #9ca3af);
  min-width: 32px;
}
.step-text {
  color: var(--text-primary, #1f2937);
  word-break: break-word;
  min-width: 0;
}
.step-code {
  background: var(--bg-code, #f3f4f6);
  padding: 1px 6px;
  border-radius: 4px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #e63946;
  word-break: break-all;
  max-width: 100%;
  display: inline-block;
}

/* ========== 关联接口 ========== */
.api-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.api-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-family: 'Consolas', 'Monaco', monospace;
  border: 1px solid transparent;
  transition: all 0.2s ease;
  max-width: 100%;
}
.api-pill:hover { transform: translateY(-1px); }
.api-pill.method-get {
  background: rgba(16, 185, 129, 0.1);
  color: #059669;
  border-color: rgba(16, 185, 129, 0.2);
}
.api-pill.method-post {
  background: rgba(245, 158, 11, 0.1);
  color: #d97706;
  border-color: rgba(245, 158, 11, 0.2);
}
.api-pill.method-put {
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
  border-color: rgba(239, 68, 68, 0.2);
}
.api-pill.method-other {
  background: rgba(99, 102, 241, 0.1);
  color: #4f46e5;
  border-color: rgba(99, 102, 241, 0.2);
}
.api-path {
  font-weight: 500;
  word-break: break-all;
}

/* ========== 执行提示 ========== */
.hint-notes {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-secondary, #6b7280);
  word-break: break-word;
}

/* ========== 测试数据 ========== */
.data-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px;
}
.data-cell {
  background: var(--bg-subtle, #fafafa);
  border: 1px solid var(--border-light, #f0f0f0);
  border-radius: 6px;
  padding: 8px 10px;
  min-width: 0;
  overflow: hidden;
}
.data-key {
  font-size: 12px;
  color: var(--text-tertiary, #9ca3af);
  margin-bottom: 2px;
  word-break: break-all;
}
.data-val {
  font-size: 13px;
  color: var(--text-primary, #1f2937);
  word-break: break-all;
}

/* ========== 状态机引用 ========== */
.state-machine-wrap {
  border: 1px solid var(--border-light, #e5e7eb);
  border-radius: 8px;
  padding: 8px;
  background: var(--bg-surface, #fff);
  overflow: hidden;
}
.state-machine-wrap :deep(.state-machine-viewer) {
  width: 100%;
}

/* ========== 编辑模式 ========== */
.case-edit {
  padding: 4px 0;
  width: 100%;
  overflow: hidden;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}
.form-grid.form-grid-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.form-grid .el-form-item { margin-bottom: 14px; min-width: 0; }

.edit-section {
  border: 1px solid var(--border-light, #e5e7eb);
  border-radius: 8px;
  padding: 12px 14px;
  margin-top: 12px;
  background: var(--bg-surface, #fff);
}
.edit-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.edit-section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #1f2937);
}
.edit-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
}
.edit-row-index {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-tag, #f3f4f6);
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
  font-weight: 600;
  border-radius: 50%;
  margin-top: 4px;
}

.structured-edit-card {
  border: 1px dashed var(--border-light, #d1d5db);
  border-radius: 8px;
  padding: 10px;
  margin-bottom: 10px;
  background: var(--bg-subtle, #fafafa);
}
.structured-edit-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.structured-edit-index {
  font-size: 13px;
  font-weight: 600;
  color: var(--primary, #6366f1);
}
.mt-2 { margin-top: 8px; }

/* ========== 底部按钮区 ========== */
.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  width: 100%;
  box-sizing: border-box;
}
.card-footer-inline {
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--border-light, #e5e7eb);
}
.footer-left {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.footer-right {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  flex-wrap: wrap;
}

.exec-case-title {
  font-weight: 600;
  color: var(--text-primary, #1f2937);
  word-break: break-all;
}

.text-muted {
  color: var(--text-tertiary, #c0c4cc);
  font-size: 12px;
}

/* ========== 响应式 ========== */
@media (max-width: 768px) {
  .form-grid,
  .form-grid.form-grid-3 {
    grid-template-columns: 1fr;
  }
  .info-grid {
    grid-template-columns: 1fr;
  }
  .card-footer,
  .card-footer-inline {
    flex-direction: column;
    align-items: stretch;
  }
  .footer-left,
  .footer-right {
    justify-content: center;
    margin-left: 0;
  }
}
</style>
