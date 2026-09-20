<template>
  <div class="evidence-conflicts page-container">
    <header class="page-header">
      <div class="page-header-main">
        <h1 class="page-title">证据冲突裁决</h1>
        <p class="page-subtitle">
          PRD 与代码两条证据链的分歧点。权威归属由判定矩阵按「需求状态 × 冲突方向 × 新鲜度」给出，
          人工裁决结果会被后续生成自动套用，不再重复打扰。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="filter-section">
      <el-radio-group v-model="verdictFilter" @change="load">
        <el-radio-button value="pending">待裁决</el-radio-button>
        <el-radio-button value="">全部</el-radio-button>
      </el-radio-group>
      <span class="filter-count">共 {{ total }} 条，其中待裁决 {{ pending }} 条</span>
    </section>

    <el-table v-loading="loading" :data="conflicts" empty-text="暂无冲突记录">
      <el-table-column label="维度" prop="dimension" width="130" />
      <el-table-column label="需求锚点" prop="anchor" min-width="180" show-overflow-tooltip />
      <el-table-column label="冲突方向" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="directionTag(row.direction)">
            {{ directionText(row.direction) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="需求状态" width="110">
        <template #default="{ row }">{{ stateText(row.requirementState) }}</template>
      </el-table-column>
      <el-table-column label="当前权威" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="authorityTag(row.authority)">
            {{ authorityText(row.authority) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="判定说明" prop="reason" min-width="300" show-overflow-tooltip />
      <el-table-column label="裁决状态" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="row.verdict === 'pending' ? 'warning' : 'success'">
            {{ verdictText(row.verdict) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="decide(row, 'prd_authoritative')">以 PRD 为准</el-button>
          <el-button size="small" @click="decide(row, 'code_authoritative')">以代码为准</el-button>
          <el-dropdown @command="(cmd) => decide(row, cmd)">
            <el-button size="small" :icon="ArrowDown" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="deprecated">需求已废弃</el-dropdown-item>
                <el-dropdown-item command="pending">撤回裁决</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, ArrowDown } from '@element-plus/icons-vue'
import { listEvidenceConflicts, submitEvidenceVerdict } from '@/api/evidence'

const route = useRoute()
const projectId = computed(() => route.params.id)

const conflicts = ref([])
const total = ref(0)
const pending = ref(0)
const verdictFilter = ref('pending')
const loading = ref(false)

const DIRECTION_TEXT = {
  PRD_ONLY: 'PRD 有·代码无',
  CODE_ONLY: '代码有·PRD 无',
  MIXED: '两侧均有差异'
}

const AUTHORITY_TEXT = {
  prd: '以 PRD 为准',
  code: '以代码为准',
  human: '需人工裁决',
  skip: '不生成用例',
  deferred: '暂缓',
  deprecated: '需求已废弃'
}

const STATE_TEXT = {
  NEW: '新增',
  MODIFIED: '变更中',
  STABLE: '存量'
}

const VERDICT_TEXT = {
  pending: '待裁决',
  prd_authoritative: '已裁决·PRD',
  code_authoritative: '已裁决·代码',
  deprecated: '已裁决·废弃'
}

function directionText(v) {
  return DIRECTION_TEXT[v] || v || '-'
}

function authorityText(v) {
  return AUTHORITY_TEXT[v] || v || '-'
}

function stateText(v) {
  return STATE_TEXT[v] || v || '-'
}

function verdictText(v) {
  return VERDICT_TEXT[v] || v || '-'
}

function directionTag(v) {
  if (v === 'CODE_ONLY') return 'info'
  if (v === 'MIXED') return 'danger'
  return 'warning'
}

function authorityTag(v) {
  if (v === 'code' || v === 'deprecated' || v === 'skip' || v === 'deferred') return 'info'
  if (v === 'human') return 'warning'
  return 'success'
}

async function load() {
  loading.value = true
  try {
    // 待裁决视图只取「真正需要人」的：skip（无 PRD 依据不生成）/ deferred（暂缓）
    // 虽然同样未裁决，但不该占用人的注意力
    const res = await listEvidenceConflicts(
      projectId.value, verdictFilter.value, verdictFilter.value === 'pending')
    conflicts.value = (res && res.data && res.data.conflicts) || []
    total.value = (res && res.data && res.data.total) || 0
    pending.value = (res && res.data && res.data.pending) || 0
  } catch (e) {
    ElMessage.error('加载冲突清单失败')
  } finally {
    loading.value = false
  }
}

async function decide(row, verdict) {
  try {
    await submitEvidenceVerdict(projectId.value, row.conflictKey, verdict)
    ElMessage.success('裁决已保存，后续生成将自动套用')
    load()
  } catch (e) {
    ElMessage.error('裁决保存失败')
  }
}

onMounted(load)
</script>

<style scoped>
.evidence-conflicts .filter-section {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}
</style>
