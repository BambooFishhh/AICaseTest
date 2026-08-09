<template>
  <div class="code-analysis" v-loading="loading">
    <div class="page-header">
      <h2>代码分析结果</h2>
      <el-button @click="goBack">返回</el-button>
    </div>

    <el-empty v-if="!loading && !analysis" description="尚未生成分析结果" />

    <el-tabs v-if="analysis" v-model="activeTab" class="analysis-tabs">
      <!-- 状态机 -->
      <el-tab-pane label="状态机" name="stateMachines">
        <el-empty v-if="stateMachines.length === 0" description="无状态机数据" />
        <div v-for="sm in stateMachines" :key="sm.id" class="sm-block">
          <el-card>
            <template #header>
              <div class="sm-header">
                <span class="sm-name">{{ sm.name }}</span>
                <div class="sm-confidence">
                  <span class="confidence-label">置信度</span>
                  <el-progress
                    type="circle"
                    :width="64"
                    :percentage="Math.round((sm.confidence || 0) * 100)"
                  />
                </div>
              </div>
            </template>

            <state-machine-viewer
              :states="sm.states"
              :transitions="sm.transitions"
              :forbidden-transitions="sm.forbiddenTransitions"
            />

            <h4 class="section-title">状态列表</h4>
            <el-table :data="sm.states || []" border size="small">
              <el-table-column prop="name" label="名称" />
              <el-table-column prop="type" label="类型" />
              <el-table-column prop="description" label="说明" />
            </el-table>

            <h4 class="section-title">转换列表</h4>
            <el-table :data="sm.transitions || []" border size="small">
              <el-table-column prop="from" label="源状态" />
              <el-table-column prop="to" label="目标状态" />
              <el-table-column prop="trigger" label="触发条件" />
              <el-table-column prop="condition" label="约束" />
            </el-table>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- API 端点 -->
      <el-tab-pane label="API端点" name="endpoints">
        <div class="filter-bar">
          <el-select
            v-model="methodFilter"
            placeholder="按方法筛选"
            clearable
            style="width: 200px"
          >
            <el-option label="GET" value="GET" />
            <el-option label="POST" value="POST" />
            <el-option label="PUT" value="PUT" />
            <el-option label="DELETE" value="DELETE" />
            <el-option label="PATCH" value="PATCH" />
          </el-select>
        </div>
        <el-empty v-if="filteredEndpoints.length === 0" description="无 API 端点数据" />
        <el-table v-else :data="filteredEndpoints" border>
          <el-table-column label="方法" width="100">
            <template #default="{ row }">
              <el-tag :type="methodTagType(row.method)">{{ row.method }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="path" label="路径" />
          <el-table-column prop="function" label="函数" />
          <el-table-column prop="file" label="来源文件" />
        </el-table>
      </el-tab-pane>

      <!-- 枚举/常量 -->
      <el-tab-pane label="枚举/常量" name="enums">
        <el-empty v-if="enums.length === 0" description="无枚举/常量数据" />
        <el-collapse v-else>
          <el-collapse-item
            v-for="(en, idx) in enums"
            :key="idx"
            :name="idx"
            :title="enumTitle(en)"
          >
            <el-table :data="en.values || []" border size="small">
              <el-table-column prop="name" label="常量名" />
              <el-table-column prop="value" label="值" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </el-tab-pane>

      <!-- 实体类 -->
      <el-tab-pane label="实体类" name="entities">
        <el-empty v-if="entities.length === 0" description="无实体类数据" />
        <el-collapse v-else>
          <el-collapse-item
            v-for="(ent, idx) in entities"
            :key="idx"
            :name="idx"
            :title="entityTitle(ent)"
          >
            <el-table :data="ent.fields || []" border size="small">
              <el-table-column prop="name" label="字段名" />
              <el-table-column prop="type" label="类型" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </el-tab-pane>

      <!-- 业务规则 -->
      <el-tab-pane label="业务规则" name="businessRules">
        <el-empty v-if="businessRules.length === 0" description="无业务规则数据" />
        <el-table v-else :data="businessRules" border>
          <el-table-column prop="file" label="来源文件" />
          <el-table-column prop="function" label="函数" />
          <el-table-column prop="rule" label="规则" />
          <el-table-column prop="ruleType" label="规则类型" width="120" />
        </el-table>
      </el-tab-pane>

      <!-- v1.11: 前端分析 -->
      <el-tab-pane label="前端分析" name="frontend">
        <el-empty v-if="!hasFrontendData" description="无前端分析数据" />

        <div v-else class="frontend-sections">
          <!-- 表单字段 -->
          <el-card v-if="frontendForms.length" class="frontend-card">
            <template #header><span class="card-title">表单字段与校验</span></template>
            <el-table :data="frontendForms" border size="small">
              <el-table-column prop="component" label="组件" width="150" />
              <el-table-column label="字段">
                <template #default="{ row }">
                  <div v-for="f in (row.fields || [])" :key="f.name" class="field-tag-row">
                    <el-tag :type="f.required ? 'danger' : 'info'" size="small">{{ f.name }}</el-tag>
                    <span class="field-type">({{ f.type }})</span>
                    <span v-if="f.label" class="field-label">{{ f.label }}</span>
                    <el-tag v-for="r in (f.rules || [])" :key="r" type="warning" size="small" effect="plain">{{ r }}</el-tag>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="file" label="文件" width="180" />
            </el-table>
          </el-card>

          <!-- 组件交互状态 -->
          <el-card v-if="componentStates.length" class="frontend-card">
            <template #header><span class="card-title">组件交互状态</span></template>
            <el-table :data="componentStates" border size="small">
              <el-table-column prop="component" label="组件" width="150" />
              <el-table-column label="类型" width="100">
                <template #default="{ row }">
                  <el-tag :type="stateTagType(row.type)" size="small">{{ row.type }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="stateVar" label="状态变量" width="150" />
              <el-table-column prop="trigger" label="触发方式" />
              <el-table-column prop="file" label="文件" width="180" />
            </el-table>
          </el-card>

          <!-- DOM 选择器 -->
          <el-card v-if="domSelectors.length" class="frontend-card">
            <template #header><span class="card-title">DOM 选择器</span></template>
            <el-table :data="domSelectors" border size="small">
              <el-table-column prop="component" label="组件" width="150" />
              <el-table-column label="选择器">
                <template #default="{ row }">
                  <div v-for="s in (row.selectors || [])" :key="s.value" class="selector-tag-row">
                    <el-tag :type="selectorTagType(s.type)" size="small">{{ s.type }}</el-tag>
                    <span class="selector-value">="{{ s.value }}"</span>
                    <span class="selector-element">({{ s.element }})</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="file" label="文件" width="180" />
            </el-table>
          </el-card>

          <!-- 页面跳转关系 -->
          <el-card v-if="pageFlows.length" class="frontend-card">
            <template #header><span class="card-title">页面跳转关系</span></template>
            <el-table :data="pageFlows" border size="small">
              <el-table-column prop="from" label="来源页面" width="150" />
              <el-table-column label="" width="40">
                <template #default><span class="flow-arrow">→</span></template>
              </el-table-column>
              <el-table-column prop="to" label="目标页面" width="150" />
              <el-table-column prop="trigger" label="触发条件" />
              <el-table-column prop="component" label="组件" width="150" />
            </el-table>
          </el-card>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAnalysis, getStateMachines } from '@/api/analysis'
import StateMachineViewer from '@/components/StateMachineViewer.vue'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id

const loading = ref(false)
const activeTab = ref('stateMachines')
const analysis = ref(null)
const stateMachines = ref([])
const methodFilter = ref('')

const backendResult = computed(() => analysis.value?.backendResult || {})
const endpoints = computed(() => backendResult.value.endpoints || [])
const enums = computed(() => backendResult.value.enums || [])
const entities = computed(() => backendResult.value.entities || [])
const businessRules = computed(() => backendResult.value.businessRules || [])

// v1.11: 前端分析结果
const frontendResult = computed(() => analysis.value?.frontendResult || {})
const frontendForms = computed(() => frontendResult.value.forms || [])
const componentStates = computed(() => frontendResult.value.componentStates || [])
const domSelectors = computed(() => frontendResult.value.domSelectors || [])
const pageFlows = computed(() => frontendResult.value.pageFlows || [])
const hasFrontendData = computed(() =>
  frontendForms.value.length > 0 ||
  componentStates.value.length > 0 ||
  domSelectors.value.length > 0 ||
  pageFlows.value.length > 0
)

const filteredEndpoints = computed(() => {
  if (!methodFilter.value) return endpoints.value
  return endpoints.value.filter(
    (e) => (e.method || '').toUpperCase() === methodFilter.value
  )
})

function methodTagType(method) {
  const m = (method || '').toUpperCase()
  if (m === 'GET') return 'success'
  if (m === 'POST') return 'warning'
  if (m === 'DELETE') return 'danger'
  if (m === 'PUT') return ''
  if (m === 'PATCH') return 'info'
  return 'info'
}

function enumTitle(en) {
  return en.name + (en.file ? ` (${en.file})` : '')
}

function entityTitle(ent) {
  return ent.name + (ent.file ? ` (${ent.file})` : '')
}

// v1.11: 前端分析 tag 类型
function stateTagType(type) {
  const map = { dialog: 'warning', drawer: 'danger', steps: 'success', tabs: 'info' }
  return map[type] || ''
}

function selectorTagType(type) {
  const map = { 'data-testid': 'success', id: '', ref: 'warning', 'aria-label': 'info' }
  return map[type] ?? 'info'
}

async function loadData() {
  loading.value = true
  try {
    const [analysisRes, smRes] = await Promise.all([
      getAnalysis(projectId),
      getStateMachines(projectId)
    ])
    analysis.value = analysisRes.data
    stateMachines.value = smRes.data || []
  } finally {
    loading.value = false
  }
}

function goBack() {
  router.push(`/projects/${projectId}`)
}

onMounted(loadData)
</script>

<style scoped>
.code-analysis {
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
.analysis-tabs {
  margin-top: 10px;
}
.sm-block {
  margin-bottom: 20px;
}
.sm-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.sm-name {
  font-weight: bold;
  font-size: 16px;
}
.sm-confidence {
  display: flex;
  align-items: center;
  gap: 12px;
}
.confidence-label {
  color: #606266;
  font-size: 13px;
}
.section-title {
  margin: 16px 0 8px;
  color: #303133;
}
.filter-bar {
  margin-bottom: 16px;
}
/* v1.11: 前端分析样式 */
.frontend-sections {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.frontend-card {
  margin: 0;
}
.card-title {
  font-weight: bold;
  font-size: 14px;
}
.field-tag-row {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}
.field-type {
  color: #909399;
  font-size: 12px;
}
.field-label {
  color: #606266;
  font-size: 12px;
  margin-right: 4px;
}
.selector-tag-row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
}
.selector-value {
  font-family: monospace;
  font-size: 13px;
  color: #e6a23c;
}
.selector-element {
  color: #909399;
  font-size: 12px;
}
.flow-arrow {
  font-size: 18px;
  color: #409eff;
  font-weight: bold;
}
</style>
