<template>
  <section class="coverage-matrix">
    <div class="matrix-head">
      <div class="matrix-head-text">
        <h2 class="matrix-title">覆盖率矩阵</h2>
        <p class="matrix-desc">展示状态机转换路径的测试覆盖情况</p>
      </div>
      <div v-if="matrix" class="matrix-summary">
        <span class="summary-text">
          {{ matrix.summary.coveredTransitions }} / {{ matrix.summary.totalTransitions }}
        </span>
        <el-progress
          :percentage="Math.round(matrix.summary.rate * 100)"
          :stroke-width="8"
          :color="rateColor"
          class="summary-progress"
        />
      </div>
    </div>

    <el-empty v-if="allTransitions.length === 0" description="暂无状态机数据" />

    <el-table
      v-else
      :data="allTransitions"
      stripe
      size="small"
      :row-class-name="rowClassName"
    >
      <el-table-column prop="smName" label="状态机" width="140" />
      <el-table-column prop="from" label="From" width="120">
        <template #default="{ row }">
          <span class="state-name">{{ row.from }}</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="40" align="center">
        <template #default>
          <el-icon class="arrow-icon"><Right /></el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="to" label="To" width="120">
        <template #default="{ row }">
          <span class="state-name">{{ row.to }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="trigger" label="Trigger" min-width="140" show-overflow-tooltip />
      <el-table-column label="覆盖" width="110" align="center">
        <template #default="{ row }">
          <span
            class="coverage-pill"
            :class="row.covered ? 'is-covered' : 'is-uncovered'"
          >
            <i class="pill-dot"></i>
            {{ row.covered ? '已覆盖' : '未覆盖' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="关联用例" width="110" align="center">
        <template #default="{ row }">
          <el-button
            v-if="row.testCaseIds.length > 0"
            type="primary"
            link
            size="small"
            @click="$emit('filter-by-ids', row.testCaseIds)"
          >
            {{ row.testCaseIds.length }} 条
          </el-button>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup>
/**
 * 覆盖率矩阵组件
 * 展示所有状态机的转换路径覆盖情况，
 * 支持点击"关联用例"按 ID 集合筛选用例。
 */
import { computed } from 'vue'
import { Right } from '@element-plus/icons-vue'

const props = defineProps({
  matrix: { type: Object, default: null }
})

defineEmits(['filter-by-ids'])

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

// 行样式：未覆盖行高亮
const rowClassName = ({ row }) => {
  return row.covered ? '' : 'row-uncovered'
}

// 进度条颜色根据覆盖率
const rateColor = computed(() => {
  return '#10b981'
})
</script>

<style scoped>
.coverage-matrix {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow-xs);
  margin-top: var(--space-md);
}

.matrix-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
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
  align-items: center;
  gap: 12px;
  min-width: 220px;
}

.summary-text {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 14px;
  font-weight: 600;
  color: var(--brand-primary);
  white-space: nowrap;
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

  &.is-uncovered {
    color: var(--color-danger);
    background: var(--color-danger-bg);
  }
}

.text-muted {
  color: var(--text-tertiary);
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
