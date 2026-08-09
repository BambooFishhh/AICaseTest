<template>
  <div class="state-machine-overview" v-loading="loading">
    <div class="page-header">
      <h2>状态机覆盖图</h2>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <el-select
      v-model="selectedSmId"
      placeholder="选择状态机"
      style="width: 300px; margin-bottom: 16px"
      @change="onSmChange"
    >
      <el-option
        v-for="sm in stateMachines"
        :key="sm.id"
        :label="sm.name"
        :value="sm.id"
      />
    </el-select>

    <el-card v-if="selectedSm" class="graph-card">
      <template #header>
        <span>{{ selectedSm.name }} — 覆盖图</span>
        <el-tag
          v-if="currentCoverage"
          :type="currentCoverage.rate >= 0.8 ? 'success' : (currentCoverage.rate >= 0.5 ? 'warning' : 'danger')"
          size="small"
          style="margin-left: 8px"
        >
          覆盖率 {{ Math.round(currentCoverage.rate * 100) }}%
        </el-tag>
      </template>

      <StateMachineViewer
        :states="parsedStates"
        :transitions="parsedTransitions"
        :forbidden-transitions="parsedForbidden"
        :coverage-data="coverageData"
      />

      <el-row :gutter="16" v-if="currentCoverage" class="coverage-summary">
        <el-col :span="8">
          <div class="summary-card">
            <div class="summary-label">总转换数</div>
            <div class="summary-value">{{ currentCoverage.total }}</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="summary-card">
            <div class="summary-label">已覆盖</div>
            <div class="summary-value covered">{{ currentCoverage.covered }}</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="summary-card">
            <div class="summary-label">未覆盖</div>
            <div class="summary-value uncovered">{{ currentCoverage.uncovered }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-empty v-if="!loading && stateMachines.length === 0" description="暂无状态机数据" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCoverageMatrix } from '@/api/coverage'
import { getStateMachines } from '@/api/analysis'
import StateMachineViewer from '@/components/StateMachineViewer.vue'

const route = useRoute()
const projectId = computed(() => route.params.id)

const loading = ref(false)
const stateMachines = ref([])
const selectedSmId = ref('')
const coverageMatrix = ref(null)

const selectedSm = computed(() =>
  stateMachines.value.find((s) => s.id === selectedSmId.value) || null
)

const parsedStates = computed(() => {
  if (!selectedSm.value) return []
  try {
    return JSON.parse(selectedSm.value.states || '[]')
  } catch {
    return []
  }
})

const parsedTransitions = computed(() => {
  if (!selectedSm.value) return []
  try {
    return JSON.parse(selectedSm.value.transitions || '[]')
  } catch {
    return []
  }
})

const parsedForbidden = computed(() => {
  if (!selectedSm.value) return []
  try {
    return JSON.parse(selectedSm.value.forbiddenTransitions || '[]')
  } catch {
    return []
  }
})

const coverageData = computed(() => {
  if (!coverageMatrix.value || !selectedSmId.value) return null
  const sm = coverageMatrix.value.stateMachines.find(
    (s) => s.id === selectedSmId.value
  )
  if (!sm) return null
  return {
    stateMachines: [sm],
    summary: coverageMatrix.value.summary
  }
})

const currentCoverage = computed(() => {
  if (!coverageData.value) return null
  const sm = coverageData.value.stateMachines[0]
  if (!sm) return null
  const transitions = sm.transitions || []
  const covered = transitions.filter((t) => t.covered).length
  const total = transitions.length
  return {
    total,
    covered,
    uncovered: total - covered,
    rate: total === 0 ? 0 : covered / total
  }
})

function onSmChange() {
  // coverage data is already loaded for all SMs
}

async function loadData() {
  loading.value = true
  try {
    const [smRes, covRes] = await Promise.all([
      getStateMachines(projectId.value),
      getCoverageMatrix(projectId.value)
    ])
    stateMachines.value = smRes.data || []
    coverageMatrix.value = covRes.data
    if (stateMachines.value.length > 0) {
      selectedSmId.value = stateMachines.value[0].id
    }
  } catch {
    ElMessage.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.state-machine-overview {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.graph-card {
  margin-bottom: 16px;
}
.coverage-summary {
  margin-top: 16px;
}
.summary-card {
  text-align: center;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
}
.summary-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 4px;
}
.summary-value {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}
.summary-value.covered {
  color: #67c23a;
}
.summary-value.uncovered {
  color: #f56c6c;
}
</style>
