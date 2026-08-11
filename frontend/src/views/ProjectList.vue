<template>
  <div class="project-list">
    <div class="page-header">
      <h2>项目列表</h2>
      <el-button type="primary" :icon="Plus" @click="goCreate">创建项目</el-button>
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
          <el-card class="project-card" shadow="never" @click="goDetail(project.id)">
            <div class="project-accent" :class="`accent-${project.status}`"></div>
            <div class="card-header">
              <div class="project-avatar" :class="`avatar-${project.status}`">
                <el-icon :size="20"><Folder /></el-icon>
              </div>
              <div class="project-info">
                <div class="project-name-row">
                  <span class="project-name">{{ project.name }}</span>
                  <el-tag
                    :type="statusTagType(project.status)"
                    size="small"
                    effect="light"
                    round
                  >
                    {{ statusText(project.status) }}
                  </el-tag>
                </div>
                <div class="project-path">
                  <el-icon :size="13"><FolderOpened /></el-icon>
                  <span class="path-text">{{ project.sourcePath }}</span>
                </div>
              </div>
            </div>
            <div class="card-footer">
              <span class="project-time">
                <el-icon :size="13"><Clock /></el-icon>
                {{ formatDate(project.createdAt) }}
              </span>
              <span @click.stop>
                <el-popconfirm
                  title="确定删除该项目吗？"
                  @confirm="handleDelete(project.id)"
                >
                  <template #reference>
                    <el-button type="danger" size="small" text :icon="Delete">删除</el-button>
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
import { Plus, Folder, FolderOpened, Clock, Delete } from '@element-plus/icons-vue'
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

<style scoped lang="scss">
.project-list {
  padding: 4px 0;
}

.project-card {
  margin-bottom: 20px;

  .project-accent {
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 4px;
    border-radius: 12px 0 0 12px;
  }

  .accent-created { background: linear-gradient(180deg, #c0c8d9, #98a2b8); }
  .accent-analyzing,
  .accent-generating { background: linear-gradient(180deg, #f7b55b, #e6a23c); }
  .accent-analyzed,
  .accent-completed { background: linear-gradient(180deg, #7ed67e, #67c23a); }
  .accent-failed { background: linear-gradient(180deg, #f78989, #f56c6c); }
}

.card-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;

  .project-avatar {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 42px;
    height: 42px;
    border-radius: 11px;
    color: #fff;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25);
  }

  .avatar-created { background: linear-gradient(135deg, #aeb6c8, #7c8698); }
  .avatar-analyzing,
  .avatar-generating { background: linear-gradient(135deg, #ffb85c, #e6a23c); }
  .avatar-analyzed,
  .avatar-completed { background: linear-gradient(135deg, #7ed67e, #58b24c); }
  .avatar-failed { background: linear-gradient(135deg, #f78989, #e84b4b); }
}

.project-info {
  flex: 1;
  min-width: 0;
}

.project-name-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;

  .project-name {
    font-weight: 700;
    font-size: 16px;
    color: var(--text-main);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.project-path {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--text-secondary);
  font-size: 12px;
  min-height: 20px;

  .path-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    word-break: break-all;
  }
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color-lighter);

  .project-time {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    color: var(--text-secondary);
    font-size: 12px;
  }
}
</style>
