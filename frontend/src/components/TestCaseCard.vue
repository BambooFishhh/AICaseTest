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
      </el-descriptions>

      <el-divider content-position="left">前置条件</el-divider>
      <ol v-if="testCase.preconditions && testCase.preconditions.length" class="numbered-list">
        <li v-for="(item, idx) in testCase.preconditions" :key="'pre-' + idx">
          {{ item }}
        </li>
      </ol>
      <el-empty v-else description="无前置条件" :image-size="60" />

      <el-divider content-position="left">测试步骤</el-divider>
      <ol v-if="testCase.steps && testCase.steps.length" class="numbered-list">
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
      </el-form>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <template v-if="!editMode">
          <el-button @click="handleClose">关闭</el-button>
          <el-button v-if="editable" type="primary" :icon="EditPen" @click="enterEditMode">
            编辑
          </el-button>
        </template>
        <template v-else>
          <el-button @click="cancelEdit">取消</el-button>
          <el-button type="primary" :icon="Check" @click="handleSave">
            保存
          </el-button>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Delete, EditPen, Check } from '@element-plus/icons-vue'
import StateMachineViewer from './StateMachineViewer.vue'

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
  }
})

const emit = defineEmits(['save', 'close'])

// 编辑模式状态
const editMode = ref(false)

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
  stateMachineRef: null,
  source: '',
  confidence: 0
})

// 置信度输入（0-1 范围）
const confidenceInput = ref(0)

// 对话框标题
const dialogTitle = computed(() => {
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
  formData.stateMachineRef = tc.stateMachineRef || null
  formData.source = tc.source || ''
  formData.confidence = typeof tc.confidence === 'number' ? tc.confidence : 0
  confidenceInput.value = formData.confidence
  editMode.value = true
}

// 取消编辑
const cancelEdit = () => {
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
    stateMachineRef: formData.stateMachineRef,
    source: formData.source,
    confidence: formData.confidence
  }
  emit('save', updated)
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

.state-machine-ref {
  margin-top: 8px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px;
}
</style>
