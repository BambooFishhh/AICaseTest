<template>
  <div class="state-machine-viewer">
    <div ref="chartRef" class="state-machine-chart"></div>
    <div v-if="!hasData" class="state-machine-empty">
      <el-empty description="暂无状态机数据" />
    </div>
  </div>
</template>

<script setup>
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
  }
})

const chartRef = ref(null)
let chartInstance = null

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
      return '#67C23A' // 绿色 - 初始状态
    case 'final':
      return '#F56C6C' // 红色 - 终止状态
    case 'normal':
    default:
      return '#409EFF' // 蓝色 - 普通状态
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
      symbolSize: 60,
      itemStyle: {
        color: '#ffffff',
        borderColor: color,
        borderWidth: 3
      },
      label: {
        show: true,
        formatter: typeLabel + state.name,
        fontSize: 12,
        color: '#303133'
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

// 构建正常转移边
const buildEdges = () => {
  if (!props.transitions || props.transitions.length === 0) return []
  return props.transitions.map((tran) => {
    const label = [tran.trigger, tran.condition]
      .filter(Boolean)
      .join(' / ')
    return {
      source: tran.from,
      target: tran.to,
      symbol: ['none', 'arrow'],
      symbolSize: [0, 10],
      lineStyle: {
        color: '#909399',
        width: 2,
        curveness: 0.2
      },
      label: {
        show: true,
        formatter: label,
        fontSize: 11,
        color: '#606266',
        backgroundColor: '#ffffff',
        padding: [2, 4],
        borderRadius: 3
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
        color: '#F56C6C',
        width: 2,
        type: 'dashed',
        curveness: 0.3
      },
      label: {
        show: true,
        formatter: label,
        fontSize: 11,
        color: '#F56C6C',
        backgroundColor: '#fef0f0',
        padding: [2, 4],
        borderRadius: 3
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
      }
    },
    legend: [
      {
        data: ['初始状态', '终止状态', '普通状态', '正常转移', '禁止转移'],
        bottom: 0,
        textStyle: { color: '#606266' }
      }
    ],
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
          repulsion: 300,
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
  () => [props.states, props.transitions, props.forbiddenTransitions],
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
</style>
