<template>
  <div class="testcase-list" v-loading="loading">
    <div class="page-header">
      <h2>测试用例</h2>
      <!-- v3.10: 工具栏按语义分组 -->
      <div class="header-actions">
        <div class="tb-group">
          <!-- v3.4: 生成参数配置 -->
          <el-button :icon="Setting" @click="handleOpenGenParams">生成参数</el-button>
          <!-- v3.5: 追加生成按钮（与重新生成互斥，streaming 时禁用） -->
          <el-button type="warning" :icon="Plus" :disabled="streaming" @click="handleOpenAppendDialog">
            追加生成
          </el-button>
          <el-button
            type="primary"
            :icon="RefreshRight"
            :loading="regenerating"
            :disabled="streaming"
            @click="handleRegenerate"
          >
            重新生成
          </el-button>
          <el-button :icon="Share" :loading="generatingMap" @click="handleGenerateMindmap">
            生成脑图
          </el-button>
          <el-button v-if="mindmapGenerated" type="success" :icon="View" @click="handleViewMindmap">
            查看脑图
          </el-button>
        </div>
        <div class="tb-group">
          <el-button
            type="danger"
            :icon="Delete"
            :disabled="selectedRows.length === 0"
            @click="handleBatchDelete"
          >
            批量删除<span v-if="selectedRows.length > 0">（{{ selectedRows.length }}）</span>
          </el-button>
          <!-- v2.1: 批量执行 -->
          <el-button
            type="success"
            :icon="VideoPlay"
            :disabled="selectedRows.length === 0"
            @click="openBatchExecuteDialog"
          >
            批量执行<span v-if="selectedRows.length > 0">（{{ selectedRows.length }}）</span>
          </el-button>
          <el-button
            :icon="Download"
            :disabled="selectedRows.length === 0"
            @click="handleExportSelected"
          >
            导出选中<span v-if="selectedRows.length > 0">（{{ selectedRows.length }}）</span>
          </el-button>
          <!-- v1.8: 批量评审下拉 -->
          <el-dropdown @command="handleReviewCommand" :disabled="selectedRows.length === 0">
            <el-button :icon="Check">
              批量评审<span v-if="selectedRows.length > 0">（{{ selectedRows.length }}）</span>
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="reviewed">标记为已评审</el-dropdown-item>
                <el-dropdown-item command="approved">标记为已批准</el-dropdown-item>
                <el-dropdown-item command="rejected">标记为已拒绝</el-dropdown-item>
                <el-dropdown-item command="draft">重置为草稿</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="tb-group">
          <!-- v3.9: 导入 XMind -->
          <el-button :icon="Upload" @click="triggerImportXmind">导入XMind</el-button>
          <!-- v3.6: 手动新增用例 -->
          <el-button type="success" :icon="Plus" @click="handleCreateTestCase">新增用例</el-button>
          <input
            ref="xmindFileInput"
            type="file"
            accept=".xmind"
            style="display: none"
            @change="handleImportXmind"
          />
        </div>
      </div>
    </div>

    <el-row :gutter="16" class="stats-bar">
      <el-col :xs="12" :sm="6" :md="4">
        <div class="stat-card stat-total">
          <div class="stat-icon"><el-icon :size="18"><Files /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.total }}</div>
            <div class="stat-label">总计</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6" :md="5">
        <div class="stat-card stat-positive">
          <div class="stat-icon"><el-icon :size="18"><CircleCheck /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.positive }}</div>
            <div class="stat-label">正向</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6" :md="5">
        <div class="stat-card stat-negative">
          <div class="stat-icon"><el-icon :size="18"><CircleClose /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.negative }}</div>
            <div class="stat-label">异常</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6" :md="5">
        <div class="stat-card stat-boundary">
          <div class="stat-icon"><el-icon :size="18"><Aim /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.boundary }}</div>
            <div class="stat-label">边界</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6" :md="5">
        <div class="stat-card stat-data">
          <div class="stat-icon"><el-icon :size="18"><Coin /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.data }}</div>
            <div class="stat-label">数据</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-card class="filter-card">
      <el-row :gutter="16">
        <el-col :xs="24" :sm="6">
          <el-select
            v-model="filters.module"
            placeholder="模块筛选"
            clearable
            style="width: 100%"
            @change="handleFilter"
          >
            <el-option v-for="m in moduleOptions" :key="m" :label="m" :value="m" />
          </el-select>
        </el-col>
        <el-col :xs="24" :sm="6">
          <el-select
            v-model="filters.type"
            placeholder="类型筛选"
            clearable
            style="width: 100%"
            @change="handleFilter"
          >
            <el-option label="正向" value="positive" />
            <el-option label="异常" value="negative" />
            <el-option label="边界" value="boundary" />
            <el-option label="数据" value="data" />
          </el-select>
        </el-col>
        <el-col :xs="24" :sm="6">
          <el-select
            v-model="filters.priority"
            placeholder="优先级筛选"
            clearable
            style="width: 100%"
          >
            <el-option label="P0" value="P0" />
            <el-option label="P1" value="P1" />
            <el-option label="P2" value="P2" />
            <el-option label="P3" value="P3" />
          </el-select>
        </el-col>
        <!-- v1.8: 评审状态筛选 -->
        <el-col :xs="24" :sm="6">
          <el-select
            v-model="filters.reviewStatus"
            placeholder="评审状态筛选"
            clearable
            style="width: 100%"
            @change="handleFilter"
          >
            <el-option label="草稿" value="draft" />
            <el-option label="已评审" value="reviewed" />
            <el-option label="已批准" value="approved" />
            <el-option label="已拒绝" value="rejected" />
          </el-select>
        </el-col>
        <el-col :xs="24" :sm="6">
          <el-input
            v-model="filters.keyword"
            placeholder="搜索用例标题/模块"
            clearable
            @keyup.enter="handleFilter"
            @clear="handleFilter"
          >
            <template #append>
              <el-button :icon="Search" @click="handleFilter" />
            </template>
          </el-input>
        </el-col>
      </el-row>
    </el-card>

    <el-alert
      v-if="progressText"
      :title="progressText"
      type="info"
      :closable="false"
      show-icon
      class="polling-alert"
    />
    <!-- v1.6: 生成失败时展示具体错误详情 -->
    <el-alert
      v-if="generationError"
      :title="`生成失败: ${generationError}`"
      type="error"
      :closable="false"
      show-icon
      class="polling-alert"
    />
    <!-- v3.2: 流式生成进度面板。v3.5: 标题按 currentGenMode 区分重新生成/追加生成 -->
    <el-alert
      v-if="streaming"
      :title="streamingAlertTitle"
      type="success"
      :closable="false"
      show-icon
      class="polling-alert streaming-alert"
    >
      <!-- v3.3: 进度文本 + 取消生成按钮 -->
      <div class="streaming-alert-body">
        <span class="streaming-progress-text">{{ streamProgress }}</span>
        <el-button
          type="danger"
          size="small"
          :loading="cancelling"
          @click="handleCancelGenerate"
        >
          取消生成
        </el-button>
      </div>
    </el-alert>

    <el-table
      :data="treeData"
      border
      style="width: 100%"
      row-key="id"
      :tree-props="{ children: 'children' }"
      :row-class-name="rowClassName"
      default-expand-all
      highlight-current-row
      @row-click="handleRowClick"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="45" />
      <el-table-column prop="id" label="编号" width="120">
        <template #default="{ row }">
          <span v-if="row.isModule"></span>
          <span v-else-if="streaming">生成中</span>
          <span v-else>{{ row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="200">
        <template #default="{ row }">
          <span v-if="row.isModule" class="module-label">
            <el-icon class="module-icon" :size="16"><FolderOpened /></el-icon>
            <span class="module-name">{{ row.title }}</span>
            <span class="module-count">{{ row.count }}</span>
          </span>
          <span v-else>{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag v-if="!row.isModule" :type="typeTagType(row.type)" size="small">
            {{ typeText(row.type) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="80">
        <template #default="{ row }">
          <el-tag v-if="!row.isModule" :type="priorityTagType(row.priority)" size="small">
            {{ row.priority }}
          </el-tag>
        </template>
      </el-table-column>
      <!-- v3.8: 前置条件/步骤/预期结果直接显示在列中 -->
      <el-table-column label="前置条件" width="220">
        <template #default="{ row }">
          <span v-if="row.isModule"></span>
          <span v-else-if="row.preconditions && row.preconditions.length" class="detail-summary">
            {{ row.preconditions[0] }}{{ row.preconditions.length > 1 ? ` (+${row.preconditions.length - 1})` : '' }}
          </span>
          <span v-else class="text-muted">无</span>
        </template>
      </el-table-column>
      <el-table-column label="测试步骤" width="220">
        <template #default="{ row }">
          <span v-if="row.isModule"></span>
          <span v-else-if="row.steps && row.steps.length" class="detail-summary">
            {{ row.steps[0] }}{{ row.steps.length > 1 ? ` (+${row.steps.length - 1})` : '' }}
          </span>
          <span v-else class="text-muted">无</span>
        </template>
      </el-table-column>
      <el-table-column label="预期结果" width="220">
        <template #default="{ row }">
          <span v-if="row.isModule"></span>
          <span v-else-if="row.expectedResults && row.expectedResults.length" class="detail-summary">
            {{ row.expectedResults[0] }}{{ row.expectedResults.length > 1 ? ` (+${row.expectedResults.length - 1})` : '' }}
          </span>
          <span v-else class="text-muted">无</span>
        </template>
      </el-table-column>
      <el-table-column label="质量" width="100">
        <template #default="{ row }">
          <el-progress
            v-if="!row.isModule && row.qualityScore > 0"
            :percentage="row.qualityScore"
            :color="qualityColor(row.qualityScore)"
            :stroke-width="14"
          />
          <span v-else-if="!row.isModule" class="text-muted">未评分</span>
        </template>
      </el-table-column>
      <el-table-column label="评审" width="80">
        <template #default="{ row }">
          <el-tag v-if="!row.isModule" :type="reviewTagType(row.reviewStatus)" size="small">
            {{ reviewText(row.reviewStatus) }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <!-- v3.6: 覆盖率面板（移到列表下方，可折叠） -->
    <el-collapse v-if="coverage" v-model="coverageExpanded" class="coverage-collapse">
      <el-collapse-item name="coverage" title="覆盖率度量">
        <el-row :gutter="16">
          <el-col :span="12">
            <div class="coverage-item">
              <div class="coverage-label">状态转换覆盖率</div>
              <el-progress
                :percentage="Math.round(coverage.stateTransition.rate * 100)"
                :color="coverageColor(coverage.stateTransition.rate)"
              />
              <div class="coverage-detail">
                {{ coverage.stateTransition.covered }} / {{ coverage.stateTransition.total }}
              </div>
            </div>
          </el-col>
          <el-col :span="12">
            <div class="coverage-item">
              <div class="coverage-label">接口覆盖率</div>
              <el-progress
                :percentage="Math.round(coverage.apiEndpoint.rate * 100)"
                :color="coverageColor(coverage.apiEndpoint.rate)"
              />
              <div class="coverage-detail">
                {{ coverage.apiEndpoint.covered }} / {{ coverage.apiEndpoint.total }}
              </div>
            </div>
          </el-col>
        </el-row>
      </el-collapse-item>
    </el-collapse>

    <!-- v1.5: 覆盖率矩阵 -->
    <CoverageMatrix
      v-if="coverageMatrix"
      :matrix="coverageMatrix"
      @filter-by-ids="handleFilterByIds"
    />

    <el-dialog
      v-model="dialogVisible"
      width="700px"
      :title="currentTestCase ? currentTestCase.title : '用例详情'"
    >
      <test-case-card
        v-if="currentTestCase"
        :test-case="currentTestCase"
        :editable="true"
        :can-go-prev="currentIndex > 0"
        :can-go-next="currentIndex < testCases.length - 1"
        @save="handleSaveTestCase"
        @close="dialogVisible = false"
        @delete="handleDeleteTestCase"
        @prev="handlePrev"
        @next="handleNext"
        @versions="handleOpenVersions"
      />
    </el-dialog>

    <!-- v3.6: 新增用例对话框（TestCaseCard 作为独立对话框） -->
    <TestCaseCard
      v-if="createDialogVisible"
      :visible="createDialogVisible"
      :test-case="{}"
      mode="create"
      @create="handleSaveNewTestCase"
      @close="createDialogVisible = false"
    />

    <!-- v1.9: 历史版本抽屉 -->
    <TestCaseVersionDrawer
      v-model:visible="versionDrawerVisible"
      :project-id="projectId"
      :testcase-id="currentTestCase?.id"
      :current-test-case="currentTestCase"
      @rollback="handleVersionRollback"
    />

    <!-- v2.1: 批量执行对话框 -->
    <el-dialog
      v-model="batchExecuteDialogVisible"
      title="批量执行测试用例"
      width="480px"
    >
      <el-form label-width="100px">
        <el-form-item label="选中用例数">
          <span>{{ selectedRows.length }} 条</span>
        </el-form-item>
        <el-form-item label="待测页面URL">
          <el-input
            v-model="batchTargetUrl"
            placeholder="http://localhost:5173"
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchExecuteDialogVisible = false">取消</el-button>
        <el-button
          type="success"
          :icon="VideoPlay"
          :loading="batchExecuting"
          @click="confirmBatchExecute"
        >
          确认执行
        </el-button>
      </template>
    </el-dialog>

    <!-- v3.4: 生成参数配置对话框 -->
    <el-dialog
      v-model="showGenParamsDialog"
      title="生成参数"
      width="480px"
    >
      <el-form :model="genParams" label-width="100px">
        <el-form-item label="用例密度">
          <el-radio-group v-model="genParams.caseDensity">
            <el-radio-button label="low">精简</el-radio-button>
            <el-radio-button label="medium">标准</el-radio-button>
            <el-radio-button label="high">详尽</el-radio-button>
          </el-radio-group>
          <div class="form-tip">控制每个状态转换/需求项的用例数量</div>
        </el-form-item>
        <el-form-item label="创造性">
          <el-slider
            v-model="genParams.temperature"
            :min="0.2"
            :max="0.6"
            :step="0.1"
            :marks="{ 0.2: '严谨', 0.4: '标准', 0.6: '发散' }"
          />
          <div class="form-tip">LLM 温度，越低越稳定一致，越高越多样发散</div>
        </el-form-item>
        <el-form-item label="聚焦类型">
          <el-checkbox-group v-model="genParams.focusTypes">
            <el-checkbox label="positive">正向</el-checkbox>
            <el-checkbox label="negative">异常</el-checkbox>
            <el-checkbox label="boundary">边界</el-checkbox>
            <el-checkbox label="data">数据</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">不选 = 全部类型（当前版本仅作为生成提示，不强制过滤）</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showGenParamsDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="savingParams"
          @click="handleSaveGenParams"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- v3.5: 追加生成类型选择对话框 -->
    <el-dialog
      v-model="showAppendDialog"
      title="追加生成"
      width="420px"
    >
      <el-form label-width="80px">
        <el-form-item label="追加类型">
          <el-radio-group v-model="appendType">
            <el-radio label="">全部类型</el-radio>
            <el-radio label="positive">正向</el-radio>
            <el-radio label="negative">异常</el-radio>
            <el-radio label="boundary">边界</el-radio>
            <el-radio label="data">数据</el-radio>
          </el-radio-group>
          <div class="form-tip">
            追加生成不会删除现有用例，新用例 ID 从现有最大 +1 续号。
            与现有用例标题重复的新用例会被自动去重。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAppendDialog = false">取消</el-button>
        <el-button
          type="warning"
          :icon="Plus"
          :loading="streaming"
          @click="handleConfirmAppend"
        >
          开始追加生成
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search,
  Delete,
  Download,
  Upload,
  Check,
  ArrowDown,
  VideoPlay,
  Setting,
  Plus,
  View,
  RefreshRight,
  Share,
  Files,
  CircleCheck,
  CircleClose,
  Aim,
  Coin,
  FolderOpened
} from '@element-plus/icons-vue'
import {
  listTestCases,
  streamGenerate,
  streamGenerateAppend,
  cancelGenerate,
  createTestCase,
  deleteTestCase,
  batchDeleteTestCases,
  importXmind,
  reviewTestCases
} from '@/api/testcase'
import { listProjects, getProject, getGenerationParams, updateGenerationParams } from '@/api/project'
import { generateMindmap } from '@/api/mindmap'
import { executeBatch } from '@/api/execution'
import { useProjectStore } from '@/stores/project'
import TestCaseCard from '@/components/TestCaseCard.vue'
import TestCaseVersionDrawer from '@/components/TestCaseVersionDrawer.vue'
import CoverageMatrix from '@/components/CoverageMatrix.vue'
import { getCoverageMatrix } from '@/api/coverage'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const projectId = route.params.id

const loading = ref(false)
const regenerating = ref(false)
const generatingMap = ref(false)
const pollingMessage = ref('')
// v1.6: 生成失败时的错误详情（来自后端 errorMessage）
const generationError = ref('')
// v1.6: 优先展示后端实时进度，兜底显示本地初始提示
const progressText = computed(
  () => projectStore.progressMessage || pollingMessage.value
)

// v3.2: SSE 流式生成状态
const streaming = ref(false)
const streamProgress = ref('')
const streamedCases = ref([]) // 流式期间实时累积的用例
const cancelling = ref(false) // v3.3: 取消生成中状态
let streamEs = null // EventSource 实例（非响应式）
// v3.5: 当前生成模式：'regenerate' | 'append' | null（用于差异化流式面板标题与完成提示）
const currentGenMode = ref(null)

// v3.5: 流式面板标题——追加生成与重新生成文案区分
// v3.7: 流式响应期间（0 条时）提示"正在接收 LLM 流式响应"
const streamingAlertTitle = computed(() => {
  if (!streaming.value) return ''
  const count = streamedCases.value.length
  const countText = count === 0 ? '正在接收 LLM 流式响应...' : `已收到 ${count} 条`
  if (currentGenMode.value === 'append') {
    return `正在追加生成测试用例... ${countText}`
  }
  return `正在生成测试用例... ${countText}`
})

const testCases = ref([])
const allTestCases = ref([])
const coverage = ref(null)
const coverageExpanded = ref([])  // 默认折叠
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filters = reactive({ module: '', type: '', priority: '', keyword: '', reviewStatus: '' })

const dialogVisible = ref(false)
const currentTestCase = ref(null)

// v1.4: 批量操作选中行
const selectedRows = ref([])
// v3.9: XMind 导入文件 input 引用
const xmindFileInput = ref(null)
// v3.9: 脑图是否已生成（显示查看按钮）
const mindmapGenerated = ref(false)

// v1.5: 覆盖率矩阵
const coverageMatrix = ref(null)

async function loadCoverageMatrix() {
  try {
    const res = await getCoverageMatrix(projectId)
    coverageMatrix.value = res.data
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

function handleSelectionChange(rows) {
  selectedRows.value = rows
}

const moduleOptions = computed(() => {
  const set = new Set()
  allTestCases.value.forEach((tc) => {
    if (tc.module) set.add(tc.module)
  })
  return Array.from(set)
})

const stats = computed(() => {
  const s = { total: total.value, positive: 0, negative: 0, boundary: 0, data: 0 }
  allTestCases.value.forEach((tc) => {
    if (tc.type === 'positive') s.positive++
    else if (tc.type === 'negative') s.negative++
    else if (tc.type === 'boundary') s.boundary++
    else if (tc.type === 'data') s.data++
  })
  return s
})

// 后端不支持 priority 服务端筛选，这里在当前页数据上做客户端筛选
// v3.2: 流式期间展示 streamedCases（实时累积），否则展示分页数据
const displayTestCases = computed(() => {
  if (streaming.value) {
    // v3.6: 追加模式合并已有用例 + 新用例；重新生成模式仅展示新用例
    const base = currentGenMode.value === 'append'
      ? [...streamedCases.value, ...testCases.value]
      : streamedCases.value
    if (!filters.priority) return base
    return base.filter((tc) => tc.priority === filters.priority)
  }
  if (!filters.priority) return testCases.value
  return testCases.value.filter((tc) => tc.priority === filters.priority)
})

// v3.8: 树状数据——按模块分组
const treeData = computed(() => {
  const cases = displayTestCases.value
  const moduleMap = new Map()
  cases.forEach((tc) => {
    const mod = tc.module || '未分类'
    if (!moduleMap.has(mod)) moduleMap.set(mod, [])
    moduleMap.get(mod).push(tc)
  })
  const tree = []
  moduleMap.forEach((children, mod) => {
    tree.push({
      id: `module-${mod}`,
      isModule: true,
      title: mod,
      count: children.length,
      module: mod,
      children,
    })
  })
  return tree
})

function rowClassName({ row }) {
  return row.isModule ? 'module-row' : 'case-row'
}

function typeTagType(type) {
  return (
    { positive: 'success', negative: 'danger', boundary: 'warning', data: 'info' }[type] || 'info'
  )
}

function typeText(type) {
  return { positive: '正向', negative: '异常', boundary: '边界', data: '数据' }[type] || type
}

function priorityTagType(priority) {
  if (priority === 'P0') return 'danger'
  if (priority === 'P1') return 'warning'
  if (priority === 'P2') return ''
  return 'info'
}

// v1.8: 评审状态标签
function reviewTagType(status) {
  return (
    { draft: 'info', reviewed: 'warning', approved: 'success', rejected: 'danger' }[status] ||
    'info'
  )
}
function reviewText(status) {
  return (
    { draft: '草稿', reviewed: '已评审', approved: '已批准', rejected: '已拒绝' }[status] ||
    status ||
    '草稿'
  )
}

// v1.8: 批量改评审状态
async function handleReviewCommand(command) {
  const ids = selectedRows.value.map((tc) => tc.id)
  const text = { reviewed: '已评审', approved: '已批准', rejected: '已拒绝', draft: '草稿' }[command]
  try {
    await ElMessageBox.confirm(
      `确定将选中的 ${ids.length} 条用例标记为「${text}」吗？`,
      '确认批量评审',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const res = await reviewTestCases(projectId, ids, command, null)
    ElMessage.success(`已更新 ${res.data.updated} 条用例状态为「${text}」`)
    await Promise.all([loadList(), loadAllForStats()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

// v1.2 覆盖率与质量颜色
function coverageColor(rate) {
  if (rate >= 0.8) return '#67c23a'
  if (rate >= 0.5) return '#e6a23c'
  return '#f56c6c'
}
function qualityColor(score) {
  if (score >= 80) return '#67c23a'
  if (score >= 50) return '#e6a23c'
  return '#f56c6c'
}

async function loadList() {
  loading.value = true
  try {
    // v3.8: 加载全部用例用于树状分组
    const params = { page: 1, pageSize: 9999 }
    if (filters.module) params.module = filters.module
    if (filters.type) params.type = filters.type
    if (filters.keyword) params.keyword = filters.keyword
    if (filters.reviewStatus) params.reviewStatus = filters.reviewStatus
    const res = await listTestCases(projectId, params)
    const data = res.data || {}
    testCases.value = data.testCases || []
    total.value = data.total || 0
    page.value = data.page || page.value
    pageSize.value = data.pageSize || pageSize.value
    coverage.value = data.coverage || null
  } finally {
    loading.value = false
  }
}

async function loadAllForStats() {
  const res = await listTestCases(projectId, { page: 1, pageSize: 9999 })
  allTestCases.value = res.data?.testCases || []
}

function handleFilter() {
  page.value = 1
  loadList()
}

function handleRowClick(row) {
  if (row.isModule) return
  currentTestCase.value = row
  dialogVisible.value = true
}

const currentIndex = computed(() => {
  if (!currentTestCase.value) return -1
  return testCases.value.findIndex((tc) => tc.id === currentTestCase.value.id)
})

function handlePrev() {
  if (currentIndex.value > 0) {
    currentTestCase.value = testCases.value[currentIndex.value - 1]
  }
}

function handleNext() {
  if (currentIndex.value < testCases.value.length - 1) {
    currentTestCase.value = testCases.value[currentIndex.value + 1]
  }
}

async function handleDeleteTestCase(testcaseId) {
  try {
    await deleteTestCase(projectId, testcaseId)
    ElMessage.success('用例已删除')
    dialogVisible.value = false
    await Promise.all([loadList(), loadAllForStats()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

async function handleSaveTestCase() {
  dialogVisible.value = false
  await Promise.all([loadList(), loadAllForStats()])
}

// v3.6: 手动新增用例
const createDialogVisible = ref(false)

function handleCreateTestCase() {
  createDialogVisible.value = true
}

async function handleSaveNewTestCase(formData) {
  try {
    await createTestCase(projectId, formData)
    ElMessage.success('用例创建成功')
    createDialogVisible.value = false
    await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
  } catch (e) {
    ElMessage.error('创建用例失败: ' + (e.message || ''))
  }
}

// v1.4: 批量删除
async function handleBatchDelete() {
  const count = selectedRows.value.length
  try {
    await ElMessageBox.confirm(
      `确定要删除选中的 ${count} 条用例吗？此操作不可撤销。`,
      '确认批量删除',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const ids = selectedRows.value.map((tc) => tc.id)
    const res = await batchDeleteTestCases(projectId, ids)
    ElMessage.success(`已删除 ${res.data} 条用例`)
    selectedRows.value = []
    await Promise.all([loadList(), loadAllForStats()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

// v1.4: 导出选中用例为脑图
async function handleExportSelected() {
  const ids = selectedRows.value.map((tc) => tc.id)
  try {
    await generateMindmap(projectId, { testcaseIds: ids })
    ElMessage.success('选中用例脑图生成成功')
    mindmapGenerated.value = true
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

// v3.9: 触发 XMind 文件选择
function triggerImportXmind() {
  xmindFileInput.value?.click()
}

// v3.9: 处理 XMind 导入
async function handleImportXmind(e) {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    const res = await importXmind(projectId, file)
    ElMessage.success(`导入完成：成功 ${res.data.imported} 条，跳过 ${res.data.skipped} 条`)
    await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
  e.target.value = ''
}

// v1.5: 按用例ID筛选（从覆盖率矩阵跳转）
function handleFilterByIds(ids) {
  // 加载全部用例后在内存中按 ids 筛选
  const all = allTestCases.value.filter((tc) => ids.includes(tc.id))
  // 设置 keyword 为第一个 id 来触发筛选
  if (ids.length > 0) {
    filters.keyword = ids[0]
    handleFilter()
  }
}

// v1.9: 历史版本抽屉
const versionDrawerVisible = ref(false)
function handleOpenVersions() {
  versionDrawerVisible.value = true
}

// v2.1: 批量执行
const batchExecuteDialogVisible = ref(false)
const batchTargetUrl = ref('http://localhost:5173')
const batchExecuting = ref(false)

function openBatchExecuteDialog() {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先选择要执行的用例')
    return
  }
  batchTargetUrl.value = 'http://localhost:5173'
  batchExecuteDialogVisible.value = true
}

async function confirmBatchExecute() {
  if (!batchTargetUrl.value || !batchTargetUrl.value.trim()) {
    ElMessage.warning('请输入待测页面URL')
    return
  }
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先选择要执行的用例')
    return
  }
  batchExecuting.value = true
  try {
    const caseIds = selectedRows.value.map((tc) => tc.id)
    const res = await executeBatch(projectId, caseIds, batchTargetUrl.value.trim())
    const batchId = res.data?.batchId
    batchExecuteDialogVisible.value = false
    ElMessage.success(`已启动批量执行，共 ${caseIds.length} 条用例`)
    if (batchId) {
      router.push(`/projects/${projectId}/batches/${batchId}`)
    }
  } catch (e) {
    // 错误已由响应拦截器统一提示
  } finally {
    batchExecuting.value = false
  }
}
async function handleVersionRollback() {
  await Promise.all([loadList(), loadAllForStats()])
  // 同步当前用例对象，使对话框内显示回滚后内容
  if (currentTestCase.value?.id) {
    try {
      const res = await listTestCases(projectId, { page: 1, pageSize: 9999 })
      const updated = (res.data?.testCases || []).find((tc) => tc.id === currentTestCase.value.id)
      if (updated) currentTestCase.value = updated
    } catch {
      // 忽略，列表已刷新
    }
  }
}

async function handleRegenerate() {
  try {
    await ElMessageBox.confirm(
      '即将重新生成测试用例，当前所有用例（含人工修改）将被覆盖删除。确定要继续吗？',
      '确认重新生成',
      { confirmButtonText: '确定生成', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  // v3.2: 进入流式生成模式
  generationError.value = ''
  streaming.value = true
  currentGenMode.value = 'regenerate' // v3.5: 标识模式
  streamProgress.value = '正在启动生成...'
  streamedCases.value = []
  regenerating.value = true

  streamEs = streamGenerate(projectId, {
    onProgress: (msg) => {
      streamProgress.value = msg
    },
    onCase: (tc) => {
      // 新用例插入到顶部，实时可见
      streamedCases.value.unshift(tc)
    },
    onComplete: async (total) => {
      ElMessage.success(`用例生成完成，共 ${total} 条`)
      streaming.value = false
      currentGenMode.value = null // v3.5: 重置
      streamProgress.value = ''
      regenerating.value = false
      cancelling.value = false
      page.value = 1
      // 刷新列表获取最终编号 + 覆盖率
      await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
    },
    onCancelled: async (msg) => {
      // v3.3: 生成被取消，旧用例已保留
      ElMessage.warning(msg || '生成已取消，旧用例已保留')
      streaming.value = false
      currentGenMode.value = null // v3.5: 重置
      streamProgress.value = ''
      regenerating.value = false
      cancelling.value = false
      // 刷新列表（显示旧用例）
      await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
    },
    onError: (msg) => {
      streaming.value = false
      currentGenMode.value = null // v3.5: 重置
      streamProgress.value = ''
      regenerating.value = false
      cancelling.value = false
      generationError.value = msg
      ElMessage.error('用例生成失败')
    }
  })
}

// v3.5: 追加生成——不删除现有用例，可选 type 过滤，跨去重，续号保存
const showAppendDialog = ref(false)
const appendType = ref('')

function handleOpenAppendDialog() {
  // 按钮已 disabled，此处二次校验防御
  if (streaming.value) {
    ElMessage.warning('正在生成中，请等待当前任务完成')
    return
  }
  appendType.value = '' // 默认全类型
  showAppendDialog.value = true
}

function handleConfirmAppend() {
  showAppendDialog.value = false
  startAppendStream(appendType.value)
}

function startAppendStream(type) {
  generationError.value = ''
  streaming.value = true
  currentGenMode.value = 'append'
  streamProgress.value = '正在启动追加生成...'
  streamedCases.value = []

  streamEs = streamGenerateAppend(projectId, type, {
    onProgress: (msg) => {
      streamProgress.value = msg
    },
    onCase: (tc) => {
      streamedCases.value.unshift(tc)
    },
    onComplete: async (data) => {
      // v3.5: data = { total, appended, dropped, existingBefore }
      const appended = data?.appended ?? 0
      const dropped = data?.dropped ?? 0
      if (appended === 0) {
        ElMessage.warning(`未追加新用例（生成 ${data?.total ?? 0} 条，全部被去重/过滤）`)
      } else {
        ElMessage.success(`追加 ${appended} 条用例，去重/过滤 ${dropped} 条`)
      }
      streaming.value = false
      currentGenMode.value = null
      streamProgress.value = ''
      cancelling.value = false
      page.value = 1
      await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
    },
    onCancelled: async (msg) => {
      ElMessage.warning(msg || '追加生成已取消，现有用例已保留')
      streaming.value = false
      currentGenMode.value = null
      streamProgress.value = ''
      cancelling.value = false
      await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
    },
    onError: (msg) => {
      streaming.value = false
      currentGenMode.value = null
      streamProgress.value = ''
      cancelling.value = false
      generationError.value = msg
      ElMessage.error('追加生成失败')
    }
  })
}

// v3.3: 取消流式生成
async function handleCancelGenerate() {
  try {
    await ElMessageBox.confirm(
      '确定要取消生成吗？已生成的用例将被丢弃，旧用例会保留。',
      '确认取消',
      { confirmButtonText: '确定取消', cancelButtonText: '继续生成', type: 'warning' }
    )
  } catch {
    return
  }
  cancelling.value = true
  try {
    await cancelGenerate(projectId)
    // 后端会在下个检查点停止并推送 cancelled 事件
    // onCancelled 回调会处理状态清理
  } catch {
    ElMessage.error('取消请求失败')
    cancelling.value = false
  }
}

async function handleGenerateMindmap() {
  generatingMap.value = true
  try {
    await generateMindmap(projectId)
    ElMessage.success('脑图生成成功')
    mindmapGenerated.value = true
  } finally {
    generatingMap.value = false
  }
}

// v3.9: 查看脑图
function handleViewMindmap() {
  router.push(`/projects/${projectId}/mindmap`)
}

// v3.4: 生成参数配置
const showGenParamsDialog = ref(false)
const savingParams = ref(false)
const genParams = ref({
  caseDensity: 'medium',
  temperature: 0.4,
  focusTypes: []
})

// v3.4: 打开对话框时拉取当前参数
async function handleOpenGenParams() {
  showGenParamsDialog.value = true
  try {
    const res = await getGenerationParams(projectId)
    if (res.data) {
      genParams.value = {
        caseDensity: res.data.caseDensity || 'medium',
        temperature: typeof res.data.temperature === 'number' ? res.data.temperature : 0.4,
        focusTypes: Array.isArray(res.data.focusTypes) ? res.data.focusTypes : []
      }
    }
  } catch {
    // 拉取失败用默认值
  }
}

// v3.4: 保存生成参数
async function handleSaveGenParams() {
  savingParams.value = true
  try {
    await updateGenerationParams(projectId, genParams.value)
    ElMessage.success('生成参数已保存，下次重新生成时生效')
    showGenParamsDialog.value = false
  } catch {
    ElMessage.error('保存失败')
  } finally {
    savingParams.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
  // v3.2: 来自 ProjectDetail "生成用例"跳转，自动触发流式生成
  if (route.query.generate === '1') {
    // 清理 query，避免刷新重复触发
    router.replace({ path: route.path })
    // 拉取项目状态，generating/analyzing 时不触发
    try {
      const res = await getProject(projectId)
      const status = res.data?.status
      if (status === 'analyzing' || status === 'generating') return
      handleRegenerate()
    } catch {
      // 状态获取失败则忽略，不自动触发
    }
  }
})

onUnmounted(() => {
  projectStore.stopPolling()
  // v3.2: 释放 EventSource，避免组件卸载后连接泄漏
  if (streamEs) {
    streamEs.close()
    streamEs = null
  }
})
</script>

<style scoped>
.testcase-list {
  padding: 4px 0;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 12px;
}
.page-header h2 {
  margin: 0;
}
/* v3.10: 工具栏分组 */
.header-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.tb-group {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border-right: 1px solid var(--el-border-color-lighter);
}
.tb-group:last-child {
  border-right: none;
  padding-right: 0;
}
.stats-bar {
  margin-bottom: 20px;
}
/* v3.10: 统计卡图标化 */
.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  text-align: left;
  padding: 14px 16px;

  .stat-icon {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: 10px;
    color: #fff;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25);
  }

  .stat-body {
    min-width: 0;
  }

  .stat-value {
    font-size: 24px;
    font-weight: 700;
    line-height: 1.1;
  }

  .stat-label {
    margin: 2px 0 0;
  }
}

.stat-total .stat-icon { background: linear-gradient(135deg, #7a92ff, #4c6fff); }
.stat-positive .stat-icon { background: linear-gradient(135deg, #7ed67e, #58b24c); }
.stat-negative .stat-icon { background: linear-gradient(135deg, #f78989, #e84b4b); }
.stat-boundary .stat-icon { background: linear-gradient(135deg, #ffb85c, #e6a23c); }
.stat-data .stat-icon { background: linear-gradient(135deg, #b39dff, #8b5cf6); }

.stat-total .stat-value { color: #4c6fff; }
.stat-positive .stat-value { color: #58b24c; }
.stat-negative .stat-value { color: #e84b4b; }
.stat-boundary .stat-value { color: #e6a23c; }
.stat-data .stat-value { color: #8b5cf6; }

.stat-total { background: linear-gradient(180deg, #f3f5ff, #fff); }
.stat-positive { background: linear-gradient(180deg, #f2fbf2, #fff); }
.stat-negative { background: linear-gradient(180deg, #fdf3f3, #fff); }
.stat-boundary { background: linear-gradient(180deg, #fdf7ee, #fff); }
.stat-data { background: linear-gradient(180deg, #f6f3ff, #fff); }

.stat-label {
  color: #909399;
  font-size: 12px;
  margin-bottom: 6px;
}
.stat-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}
.stat-label {
  color: #909399;
  font-size: 13px;
  margin-bottom: 6px;
}
.stat-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}
.filter-card {
  margin-bottom: 20px;
}
.polling-alert {
  margin-bottom: 16px;
}
/* v3.3: 流式生成 alert 内进度文本 + 取消按钮布局 */
.streaming-alert-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.streaming-progress-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pagination {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
.coverage-card {
  margin-bottom: 20px;
}
.coverage-collapse {
  margin-top: 20px;
}
.coverage-item {
  margin-bottom: 8px;
}
.coverage-label {
  font-size: 13px;
  color: #606266;
  margin-bottom: 6px;
}
.coverage-detail {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
/* v3.8: 移除展开行样式（改为树状表格直接显示详情列） */
.text-muted {
  color: #c0c4cc;
  font-size: 12px;
}
/* v3.4: 生成参数对话框提示文本 */
.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.4;
}
/* v3.8: 树状用例列表样式 */
.module-row {
  font-weight: bold;
  background-color: #f6f8ff;

  td.el-table__cell {
    background-color: #f6f8ff;
  }
}
.case-row {
  cursor: pointer;
}
.module-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #2c3f8f;

  .module-icon {
    color: #4c6fff;
  }

  .module-name {
    font-weight: 600;
  }

  .module-count {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 20px;
    height: 18px;
    padding: 0 6px;
    border-radius: 9px;
    background: var(--el-color-primary-light-8);
    color: var(--el-color-primary);
    font-size: 12px;
    font-weight: 600;
  }
}
.detail-summary {
  color: var(--el-text-color-regular, #606266);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: inline-block;
  max-width: 200px;
}

@media (max-width: 768px) {
  .tb-group {
    border-right: none;
    padding: 0;
  }
}
</style>
