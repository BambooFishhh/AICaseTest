<template>
  <el-collapse v-model="expanded" class="coverage-matrix-collapse">
    <el-collapse-item name="matrix">
      <template #title>
        <div class="matrix-head">
          <div class="matrix-head-text">
            <h2 class="matrix-title">覆盖率矩阵</h2>
            <p class="matrix-desc">计划覆盖与执行验证（v8.3 分母 = 已确认本期范围：目标接口 + 本期转换；历史转换仅展示不参与统计）</p>
          </div>
          <div v-if="matrix" class="matrix-summary">
            <div class="summary-row">
              <span class="summary-label">计划覆盖</span>
              <span class="summary-text">{{ plannedCovered }} / {{ matrix.summary.totalTransitions }}</span>
              <el-progress
                :percentage="plannedRatePct"
                :stroke-width="8"
                :color="rateColor"
                class="summary-progress"
              />
            </div>
            <div class="summary-row">
              <span class="summary-label">执行验证</span>
              <span class="summary-text summary-text-exec">{{ executedCovered }} / {{ matrix.summary.totalTransitions }}</span>
              <el-progress
                :percentage="executedRatePct"
                :stroke-width="8"
                :color="executedRateColor"
                class="summary-progress"
              />
            </div>
          </div>
        </div>
      </template>

      <el-empty v-if="allTransitions.length === 0" description="暂无状态机数据" />

      <el-table
        v-else
        :data="allTransitions"
        stripe
        size="small"
        :row-class-name="rowClassName"
      >
        <el-table-column prop="smName" label="状态机" width="140" />
        <el-table-column prop="from" label="From" width="110">
          <template #default="{ row }">
            <span class="state-name">{{ row.from }}</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="40" align="center">
          <template #default>
            <el-icon class="arrow-icon"><Right /></el-icon>
          </template>
        </el-table-column>
        <el-table-column prop="to" label="To" width="110">
          <template #default="{ row }">
            <span class="state-name">{{ row.to }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="trigger" label="Trigger" min-width="130" show-overflow-tooltip />
        <el-table-column label="范围" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.inScope === false" type="info" size="small" effect="plain">历史</el-tag>
            <el-tag v-else type="primary" size="small" effect="plain">本期</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计划覆盖" width="95" align="center">
          <template #default="{ row }">
            <span v-if="row.inScope === false" class="text-muted">—</span>
            <span
              v-else
              class="coverage-pill"
              :class="plannedOf(row) ? 'is-planned' : 'is-uncovered'"
            >
              <i class="pill-dot"></i>
              {{ plannedOf(row) ? '已规划' : '未覆盖' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="执行验证" width="95" align="center">
          <template #default="{ row }">
            <span v-if="row.inScope === false" class="text-muted">—</span>
            <span
              v-else
              class="coverage-pill"
              :class="executedOf(row) ? 'is-covered' : (plannedOf(row) ? 'is-planned' : 'is-uncovered')"
            >
              <i class="pill-dot"></i>
              {{ executedOf(row) ? '已验证' : (plannedOf(row) ? '待执行' : '未覆盖') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="关联用例" width="150" align="center">
          <template #default="{ row }">
            <span class="case-links">
              <el-button
                v-if="plannedIdsOf(row).length > 0"
                type="primary"
                link
                size="small"
                @click="$emit('filter-by-ids', plannedIdsOf(row))"
              >
                计划 {{ plannedIdsOf(row).length }} 条
              </el-button>
              <el-button
                v-if="executedIdsOf(row).length > 0"
                type="success"
                link
                size="small"
                @click="$emit('filter-by-ids', executedIdsOf(row))"
              >
                执行 {{ executedIdsOf(row).length }} 条
              </el-button>
              <span v-if="plannedIdsOf(row).length === 0 && executedIdsOf(row).length === 0" class="text-muted">—</span>
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup>
/**
 * 覆盖率矩阵组件
 * v7.8(R7): 计划覆盖（coverageRefs 声明，生成时计划）与执行验证（isExecuted 用例实际跑过）
 * 双栏展示——用户不再把"计划覆盖 80%"当"验证过 80%"。
 * 兼容旧后端数据：planned/executed 缺失时回退 covered 单栏口径。
 */
import { computed, ref } from 'vue'
import { Right } from '@element-plus/icons-vue'

const props = defineProps({
  matrix: { type: Object, default: null },
  // v5.13: 默认折叠，用户可按需展开
  defaultExpanded: { type: Boolean, default: false }
})

defineEmits(['filter-by-ids'])

const expanded = ref(props.defaultExpanded ? ['matrix'] : [])

// 展平所有状态机的转换为一维数组
const allTransitions = computed(() => {
  if (!props.matrix || !props.matrix.stateMachines) return []
  const result = []
  for (const sm of props.matrix.stateMachines) {
    for (const tran of (sm.transitions || [])) {
      result.push({
        ...tran,
        smName: sm.name,
        smId: sm.id
      })
    }
  }
  return result
})

// v7.8(R7): planned/executed 字段缺省时回退旧口径（covered = refs 计划 ∪ 已执行 smRef 兜底）
const plannedOf = (row) => row.planned ?? row.covered ?? false
const executedOf = (row) => row.executed ?? false
const plannedIdsOf = (row) => row.plannedCaseIds ?? row.testCaseIds ?? []
const executedIdsOf = (row) => row.executedCaseIds ?? []

const plannedCovered = computed(() =>
  props.matrix?.summary?.plannedCoveredTransitions ?? props.matrix?.summary?.coveredTransitions ?? 0
)
const executedCovered = computed(() => props.matrix?.summary?.executedCoveredTransitions ?? 0)

const plannedRatePct = computed(() => {
  const rate = props.matrix?.summary?.plannedRate ?? props.matrix?.summary?.rate ?? 0
  return Math.round(rate * 100)
})
const executedRatePct = computed(() => {
  const rate = props.matrix?.summary?.executedRate ?? 0
  return Math.round(rate * 100)
})

// 行样式三态：已执行（绿）/ 仅计划（黄）/ 未覆盖（红）
const rowClassName = ({ row }) => {
  if (executedOf(row)) return 'row-executed'
  return plannedOf(row) ? 'row-planned-only' : 'row-uncovered'
}

// 进度条颜色：计划覆盖绿色，执行验证蓝色（区分两栏语义）
const rateColor = computed(() => '#10b981')
const executedRateColor = computed(() => '#6366f1')
</script>

<style scoped>
.coverage-matrix-collapse {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
  margin-top: var(--space-md);
}

.coverage-matrix-collapse :deep(.el-collapse-item__header) {
  padding: 16px 20px;
  height: auto;
  border-bottom: 1px solid var(--card-border);
  background: transparent;
}

.coverage-matrix-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}

.coverage-matrix-collapse :deep(.el-collapse-item__content) {
  padding: 4px 20px 20px;
}

.matrix-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 1;
  min-width: 0;
  margin-bottom: 0;
  gap: var(--space-md);
  flex-wrap: wrap;
}

.matrix-head-text {
  display: flex;
  flex-direction: column;
}

.matrix-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.matrix-desc {
  font-size: 13px;
  color: var(--text-tertiary);
  margin-top: 2px;
}

.matrix-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 280px;
}

.summary-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.summary-label {
  font-size: 12px;
  color: var(--text-tertiary);
  white-space: nowrap;
  width: 48px;
}

.summary-text {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  font-weight: 600;
  color: var(--brand-primary);
  white-space: nowrap;
}

.summary-text-exec {
  color: var(--color-info);
}

.summary-progress {
  flex: 1;
  min-width: 120px;
}

.state-name {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--text-secondary);
}

.arrow-icon {
  color: var(--brand-primary);
  font-weight: bold;
}

.coverage-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 600;

  .pill-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  &.is-covered {
    color: var(--color-success);
    background: var(--color-success-bg);
  }

  &.is-planned {
    color: var(--color-warning);
    background: var(--color-warning-bg);
  }

  &.is-uncovered {
    color: var(--color-danger);
    background: var(--color-danger-bg);
  }
}

.case-links {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.text-muted {
  color: var(--text-tertiary);
}

:deep(.row-planned-only) {
  background-color: var(--color-warning-bg) !important;
}

:deep(.row-uncovered) {
  background-color: var(--color-danger-bg) !important;
}

/* 表格圆角 */
:deep(.el-table) {
  border-radius: var(--radius-md);
  overflow: hidden;
}
</style>
