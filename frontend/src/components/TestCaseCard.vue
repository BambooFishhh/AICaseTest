<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="70%"
    @close="handleClose"
  >
    <!-- 查看模式 -->
    <div v-if="!editMode" class="test-case-view">
      <el-descriptions :column="3" border>
        <el-descriptions-item label="用例ID">
          {{ testCase.id || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="标题">
          {{ testCase.title || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="所属模块">
          {{ testCase.module || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="用例类型">
          <el-tag :type="getTypeTagType(testCase.type)" size="small">
            {{ typeLabel(testCase.type) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="优先级">
          <el-tag :type="getPriorityTagType(testCase.priority)" size="small">
            {{ testCase.priority || '-' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源">
          {{ testCase.source || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="置信度">
          <el-progress
            v-if="typeof testCase.confidence === 'number'"
            :percentage="Math.round(testCase.confidence * 100)"
            :status="getConfidenceStatus(testCase.confidence)"
            :stroke-width="14"
          />
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="执行状态">
          <el-tag :type="getExecutionStatusTagType(testCase.executionStatus)" size="small">
            {{ getExecutionStatusLabel(testCase.executionStatus) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="质量评分">
          <el-progress
            v-if="testCase.qualityScore > 0"
            :percentage="testCase.qualityScore"
            :color="qualityColor(testCase.qualityScore)"
            :stroke-width="14"
          />
          <span v-else class="text-muted">未评分</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">前置条件</el-divider>
      <ol v-if="testCase.preconditions && testCase.preconditions.length" class="numbered-list">
        <li v-for="(item, idx) in testCase.preconditions" :key="'pre-' + idx">
          {{ item }}
        </li>
      </ol>
      <el-empty v-else description="无前置条件" :image-size="60" />

      <el-divider content-position="left">测试步骤</el-divider>
      <!-- 结构化步骤（v1.1） -->
      <div v-if="hasStructuredSteps" class="structured-steps">
        <div
          v-for="step in testCase.structuredSteps"
          :key="'sstep-' + step.order"
          class="step-card"
        >
          <div class="step-header">
            <el-tag size="small" type="info">{{ step.order }}</el-tag>
            <span class="step-action">{{ step.action }}</span>
            <el-tag
              v-if="step.type"
              :type="getStepTypeTagType(step.type)"
              size="small"
            >
              {{ getStepTypeLabel(step.type) }}
            </el-tag>
          </div>
          <div class="step-body">
            <div v-if="step.target" class="step-row">
              <span class="step-label">目标:</span>
              <code>{{ step.target }}</code>
            </div>
            <div v-if="step.expected" class="step-row">
              <span class="step-label">预期:</span>
              <span>{{ step.expected }}</span>
            </div>
            <div v-if="hasStepData(step.data)" class="step-row">
              <span class="step-label">数据:</span>
              <code>{{ JSON.stringify(step.data) }}</code>
            </div>
          </div>
        </div>
      </div>
      <!-- 回退：纯文本步骤 -->
      <ol v-else-if="testCase.steps && testCase.steps.length" class="numbered-list">
        <li v-for="(step, idx) in testCase.steps" :key="'step-' + idx">
          {{ step }}
        </li>
      </ol>
      <el-empty v-else description="无测试步骤" :image-size="60" />

      <el-divider content-position="left">预期结果</el-divider>
      <ol
        v-if="testCase.expectedResults && testCase.expectedResults.length"
        class="numbered-list"
      >
        <li v-for="(result, idx) in testCase.expectedResults" :key="'exp-' + idx">
          {{ result }}
        </li>
      </ol>
      <el-empty v-else description="无预期结果" :image-size="60" />

      <!-- 关联接口（v1.1） -->
      <template v-if="hasApiEndpoints">
        <el-divider content-position="left">关联接口</el-divider>
        <div class="api-endpoints">
          <el-tag
            v-for="(ep, i) in testCase.apiEndpoints"
            :key="'api-' + i"
            :type="getMethodTagType(ep.method)"
            class="api-tag"
          >
            <strong>{{ ep.method }}</strong> {{ ep.path }}
          </el-tag>
        </div>
      </template>

      <!-- 执行提示（v1.1） -->
      <template v-if="hasExecutionHints">
        <el-divider content-position="left">执行提示</el-divider>
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
      </template>

      <!-- 测试数据（v1.1） -->
      <template v-if="hasTestData">
        <el-divider content-position="left">测试数据</el-divider>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item
            v-for="(val, key) in testCase.testData"
            :key="'td-' + key"
            :label="key"
          >
            {{ typeof val === 'object' ? JSON.stringify(val) : val }}
          </el-descriptions-item>
        </el-descriptions>
      </template>

      <el-divider content-position="left">状态机引用</el-divider>
      <div v-if="hasStateMachineRef" class="state-machine-ref">
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
    </div>

    <!-- 编辑模式 -->
    <div v-else class="test-case-edit">
      <el-form :model="formData" label-width="100px" label-position="right">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="用例ID">
              <el-input :model-value="formData.id" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="标题">
              <el-input v-model="formData.title" placeholder="请输入用例标题" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="所属模块">
              <el-input v-model="formData.module" placeholder="请输入所属模块" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="用例类型">
              <el-select v-model="formData.type" placeholder="请选择用例类型" style="width: 100%">
                <el-option label="正向用例" value="positive" />
                <el-option label="负向用例" value="negative" />
                <el-option label="边界值用例" value="boundary" />
                <el-option label="数据驱动用例" value="data" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="优先级">
              <el-select v-model="formData.priority" placeholder="请选择优先级" style="width: 100%">
                <el-option label="P0 (最高)" value="P0" />
                <el-option label="P1 (高)" value="P1" />
                <el-option label="P2 (中)" value="P2" />
                <el-option label="P3 (低)" value="P3" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="来源">
              <el-input v-model="formData.source" placeholder="请输入用例来源" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
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
          </el-col>
        </el-row>

        <el-divider content-position="left">前置条件</el-divider>
        <div
          v-for="(item, idx) in formData.preconditions"
          :key="'pre-edit-' + idx"
          class="edit-list-item"
        >
          <span class="edit-list-index">{{ idx + 1 }}.</span>
          <el-input
            v-model="formData.preconditions[idx]"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 3 }"
            placeholder="请输入前置条件"
          />
          <el-button
            type="danger"
            :icon="Delete"
            circle
            size="small"
            @click="removeItem('preconditions', idx)"
          />
        </div>
        <el-button
          type="primary"
          plain
          :icon="Plus"
          size="small"
          @click="addItem('preconditions')"
        >
          添加前置条件
        </el-button>

        <el-divider content-position="left">测试步骤</el-divider>
        <div
          v-for="(step, idx) in formData.steps"
          :key="'step-edit-' + idx"
          class="edit-list-item"
        >
          <span class="edit-list-index">{{ idx + 1 }}.</span>
          <el-input
            v-model="formData.steps[idx]"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 3 }"
            placeholder="请输入测试步骤"
          />
          <el-button
            type="danger"
            :icon="Delete"
            circle
            size="small"
            @click="removeItem('steps', idx)"
          />
        </div>
        <el-button
          type="primary"
          plain
          :icon="Plus"
          size="small"
          @click="addItem('steps')"
        >
          添加测试步骤
        </el-button>

        <el-divider content-position="left">预期结果</el-divider>
        <div
          v-for="(result, idx) in formData.expectedResults"
          :key="'exp-edit-' + idx"
          class="edit-list-item"
        >
          <span class="edit-list-index">{{ idx + 1 }}.</span>
          <el-input
            v-model="formData.expectedResults[idx]"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 3 }"
            placeholder="请输入预期结果"
          />
          <el-button
            type="danger"
            :icon="Delete"
            circle
            size="small"
            @click="removeItem('expectedResults', idx)"
          />
        </div>
        <el-button
          type="primary"
          plain
          :icon="Plus"
          size="small"
          @click="addItem('expectedResults')"
        >
          添加预期结果
        </el-button>

        <el-divider content-position="left">结构化步骤（可执行）</el-divider>
        <div
          v-for="(step, idx) in formData.structuredSteps"
          :key="'ss-' + idx"
          class="edit-structured-step"
        >
          <div class="step-edit-header">
            <span class="step-edit-index">步骤 {{ idx + 1 }}</span>
            <el-button
              type="danger"
              :icon="Delete"
              circle
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
            class="step-edit-input"
          />
          <el-input
            v-model="step.expected"
            placeholder="预期结果"
            size="small"
            class="step-edit-input"
          />
        </div>
        <el-button
          type="primary"
          plain
          :icon="Plus"
          size="small"
          @click="addStructuredStep"
        >
          添加结构化步骤
        </el-button>
      </el-form>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <template v-if="!editMode">
          <el-button @click="handleClose">关闭</el-button>
          <el-button type="danger" :icon="Delete" @click="handleDelete">删除</el-button>
          <el-button v-if="editable" type="primary" :icon="EditPen" @click="enterEditMode">
            编辑
          </el-button>
          <el-button :icon="Clock" @click="emit('versions')">历史版本</el-button>
          <el-button type="success" :icon="VideoPlay" @click="openExecuteDialog">执行</el-button>
          <el-button @click="goPrev" :disabled="!canGoPrev">上一条</el-button>
          <el-button @click="goNext" :disabled="!canGoNext">下一条</el-button>
        </template>
        <template v-else>
          <el-button @click="cancelEdit">取消</el-button>
          <el-button type="primary" :icon="Check" @click="handleSave">
            {{ mode === 'create' ? '创建' : '保存' }}
          </el-button>
        </template>
      </div>
    </template>
  </el-dialog>

  <!-- v2.0 执行测试用例对话框 -->
  <el-dialog
    v-model="executeDialogVisible"
    title="执行测试用例"
    width="480px"
    append-to-body
  >
    <el-form label-width="100px">
      <el-form-item label="用例标题">
        <span>{{ testCase.title || '-' }}</span>
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
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, EditPen, Check, Clock, VideoPlay } from '@element-plus/icons-vue'
import StateMachineViewer from './StateMachineViewer.vue'
import { executeTestCase } from '@/api/execution'

const props = defineProps({
  testCase: {
    type: Object,
    default: () => ({})
  },
  editable: {
    type: Boolean,
    default: false
  },
  visible: {
    type: Boolean,
    default: false
  },
  canGoPrev: {
    type: Boolean,
    default: false
  },
  canGoNext: {
    type: Boolean,
    default: false
  },
  mode: {
    type: String,
    default: 'view'
  }
})

const emit = defineEmits(['save', 'close', 'delete', 'prev', 'next', 'versions', 'create'])

const route = useRoute()
const router = useRouter()
const projectId = route.params.id

// 编辑模式状态
const editMode = ref(props.mode === 'create')

// 执行相关状态
const executeDialogVisible = ref(false)
const targetUrl = ref('http://localhost:5173')
const executing = ref(false)
// v2.1: 执行模式，默认 Agent 模式
const executeMode = ref('agent')

// 表单数据（编辑模式）
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

// 置信度输入（0-1 范围）
const confidenceInput = ref(0)

// 对话框标题
const dialogTitle = computed(() => {
  // v3.6: 创建模式
  if (props.mode === 'create') {
    return '新增用例'
  }
  const id = props.testCase?.id || ''
  const title = props.testCase?.title || ''
  if (editMode.value) {
    return `编辑用例 ${id ? '- ' + id : ''}`
  }
  return [id, title].filter(Boolean).join(' - ') || '用例详情'
})

// 是否存在状态机引用
const hasStateMachineRef = computed(() => {
  const sm = props.testCase?.stateMachineRef
  if (!sm) return false
  return (
    (sm.states && sm.states.length > 0) ||
    (sm.transitions && sm.transitions.length > 0)
  )
})

// 类型标签文案
const typeLabel = (type) => {
  const map = {
    positive: '正向用例',
    negative: '负向用例',
    boundary: '边界值用例',
    data: '数据驱动用例'
  }
  return map[type] || type || '-'
}

// 类型标签颜色
const getTypeTagType = (type) => {
  const map = {
    positive: 'success',
    negative: 'danger',
    boundary: 'warning',
    data: 'info'
  }
  return map[type] || 'info'
}

// 优先级标签颜色
const getPriorityTagType = (priority) => {
  const map = {
    P0: 'danger',
    P1: 'warning',
    P2: 'primary',
    P3: 'info'
  }
  return map[priority] || 'info'
}

// 置信度状态
const getConfidenceStatus = (confidence) => {
  if (confidence >= 0.8) return 'success'
  if (confidence >= 0.5) return 'warning'
  return 'exception'
}

// ========== v1.1 结构化字段计算属性 ==========
const hasStructuredSteps = computed(() =>
  Array.isArray(props.testCase?.structuredSteps) &&
  props.testCase.structuredSteps.length > 0
)
const hasApiEndpoints = computed(() =>
  Array.isArray(props.testCase?.apiEndpoints) &&
  props.testCase.apiEndpoints.length > 0
)
const hasExecutionHints = computed(() => {
  const h = props.testCase?.executionHints
  return h && typeof h === 'object' && h.approach
})
const hasTestData = computed(() => {
  const d = props.testCase?.testData
  return d && typeof d === 'object' && Object.keys(d).length > 0
})

// 步骤是否有数据
const hasStepData = (data) => {
  return data && typeof data === 'object' && Object.keys(data).length > 0
}

// ========== v1.1 辅助方法 ==========
// 步骤类型标签文案
const getStepTypeLabel = (type) => {
  const map = {
    api_call: '接口调用',
    ui_action: '界面操作',
    state_assert: '状态断言',
    manual: '人工'
  }
  return map[type] || type || '-'
}
// 步骤类型标签颜色
const getStepTypeTagType = (type) => {
  const map = {
    api_call: 'success',
    ui_action: 'warning',
    state_assert: 'primary',
    manual: 'info'
  }
  return map[type] || 'info'
}
// HTTP method 标签颜色
const getMethodTagType = (method) => {
  const m = (method || '').toUpperCase()
  if (m === 'GET') return 'success'
  if (m === 'POST') return 'warning'
  if (m === 'PUT' || m === 'DELETE') return 'danger'
  return 'info'
}
// 执行方式标签文案
const getApproachLabel = (approach) => {
  const map = {
    api_call: '接口调用',
    browser: '浏览器操作',
    manual: '人工执行'
  }
  return map[approach] || approach || '-'
}
// 执行方式 alert 类型
const getApproachAlertType = (approach) => {
  const map = {
    api_call: 'success',
    browser: 'warning',
    manual: 'info'
  }
  return map[approach] || 'info'
}
// 执行状态标签文案
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
// 执行状态标签颜色
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

// v1.2 质量评分颜色
const qualityColor = (score) => {
  if (score >= 80) return '#67c23a'
  if (score >= 50) return '#e6a23c'
  return '#f56c6c'
}

// 进入编辑模式：克隆 props 数据到表单
const enterEditMode = () => {
  const tc = props.testCase || {}
  formData.id = tc.id || ''
  formData.title = tc.title || ''
  formData.module = tc.module || ''
  formData.type = tc.type || ''
  formData.priority = tc.priority || ''
  formData.preconditions = Array.isArray(tc.preconditions)
    ? [...tc.preconditions]
    : []
  formData.steps = Array.isArray(tc.steps) ? [...tc.steps] : []
  formData.expectedResults = Array.isArray(tc.expectedResults)
    ? [...tc.expectedResults]
    : []
  formData.structuredSteps = Array.isArray(tc.structuredSteps)
    ? tc.structuredSteps.map((s) => ({ ...s, data: s.data || {} }))
    : []
  formData.stateMachineRef = tc.stateMachineRef || null
  formData.source = tc.source || ''
  formData.confidence = typeof tc.confidence === 'number' ? tc.confidence : 0
  confidenceInput.value = formData.confidence
  editMode.value = true
}

// 取消编辑
const cancelEdit = () => {
  // v3.6: 创建模式取消 = 关闭对话框
  if (props.mode === 'create') {
    handleClose()
    return
  }
  editMode.value = false
}

// 添加列表项
const addItem = (field) => {
  formData[field].push('')
}

// 移除列表项
const removeItem = (field, idx) => {
  formData[field].splice(idx, 1)
}

// v1.3 结构化步骤编辑
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
  formData.structuredSteps.forEach((s, i) => {
    s.order = i + 1
  })
}

// v1.3 删除
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

// v1.3 导航
const goPrev = () => emit('prev')
const goNext = () => emit('next')

// v2.0 执行测试用例
const openExecuteDialog = () => {
  targetUrl.value = 'http://localhost:5173'
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
    executeDialogVisible.value = false
    if (eid) {
      router.push(`/projects/${projectId}/executions/${eid}`)
    }
  } catch (e) {
    // 错误已由响应拦截器统一提示
  } finally {
    executing.value = false
  }
}

// 保存：同步置信度并发射事件
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
  // v3.6: 创建模式 emit create
  if (props.mode === 'create') {
    emit('create', updated)
  } else {
    emit('save', updated)
  }
  editMode.value = false
}

// 关闭对话框
const handleClose = () => {
  editMode.value = false
  emit('close')
}

// 可见性变化时重置编辑模式
watch(
  () => props.visible,
  (val) => {
    if (!val) {
      editMode.value = false
    }
  }
)
</script>

<style scoped>
.test-case-view,
.test-case-edit {
  padding: 0 8px;
}

.numbered-list {
  margin: 0;
  padding-left: 24px;
  line-height: 1.9;
  color: #303133;
}

.numbered-list li {
  margin-bottom: 6px;
}

.edit-list-item {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.edit-list-index {
  flex-shrink: 0;
  width: 24px;
  text-align: right;
  color: #909399;
  font-weight: 500;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

/* v1.3 结构化步骤编辑 */
.edit-structured-step {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
  margin-bottom: 10px;
  background: #fafafa;
}
.step-edit-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.step-edit-index {
  font-weight: 600;
  color: #409eff;
}
.step-edit-input {
  margin-top: 6px;
}

.state-machine-ref {
  margin-top: 8px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px;
}

/* v1.1 结构化步骤样式 */
.structured-steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.step-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  background: #fafafa;
}
.step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.step-action {
  font-weight: 600;
  color: #303133;
  flex: 1;
}
.step-body {
  padding-left: 4px;
}
.step-row {
  margin-bottom: 4px;
  font-size: 13px;
  line-height: 1.6;
}
.step-row .step-label {
  color: #909399;
  margin-right: 6px;
}
.step-row code {
  background: #f0f0f0;
  padding: 1px 6px;
  border-radius: 3px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #e63946;
}

/* v1.1 关联接口样式 */
.api-endpoints {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.api-tag {
  font-family: 'Consolas', 'Monaco', monospace;
}

/* v1.1 执行提示样式 */
.hint-notes {
  margin-top: 4px;
  font-size: 13px;
  color: #606266;
}
.text-muted {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
