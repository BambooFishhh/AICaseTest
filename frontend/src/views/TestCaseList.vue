<template>
  <div class="testcase-list" v-loading="loading">
    <div class="page-header">
      <h2>测试用例</h2>
      <div class="header-actions">
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
        <!-- v1.7: 导入导出与协作 -->
        <el-button :icon="Download" @click="handleExportJson">
          导出JSON<span v-if="selectedRows.length > 0">（{{ selectedRows.length }}）</span>
        </el-button>
        <el-button :icon="Document" @click="handleExportCsv">导出CSV</el-button>
        <el-button :icon="Upload" @click="triggerImportFile">导入JSON</el-button>
        <el-button
          :icon="CopyDocument"
          :disabled="selectedRows.length === 0"
          @click="handleCopyTo"
        >
          复制到<span v-if="selectedRows.length > 0">（{{ selectedRows.length }}）</span>
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
        <input
          ref="importFileInput"
          type="file"
          accept=".json,application/json"
          style="display: none"
          @change="handleImportFile"
        />
        <el-button type="primary" :loading="regenerating" @click="handleRegenerate">
          重新生成
        </el-button>
        <el-button :loading="generatingMap" @click="handleGenerateMindmap">生成脑图</el-button>
      </div>
    </div>

    <el-row :gutter="16" class="stats-bar">
      <el-col :xs="12" :sm="6" :md="4">
        <div class="stat-card">
          <div class="stat-label">总计</div>
          <div class="stat-value">{{ stats.total }}</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6" :md="5">
        <div class="stat-card">
          <div class="stat-label">正向</div>
          <div class="stat-value">{{ stats.positive }}</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6" :md="5">
        <div class="stat-card">
          <div class="stat-label">异常</div>
          <div class="stat-value">{{ stats.negative }}</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6" :md="5">
        <div class="stat-card">
          <div class="stat-label">边界</div>
          <div class="stat-value">{{ stats.boundary }}</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6" :md="5">
        <div class="stat-card">
          <div class="stat-label">数据</div>
          <div class="stat-value">{{ stats.data }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- 覆盖率面板（v1.2） -->
    <el-card v-if="coverage" class="coverage-card">
      <template #header>覆盖率度量</template>
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
    </el-card>

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

    <!-- v1.5: 覆盖率矩阵 -->
    <CoverageMatrix
      v-if="coverageMatrix"
      :matrix="coverageMatrix"
      @filter-by-ids="handleFilterByIds"
    />

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
    <!-- v3.2: 流式生成进度面板 -->
    <el-alert
      v-if="streaming"
      :title="`正在生成测试用例... 已收到 ${streamedCases.length} 条`"
      :description="streamProgress"
      type="success"
      :closable="false"
      show-icon
      class="polling-alert streaming-alert"
    />

    <el-table
      :data="displayTestCases"
      border
      style="width: 100%"
      highlight-current-row
      @row-click="handleRowClick"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="45" />
      <el-table-column prop="id" label="编号" width="120">
        <template #default="{ row }">
          <span v-if="streaming">生成中</span>
          <span v-else>{{ row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="200" />
      <el-table-column prop="module" label="模块" width="140" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="typeTagType(row.type)">{{ typeText(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="100">
        <template #default="{ row }">
          <el-tag :type="priorityTagType(row.priority)">{{ row.priority }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="质量" width="120">
        <template #default="{ row }">
          <el-progress
            v-if="row.qualityScore > 0"
            :percentage="row.qualityScore"
            :color="qualityColor(row.qualityScore)"
            :stroke-width="14"
          />
          <span v-else class="text-muted">未评分</span>
        </template>
      </el-table-column>
      <!-- v1.8: 评审状态列 -->
      <el-table-column label="评审" width="100">
        <template #default="{ row }">
          <el-tag :type="reviewTagType(row.reviewStatus)" size="small">
            {{ reviewText(row.reviewStatus) }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > 0 && !streaming"
      class="pagination"
      background
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :current-page="page"
      :page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
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
  CopyDocument,
  Document,
  Check,
  ArrowDown,
  VideoPlay
} from '@element-plus/icons-vue'
import {
  listTestCases,
  streamGenerate,
  deleteTestCase,
  batchDeleteTestCases,
  exportTestCases,
  importTestCases,
  copyToProject,
  reviewTestCases
} from '@/api/testcase'
import { listProjects, getProject } from '@/api/project'
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
let streamEs = null // EventSource 实例（非响应式）

const testCases = ref([])
const allTestCases = ref([])
const coverage = ref(null)
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filters = reactive({ module: '', type: '', priority: '', keyword: '', reviewStatus: '' })

const dialogVisible = ref(false)
const currentTestCase = ref(null)

// v1.4: 批量操作选中行
const selectedRows = ref([])
// v1.7: 导入文件 input 引用
const importFileInput = ref(null)

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
    if (!filters.priority) return streamedCases.value
    return streamedCases.value.filter((tc) => tc.priority === filters.priority)
  }
  if (!filters.priority) return testCases.value
  return testCases.value.filter((tc) => tc.priority === filters.priority)
})

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
    const params = { page: page.value, pageSize: pageSize.value }
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

function handlePageChange(p) {
  page.value = p
  loadList()
}

function handleSizeChange(s) {
  pageSize.value = s
  page.value = 1
  loadList()
}

function handleRowClick(row) {
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
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

// v1.7: blob 文件下载（接收 exportTestCases 返回的 { data, fileName }）
function downloadBlob({ data, fileName }) {
  const url = URL.createObjectURL(data)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// v1.7: 导出 JSON（选中时只导出选中，否则导出全部）
async function handleExportJson() {
  const ids = selectedRows.value.map((tc) => tc.id)
  try {
    const result = await exportTestCases(projectId, 'json', ids)
    downloadBlob(result)
    ElMessage.success('JSON 导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  }
}

// v1.7: 导出 CSV（全部用例）
async function handleExportCsv() {
  try {
    const result = await exportTestCases(projectId, 'csv', null)
    downloadBlob(result)
    ElMessage.success('CSV 导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  }
}

// v1.7: 触发文件选择
function triggerImportFile() {
  importFileInput.value?.click()
}

// v1.7: 处理导入文件
async function handleImportFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    const res = await importTestCases(projectId, file)
    ElMessage.success(`导入完成：成功 ${res.data.imported} 条，跳过 ${res.data.skipped} 条`)
    await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
  e.target.value = '' // 重置以便重复导入同一文件
}

// v1.7: 复制选中用例到其他项目
async function handleCopyTo() {
  const ids = selectedRows.value.map((tc) => tc.id)
  try {
    const projRes = await listProjects()
    const projects = (projRes.data || []).filter((p) => p.id !== projectId)
    if (projects.length === 0) {
      ElMessage.warning('没有可复制的目标项目，请先创建其他项目')
      return
    }
    const options = projects
      .map((p) => `${p.id} - ${p.name}`)
      .join('\n')
    const { value: targetId } = await ElMessageBox.prompt(
      `可复制到的目标项目：\n${options}\n\n请输入目标项目 ID：`,
      '复制用例到其他项目',
      {
        confirmButtonText: '复制',
        cancelButtonText: '取消',
        inputType: 'text',
        inputValidator: (v) => !!v?.trim() || '请输入目标项目 ID'
      }
    )
    const res = await copyToProject(projectId, ids, targetId.trim())
    ElMessage.success(`已复制 ${res.data.copied} 条用例到目标项目`)
  } catch {
    // 用户取消或错误已由响应拦截器统一提示
  }
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
      streamProgress.value = ''
      regenerating.value = false
      page.value = 1
      // 刷新列表获取最终编号 + 覆盖率
      await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
    },
    onError: (msg) => {
      streaming.value = false
      streamProgress.value = ''
      regenerating.value = false
      generationError.value = msg
      ElMessage.error('用例生成失败')
    }
  })
}

async function handleGenerateMindmap() {
  generatingMap.value = true
  try {
    await generateMindmap(projectId)
    ElMessage.success('脑图生成成功')
  } finally {
    generatingMap.value = false
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
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header h2 {
  margin: 0;
}
.header-actions {
  display: flex;
  gap: 8px;
}
.stats-bar {
  margin-bottom: 20px;
}
.stat-card {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 16px;
  text-align: center;
  margin-bottom: 12px;
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
.pagination {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
.coverage-card {
  margin-bottom: 20px;
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
.text-muted {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
