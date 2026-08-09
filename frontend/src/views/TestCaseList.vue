<template>
  <div class="testcase-list" v-loading="loading">
    <div class="page-header">
      <h2>测试用例</h2>
      <div class="header-actions">
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
        <el-col :xs="24" :sm="8">
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
        <el-col :xs="24" :sm="8">
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
        <el-col :xs="24" :sm="8">
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
      </el-row>
    </el-card>

    <el-alert
      v-if="pollingMessage"
      :title="pollingMessage"
      type="info"
      :closable="false"
      show-icon
      class="polling-alert"
    />

    <el-table
      :data="displayTestCases"
      border
      style="width: 100%"
      highlight-current-row
      @row-click="handleRowClick"
    >
      <el-table-column prop="id" label="编号" width="120" />
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
    </el-table>

    <el-pagination
      v-if="total > 0"
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
        @save="handleSaveTestCase"
        @close="dialogVisible = false"
      />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listTestCases, triggerGenerate } from '@/api/testcase'
import { generateMindmap } from '@/api/mindmap'
import { useProjectStore } from '@/stores/project'
import TestCaseCard from '@/components/TestCaseCard.vue'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const projectId = route.params.id

const loading = ref(false)
const regenerating = ref(false)
const generatingMap = ref(false)
const pollingMessage = ref('')

const testCases = ref([])
const allTestCases = ref([])
const coverage = ref(null)
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filters = reactive({ module: '', type: '', priority: '' })

const dialogVisible = ref(false)
const currentTestCase = ref(null)

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
const displayTestCases = computed(() => {
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

async function handleSaveTestCase() {
  dialogVisible.value = false
  await Promise.all([loadList(), loadAllForStats()])
}

async function handleRegenerate() {
  try {
    await triggerGenerate(projectId, {})
    ElMessage.success('用例生成已启动')
    pollingMessage.value = '正在生成测试用例，请稍候...'
    regenerating.value = true
    projectStore.startPolling(projectId, async (status) => {
      pollingMessage.value = ''
      regenerating.value = false
      if (status === 'completed') {
        ElMessage.success('用例生成完成')
        page.value = 1
        await Promise.all([loadList(), loadAllForStats()])
      } else if (status === 'failed') {
        ElMessage.error('用例生成失败')
      }
    })
  } catch (e) {
    // 错误已由响应拦截器统一提示
  }
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
  await Promise.all([loadList(), loadAllForStats()])
})

onUnmounted(() => {
  projectStore.stopPolling()
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
