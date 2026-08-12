<template>
  <div class="dashboard page-container" v-loading="loading">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <h1 class="page-title">仪表盘</h1>
        <p class="page-subtitle">系统全局数据总览</p>
      </div>
      <div class="page-actions">
        <el-button :icon="RefreshRight" @click="loadData">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="goCreate">创建项目</el-button>
      </div>
    </header>

    <!-- 统计卡 -->
    <div class="stats-grid">
      <div class="stat-card stat-project">
        <div class="stat-icon"><el-icon :size="20"><FolderOpened /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.projectCount }}</div>
          <div class="stat-label">项目数</div>
        </div>
      </div>
      <div class="stat-card stat-case">
        <div class="stat-icon"><el-icon :size="20"><Files /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.testCaseCount }}</div>
          <div class="stat-label">用例总数</div>
        </div>
      </div>
      <div class="stat-card stat-exec">
        <div class="stat-icon"><el-icon :size="20"><VideoPlay /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.executionCount }}</div>
          <div class="stat-label">执行次数</div>
        </div>
      </div>
      <div class="stat-card stat-pass">
        <div class="stat-icon"><el-icon :size="20"><CircleCheck /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.passRate }}%</div>
          <div class="stat-label">整体通过率</div>
        </div>
      </div>
      <div class="stat-card stat-cov">
        <div class="stat-icon"><el-icon :size="20"><TrendCharts /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.avgStateRate }}%</div>
          <div class="stat-label">平均状态机覆盖率</div>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <section v-if="!loading && stats.projectCount === 0" class="empty-section">
      <el-empty description="还没有项目，创建第一个项目开始体验" :image-size="120">
        <el-button type="primary" :icon="Plus" @click="goCreate">创建项目</el-button>
      </el-empty>
    </section>

    <!-- 图表区 -->
    <template v-else>
      <div class="chart-grid">
        <section class="chart-section">
          <div class="section-head">
            <h2 class="section-title">用例类型分布</h2>
            <p class="section-desc">正向 / 异常 / 边界 / 数据</p>
          </div>
          <div ref="typeChartRef" class="chart-box"></div>
        </section>
        <section class="chart-section">
          <div class="section-head">
            <h2 class="section-title">项目状态机覆盖率</h2>
            <p class="section-desc">各项目状态转换覆盖率（%）</p>
          </div>
          <div ref="covChartRef" class="chart-box"></div>
        </section>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import {
  RefreshRight, Plus, FolderOpened, Files, VideoPlay, CircleCheck, TrendCharts
} from '@element-plus/icons-vue'
import { getStatsOverview } from '@/api/stats'

const router = useRouter()
const loading = ref(false)
const stats = reactive({
  projectCount: 0,
  testCaseCount: 0,
  executionCount: 0,
  passRate: 0,
  avgStateRate: 0,
  typeCounts: {},
  projectCoverage: []
})

const typeChartRef = ref(null)
const covChartRef = ref(null)
let typeChart = null
let covChart = null

async function loadData() {
  loading.value = true
  try {
    const res = await getStatsOverview()
    const data = res.data || {}
    stats.projectCount = data.projectCount || 0
    stats.testCaseCount = data.testCaseCount || 0
    stats.executionCount = data.executionCount || 0
    stats.passRate = data.passRate || 0
    stats.avgStateRate = data.avgStateRate || 0
    stats.typeCounts = data.typeCounts || {}
    stats.projectCoverage = data.projectCoverage || []
    await nextTick()
    renderCharts()
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    loading.value = false
  }
}

function renderCharts() {
  if (typeChartRef.value) {
    if (!typeChart) typeChart = echarts.init(typeChartRef.value)
    const typeMap = { positive: '正向', negative: '异常', boundary: '边界', data: '数据' }
    const colorMap = { positive: '#10b981', negative: '#ef4444', boundary: '#f59e0b', data: '#8b5cf6' }
    const data = Object.entries(stats.typeCounts).map(([k, v]) => ({
      name: typeMap[k] || k,
      value: v,
      itemStyle: { color: colorMap[k] || '#6366f1' }
    }))
    typeChart.setOption({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [
        {
          type: 'pie',
          radius: ['42%', '68%'],
          center: ['50%', '46%'],
          data,
          label: { formatter: '{b}: {c}' },
          emphasis: { itemStyle: { shadowBlur: 8, shadowColor: 'rgba(0,0,0,0.2)' } }
        }
      ]
    })
  }

  if (covChartRef.value) {
    if (!covChart) covChart = echarts.init(covChartRef.value)
    const items = stats.projectCoverage
    covChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 20, bottom: 60 },
      xAxis: {
        type: 'category',
        data: items.map((p) => p.name || p.id),
        axisLabel: { fontSize: 11, color: '#94a3b8', interval: 0, rotate: 20 }
      },
      yAxis: {
        type: 'value',
        max: 100,
        axisLabel: { formatter: '{value}%', fontSize: 11, color: '#94a3b8' },
        splitLine: { lineStyle: { color: '#f1f5f9' } }
      },
      series: [
        {
          type: 'bar',
          data: items.map((p) => p.stateRate || 0),
          barWidth: 26,
          itemStyle: {
            borderRadius: [6, 6, 0, 0],
            color: {
              type: 'linear',
              x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [
                { offset: 0, color: '#818cf8' },
                { offset: 1, color: '#4f46e5' }
              ]
            }
          }
        }
      ]
    })
  }
}

function handleResize() {
  typeChart?.resize()
  covChart?.resize()
}

function goCreate() {
  router.push('/projects/create')
}

onMounted(async () => {
  await loadData()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  typeChart?.dispose()
  covChart?.dispose()
})
</script>

<style scoped lang="scss">
.dashboard {
  padding: var(--space-lg) var(--space-xl);
  max-width: 1280px;
  margin: 0 auto;
}

.page-header-main {
  display: flex;
  align-items: baseline;
  gap: var(--space-md);
}

.page-subtitle {
  font-size: 13px;
  color: var(--text-tertiary);
  margin: 0;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
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
  }

  .stat-label {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 2px;
  }
}

.stat-project .stat-icon { background: linear-gradient(135deg, #818cf8, #4f46e5); }
.stat-project .stat-value { color: var(--brand-primary); }
.stat-case .stat-icon { background: linear-gradient(135deg, #22d3ee, #06b6d4); }
.stat-case .stat-value { color: #06b6d4; }
.stat-exec .stat-icon { background: linear-gradient(135deg, #a78bfa, #8b5cf6); }
.stat-exec .stat-value { color: #8b5cf6; }
.stat-pass .stat-icon { background: linear-gradient(135deg, #34d399, #10b981); }
.stat-pass .stat-value { color: var(--color-success); }
.stat-cov .stat-icon { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.stat-cov .stat-value { color: var(--color-warning); }

.empty-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-xl);
  padding: var(--space-2xl) var(--space-lg);
  box-shadow: var(--shadow-xs);
}

.chart-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-lg);
}

.chart-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 18px 20px;
  box-shadow: var(--shadow-xs);
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 2px;
}

.section-desc {
  font-size: 12px;
  color: var(--text-tertiary);
  margin: 0;
}

.chart-box {
  width: 100%;
  height: 300px;
}

@media (max-width: 1024px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
