<template>
  <el-card class="coverage-matrix">
    <template #header>
      <span>覆盖率矩阵</span>
      <el-tag v-if="matrix" type="info" size="small" style="margin-left: 8px">
        {{ matrix.summary.coveredTransitions }} / {{ matrix.summary.totalTransitions }}
        ({{ Math.round(matrix.summary.rate * 100) }}%)
      </el-tag>
    </template>

    <el-table
      v-if="allTransitions.length > 0"
      :data="allTransitions"
      border
      size="small"
      :row-class-name="rowClassName"
      style="width: 100%"
    >
      <el-table-column prop="smName" label="状态机" width="140" />
      <el-table-column prop="from" label="From" width="120" />
      <el-table-column prop="to" label="To" width="120" />
      <el-table-column prop="trigger" label="Trigger" width="120" />
      <el-table-column label="覆盖" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.covered ? 'success' : 'danger'" size="small">
            {{ row.covered ? '✓ 已覆盖' : '✗ 未覆盖' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="关联用例" width="100" align="center">
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
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else description="暂无状态机数据" />
  </el-card>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  matrix: { type: Object, default: null }
})

defineEmits(['filter-by-ids'])

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

const rowClassName = ({ row }) => {
  return row.covered ? '' : 'row-uncovered'
}
</script>

<style scoped>
.coverage-matrix {
  margin-top: 16px;
}
:deep(.row-uncovered) {
  background-color: #fef0f0;
}
</style>
