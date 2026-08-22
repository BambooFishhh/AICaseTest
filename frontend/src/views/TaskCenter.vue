<template>
  <div class="task-center page-container">
    <div class="page-header">
      <h2>任务中心</h2>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-select v-model="filters.taskType" placeholder="任务类型" clearable style="width: 170px">
          <el-option label="分析" value="analysis" />
          <el-option label="生成" value="generation" />
          <el-option label="追加生成" value="append_generation" />
          <el-option label="执行" value="execution" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 170px">
          <el-option label="排队中" value="QUEUED" />
          <el-option label="运行中" value="RUNNING" />
          <el-option label="成功" value="SUCCEEDED" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已取消" value="CANCELLED" />
          <el-option label="待复核" value="NEEDS_REVIEW" />
          <el-option label="死信" value="DLQ" />
        </el-select>
        <el-input v-model="filters.projectId" placeholder="项目 ID" clearable style="width: 200px" />
        <el-button type="primary" :icon="Search" @click="loadTasks">查询</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="tasks" v-loading="loading" stripe>
        <el-table-column prop="id" label="任务 ID" min-width="110" show-overflow-tooltip />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">{{ taskTypeLabel(row.taskType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="phase" label="阶段" width="130" show-overflow-tooltip />
        <el-table-column prop="attempts" label="尝试" width="70" />
        <el-table-column label="降级" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.degraded" type="warning" size="small">是</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="projectId" label="项目 ID" min-width="110" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row.id)">详情</el-button>
            <el-button
              v-if="['FAILED', 'DLQ', 'NEEDS_REVIEW'].includes(row.status)"
              size="small"
              type="primary"
              :loading="retryingId === row.id"
              @click="handleRetry(row.id)"
            >重试</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-row">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadTasks"
          @current-change="loadTasks"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="任务详情" size="520px">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item label="任务 ID">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ taskTypeLabel(detail.taskType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
        <el-descriptions-item label="阶段">{{ detail.phase }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ detail.attempts }} / {{ detail.maxAttempts }}</el-descriptions-item>
        <el-descriptions-item label="降级">{{ detail.degraded ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="错误码">{{ detail.errorCode || '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误信息">{{ detail.errorMessage || '-' }}</el-descriptions-item>
        <el-descriptions-item label="租约归属">{{ detail.leaseOwner || '-' }}</el-descriptions-item>
        <el-descriptions-item label="租约过期">{{ detail.leaseExpireAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="项目 ID">{{ detail.projectId }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="detail && ['FAILED', 'DLQ', 'NEEDS_REVIEW'].includes(detail.status)"
          type="primary"
          :loading="retryingId === detail.id"
          @click="handleRetry(detail.id)"
        >重试任务</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listTasks, getTask, retryTask } from '@/api/task'

const tasks = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const retryingId = ref('')
const detailVisible = ref(false)
const detail = ref(null)
const filters = reactive({
  taskType: '',
  status: '',
  projectId: ''
})

const taskTypes = {
  analysis: '分析',
  generation: '生成',
  append_generation: '追加生成',
  execution: '执行'
}

function taskTypeLabel(type) {
  return taskTypes[type] || type || '-'
}

function statusType(status) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'DLQ') return 'danger'
  if (status === 'CANCELLED') return 'info'
  if (status === 'NEEDS_REVIEW') return 'warning'
  return 'primary'
}

async function loadTasks() {
  loading.value = true
  try {
    const params = {
      page: page.value - 1,
      size: pageSize.value
    }
    if (filters.taskType) params.taskType = filters.taskType
    if (filters.status) params.status = filters.status
    if (filters.projectId) params.projectId = filters.projectId
    const res = await listTasks(params)
    tasks.value = res.data.content || []
    total.value = res.data.totalElements || 0
  } finally {
    loading.value = false
  }
}

async function openDetail(id) {
  const res = await getTask(id)
  detail.value = res.data
  detailVisible.value = true
}

async function handleRetry(id) {
  retryingId.value = id
  try {
    const res = await retryTask(id)
    ElMessage.success(res.message || '任务已重试')
    await loadTasks()
    if (detail.value && detail.value.id === id) {
      await openDetail(id)
    }
  } finally {
    retryingId.value = ''
  }
}

onMounted(loadTasks)
</script>

<style scoped>
.page-header h2 {
  margin: 0 0 16px;
  font-size: 20px;
}

.filter-card {
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  gap: 12px;
  align-items: center;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
