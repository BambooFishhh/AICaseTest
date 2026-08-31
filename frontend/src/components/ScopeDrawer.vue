<template>
  <el-drawer
    :model-value="modelValue"
    size="72%"
    :with-header="false"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="scope-panel" v-loading="pageLoading">
      <!-- 头部 -->
      <div class="panel-header">
        <div class="header-text">
          <h2 class="panel-title">
            <el-icon :size="20"><CollectionTag /></el-icon>
            本期范围
          </h2>
          <p class="panel-subtitle">分析完成后自动基于主干 diff 识别并锁定 · 作为生成目标与覆盖率分母（已确认范围可手动增删条目）</p>
        </div>
        <el-button :icon="Close" circle text @click="$emit('update:modelValue', false)" />
      </div>

      <!-- 空态：分步引导 + 内联创建表单 -->
      <template v-if="definitions.length === 0 && !creating">
        <div class="wizard-card">
          <el-steps :active="0" align-center class="wizard-steps">
            <el-step title="代码分析" description="已完成 ✓" status="finish" />
            <el-step title="自动识别范围" description="分析完成后自动完成（当前为手动兜底）" status="process" />
            <el-step title="生成用例" description="只聚焦本期变更" />
          </el-steps>

          <div class="create-card">
            <div class="create-title">创建本期范围</div>
            <el-alert
              v-if="gitRefs === false"
              type="warning"
              :closable="false"
              show-icon
              class="create-alert"
              title="源码路径不是 Git 仓库，无法自动识别；可创建空草稿后手动添加条目"
            />
            <el-form label-position="top" class="create-form">
              <div class="form-row">
                <el-form-item label="范围名称" class="grow">
                  <el-input v-model="createForm.name" placeholder="如：S36 迭代" maxlength="64" />
                </el-form-item>
                <el-form-item label="基线（上期结束点）" class="grow">
                  <el-select
                    v-model="createForm.baselineRef"
                    filterable allow-create clearable default-first-option
                    :placeholder="gitRefs?.defaultBaseline
                      ? `默认 ${gitRefs.defaultBaseline}（可改）`
                      : '选 tag / 分支 / 直接输 commit'"
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
                <el-button type="primary" :icon="Plus" class="submit-btn" :loading="creating" @click="handleCreate">
                  创建
                </el-button>
              </div>
              <div class="form-hint">范围 = 基线 → 当前代码 的变更。基线留空默认用仓库主干（{{ gitRefs?.defaultBaseline || 'origin 默认分支' }}）；发布迭代可改选上一期 tag 或 commit，不要选会移动的分支指针。</div>
            </el-form>
          </div>
        </div>
      </template>

      <!-- 创建中横幅 -->
      <div v-if="creating" class="progress-banner">
        <el-icon class="is-loading"><Loading /></el-icon>
        正在识别本期变更：执行 Git diff → 匹配接口/状态机/页面 → LLM 辅助映射（约 1-2 分钟，可离开此页稍后回来）
      </div>

      <!-- 范围主体 -->
      <template v-for="def in definitions" :key="def.id">
        <div class="scope-card">
          <!-- 范围头卡 -->
          <div class="def-head">
            <div class="def-info">
              <span class="def-name">{{ def.name }}</span>
              <el-tag :type="def.status === 'confirmed' ? 'success' : 'info'" effect="dark" size="small" round>
                {{ def.status === 'confirmed' ? '已确认' : '草稿' }}
              </el-tag>
              <span class="def-baseline">基线 <code>{{ def.baselineRef }}</code></span>
            </div>
            <div class="def-actions">
              <template v-if="def.status === 'draft'">
                <el-button size="small" :icon="Refresh" @click.stop="handleRecompute(def)">重算</el-button>
                <el-popconfirm
                    title="确认后作为生成目标与覆盖率分母，确定？"
                    width="220"
                    :disabled="!def.itemCount"
                    @confirm="handleConfirm(def)"
                  >
                    <template #reference>
                      <el-button size="small" type="success" :icon="Check" :disabled="!def.itemCount">
                        确认锁定
                      </el-button>
                    </template>
                    <template #title v-if="!def.itemCount">
                      范围暂无条目，无法确认。请先「重算」识别或手动添加条目。
                    </template>
                  </el-popconfirm>
              </template>
              <el-popconfirm title="删除该范围定义及全部条目？" width="200" @confirm="handleDelete(def)">
                <template #reference>
                  <el-button size="small" type="danger" plain :icon="Delete">删除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>

          <!-- 统计徽章条 -->
          <div class="stat-chips">
            <div class="chip chip-total"><span class="num">{{ allItems(def.id).length }}</span>条目</div>
            <div class="chip"><span class="num">{{ countBy(def.id, 'ENDPOINT') }}</span>接口</div>
            <div class="chip"><span class="num">{{ countBy(def.id, 'STATE_MACHINE') }}</span>状态机</div>
            <div class="chip"><span class="num">{{ countBy(def.id, 'PAGE') }}</span>页面</div>
            <div class="chip chip-auto"><span class="num">{{ countOrigin(def.id, 'AUTO_DIFF') }}</span>Git 识别</div>
            <div class="chip chip-llm"><span class="num">{{ countOrigin(def.id, 'LLM_MAPPED') }}</span>LLM 映射</div>
            <div class="chip chip-manual"><span class="num">{{ countOrigin(def.id, 'MANUAL') }}</span>手动</div>
          </div>

          <!-- 工具栏 -->
          <div class="items-toolbar">
            <el-radio-group v-model="typeFilter" size="small">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="ENDPOINT">接口</el-radio-button>
              <el-radio-button value="STATE_MACHINE">状态机</el-radio-button>
              <el-radio-button value="PAGE">页面</el-radio-button>
            </el-radio-group>
            <el-input
              v-model="keyword"
              size="small"
              placeholder="搜索引用 / 说明"
              clearable
              :prefix-icon="Search"
              class="kw-input"
            />
            <el-button
              size="small"
              type="primary"
              plain
              :icon="Plus"
              @click="openItemDialog(def)"
            >手动添加</el-button>
          </div>

          <!-- 条目表 -->
          <el-table
            :data="filteredItems(def.id)"
            size="small"
            border
            stripe
            class="items-table"
            v-loading="itemsLoadingId === def.id"
            :empty-text="'暂无条目'"
          >
            <el-table-column label="类型" width="92" align="center">
              <template #default="{ row: item }">
                <el-tag :type="typeTag(item.itemType)" size="small" effect="light">
                  {{ typeLabel(item.itemType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="itemRef" label="引用" min-width="240" show-overflow-tooltip>
              <template #default="{ row: item }"><code class="ref-code">{{ item.itemRef }}</code></template>
            </el-table-column>
            <el-table-column label="变更" width="90" align="center">
              <template #default="{ row: item }">
                <span class="kind-pill" :class="'kind-' + item.changeKind">{{ kindLabel(item.changeKind) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="100" align="center">
              <template #default="{ row: item }">
                <span class="origin-dot" :class="'origin-' + item.origin"></span>{{ originLabel(item.origin) }}
              </template>
            </el-table-column>
            <el-table-column prop="note" label="说明" min-width="200" show-overflow-tooltip />
            <el-table-column label="" width="60" align="center">
              <template #default="{ row: item }">
                <!-- v9.0: 已确认范围删条目会改变覆盖率分母，二次确认；草稿态直接删 -->
                <el-popconfirm
                  v-if="def.status !== 'draft'"
                  title="已确认范围删除条目会改变覆盖率分母，确定？"
                  width="250"
                  @confirm="handleRemoveItem(def, item)"
                >
                  <template #reference>
                    <el-button text type="danger" size="small" :icon="Delete" />
                  </template>
                </el-popconfirm>
                <el-button
                  v-else
                  text type="danger" size="small" :icon="Delete"
                  @click="handleRemoveItem(def, item)"
                />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </div>

    <!-- 手动添加小弹窗（轻操作，快速关闭无妨） -->
    <el-dialog v-model="itemDialog.visible" title="手动添加范围条目" width="480px" append-to-body>
      <el-form label-width="86px">
        <el-form-item label="类型">
          <el-radio-group v-model="itemDialog.itemType">
            <el-radio-button value="ENDPOINT">接口</el-radio-button>
            <el-radio-button value="STATE_MACHINE">状态机</el-radio-button>
            <el-radio-button value="PAGE">页面</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="itemDialog.itemType === 'ENDPOINT' ? '引用' : itemDialog.itemType === 'PAGE' ? '路由/路径' : '状态机ID'">
          <el-input v-model="itemDialog.itemRef"
            :placeholder="itemDialog.itemType === 'ENDPOINT' ? 'GET /wx/order/list'
              : itemDialog.itemType === 'PAGE' ? '/collect 或页面文件路径' : '状态机 id'" />
        </el-form-item>
        <el-form-item label="变更类型">
          <el-select v-model="itemDialog.changeKind" style="width:100%">
            <el-option label="新增" value="ADDED" /><el-option label="变更" value="MODIFIED" />
            <el-option label="受影响" value="AFFECTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="itemDialog.note" placeholder="可选" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="itemDialog.saving" @click="handleAddItem">添加</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CollectionTag, Close, Plus, Refresh, Check, Delete, Search, Loading
} from '@element-plus/icons-vue'
import {
  getScopeList, createScope, getGitRefs, getScopeItems,
  addScopeItem, removeScopeItem, recomputeScope, confirmScope, deleteScope
} from '@/api/scope'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  projectId: { type: String, required: true }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const pageLoading = ref(false)
const definitions = ref([])
const itemsByDef = ref({})
const itemsLoadingId = ref('')
const gitRefs = ref(null)
const refsLoading = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', baselineRef: '' })
const typeFilter = ref('')
const keyword = ref('')
const itemDialog = reactive({
  visible: false, saving: false,
  definitionId: '', itemType: 'ENDPOINT', itemRef: '', changeKind: 'MODIFIED', note: ''
})

function kindLabel(kind) {
  return { ADDED: '新增', MODIFIED: '变更', AFFECTED: '受影响' }[kind] || kind
}
function typeLabel(type) {
  return { ENDPOINT: '接口', STATE_MACHINE: '状态机', PAGE: '页面' }[type] || type
}
function typeTag(type) {
  return { ENDPOINT: 'primary', STATE_MACHINE: 'warning', PAGE: 'success' }[type] || 'info'
}
function originLabel(origin) {
  return { AUTO_DIFF: 'Git 识别', LLM_MAPPED: 'LLM 映射', MANUAL: '手动' }[origin] || origin
}

function allItems(defId) { return itemsByDef.value[defId] || [] }
function countBy(defId, type) { return allItems(defId).filter((i) => i.itemType === type).length }
function countOrigin(defId, origin) { return allItems(defId).filter((i) => i.origin === origin).length }

function filteredItems(defId) {
  let list = allItems(defId)
  if (typeFilter.value) list = list.filter((i) => i.itemType === typeFilter.value)
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((i) =>
      (i.itemRef || '').toLowerCase().includes(kw) || (i.note || '').toLowerCase().includes(kw))
  }
  return list
}

async function loadList() {
  pageLoading.value = true
  try {
    const res = await getScopeList(props.projectId)
    definitions.value = res.data || []
    await Promise.all(definitions.value.map((d) => loadItems(d.id)))
  } finally {
    pageLoading.value = false
  }
}

async function loadItems(definitionId) {
  itemsLoadingId.value = definitionId
  try {
    const res = await getScopeItems(props.projectId, definitionId)
    itemsByDef.value[definitionId] = res.data || []
  } catch {
    itemsByDef.value[definitionId] = []
  } finally {
    itemsLoadingId.value = ''
  }
}

async function loadGitRefs() {
  if (gitRefs.value !== null) return
  refsLoading.value = true
  try {
    const res = await getGitRefs(props.projectId)
    gitRefs.value = res.data
    // v8.9.8: 常规迭代基线即主干——自动预填默认基线，用户无需手选（可改）
    if (!createForm.baselineRef && gitRefs.value?.defaultBaseline) {
      createForm.baselineRef = gitRefs.value.defaultBaseline
    }
  } catch {
    gitRefs.value = false
  } finally {
    refsLoading.value = false
  }
}

watch(() => props.modelValue, (open) => {
  if (open) {
    loadList()
    loadGitRefs()
    typeFilter.value = ''
    keyword.value = ''
  }
})

/** v8.3fix: 提交即收起表单、横幅接管进度——不再让用户在弹窗里干等 1-2 分钟 */
async function handleCreate() {
  // v8.9.8: 基线可留空，后端自动回退仓库默认主干（异常仓库会报错提示手填）
  if (!createForm.name.trim()) {
    ElMessage.warning('请填写范围名称')
    return
  }
  creating.value = true
  try {
    const res = await createScope(props.projectId, {
      name: createForm.name.trim(),
      baselineRef: createForm.baselineRef.trim()
    })
    if (res.data && res.data.autoIdentified === false) {
      ElMessage.warning('已创建空草稿：非 Git 仓库或缺少分析结果，请手动添加条目')
    } else {
      ElMessage.success('本期范围草稿已创建')
    }
    createForm.name = ''
    createForm.baselineRef = gitRefs.value?.defaultBaseline || ''
    emit('changed')
    await loadList()
    // 分析旧于最新提交时提示重分析后重算，避免映射表过期导致系统性漏识别
    if (res.data?.analysisStale) {
      ElMessage.warning('代码分析旧于最新提交，识别可能漏项——建议重新分析后点「重算」')
    }
  } catch (e) {
    // 单例冲突等业务错误由拦截器提示；保留表单内容供修正
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
    await addScopeItem(props.projectId, itemDialog.definitionId, {
      itemType: itemDialog.itemType,
      itemRef: itemDialog.itemRef.trim(),
      changeKind: itemDialog.changeKind,
      note: itemDialog.note.trim()
    })
    ElMessage.success('条目已添加')
    itemDialog.visible = false
    emit('changed')
    await loadList()
  } finally {
    itemDialog.saving = false
  }
}

async function handleRemoveItem(def, item) {
  await removeScopeItem(props.projectId, def.id, item.id)
  emit('changed')
  await loadList()
}

async function handleRecompute(def) {
  pageLoading.value = true
  try {
    await recomputeScope(props.projectId, def.id)
    ElMessage.success('已重新识别（手动条目保留）')
    emit('changed')
    await loadList()
  } finally {
    pageLoading.value = false
  }
}

async function handleConfirm(def) {
  await confirmScope(props.projectId, def.id)
  ElMessage.success('范围已确认锁定，生成用例与覆盖率将以此为分母')
  emit('changed')
  await loadList()
}

async function handleDelete(def) {
  await deleteScope(props.projectId, def.id)
  ElMessage.success('已删除')
  emit('changed')
  await loadList()
}
</script>

<style scoped lang="scss">
.scope-panel {
  padding: 20px 24px;
  height: 100%;
  overflow-y: auto;
}
.panel-header {
  display: flex; align-items: flex-start; gap: 12px;
  margin-bottom: 18px;
}
.header-text { flex: 1; }
.panel-title {
  margin: 0; font-size: 19px; display: flex; align-items: center; gap: 8px;
}
.panel-subtitle {
  margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6;
}

/* ===== 空态向导 ===== */
.wizard-card {
  background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px; padding: 28px 32px 24px;
}
.wizard-steps { margin-bottom: 26px; }
.create-card {
  max-width: 760px; margin: 0 auto;
  border-top: 1px dashed var(--el-border-color-lighter); padding-top: 22px;
}
.create-title { font-weight: 600; margin-bottom: 14px; font-size: 15px; }
.create-alert { margin-bottom: 14px; }
.form-row { display: flex; gap: 12px; align-items: flex-end; }
.grow { flex: 1; }
.submit-btn { height: 32px; margin-bottom: 18px; }
.form-hint {
  color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6;
  background: var(--el-fill-color-light); border-radius: 6px; padding: 8px 12px;
}

/* ===== 创建中横幅 ===== */
.progress-banner {
  display: flex; align-items: center; gap: 10px;
  background: var(--el-color-primary-light-9); color: var(--el-color-primary);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 8px; padding: 12px 16px; font-size: 13px; margin-bottom: 16px;
}

/* ===== 范围卡片 ===== */
.scope-card {
  background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px; padding: 16px 18px; margin-bottom: 16px;
}
.def-head {
  display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px;
}
.def-info { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.def-name { font-size: 16px; font-weight: 600; }
.def-baseline { color: var(--el-text-color-secondary); font-size: 13px; }
.def-baseline code {
  background: var(--el-fill-color-light); padding: 2px 6px; border-radius: 4px; font-size: 12px;
}
.def-actions { display: flex; gap: 8px; }

.stat-chips { display: flex; gap: 10px; flex-wrap: wrap; margin: 14px 0; }
.chip {
  display: flex; align-items: baseline; gap: 5px;
  background: var(--el-fill-color-light); border-radius: 999px;
  padding: 4px 14px; font-size: 12px; color: var(--el-text-color-secondary);
  .num { font-size: 15px; font-weight: 700; color: var(--el-text-color-primary); }
  &.chip-total .num { color: var(--el-color-primary); }
  &.chip-auto .num { color: var(--el-color-success); }
  &.chip-llm .num { color: var(--el-color-warning); }
  &.chip-manual .num { color: var(--el-color-info); }
}

.items-toolbar {
  display: flex; align-items: center; gap: 10px; margin-bottom: 10px;
  .kw-input { width: 220px; margin-left: auto; }
}
.items-table { --el-table-border-color: var(--el-border-color-lighter); }

.ref-code {
  font-family: ui-monospace, Consolas, monospace; font-size: 12px;
  background: var(--el-fill-color-light); padding: 1px 6px; border-radius: 4px;
}
.kind-pill {
  font-size: 12px; padding: 1px 8px; border-radius: 999px;
  &.kind-ADDED { background: var(--el-color-success-light-9); color: var(--el-color-success); }
  &.kind-MODIFIED { background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); }
  &.kind-AFFECTED { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
}
.origin-dot {
  display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 6px;
  &.origin-AUTO_DIFF { background: var(--el-color-success); }
  &.origin-LLM_MAPPED { background: var(--el-color-warning); }
  &.origin-MANUAL { background: var(--el-color-info); }
}
.text-muted { color: var(--el-text-color-secondary); }
</style>
