<template>
  <el-drawer
    :model-value="visible"
    title="历史版本"
    direction="rtl"
    size="50%"
    @update:model-value="$emit('update:visible', $event)"
  >
    <div v-loading="loading">
      <el-empty v-if="!versions.length && !loading" description="暂无历史版本" />
      <div v-for="v in versions" :key="v.id" class="version-item">
        <div class="version-head">
          <el-tag :type="actionTagType(v.action)" size="small">
            v{{ v.versionNo }} · {{ actionText(v.action) }}
          </el-tag>
          <span class="version-time">{{ formatTime(v.createdAt) }}</span>
        </div>
        <div class="version-actions">
          <el-button link @click="viewVersion(v)">查看 / 对比</el-button>
          <el-button link type="warning" @click="confirmRollback(v)">回滚到此版本</el-button>
        </div>
      </div>

      <el-dialog v-model="detailVisible" title="版本详情与对比" width="720px" append-to-body>
        <div v-if="detail">
          <el-alert
            v-if="changedFields.length"
            :title="`与当前版本有 ${changedFields.length} 处差异：${changedFields.join('、')}`"
            type="warning"
            :closable="false"
            show-icon
            class="diff-alert"
          />
          <el-alert
            v-else
            title="与当前版本完全一致"
            type="success"
            :closable="false"
            show-icon
            class="diff-alert"
          />
          <el-descriptions :column="1" border>
            <el-descriptions-item label="标题">{{ detail.title || '-' }}</el-descriptions-item>
            <el-descriptions-item label="模块">{{ detail.module || '-' }}</el-descriptions-item>
            <el-descriptions-item label="类型">{{ detail.type || '-' }}</el-descriptions-item>
            <el-descriptions-item label="优先级">{{ detail.priority || '-' }}</el-descriptions-item>
            <el-descriptions-item label="评审状态">{{ detail.reviewStatus || '-' }}</el-descriptions-item>
            <el-descriptions-item label="步骤数">{{ (detail.steps || []).length }}</el-descriptions-item>
            <el-descriptions-item label="结构化步骤数">{{ (detail.structuredSteps || []).length }}</el-descriptions-item>
            <el-descriptions-item label="质量评分">{{ detail.qualityScore ?? '-' }}</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-dialog>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listTestCaseVersions,
  getTestCaseVersion,
  rollbackTestCaseVersion
} from '@/api/testcase'

const props = defineProps({
  visible: Boolean,
  projectId: String,
  testcaseId: String,
  currentTestCase: Object
})
const emit = defineEmits(['update:visible', 'rollback'])

const loading = ref(false)
const versions = ref([])
const detailVisible = ref(false)
const detail = ref(null)

watch(
  () => [props.visible, props.testcaseId],
  ([show]) => {
    if (show && props.testcaseId) loadVersions()
  }
)

async function loadVersions() {
  loading.value = true
  try {
    const res = await listTestCaseVersions(props.projectId, props.testcaseId)
    versions.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function viewVersion(v) {
  try {
    const res = await getTestCaseVersion(props.projectId, props.testcaseId, v.id)
    detail.value = res.data?.snapshot || {}
    detailVisible.value = true
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

// v1.9: 字段级 diff（对比快照与当前用例）
const changedFields = computed(() => {
  if (!detail.value || !props.currentTestCase) return []
  const cur = props.currentTestCase
  const old = detail.value
  const fields = [
    ['标题', 'title'],
    ['模块', 'module'],
    ['类型', 'type'],
    ['优先级', 'priority'],
    ['评审状态', 'reviewStatus'],
    ['步骤', 'steps'],
    ['结构化步骤', 'structuredSteps']
  ]
  const changed = []
  for (const [label, key] of fields) {
    if (!isEqual(cur[key], old[key])) changed.push(label)
  }
  return changed
})

function isEqual(a, b) {
  return JSON.stringify(a) === JSON.stringify(b)
}

async function confirmRollback(v) {
  try {
    await ElMessageBox.confirm(
      `确定回滚到 v${v.versionNo} 吗？当前内容会先备份为版本（可再次回滚撤销）。`,
      '确认回滚',
      { confirmButtonText: '确定回滚', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await rollbackTestCaseVersion(props.projectId, props.testcaseId, v.id)
    ElMessage.success('已回滚到 v' + v.versionNo)
    detailVisible.value = false
    emit('rollback')
    await loadVersions()
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

function actionTagType(action) {
  return { edit: '', rollback: 'warning' }[action] || 'info'
}
function actionText(action) {
  return { edit: '编辑前', rollback: '回滚前' }[action] || action
}
function formatTime(t) {
  return t ? new Date(t).toLocaleString('zh-CN') : ''
}
</script>

<style scoped>
.version-item {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 10px;
}
.version-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.version-time {
  font-size: 12px;
  color: #909399;
}
.version-actions {
  display: flex;
  gap: 12px;
}
.diff-alert {
  margin-bottom: 12px;
}
</style>
