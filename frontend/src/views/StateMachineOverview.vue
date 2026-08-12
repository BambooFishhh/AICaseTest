<template>
  <div class="state-machine-overview page-container" v-loading="loading">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <div class="title-block">
          <h1 class="page-title">状态机覆盖图</h1>
          <p class="page-subtitle">查看状态机转换路径的测试覆盖情况</p>
        </div>
      </div>
    </header>

    <!-- 空状态 -->
    <section v-if="!loading && stateMachines.length === 0" class="empty-section">
      <el-empty description="暂无状态机数据，请先运行代码分析" :image-size="120">
        <el-button type="primary" :icon="DataAnalysis" @click="goBack">返回项目详情</el-button>
      </el-empty>
    </section>

    <template v-if="stateMachines.length > 0">
      <!-- 选择器 -->
      <section class="selector-section">
        <div class="selector-label">
          <el-icon :size="16"><Share /></el-icon>
          <span>选择状态机</span>
        </div>
        <el-select
          v-model="selectedSmId"
          placeholder="请选择状态机"
          size="large"
          class="sm-select"
          @change="onSmChange"
        >
          <el-option
            v-for="sm in stateMachines"
            :key="sm.id"
            :label="sm.name"
            :value="sm.id"
          />
        </el-select>
      </section>

      <!-- 覆盖图 -->
      <section v-if="selectedSm" class="graph-section">
        <div class="graph-head">
          <div class="graph-head-text">
            <h2 class="section-title">{{ selectedSm.name }}</h2>
            <p class="section-desc">状态机转换路径覆盖图</p>
          </div>
          <div v-if="currentCoverage" class="coverage-badge" :class="coverageLevel">
            <el-icon :size="16">
              <CircleCheckFilled v-if="currentCoverage.rate >= 0.8" />
              <WarningFilled v-else-if="currentCoverage.rate >= 0.5" />
              <CircleCloseFilled v-else />
            </el-icon>
            <span>覆盖率 {{ Math.round(currentCoverage.rate * 100) }}%</span>
          </div>
        </div>

        <div class="graph-body">
          <StateMachineViewer
            :states="parsedStates"
            :transitions="parsedTransitions"
            :forbidden-transitions="parsedForbidden"
            :coverage-data="coverageData"
          />
        </div>

        <!-- 覆盖统计 -->
        <div v-if="currentCoverage" class="coverage-grid">
          <div class="coverage-card coverage-total">
            <div class="coverage-icon"><el-icon :size="18"><Share /></el-icon></div>
            <div class="coverage-body">
              <div class="coverage-value">{{ currentCoverage.total }}</div>
              <div class="coverage-label">总转换数</div>
            </div>
          </div>
          <div class="coverage-card coverage-covered">
            <div class="coverage-icon"><el-icon :size="18"><CircleCheck /></el-icon></div>
            <div class="coverage-body">
              <div class="coverage-value">{{ currentCoverage.covered }}</div>
              <div class="coverage-label">已覆盖</div>
            </div>
          </div>
          <div class="coverage-card coverage-uncovered">
            <div class="coverage-icon"><el-icon :size="18"><CircleClose /></el-icon></div>
            <div class="coverage-body">
              <div class="coverage-value">{{ currentCoverage.uncovered }}</div>
              <div class="coverage-label">未覆盖</div>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
/**
 * 状态机覆盖图页
 * 展示项目的状态机及其测试覆盖情况：
 * - 选择状态机
 * - 可视化图（带覆盖标记）
 * - 统计（总转换数、已覆盖、未覆盖）
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, Share, CircleCheck, CircleClose,
  CircleCheckFilled, CircleCloseFilled, WarningFilled, DataAnalysis
} from '@element-plus/icons-vue'
import { getCoverageMatrix } from '@/api/coverage'
import { getStateMachines } from '@/api/analysis'
import StateMachineViewer from '@/components/StateMachineViewer.vue'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.id)

const loading = ref(false)
const stateMachines = ref([])
const selectedSmId = ref('')
const coverageMatrix = ref(null)

// 当前选中的状态机
const selectedSm = computed(() =>
  stateMachines.value.find((s) => s.id === selectedSmId.value) || null
)

// 解析状态机字段
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

// 覆盖数据（仅包含当前选中的状态机）
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

// 当前覆盖率统计
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

// 覆盖率等级
const coverageLevel = computed(() => {
  if (!currentCoverage.value) return ''
  const r = currentCoverage.value.rate
  if (r >= 0.8) return 'level-high'
  if (r >= 0.5) return 'level-mid'
  return 'level-low'
})

function onSmChange() {
  // coverage 数据已一次性加载，切换无需重新请求
}

// 加载状态机与覆盖率数据
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

function goBack() {
  router.push(`/projects/${projectId.value}`)
}

onMounted(loadData)
</script>

<style scoped>
/* ===== 页头 ===== */
.page-header-main {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.title-block {
  display: flex;
  flex-direction: column;
}

/* ===== 选择器 ===== */
.selector-section {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
  padding: 16px 20px;
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
}

.selector-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);

  .el-icon {
    color: var(--brand-primary);
  }
}

.sm-select {
  width: 320px;
  max-width: 100%;
}

/* ===== 图区 ===== */
.graph-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
  overflow: hidden;
}

.graph-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  background: linear-gradient(to right, var(--el-color-primary-light-9), transparent);
  border-bottom: 1px solid var(--card-border-light);
  flex-wrap: wrap;
  gap: var(--space-md);
}

.graph-head-text {
  display: flex;
  flex-direction: column;
}

.section-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.section-desc {
  font-size: 13px;
  color: var(--text-tertiary);
  margin-top: 2px;
}

/* 覆盖率徽章 */
.coverage-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: var(--radius-full);
  font-size: 13px;
  font-weight: 700;

  &.level-high {
    color: var(--color-success);
    background: var(--color-success-bg);
  }

  &.level-mid {
    color: var(--color-warning);
    background: var(--color-warning-bg);
  }

  &.level-low {
    color: var(--color-danger);
    background: var(--color-danger-bg);
  }
}

.graph-body {
  padding: 20px;
  background: #fafbfc;
}

/* ===== 覆盖统计 ===== */
.coverage-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-md);
  padding: 20px;
  border-top: 1px solid var(--card-border-light);
  background: var(--bg-surface);
}

.coverage-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  border-radius: var(--radius-md);
  background: #f8fafc;
  border: 1px solid var(--card-border-light);
  transition: all var(--transition-normal);

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--shadow-sm);
  }

  .coverage-icon {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: var(--radius-md);
    color: #fff;
  }

  .coverage-value {
    font-size: 22px;
    font-weight: 700;
    line-height: 1.1;
    color: var(--text-primary);
  }

  .coverage-label {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 2px;
  }

  &.coverage-total .coverage-icon { background: var(--brand-primary); }
  &.coverage-covered .coverage-icon { background: var(--color-success); }
  &.coverage-uncovered .coverage-icon { background: var(--color-danger); }
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .selector-section {
    flex-direction: column;
    align-items: stretch;
  }

  .sm-select {
    width: 100%;
  }

  .coverage-grid {
    grid-template-columns: 1fr;
  }

  .graph-head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
