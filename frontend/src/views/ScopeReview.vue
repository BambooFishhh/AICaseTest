<template>
  <div class="scope-review">
    <div class="page-header">
      <el-button :icon="ArrowLeft" text @click="goBack">返回</el-button>
      <div>
        <h1 class="page-title">本期范围</h1>
        <p class="page-subtitle">基于 Git 基线 diff 识别本期变更接口与受影响状态机，确认后作为生成与覆盖率的分母（v8.1）</p>
      </div>
      <el-button type="primary" :icon="Plus" class="header-action" @click="openCreateDialog">
        新建范围
      </el-button>
    </div>

    <!-- 非 Git 提示 -->
    <el-alert
      v-if="gitRefs === false"
      type="warning"
      :closable="false"
      show-icon
      title="源码路径不是 Git 仓库，无法自动识别；可在草稿中手动添加范围条目"
      style="margin-bottom: 16px"
    />

    <!-- 定义列表 -->
    <el-table v-loading="loading" :data="definitions" border>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="items-wrap">
            <div class="items-toolbar" v-if="row.status === 'draft'">
              <el-button size="small" :icon="Plus" @click="openItemDialog(row)">手动添加条目</el-button>
              <el-button size="small" :icon="Refresh" @click="handleRecompute(row)">重算识别</el-button>
              <el-button size="small" type="success" :icon="Check" @click="handleConfirm(row)">确认锁定</el-button>
            </div>
            <el-table :data="itemsByDef[row.id] || []" size="small" border v-loading="itemsLoadingId === row.id">
              <el-table-column label="类型" width="110">
                <template #default="{ row: item }">
                  <el-tag :type="item.itemType === 'ENDPOINT' ? 'primary' : 'warning'" size="small">
                    {{ item.itemType === 'ENDPOINT' ? '接口' : '状态机' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="itemRef" label="引用" min-width="220" show-overflow-tooltip />
              <el-table-column label="变更类型" width="100">
                <template #default="{ row: item }">
                  <el-tag :type="kindTagType(item.changeKind)" size="small">{{ kindLabel(item.changeKind) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="来源" width="110">
                <template #default="{ row: item }">
                  <el-tag effect="plain" size="small">{{ originLabel(item.origin) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="note" label="说明" min-width="200" show-overflow-tooltip />
              <el-table-column v-if="row.status === 'draft'" label="操作" width="80">
                <template #default="{ row: item }">
                  <el-button text type="danger" size="small" @click="handleRemoveItem(row, item)">移除</el-button>
                </template>
              </el-table-column>
              <template #empty>暂无条目</template>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="范围名称" min-width="180" />
      <el-table-column prop="baselineRef" label="基线" min-width="140" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'confirmed' ? 'success' : 'info'">
            {{ row.status === 'confirmed' ? '已确认' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="itemCount" label="条目数" width="80" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-popconfirm title="确定删除该范围定义？" @confirm="handleDelete(row)">
            <template #reference>
              <el-button text type="danger" size="small">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="尚未创建本期范围" :image-size="100" />
      </template>
    </el-table>

    <!-- 新建范围 -->
    <el-dialog v-model="createVisible" title="新建本期范围" width="560px">
      <el-form label-width="90px">
        <el-form-item label="范围名称" required>
          <el-input v-model="createForm.name" placeholder="如：2026-S35 迭代" maxlength="64" />
        </el-form-item>
        <el-form-item label="基线" required>
          <el-select
            v-model="createForm.baselineRef"
            filterable
            allow-create
            default-first-option
            placeholder="选择分支 / tag，或直接输入 commit"
            style="width: 100%"
            :loading="refsLoading"
          >
            <el-option-group v-if="(gitRefs?.heads || []).length" label="本地分支">
              <el-option v-for="r in gitRefs.heads" :key="'h-' + r" :label="r" :value="r" />
            </el-option-group>
            <el-option-group v-if="(gitRefs?.remotes || []).length" label="远端分支">
              <el-option v-for="r in gitRefs.remotes" :key="'r-' + r" :label="r" :value="r" />
            </el-option-group>
            <el-option-group v-if="(gitRefs?.tags || []).length" label="Tag">
              <el-option v-for="r in gitRefs.tags" :key="'t-' + r" :label="r" :value="r" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item>
          <span class="form-hint">
            创建时会执行 Git diff 并调用 LLM 辅助映射，可能需要数十秒。
            基线应为本期迭代开始前的分支点或上期 tag。
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建并识别</el-button>
      </template>
    </el-dialog>

    <!-- 手动添加条目 -->
    <el-dialog v-model="itemDialog.visible" title="手动添加范围条目" width="520px">
      <el-form label-width="90px">
        <el-form-item label="类型" required>
          <el-radio-group v-model="itemDialog.itemType">
            <el-radio-button value="ENDPOINT">接口</el-radio-button>
            <el-radio-button value="STATE_MACHINE">状态机</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="itemDialog.itemType === 'ENDPOINT' ? '接口引用' : '状态机ID'" required>
          <el-input
            v-model="itemDialog.itemRef"
            :placeholder="itemDialog.itemType === 'ENDPOINT' ? 'GET /admin/order/list' : '状态机 id'"
          />
        </el-form-item>
        <el-form-item label="变更类型" required>
          <el-select v-model="itemDialog.changeKind" style="width: 100%">
            <el-option label="新增(ADDED)" value="ADDED" />
            <el-option label="变更(MODIFIED)" value="MODIFIED" />
            <el-option label="受影响(AFFECTED)" value="AFFECTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="itemDialog.note" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="itemDialog.saving" @click="handleAddItem">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Plus, Refresh, Check } from '@element-plus/icons-vue'
import {
  getScopeList, createScope, getGitRefs, getScopeItems,
  addScopeItem, removeScopeItem, recomputeScope, confirmScope, deleteScope
} from '@/api/scope'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id

const loading = ref(false)
const definitions = ref([])
const itemsByDef = ref({})
const itemsLoadingId = ref('')
const gitRefs = ref(null)
const refsLoading = ref(false)

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', baselineRef: '' })

const itemDialog = reactive({
  visible: false, saving: false,
  definitionId: '', itemType: 'ENDPOINT', itemRef: '', changeKind: 'MODIFIED', note: ''
})

function goBack() {
  router.push(`/projects/${projectId}`)
}

function kindLabel(kind) {
  return { ADDED: '新增', MODIFIED: '变更', AFFECTED: '受影响' }[kind] || kind
}

function kindTagType(kind) {
  return { ADDED: 'success', MODIFIED: 'warning', AFFECTED: 'primary' }[kind] || 'info'
}

function originLabel(origin) {
  return { AUTO_DIFF: 'Git识别', LLM_MAPPED: 'LLM映射', MANUAL: '手动' }[origin] || origin
}

async function loadList() {
  loading.value = true
  try {
    const res = await getScopeList(projectId)
    definitions.value = res.data || []
    for (const def of definitions.value) {
      loadItems(def.id)
    }
  } finally {
    loading.value = false
  }
}

async function loadItems(definitionId) {
  itemsLoadingId.value = definitionId
  try {
    const res = await getScopeItems(projectId, definitionId)
    itemsByDef.value[definitionId] = res.data || []
  } catch (e) {
    itemsByDef.value[definitionId] = []
  } finally {
    itemsLoadingId.value = ''
  }
}

async function loadGitRefs() {
  refsLoading.value = true
  try {
    const res = await getGitRefs(projectId)
    gitRefs.value = res.data
  } catch (e) {
    gitRefs.value = false
  } finally {
    refsLoading.value = false
  }
}

function openCreateDialog() {
  createForm.name = ''
  createForm.baselineRef = ''
  createVisible.value = true
  if (gitRefs.value === null) {
    loadGitRefs()
  }
}

async function handleCreate() {
  if (!createForm.name.trim() || !createForm.baselineRef.trim()) {
    ElMessage.warning('请填写范围名称与基线')
    return
  }
  creating.value = true
  try {
    const res = await createScope(projectId, {
      name: createForm.name.trim(),
      baselineRef: createForm.baselineRef.trim()
    })
    if (res.data && res.data.autoIdentified === false) {
      ElMessage.warning('已创建空草稿：该项目非 Git 仓库或缺少分析结果，请手动添加条目')
    } else {
      ElMessage.success('范围草稿已创建')
    }
    createVisible.value = false
    await loadList()
  } finally {
    creating.value = false
  }
}

function openItemDialog(def) {
  itemDialog.definitionId = def.id
  itemDialog.itemType = 'ENDPOINT'
  itemDialog.itemRef = ''
  itemDialog.changeKind = 'MODIFIED'
  itemDialog.note = ''
  itemDialog.visible = true
}

async function handleAddItem() {
  if (!itemDialog.itemRef.trim()) {
    ElMessage.warning('请填写条目引用')
    return
  }
  itemDialog.saving = true
  try {
    await addScopeItem(projectId, itemDialog.definitionId, {
      itemType: itemDialog.itemType,
      itemRef: itemDialog.itemRef.trim(),
      changeKind: itemDialog.changeKind,
      note: itemDialog.note.trim()
    })
    ElMessage.success('条目已添加')
    itemDialog.visible = false
    await loadList()
  } finally {
    itemDialog.saving = false
  }
}

async function handleRemoveItem(def, item) {
  await removeScopeItem(projectId, def.id, item.id)
  ElMessage.success('已移除')
  await loadList()
}

async function handleRecompute(def) {
  loading.value = true
  try {
    await recomputeScope(projectId, def.id)
    ElMessage.success('已重新识别（手动条目保留）')
    await loadList()
  } finally {
    loading.value = false
  }
}

async function handleConfirm(def) {
  await confirmScope(projectId, def.id)
  ElMessage.success('范围已确认锁定')
  await loadList()
}

async function handleDelete(def) {
  await deleteScope(projectId, def.id)
  ElMessage.success('已删除')
  await loadList()
}

onMounted(loadList)
</script>

<style scoped>
.scope-review {
  padding: 20px 24px;
}
.page-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}
.header-action {
  margin-left: auto;
}
.page-title {
  margin: 0;
  font-size: 20px;
}
.page-subtitle {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.items-wrap {
  padding: 8px 16px 12px 48px;
}
.items-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.form-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
