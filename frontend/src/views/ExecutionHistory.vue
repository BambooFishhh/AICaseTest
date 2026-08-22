<template>
  <div class="execution-history page-container" v-loading="loading">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <div class="title-block">
          <h1 class="page-title">{{ isFiltered ? '用例执行历史' : '执行历史' }}</h1>
          <p class="page-subtitle">{{ pageSubtitle }}</p>
        </div>
      </div>
      <div class="page-actions">
        <el-button :icon="RefreshRight" @click="loadExecutions">刷新</el-button>
      </div>
    </header>

    <el-alert
      v-if="isFiltered"
      :title="filterTitle"
      type="info"
      :closable="false"
      show-icon
      class="filter-banner"
    >
      <el-button link type="primary" @click="clearTestCaseFilter">查看全部</el-button>
    </el-alert>

    <!-- 统计卡 -->
    <div class="stats-grid">
      <div class="stat-card stat-total">
        <div class="stat-icon"><el-icon :size="20"><Files /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.total }}</div>
          <div class="stat-label">总计</div>
        </div>
      </div>
      <div class="stat-card stat-passed">
        <div class="stat-icon"><el-icon :size="20"><CircleCheck /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.passed }}</div>
          <div class="stat-label">通过</div>
        </div>
      </div>
      <div class="stat-card stat-failed">
        <div class="stat-icon"><el-icon :size="20"><CircleClose /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.failed }}</div>
          <div class="stat-label">失败</div>
        </div>
      </div>
      <div class="stat-card stat-running">
        <div class="stat-icon"><el-icon :size="20"><Loading /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.running }}</div>
          <div class="stat-label">运行中</div>
        </div>
      </div>
      <div class="stat-card stat-skipped">
        <div class="stat-icon"><el-icon :size="20"><Remove /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.skipped || 0 }}</div>
          <div class="stat-label">已跳过</div>
        </div>
      </div>
      <div class="stat-card stat-rate">
        <div class="stat-icon"><el-icon :size="20"><TrendCharts /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ passRateText }}</div>
          <div class="stat-label">通过率</div>
        </div>
      </div>
    </div>

    <!-- v3.15: 通过率趋势 -->
    <section v-if="trendData.length > 0" class="trend-section">
      <div class="trend-head">
        <div>
          <h2 class="section-title">通过率趋势</h2>
          <p class="section-desc">最近 {{ trendData.length }} 次执行的滚动通过率</p>
        </div>
      </div>
      <div ref="trendChartRef" class="trend-chart"></div>
    </section>

    <!-- 空状态 -->
    <section v-if="!loading && records.length === 0" class="empty-section">
      <el-empty description="暂无执行记录，去用例列表执行用例" :image-size="120">
        <el-button type="primary" :icon="VideoPlay" @click="goTestcases">
          去执行用例
        </el-button>
      </el-empty>
    </section>

    <!-- 执行记录表格 -->
    <section v-else class="table-section">
      <el-table
        :data="records"
        highlight-current-row
        @row-click="goToDetail"
      >
        <el-table-column prop="testCaseTitle" label="用例标题" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="case-title">{{ row.testCaseTitle || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模式" width="100">
          <template #default="{ row }">
            <el-tag :type="modeTagType(row.mode)" size="small" effect="light">
              {{ modeText(row.mode) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="status-pill" :class="`status-${row.status}`">
              <i class="status-dot"></i>{{ statusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="110">
          <template #default="{ row }">
            <span class="duration">{{ duration(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="批次" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.batchId" type="warning" size="small" effect="plain">
              {{ row.batchId }}
            </el-tag>
            <span v-else class="text-muted">单条</span>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="180">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.startTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              :icon="View"
              @click.stop="goToDetail(row)"
            >
              查看详情
            </el-button>
            <el-button
              link
              :icon="Download"
              @click.stop="downloadReport(row)"
            >
              报告
            </el-button>
            <el-button
              v-if="row.status === 'running'"
              type="danger"
              link
              :icon="CircleClose"
              @click.stop="handleCancelExecution(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadExecutions"
          @size-change="handleSizeChange"
        />
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as echarts from 'echarts'
import {
  ArrowLeft,
  RefreshRight,
  Files,
  CircleCheck,
  CircleClose,
  Loading,
  TrendCharts,
  VideoPlay,
  View,
  Download,
  Remove
} from '@element-plus/icons-vue'
import { getExecutions, cancelExecution } from '@/api/execution'
import { ElMessage, ElMessageBox } from 'element-plus'
import { openAuthPreview } from '@/utils/download'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id

const loading = ref(false)
const records = ref([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
// v3.15/v5.7: 统计与趋势由后端全量计算
const stats = ref({ total: 0, passed: 0, failed: 0, running: 0 })
const trendData = ref([])
const trendChartRef = ref(null)
let trendChart = null

// v5.10: 按用例维度过滤
const filteredTestCaseId = computed(() => (route.query.testCaseId ? String(route.query.testCaseId) : ''))
const filteredTestCaseTitle = computed(() => (route.query.testCaseTitle ? String(route.query.testCaseTitle) : ''))
const isFiltered = computed(() => Boolean(filteredTestCaseId.value))
const pageSubtitle = computed(() =>
  isFiltered.value ? '仅展示该用例的执行记录、统计与趋势' : '项目内所有用例执行记录与结果总览'
)
const filterTitle = computed(() =>
  `当前仅展示「${filteredTestCaseTitle.value || filteredTestCaseId.value}」的执行记录`
)

const passRateText = computed(() => {
  const completed = stats.value.passed + stats.value.failed
  if (completed === 0) return '—'
  return `${Math.round((stats.value.passed / completed) * 100)}%`
})

function renderTrendChart() {
  if (!trendChartRef.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value)
  }
  const labels = trendData.value.map((_, i) => `第${i + 1}次`)
  trendChart.setOption({
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: { fontSize: 11, color: '#94a3b8' }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { formatter: '{value}%', fontSize: 11, color: '#94a3b8' },
      splitLine: { lineStyle: { color: '#f1f5f9' } }
    },
    series: [
      {
        type: 'line',
        data: trendData.value,
        smooth: true,
        symbolSize: 6,
        lineStyle: { color: '#4f46e5', width: 2 },
        itemStyle: { color: '#4f46e5' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(79,70,229,0.25)' },
              { offset: 1, color: 'rgba(79,70,229,0)' }
            ]
          }
        }
      }
    ]
  })
}

function handleResize() {
  trendChart?.resize()
}

const statusLabel = (status) => {
  const map = {
    passed: '通过',
    failed: '失败',
    running: '执行中',
    pending: '排队中',
    cancelled: '已取消',
    skipped: '已跳过'
  }
  return map[status] || status || '-'
}

const modeTagType = (mode) => {
  return mode === 'agent' ? 'success' : 'info'
}

const modeText = (mode) => {
  return mode === 'agent' ? 'Agent' : '程序化'
}

const duration = (row) => {
  if (!row.startTime) return '-'
  const start = new Date(row.startTime)
  const end = row.endTime ? new Date(row.endTime) : new Date()
  const diff = end - start
  if (isNaN(diff) || diff < 0) return '-'
  const seconds = Math.floor(diff / 1000)
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  const remainSec = seconds % 60
  return `${minutes} 分 ${remainSec} 秒`
}

const formatTime = (time) => {
  if (!time) return '-'
  const d = new Date(time)
  if (isNaN(d.getTime())) return '-'
  return d.toLocaleString('zh-CN', { hour12: false })
}

async function loadExecutions() {
  loading.value = true
  try {
    const params = { page: page.value, pageSize: pageSize.value }
    if (filteredTestCaseId.value) params.testCaseId = filteredTestCaseId.value
    const res = await getExecutions(projectId, params)
    const data = res.data
    // 兼容新旧后端：v5.7 返回 {items,total,stats,trend}，旧后端直接返回执行记录数组
    const list = Array.isArray(data) ? data : (data?.items || data?.executions || data?.records || [])
    records.value = list
    total.value = Array.isArray(data) ? list.length : (data?.total || list.length)
    stats.value = data?.stats || {
      total: list.length,
      passed: list.filter((r) => r.status === 'passed').length,
      failed: list.filter((r) => r.status === 'failed').length,
      running: list.filter((r) => r.status === 'running').length,
      skipped: list.filter((r) => r.status === 'skipped').length
    }
    trendData.value = data?.trend || []
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    loading.value = false
  }
}

// v5.7: 运行中的执行可直接在历史页取消
async function handleCancelExecution(row) {
  if (!row?.id) return
  try {
    await ElMessageBox.confirm(
      `确定取消「${row.testCaseTitle || row.id}」的执行吗？运行中的步骤会在下一个检查点停止。`,
      '确认取消执行',
      { confirmButtonText: '确定取消', cancelButtonText: '继续执行', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await cancelExecution(row.id)
    ElMessage.success('取消请求已发出，正在停止...')
    await loadExecutions()
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

function handleSizeChange() {
  page.value = 1
  loadExecutions()
}

watch(trendData, () => {
  nextTick(renderTrendChart)
})

watch(() => route.query.testCaseId, () => {
  page.value = 1
  loadExecutions()
})

function goToDetail(row) {
  if (row.id) {
    router.push(`/projects/${projectId}/executions/${row.id}`)
  }
}

function downloadReport(row) {
  openAuthPreview(`/api/executions/${row.id}/report`)
}

function goTestcases() {
  router.push(`/projects/${projectId}/testcases`)
}

function goBack() {
  router.push(`/projects/${projectId}/testcases`)
}

function clearTestCaseFilter() {
  router.replace({ path: `/projects/${projectId}/executions`, query: {} })
}

function handlePageShow(e) {
  if (e.persisted) {
    loadExecutions()
  }
}

onMounted(async () => {
  await loadExecutions()
  if (trendData.value.length > 0) {
    nextTick(renderTrendChart)
  }
  window.addEventListener('resize', handleResize)
  window.addEventListener('pageshow', handlePageShow)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('pageshow', handlePageShow)
  if (trendChart) {
    trendChart.dispose()
    trendChart = null
  }
})
</script>

<style scoped lang="scss">
.execution-history {
  padding: var(--space-lg) var(--space-xl);
  max-width: 1280px;
  margin: 0 auto;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.title-block {
  display: flex;
  align-items: baseline;
  gap: var(--space-md);
}

.page-subtitle {
  font-size: 13px;
  color: var(--text-tertiary);
  margin: 0;
}

.filter-banner {
  margin-bottom: var(--space-lg);
}

/* ===== 统计卡 ===== */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: var(--space-md) var(--space-lg);
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
  transition: all var(--transition-normal);

  &:hover {
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
  }

  .stat-icon {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    border-radius: var(--radius-md);
    color: #fff;
  }

  .stat-value {
    font-size: 24px;
    font-weight: 700;
    line-height: 1.1;
    color: var(--text-primary);
  }

  .stat-label {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 2px;
  }
}

.stat-total .stat-icon { background: linear-gradient(135deg, #818cf8, #4f46e5); }
.stat-passed .stat-icon { background: linear-gradient(135deg, #34d399, #10b981); }
.stat-failed .stat-icon { background: linear-gradient(135deg, #f87171, #ef4444); }
.stat-running .stat-icon { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.stat-skipped .stat-icon { background: linear-gradient(135deg, #94a3b8, #64748b); }
.stat-rate .stat-icon { background: linear-gradient(135deg, #22d3ee, #06b6d4); }

.stat-total .stat-value { color: var(--brand-primary); }
.stat-passed .stat-value { color: var(--color-success); }
.stat-failed .stat-value { color: var(--color-danger); }
.stat-running .stat-value { color: var(--color-warning); }
.stat-skipped .stat-value { color: var(--text-secondary); }
.stat-rate .stat-value { color: #06b6d4; }

/* ===== v3.15: 通过率趋势 ===== */
.trend-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 18px 20px;
  box-shadow: var(--shadow-xs);
  margin-bottom: var(--space-lg);
}

.trend-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 10px;
}

.trend-chart {
  width: 100%;
  height: 240px;
}

/* ===== 空状态 ===== */
.empty-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-xl);
  padding: var(--space-2xl) var(--space-lg);
  box-shadow: var(--shadow-xs);
}

/* ===== 表格 ===== */
.table-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding: 14px 16px;
  border-top: 1px solid var(--card-border-light);
}

.case-title {
  color: var(--text-primary);
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;

  .status-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  &.status-passed { color: var(--color-success); }
  &.status-failed { color: var(--color-danger); }
  &.status-running {
    color: var(--color-warning);
    .status-dot { animation: pulse 1.5s ease-in-out infinite; }
  }
  &.status-pending { color: var(--text-tertiary); }
  &.status-skipped { color: var(--text-tertiary); }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.duration,
.time-text {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--text-secondary);
}

.text-muted {
  color: var(--text-tertiary);
  font-size: 12px;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .execution-history {
    padding: var(--space-md);
  }

  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
