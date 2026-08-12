<template>
  <div class="state-machine-viewer">
    <div ref="chartRef" class="state-machine-chart"></div>
    <div v-if="!hasData" class="state-machine-empty">
      <el-empty description="暂无状态机数据" :image-size="100" />
    </div>
    <!-- 图例 -->
    <div v-if="hasData" class="chart-legend">
      <span class="legend-item">
        <i class="legend-dot dot-initial"></i>初始状态
      </span>
      <span class="legend-item">
        <i class="legend-dot dot-final"></i>终止状态
      </span>
      <span class="legend-item">
        <i class="legend-dot dot-normal"></i>普通状态
      </span>
      <span class="legend-item">
        <i class="legend-line line-covered"></i>已覆盖
      </span>
      <span class="legend-item">
        <i class="legend-line line-uncovered"></i>未覆盖
      </span>
      <span class="legend-item">
        <i class="legend-line line-forbidden"></i>禁止转移
      </span>
    </div>
  </div>
</template>

<script setup>
/**
 * 状态机可视化组件
 * 基于 ECharts Graph 图展示状态机的节点与转换：
 * - 节点颜色按状态类型区分（initial/final/normal）
 * - 边颜色按覆盖状态区分（已覆盖/未覆盖/禁止）
 * - 支持拖拽、缩放、tooltip
 */
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  states: {
    type: Array,
    default: () => []
  },
  transitions: {
    type: Array,
    default: () => []
  },
  forbiddenTransitions: {
    type: Array,
    default: () => []
  },
  // v1.5: 覆盖数据，用于标注边和节点的覆盖状态
  coverageData: {
    type: Object,
    default: null
  }
})

const chartRef = ref(null)
let chartInstance = null

// 是否有数据可渲染
const hasData = () => {
  return (
    (props.states && props.states.length > 0) ||
    (props.transitions && props.transitions.length > 0) ||
    (props.forbiddenTransitions && props.forbiddenTransitions.length > 0)
  )
}

// 根据状态类型获取节点边框颜色
const getColorByType = (type) => {
  switch (type) {
    case 'initial':
      return '#10b981' // 绿色 - 初始状态
    case 'final':
      return '#ef4444' // 红色 - 终止状态
    case 'normal':
    default:
      return '#4f46e5' // 品牌色 - 普通状态
  }
}

// 构建节点数据
const buildNodes = () => {
  if (!props.states || props.states.length === 0) return []
  return props.states.map((state) => {
    const color = getColorByType(state.type)
    const typeLabel =
      state.type === 'initial'
        ? '【初始】'
        : state.type === 'final'
          ? '【终止】'
          : ''
    return {
      id: state.name,
      name: state.name,
      symbol: 'circle',
      symbolSize: 64,
      itemStyle: {
        color: '#ffffff',
        borderColor: color,
        borderWidth: 3,
        shadowBlur: 8,
        shadowColor: 'rgba(0, 0, 0, 0.08)'
      },
      label: {
        show: true,
        formatter: typeLabel + state.name,
        fontSize: 12,
        color: '#0f172a',
        fontWeight: 600
      },
      tooltip: {
        formatter: () => {
          let html = `<b>${state.name}</b><br/>`
          html += `类型: ${state.type || 'normal'}<br/>`
          if (state.description) {
            html += `描述: ${state.description}`
          }
          return html
        }
      }
    }
  })
}

// v1.5: 检查转换是否被覆盖
const isCovered = (tran) => {
  if (!props.coverageData) return null
  const sm = props.coverageData.stateMachines?.[0]
  if (!sm) return null
  const match = (sm.transitions || []).find(t =>
    t.from === tran.from && t.to === tran.to
  )
  return match ? match.covered : null
}

// 构建正常转移边
const buildEdges = () => {
  if (!props.transitions || props.transitions.length === 0) return []
  return props.transitions.map((tran) => {
    const label = [tran.trigger, tran.condition]
      .filter(Boolean)
      .join(' / ')
    const covered = isCovered(tran)
    return {
      source: tran.from,
      target: tran.to,
      symbol: ['none', 'arrow'],
      symbolSize: [0, 10],
      lineStyle: {
        color: covered === false ? '#ef4444' : (covered === true ? '#10b981' : '#94a3b8'),
        width: 2,
        type: covered === false ? 'dashed' : 'solid',
        curveness: 0.2
      },
      label: {
        show: true,
        formatter: label,
        fontSize: 11,
        color: '#475569',
        backgroundColor: '#ffffff',
        padding: [3, 6],
        borderRadius: 4,
        borderColor: '#e2e8f0',
        borderWidth: 1
      }
    }
  })
}

// 构建禁止转移边（红色虚线）
const buildForbiddenEdges = () => {
  if (!props.forbiddenTransitions || props.forbiddenTransitions.length === 0)
    return []
  return props.forbiddenTransitions.map((tran) => {
    const label = tran.reason ? `禁止: ${tran.reason}` : '禁止'
    return {
      source: tran.from,
      target: tran.to,
      symbol: ['none', 'arrow'],
      symbolSize: [0, 10],
      lineStyle: {
        color: '#ef4444',
        width: 2,
        type: 'dashed',
        curveness: 0.3
      },
      label: {
        show: true,
        formatter: label,
        fontSize: 11,
        color: '#ef4444',
        backgroundColor: '#fee2e2',
        padding: [3, 6],
        borderRadius: 4,
        borderColor: '#fecaca',
        borderWidth: 1
      }
    }
  })
}

// 构建 ECharts 配置项
const buildOption = () => {
  const nodes = buildNodes()
  const edges = buildEdges()
  const forbiddenEdges = buildForbiddenEdges()

  return {
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        if (params.dataType === 'node') {
          return params.data.tooltip ? params.data.tooltip.formatter() : params.data.name
        }
        if (params.dataType === 'edge') {
          const data = params.data
          return `${data.source} → ${data.target}<br/>${
            data.label ? data.label.formatter : ''
          }`
        }
        return params.name
      },
      backgroundColor: 'rgba(255, 255, 255, 0.96)',
      borderColor: '#e2e8f0',
      borderWidth: 1,
      textStyle: {
        color: '#0f172a',
        fontSize: 12
      },
      extraCssText: 'box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08); border-radius: 8px;'
    },
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        label: { show: true },
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: [0, 10],
        force: {
          repulsion: 320,
          edgeLength: [120, 200],
          gravity: 0.1
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 4 }
        },
        data: nodes,
        links: [...edges, ...forbiddenEdges]
      }
    ]
  }
}

// 渲染图表
const renderChart = () => {
  if (!chartRef.value) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }
  chartInstance.setOption(buildOption(), true)
}

// 窗口大小变化时自适应
const handleResize = () => {
  if (chartInstance) {
    chartInstance.resize()
  }
}

onMounted(async () => {
  await nextTick()
  renderChart()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})

watch(
  () => [props.states, props.transitions, props.forbiddenTransitions, props.coverageData],
  () => {
    nextTick(() => renderChart())
  },
  { immediate: true, deep: true }
)
</script>

<style scoped>
.state-machine-viewer {
  width: 100%;
  position: relative;
}

.state-machine-chart {
  width: 100%;
  height: 400px;
}

.state-machine-empty {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 400px;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

/* 图例 */
.chart-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 16px;
  padding: 10px 14px;
  margin-top: 8px;
  background: #f8fafc;
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}

.legend-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid;
  background: #fff;

  &.dot-initial { border-color: #10b981; }
  &.dot-final { border-color: #ef4444; }
  &.dot-normal { border-color: #4f46e5; }
}

.legend-line {
  display: inline-block;
  width: 18px;
  height: 0;
  border-top: 2px solid;

  &.line-covered { border-color: #10b981; }
  &.line-uncovered { border-color: #ef4444; border-top-style: dashed; }
  &.line-forbidden { border-color: #ef4444; border-top-style: dashed; }
}
</style>
