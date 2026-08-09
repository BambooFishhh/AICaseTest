<template>
  <div class="mindmap-preview" v-loading="loading">
    <div class="page-header">
      <h2>脑图预览</h2>
      <div class="header-actions">
        <el-button type="primary" :loading="generating" @click="handleGenerate">
          生成脑图
        </el-button>
        <el-button :disabled="!hasData" @click="handleDownload">下载.xmind</el-button>
        <el-button @click="goBack">返回</el-button>
      </div>
    </div>

    <el-empty v-if="!loading && !hasData" description="尚未生成脑图" />

    <template v-if="hasData">
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
            <div class="stat-value positive">{{ stats.positive }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6" :md="5">
          <div class="stat-card">
            <div class="stat-label">异常</div>
            <div class="stat-value negative">{{ stats.negative }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6" :md="5">
          <div class="stat-card">
            <div class="stat-label">边界</div>
            <div class="stat-value boundary">{{ stats.boundary }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6" :md="5">
          <div class="stat-card">
            <div class="stat-label">数据</div>
            <div class="stat-value data">{{ stats.data }}</div>
          </div>
        </el-col>
      </el-row>

      <el-card>
        <template #header>
          <div class="card-header">
            <span class="card-title">{{ previewData?.title || '脑图结构' }}</span>
            <div class="legend">
              <span class="legend-item"><i class="dot dot-root"></i>根节点</span>
              <span class="legend-item"><i class="dot dot-module"></i>模块</span>
              <span class="legend-item"><i class="dot dot-positive"></i>正向</span>
              <span class="legend-item"><i class="dot dot-negative"></i>异常</span>
              <span class="legend-item"><i class="dot dot-boundary"></i>边界</span>
            </div>
          </div>
        </template>
        <div ref="chartRef" class="mindmap-chart"></div>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { previewMindmap, generateMindmap, downloadMindmapUrl } from '@/api/mindmap'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id

const loading = ref(false)
const generating = ref(false)
const previewData = ref(null)
const chartRef = ref(null)
let chartInstance = null

const hasData = computed(() => {
  return !!(previewData.value && previewData.value.children && previewData.value.children.length > 0)
})

const stats = computed(() => {
  const s = { total: 0, positive: 0, negative: 0, boundary: 0, data: 0 }
  if (!previewData.value) return s
  const typeMap = { '正向': 'positive', '异常': 'negative', '边界': 'boundary', '数据': 'data' }

  function countLeaves(node) {
    const children = Array.isArray(node?.children) ? node.children : []
    if (children.length === 0) return 1
    let n = 0
    children.forEach((c) => {
      n += countLeaves(c)
    })
    return n
  }

  function walk(node) {
    if (!node) return
    const children = Array.isArray(node.children) ? node.children : []
    children.forEach((c) => {
      if (typeMap[c.title]) {
        s[typeMap[c.title]] += countLeaves(c)
      } else {
        walk(c)
      }
    })
  }

  walk(previewData.value)
  s.total = countLeaves(previewData.value)
  return s
})

// 将后端数据转换为 ECharts tree 格式
function transformNode(node) {
  if (!node) return null
  const result = {
    name: node.title || node.name || '未命名',
    id: node.id || '',
    children: []
  }

  // 根据节点类型设置样式
  if (node.id === 'root') {
    result.itemStyle = { color: '#1e3a8a', borderColor: '#1e3a8a' }
    result.label = { fontWeight: 'bold', fontSize: 15, color: '#1e3a8a' }
    result.symbolSize = 14
  } else if (node.id && node.id.startsWith('module-')) {
    result.itemStyle = { color: '#3b82f6', borderColor: '#3b82f6' }
    result.label = { fontWeight: 'bold', fontSize: 13, color: '#3b82f6' }
    result.symbolSize = 10
  } else if (node.id && node.id.startsWith('type-')) {
    if (node.title === '正向') {
      result.itemStyle = { color: '#10b981', borderColor: '#10b981' }
      result.label = { color: '#10b981', fontWeight: 'bold' }
    } else if (node.title === '异常') {
      result.itemStyle = { color: '#ef4444', borderColor: '#ef4444' }
      result.label = { color: '#ef4444', fontWeight: 'bold' }
    } else if (node.title === '边界') {
      result.itemStyle = { color: '#f59e0b', borderColor: '#f59e0b' }
      result.label = { color: '#f59e0b', fontWeight: 'bold' }
    } else if (node.title === '数据') {
      result.itemStyle = { color: '#8b5cf6', borderColor: '#8b5cf6' }
      result.label = { color: '#8b5cf6', fontWeight: 'bold' }
    }
    result.symbolSize = 8
  } else {
    // 叶子节点（测试用例）
    result.itemStyle = { color: '#9ca3af', borderColor: '#9ca3af' }
    result.symbolSize = 6
  }

  // 递归处理 children
  const children = Array.isArray(node.children) ? node.children : []
  if (children.length > 0) {
    result.children = children.map(transformNode).filter(Boolean)
  } else {
    delete result.children
  }

  return result
}

function getChartOption() {
  const data = previewData.value ? transformNode(previewData.value) : null
  return {
    tooltip: {
      trigger: 'item',
      triggerOn: 'mousemove',
      formatter: function (params) {
        return params.data.name
      },
      backgroundColor: 'rgba(30, 41, 59, 0.9)',
      borderColor: '#475569',
      textStyle: { color: '#f1f5f9', fontSize: 12 }
    },
    series: [
      {
        type: 'tree',
        data: data ? [data] : [],
        top: '2%',
        left: '8%',
        bottom: '2%',
        right: '20%',
        symbol: 'circle',
        symbolSize: 7,
        orient: 'LR',
        layout: 'orthogonal',
        roam: true,
        label: {
          position: 'left',
          verticalAlign: 'middle',
          align: 'right',
          fontSize: 12,
          color: '#374151'
        },
        leaves: {
          label: {
            position: 'right',
            verticalAlign: 'middle',
            align: 'left',
            color: '#6b7280'
          }
        },
        emphasis: {
          focus: 'descendant',
          itemStyle: {
            shadowBlur: 10,
            shadowColor: 'rgba(0, 0, 0, 0.3)'
          }
        },
        expandAndCollapse: true,
        animationDuration: 550,
        animationDurationUpdate: 750,
        lineStyle: {
          color: '#cbd5e1',
          width: 1.5,
          curveness: 0.5
        }
      }
    ]
  }
}

function initChart() {
  if (!chartRef.value) return
  if (chartInstance) {
    chartInstance.dispose()
  }
  chartInstance = echarts.init(chartRef.value)
  chartInstance.setOption(getChartOption())
}

function handleResize() {
  if (chartInstance) {
    chartInstance.resize()
  }
}

async function loadPreview() {
  loading.value = true
  try {
    const res = await previewMindmap(projectId)
    previewData.value = res.data || null
  } finally {
    loading.value = false
  }
}

async function handleGenerate() {
  generating.value = true
  try {
    await generateMindmap(projectId)
    ElMessage.success('脑图生成成功')
    await loadPreview()
  } finally {
    generating.value = false
  }
}

function handleDownload() {
  window.open(downloadMindmapUrl(projectId))
}

function goBack() {
  router.push(`/projects/${projectId}`)
}

watch(previewData, () => {
  if (hasData.value) {
    nextTick(initChart)
  }
})

onMounted(async () => {
  await loadPreview()
  if (hasData.value) {
    nextTick(initChart)
  }
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<style scoped>
.mindmap-preview {
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
.stat-value.positive {
  color: #10b981;
}
.stat-value.negative {
  color: #ef4444;
}
.stat-value.boundary {
  color: #f59e0b;
}
.stat-value.data {
  color: #8b5cf6;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-title {
  font-weight: 600;
  font-size: 15px;
}
.legend {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #6b7280;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
}
.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.dot-root {
  background: #1e3a8a;
}
.dot-module {
  background: #3b82f6;
}
.dot-positive {
  background: #10b981;
}
.dot-negative {
  background: #ef4444;
}
.dot-boundary {
  background: #f59e0b;
}

.mindmap-chart {
  width: 100%;
  height: 600px;
}
</style>
