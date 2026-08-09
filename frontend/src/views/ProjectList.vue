<template>
  <div class="project-list">
    <div class="page-header">
      <h2>项目列表</h2>
      <el-button type="primary" @click="goCreate">创建项目</el-button>
    </div>

    <div v-loading="loading">
      <el-empty
        v-if="!loading && projects.length === 0"
        description="暂无项目，点击右上角创建"
      />

      <el-row v-else :gutter="20">
        <el-col
          v-for="project in projects"
          :key="project.id"
          :xs="24"
          :sm="12"
          :md="8"
        >
          <el-card class="project-card" shadow="hover" @click="goDetail(project.id)">
            <div class="card-header">
              <span class="project-name">{{ project.name }}</span>
              <el-tag :type="statusTagType(project.status)" size="small">
                {{ statusText(project.status) }}
              </el-tag>
            </div>
            <div class="project-path">{{ project.sourcePath }}</div>
            <div class="card-footer">
              <span class="project-time">{{ formatDate(project.createdAt) }}</span>
              <span @click.stop>
                <el-popconfirm
                  title="确定删除该项目吗？"
                  @confirm="handleDelete(project.id)"
                >
                  <template #reference>
                    <el-button type="danger" size="small" text>删除</el-button>
                  </template>
                </el-popconfirm>
              </span>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listProjects, deleteProject } from '@/api/project'

const router = useRouter()
const projects = ref([])
const loading = ref(false)

const statusTypeMap = {
  created: 'info',
  analyzing: 'warning',
  analyzed: 'success',
  generating: 'warning',
  completed: 'success',
  failed: 'danger'
}

const statusTextMap = {
  created: '已创建',
  analyzing: '分析中...',
  analyzed: '已分析',
  generating: '生成中...',
  completed: '已完成',
  failed: '失败'
}

function statusTagType(status) {
  return statusTypeMap[status] || 'info'
}

function statusText(status) {
  return statusTextMap[status] || status
}

function formatDate(val) {
  if (!val) return '-'
  const d = new Date(val)
  if (isNaN(d.getTime())) return val
  return d.toLocaleString('zh-CN', { hour12: false })
}

async function loadProjects() {
  loading.value = true
  try {
    const res = await listProjects()
    projects.value = res.data || []
  } finally {
    loading.value = false
  }
}

function goCreate() {
  router.push('/projects/create')
}

function goDetail(id) {
  router.push(`/projects/${id}`)
}

async function handleDelete(id) {
  await deleteProject(id)
  ElMessage.success('删除成功')
  loadProjects()
}

onMounted(loadProjects)
</script>

<style scoped>
.project-list {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header h2 {
  margin: 0;
}
.project-card {
  margin-bottom: 20px;
  cursor: pointer;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  gap: 8px;
}
.project-name {
  font-weight: bold;
  font-size: 16px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.project-path {
  color: #999;
  font-size: 12px;
  margin-bottom: 10px;
  word-break: break-all;
  min-height: 32px;
}
.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.project-time {
  color: #666;
  font-size: 12px;
}
</style>
