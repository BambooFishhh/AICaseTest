<template>
  <div class="mindmap-preview page-container" v-loading="loading">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <h1 class="page-title">脑图预览</h1>
      </div>
      <div class="page-actions">
        <el-button type="primary" :loading="generating" :icon="Share" @click="handleGenerate">
          生成脑图
        </el-button>
        <el-button :disabled="!hasData" :icon="Download" @click="handleDownload">
          下载 .xmind
        </el-button>
        <!-- v3.18: 导出 PNG -->
        <el-button :disabled="!hasData" :icon="Picture" @click="exportPng">
          导出 PNG
        </el-button>
      </div>
    </header>

    <!-- 空状态 -->
    <div v-if="!loading && !hasData" class="empty-state">
      <el-icon :size="64" class="empty-icon"><Share /></el-icon>
      <h3 class="empty-title">尚未生成脑图</h3>
      <p class="empty-desc">点击"生成脑图"按钮，基于测试用例创建 XMind 脑图</p>
      <el-button type="primary" :loading="generating" :icon="Share" @click="handleGenerate">
        立即生成
      </el-button>
    </div>

    <template v-if="hasData">
      <!-- 统计卡片 -->
      <div class="stats-grid">
        <div class="stat-card stat-total">
          <div class="stat-icon"><el-icon :size="20"><Files /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.total }}</div>
            <div class="stat-label">总计</div>
          </div>
        </div>
        <div class="stat-card stat-positive">
          <div class="stat-icon"><el-icon :size="20"><CircleCheck /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.positive }}</div>
            <div class="stat-label">正向</div>
          </div>
        </div>
        <div class="stat-card stat-negative">
          <div class="stat-icon"><el-icon :size="20"><CircleClose /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.negative }}</div>
            <div class="stat-label">异常</div>
          </div>
        </div>
        <div class="stat-card stat-boundary">
          <div class="stat-icon"><el-icon :size="20"><Aim /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.boundary }}</div>
            <div class="stat-label">边界</div>
          </div>
        </div>
        <div class="stat-card stat-data">
          <div class="stat-icon"><el-icon :size="20"><Coin /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stats.data }}</div>
            <div class="stat-label">数据</div>
          </div>
        </div>
      </div>

      <!-- 脑图卡片 -->
      <section class="chart-card">
        <div class="chart-header">
          <div class="chart-title-block">
            <h2 class="chart-title">{{ previewData?.title || '脑图结构' }}</h2>
            <span class="chart-meta">共 {{ stats.total }} 个叶子节点</span>
          </div>
          <div class="chart-tools">
            <el-button text size="small" :icon="FullScreen" @click="fitView">适应屏幕</el-button>
            <el-button text size="small" @click="expandAll">全部展开</el-button>
            <el-button text size="small" @click="collapseAll">全部折叠</el-button>
          </div>
          <div class="legend">
            <span class="legend-item">
              <i class="dot dot-root"></i>根节点
            </span>
            <span class="legend-item">
              <i class="dot dot-module"></i>模块名
            </span>
            <span class="legend-item">
              <i class="dot dot-testcase"></i>用例名
            </span>
            <span class="legend-item">
              <i class="dot dot-preconditions"></i>前置条件
            </span>
            <span class="legend-item">
              <i class="dot dot-steps"></i>步骤
            </span>
            <span class="legend-item">
              <i class="dot dot-expected"></i>预期结果
            </span>
          </div>
        </div>
        <!-- markmap 渲染容器：必须用 svg 元素，需显式 width/height -->
        <div class="mindmap-chart-wrap">
          <svg ref="chartRef" class="mindmap-chart" width="100%" height="900"></svg>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
/**
 * 脑图预览页面
 * 使用 markmap 渲染 XMind 风格脑图：
 * - 自动布局，节点不会重叠
 * - 支持折叠/展开（点击节点圆点）
 * - 支持缩放（滚轮）和拖拽（空白处拖动）
 * - 分层配色：根/模块/类型/叶子各有颜色
 */
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Markmap } from 'markmap-view'
import {
  ArrowLeft, Share, Download, Picture, Files, CircleCheck, CircleClose, Aim, Coin, FullScreen
} from '@element-plus/icons-vue'
import { previewMindmap, generateMindmap, downloadMindmapUrl } from '@/api/mindmap'
import { downloadAuth } from '@/utils/download'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id

const loading = ref(false)
const generating = ref(false)
const previewData = ref(null)
const chartRef = ref(null)
let markmapInstance = null

const hasData = computed(() => {
  return !!(previewData.value && previewData.value.children && previewData.value.children.length > 0)
})

// 统计叶子节点数
function countLeaves(node) {
  if (!node) return 0
  const children = Array.isArray(node.children) ? node.children : []
  if (children.length === 0) return 1
  return children.reduce((sum, c) => sum + countLeaves(c), 0)
}

const stats = computed(() => {
  const s = { total: 0, positive: 0, negative: 0, boundary: 0, data: 0 }
  if (!previewData.value) return s
  const typeMap = { '正向': 'positive', '异常': 'negative', '边界': 'boundary', '数据': 'data' }

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

// 节点类型颜色映射（按业务语义分类）
const NODE_COLORS = {
  root:         { fill: '#1e3a8a', fontWeight: 'bold', fontSize: '16px' },  // 根节点 - 深蓝
  module:       { fill: '#3b82f6', fontWeight: 'bold', fontSize: '14px' },  // 模块名 - 蓝
  type:         { fill: '#64748b', fontWeight: 'normal', fontSize: '13px' }, // 类型 - 中性灰
  testcase:     { fill: '#f59e0b', fontWeight: 'bold', fontSize: '13px' },   // 用例名 - 橙
  preconditions:{ fill: '#10b981', fontWeight: 'bold', fontSize: '13px' },   // 前置条件 - 绿
  steps:        { fill: '#8b5cf6', fontWeight: 'bold', fontSize: '13px' },   // 步骤 - 紫
  expected:     { fill: '#06b6d4', fontWeight: 'bold', fontSize: '13px' },   // 预期结果 - 青
  leaf:         { fill: '#475569', fontWeight: 'normal', fontSize: '12px' }  // 叶子 - 深灰
}

// 连接线颜色映射
const BRANCH_COLORS = {
  root: '#1e3a8a',
  module: '#3b82f6',
  type: '#94a3b8',
  testcase: '#f59e0b',
  preconditions: '#10b981',
  steps: '#8b5cf6',
  expected: '#06b6d4',
  leaf: '#cbd5e1'
}

/**
 * 判断节点类型（根据 id 前缀和层级）
 */
function getNodeType(node, depth = 0) {
  const id = node?.id || ''
  if (id === 'root') return 'root'
  if (id.startsWith('module-')) return 'module'
  if (id.startsWith('type-')) return 'type'
  if (id.startsWith('preconditions-')) return 'preconditions'
  if (id.startsWith('steps-')) return 'steps'
  if (id.startsWith('expected-')) return 'expected'
  // depth 3 且有 children 的是用例节点；无 children 的是叶子
  if (depth >= 4) return 'leaf'
  return 'testcase'
}

/**
 * HTML 转义（防止 XSS），markmap 用 .html() 渲染内容
 */
function escapeHtml(str) {
  if (!str) return ''
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 将后端树状数据转换为 markmap 数据结构
 */
function transformToMarkmap(node, depth = 0) {
  if (!node) return null
  // markmap 用 .html() 渲染内容，需先转义防止 XSS
  let name = escapeHtml(node.title || node.name || '未命名')
  const nodeType = getNodeType(node, depth)
  const colorConfig = NODE_COLORS[nodeType] || NODE_COLORS.leaf

  // 用例名在 ID 和标题之间换行（后端格式为 "tcId 标题"，空格分隔）
  if (nodeType === 'testcase') {
    const spaceIdx = name.indexOf(' ')
    if (spaceIdx > 0 && spaceIdx < name.length - 1) {
      name = name.slice(0, spaceIdx) + '<br>' + name.slice(spaceIdx + 1)
    }
  }

  const result = {
    content: name,
    children: [],
    payload: {
      nodeType,
      style: { ...colorConfig }
    }
  }

  const children = Array.isArray(node.children) ? node.children : []
  if (children.length > 0) {
    result.children = children.map((c) => transformToMarkmap(c, depth + 1)).filter(Boolean)
  }

  return result
}

// markmap 连接线颜色回调
// markmap 的 color 回调接收 INode：node.payload 包含我们设置的 nodeType
function getBranchColor(node) {
  try {
    // 尝试从 payload 获取节点类型
    const nodeType = node?.payload?.nodeType || node?.data?.payload?.nodeType
    if (nodeType && BRANCH_COLORS[nodeType]) {
      return BRANCH_COLORS[nodeType]
    }
    // 兜底：按 depth 返回
    const depth = node?.state?.depth ?? node?.depth ?? 0
    const fallback = [BRANCH_COLORS.root, BRANCH_COLORS.module, BRANCH_COLORS.type,
                      BRANCH_COLORS.testcase, BRANCH_COLORS.leaf]
    return fallback[Math.min(depth, fallback.length - 1)]
  } catch {
    return BRANCH_COLORS.leaf
  }
}

function getMarkmapOptions() {
  return {
    autoFit: false,           // 不自动缩放，保持原始尺寸避免文字过小
    duration: 400,
    nodeMinHeight: 36,
    spacingHorizontal: 110,
    spacingVertical: 24,
    paddingX: 12,
    initialExpandLevel: 2,   // 初始只展开 2 层，避免过于密集
    maxWidth: 360,
    zoom: true,
    pan: true,
    color: getBranchColor
  }
}

function initChart() {
  if (!chartRef.value || !hasData.value) return
  const data = transformToMarkmap(previewData.value)
  if (!data) return

  try {
    if (markmapInstance) {
      markmapInstance.destroy()
      markmapInstance = null
    }

    // 确保 SVG 有内容容器
    markmapInstance = Markmap.create(chartRef.value, getMarkmapOptions(), data)

    // 渲染后自动适应
    nextTick(() => {
      if (markmapInstance) {
        markmapInstance.fit().catch(() => {})
      }
    })
    setTimeout(() => {
      if (markmapInstance) {
        markmapInstance.fit().catch(() => {})
      }
    }, 300)
  } catch (err) {
    console.error('[MindMap] markmap 渲染失败:', err)
  }
}

// 重新创建 markmap 实例（用于展开/折叠级别切换）
function rebuild(expandLevel) {
  if (!chartRef.value || !hasData.value) return
  const data = transformToMarkmap(previewData.value)
  if (!data) return

  if (markmapInstance) {
    markmapInstance.destroy()
    markmapInstance = null
  }

  const opts = getMarkmapOptions()
  opts.initialExpandLevel = expandLevel
  try {
    markmapInstance = Markmap.create(chartRef.value, opts, data)
    nextTick(() => markmapInstance?.fit()?.catch(() => {}))
    setTimeout(() => markmapInstance?.fit()?.catch(() => {}), 300)
  } catch (err) {
    console.error('[MindMap] rebuild 失败:', err)
  }
}

// 工具栏操作
function fitView() {
  if (markmapInstance) {
    markmapInstance.fit().catch(() => {})
  }
}

function expandAll() {
  rebuild(-1) // -1 = 全部展开
}

function collapseAll() {
  rebuild(0) // 0 = 只展开根节点
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
  downloadAuth(downloadMindmapUrl(projectId), 'mindmap.xmind')
}

// v3.18: 脑图导出 PNG（SVG → canvas）
function exportPng() {
  const svg = chartRef.value
  if (!svg) return
  const bounds = svg.getBoundingClientRect()
  const width = Math.max(bounds.width || 1200, 1200)
  const height = Math.max(bounds.height || 900, 900)
  const clone = svg.cloneNode(true)
  clone.setAttribute('width', width)
  clone.setAttribute('height', height)
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  const xml = new XMLSerializer().serializeToString(clone)
  const url = URL.createObjectURL(new Blob([xml], { type: 'image/svg+xml;charset=utf-8' }))
  const img = new Image()
  img.onload = () => {
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, width, height)
    ctx.drawImage(img, 0, 0, width, height)
    URL.revokeObjectURL(url)
    const a = document.createElement('a')
    a.href = canvas.toDataURL('image/png')
    a.download = `${previewData.value?.title || 'mindmap'}.png`
    a.click()
  }
  img.onerror = () => {
    URL.revokeObjectURL(url)
    ElMessage.error('导出 PNG 失败')
  }
  img.src = url
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

function handleResize() {
  if (markmapInstance) {
    markmapInstance.fit()
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (markmapInstance) {
    markmapInstance.destroy()
    markmapInstance = null
  }
})
</script>

<style scoped lang="scss">
.mindmap-preview {
  padding: var(--space-lg) var(--space-xl);
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

/* ===== 空状态 ===== */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-2xl) var(--space-lg);
  text-align: center;

  .empty-icon {
    color: var(--text-muted);
    margin-bottom: var(--space-md);
  }

  .empty-title {
    font-size: 18px;
    font-weight: 600;
    color: var(--text-secondary);
    margin-bottom: var(--space-xs);
  }

  .empty-desc {
    color: var(--text-tertiary);
    margin-bottom: var(--space-lg);
  }
}

/* ===== 统计卡片 ===== */
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
    color: var(--text-primary);
  }

  .stat-label {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 2px;
  }
}

.stat-total .stat-icon { background: linear-gradient(135deg, #818cf8, #4f46e5); }
.stat-positive .stat-icon { background: linear-gradient(135deg, #34d399, #10b981); }
.stat-negative .stat-icon { background: linear-gradient(135deg, #f87171, #ef4444); }
.stat-boundary .stat-icon { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.stat-data .stat-icon { background: linear-gradient(135deg, #a78bfa, #8b5cf6); }

/* ===== 脑图卡片 ===== */
.chart-card {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-md);
  padding: var(--space-lg) var(--space-xl);
  border-bottom: 1px solid var(--card-border-light);
  background: var(--bg-base);
}

.chart-title-block {
  display: flex;
  flex-direction: column;
  gap: 2px;

  .chart-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--text-primary);
    margin: 0;
  }

  .chart-meta {
    font-size: 12px;
    color: var(--text-tertiary);
  }
}

.chart-tools {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--text-secondary);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 5px;
}

.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.dot-root { background: #1e3a8a; }
.dot-module { background: #3b82f6; }
.dot-testcase { background: #f59e0b; }
.dot-preconditions { background: #10b981; }
.dot-steps { background: #8b5cf6; }
.dot-expected { background: #06b6d4; }

/* ===== markmap SVG 容器 ===== */
.mindmap-chart-wrap {
  width: 100%;
  height: 900px;
  overflow: auto;       /* 允许滚动查看大型脑图 */
  position: relative;
  background: var(--bg-base);
}

.mindmap-chart {
  width: 100%;
  height: 100%;
  display: block;
}

/* markmap 节点文字样式优化 */
.mindmap-chart :deep(text) {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* markmap 节点圆点（折叠/展开指示器）样式 */
.mindmap-chart :deep(circle) {
  cursor: pointer;
  transition: r 0.2s ease;
}

.mindmap-chart :deep(circle:hover) {
  r: 7;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .mindmap-preview {
    padding: var(--space-md);
  }

  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .mindmap-chart-wrap {
    height: 520px;
  }
}
</style>
