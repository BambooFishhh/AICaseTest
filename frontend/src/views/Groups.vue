<template>
  <div class="groups-page page-container">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <h1 class="page-title">项目组</h1>
        <p class="page-subtitle">部门成员共享项目，组创建者指派操作权限</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建项目组</el-button>
      </div>
    </header>

    <!-- 列表 -->
    <section class="groups-section" v-loading="loading">
      <el-empty v-if="!loading && groups.length === 0" description="还没有项目组，点击右上角创建" :image-size="110" />
      <div v-else class="group-list">
        <div v-for="g in groups" :key="g.id" class="group-item">
          <div class="group-main">
            <div class="group-name">
              <el-icon :size="18"><Connection /></el-icon>
              <span>{{ g.name }}</span>
              <el-tag v-if="g.myRole === 'OWNER'" size="small" type="warning" effect="light">创建者</el-tag>
              <el-tag v-else-if="g.myRole === 'OPERATOR'" size="small" type="success" effect="light">操作成员</el-tag>
              <el-tag v-else size="small" type="info" effect="light">只读成员</el-tag>
            </div>
            <div class="group-desc">{{ g.description || '暂无描述' }}</div>
          </div>
          <div class="group-meta">
            <span class="member-count">{{ g.memberCount }} 名成员</span>
          </div>
          <div class="group-actions">
            <el-button link :icon="User" @click="openMembers(g)">成员管理</el-button>
            <template v-if="g.myRole === 'OWNER'">
              <el-button link :icon="EditPen" @click="openEdit(g)">编辑</el-button>
              <el-button link type="danger" :icon="Delete" @click="removeGroup(g)">删除</el-button>
            </template>
          </div>
        </div>
      </div>
    </section>

    <!-- 新建/编辑 -->
    <el-dialog v-model="editVisible" :title="editing ? '编辑项目组' : '新建项目组'" width="460px">
      <el-form label-width="80px">
        <el-form-item label="组名">
          <el-input v-model="editForm.name" placeholder="如：质量保障部" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGroup">保存</el-button>
      </template>
    </el-dialog>

    <!-- 成员管理 -->
    <el-dialog v-model="membersVisible" :title="`成员管理 - ${currentGroup?.name || ''}`" width="620px">
      <div v-if="isOwner" class="member-add">
        <el-select
          v-model="newMemberUserId"
          placeholder="搜索用户"
          filterable
          remote
          :remote-method="searchUsers"
          style="flex: 1"
        >
          <el-option
            v-for="u in userOptions"
            :key="u.id"
            :label="`${u.displayName || u.username}（${u.username}）`"
            :value="u.id"
          />
        </el-select>
        <el-select v-model="newMemberRole" style="width: 120px">
          <el-option label="只读" value="VIEWER" />
          <el-option label="操作" value="OPERATOR" />
        </el-select>
        <el-button type="primary" :icon="Plus" :loading="addingMember" @click="addMember">添加</el-button>
      </div>

      <el-empty v-if="members.length === 0" description="暂无成员" :image-size="80" />
      <div v-for="m in members" :key="m.userId" class="member-item">
        <div class="member-info">
          <span class="member-name">{{ m.displayName || m.username }}</span>
          <span class="member-user">@{{ m.username }}</span>
        </div>
        <div class="member-ops">
          <el-select
            v-if="isOwner"
            :model-value="m.role"
            size="small"
            style="width: 100px"
            @change="(r) => changeRole(m, r)"
          >
            <el-option label="只读" value="VIEWER" />
            <el-option label="操作" value="OPERATOR" />
          </el-select>
          <el-tag v-else :type="m.role === 'OPERATOR' ? 'success' : 'info'" size="small">
            {{ m.role === 'OPERATOR' ? '操作' : '只读' }}
          </el-tag>
          <el-button
            v-if="isOwner"
            link
            type="danger"
            :icon="Delete"
            @click="removeMember(m)"
          />
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Connection, User, EditPen, Delete } from '@element-plus/icons-vue'
import {
  listGroups, createGroup, updateGroup, deleteGroup,
  listGroupMembers, addGroupMember, updateGroupMemberRole, removeGroupMember
} from '@/api/group'
import { listUsers } from '@/api/user'

const loading = ref(false)
const groups = ref([])

// 新建/编辑
const editVisible = ref(false)
const editing = ref(null)
const editForm = ref({ name: '', description: '' })
const saving = ref(false)

// 成员
const membersVisible = ref(false)
const currentGroup = ref(null)
const members = ref([])
const isOwner = computed(() => currentGroup.value?.myRole === 'OWNER')
const newMemberUserId = ref('')
const newMemberRole = ref('VIEWER')
const userOptions = ref([])
const addingMember = ref(false)

async function loadGroups() {
  loading.value = true
  try {
    const res = await listGroups()
    groups.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  editForm.value = { name: '', description: '' }
  editVisible.value = true
}

function openEdit(g) {
  editing.value = g
  editForm.value = { name: g.name, description: g.description || '' }
  editVisible.value = true
}

async function saveGroup() {
  if (!editForm.value.name.trim()) {
    ElMessage.warning('请输入组名')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateGroup(editing.value.id, editForm.value)
      ElMessage.success('项目组已更新')
    } else {
      await createGroup(editForm.value)
      ElMessage.success('项目组已创建')
    }
    editVisible.value = false
    await loadGroups()
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    saving.value = false
  }
}

async function removeGroup(g) {
  try {
    await ElMessageBox.confirm(`确定删除项目组「${g.name}」吗？组内项目将保留但不再共享。`, '确认删除', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteGroup(g.id)
    ElMessage.success('项目组已删除')
    await loadGroups()
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

async function openMembers(g) {
  currentGroup.value = g
  membersVisible.value = true
  newMemberUserId.value = ''
  await loadMembers(g.id)
}

async function loadMembers(groupId) {
  try {
    const res = await listGroupMembers(groupId)
    members.value = res.data || []
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

async function searchUsers(keyword) {
  try {
    const res = await listUsers(keyword || '')
    userOptions.value = res.data || []
  } catch {
    userOptions.value = []
  }
}

async function addMember() {
  if (!newMemberUserId.value) {
    ElMessage.warning('请选择用户')
    return
  }
  addingMember.value = true
  try {
    await addGroupMember(currentGroup.value.id, {
      userId: newMemberUserId.value,
      role: newMemberRole.value
    })
    ElMessage.success('成员已添加')
    newMemberUserId.value = ''
    await loadMembers(currentGroup.value.id)
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    addingMember.value = false
  }
}

async function changeRole(m, role) {
  try {
    await updateGroupMemberRole(currentGroup.value.id, m.userId, role)
    m.role = role
    ElMessage.success('角色已更新')
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

async function removeMember(m) {
  try {
    await ElMessageBox.confirm(`确定移除成员 ${m.displayName || m.username} 吗？`, '确认移除', { type: 'warning' })
  } catch {
    return
  }
  try {
    await removeGroupMember(currentGroup.value.id, m.userId)
    ElMessage.success('成员已移除')
    await loadMembers(currentGroup.value.id)
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

onMounted(loadGroups)
</script>

<style scoped lang="scss">
.groups-page {
  padding: var(--space-lg) var(--space-xl);
  max-width: 1080px;
  margin: 0 auto;
}

.groups-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-xl);
  padding: var(--space-lg);
  box-shadow: var(--shadow-xs);
}

.group-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.group-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
  background: var(--bg-base);
  transition: all var(--transition-fast);

  &:hover {
    border-color: var(--brand-primary-lighter);
    box-shadow: var(--shadow-sm);
  }

  .group-main {
    flex: 1;
    min-width: 0;
  }

  .group-name {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
    color: var(--text-primary);

    .el-icon {
      color: var(--brand-primary);
    }
  }

  .group-desc {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .group-meta {
    font-size: 12px;
    color: var(--text-tertiary);
    white-space: nowrap;
  }

  .group-actions {
    display: flex;
    gap: 4px;
    white-space: nowrap;
  }
}

.member-add {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.member-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
  margin-bottom: 8px;

  .member-info {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .member-name {
    font-weight: 500;
    color: var(--text-primary);
  }

  .member-user {
    font-size: 12px;
    color: var(--text-tertiary);
  }

  .member-ops {
    display: flex;
    align-items: center;
    gap: 8px;
  }
}
</style>
