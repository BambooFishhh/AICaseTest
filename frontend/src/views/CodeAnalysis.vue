<template>
  <div class="code-analysis page-container" v-loading="loading">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <div class="title-block">
          <h1 class="page-title">代码分析结果</h1>
          <p class="page-subtitle">查看项目的状态机、API 端点、实体类等分析数据</p>
        </div>
      </div>
    </header>

    <!-- 空状态 -->
    <section v-if="!loading && !analysis" class="empty-section">
      <el-empty description="尚未生成分析结果，请先在项目详情页运行代码分析">
        <el-button type="primary" :icon="DataAnalysis" @click="goBack">返回项目详情</el-button>
      </el-empty>
    </section>

    <!-- 分析内容 -->
    <section v-if="analysis" class="analysis-content">
      <!-- 概览统计 -->
      <div class="stats-grid">
        <div class="stat-card stat-primary">
          <div class="stat-icon"><el-icon :size="20"><Share /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ stateMachines.length }}</div>
            <div class="stat-label">状态机</div>
          </div>
        </div>
        <div class="stat-card stat-success">
          <div class="stat-icon"><el-icon :size="20"><Connection /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ endpoints.length }}</div>
            <div class="stat-label">API 端点</div>
          </div>
        </div>
        <div class="stat-card stat-warning">
          <div class="stat-icon"><el-icon :size="20"><Collection /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ entities.length }}</div>
            <div class="stat-label">实体类</div>
          </div>
        </div>
        <div class="stat-card stat-danger">
          <div class="stat-icon"><el-icon :size="20"><Files /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ businessRules.length }}</div>
            <div class="stat-label">业务规则</div>
          </div>
        </div>
        <div class="stat-card stat-info">
          <div class="stat-icon"><el-icon :size="20"><List /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ enums.length }}</div>
            <div class="stat-label">枚举常量</div>
          </div>
        </div>
      </div>

      <!-- 选项卡 -->
      <el-tabs v-model="activeTab" class="analysis-tabs">
        <!-- 状态机 -->
        <el-tab-pane name="stateMachines">
          <template #label>
            <span class="tab-label">
              <el-icon><Share /></el-icon>状态机
              <span v-if="stateMachines.length" class="tab-count">{{ stateMachines.length }}</span>
            </span>
          </template>
          <el-empty v-if="stateMachines.length === 0" description="无状态机数据" />
          <div v-else class="sm-list">
            <article v-for="sm in stateMachines" :key="sm.id" class="sm-block">
              <div class="sm-head">
                <div class="sm-title-row">
                  <el-icon :size="18" class="sm-icon"><Share /></el-icon>
                  <h3 class="sm-name">{{ sm.name }}</h3>
                </div>
                <div class="sm-confidence">
                  <span class="confidence-label">置信度</span>
                  <el-progress
                    type="circle"
                    :width="56"
                    :percentage="Math.round((sm.confidence || 0) * 100)"
                    :color="confidenceColor(sm.confidence)"
                  />
                </div>
              </div>

              <div class="sm-visual">
                <state-machine-viewer
                  :states="sm.states"
                  :transitions="sm.transitions"
                  :forbidden-transitions="sm.forbiddenTransitions"
                />
              </div>

              <div class="sm-tables">
                <div class="sm-table-block">
                  <h4 class="sub-title">
                    <el-icon><List /></el-icon>状态列表
                    <span class="sub-count">{{ (sm.states || []).length }}</span>
                  </h4>
                  <el-table :data="sm.states || []" stripe size="small">
                    <el-table-column prop="name" label="名称" min-width="120" />
                    <el-table-column prop="type" label="类型" width="100">
                      <template #default="{ row }">
                        <el-tag v-if="row.type" size="small" effect="light">{{ row.type }}</el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
                  </el-table>
                </div>

                <div class="sm-table-block">
                  <h4 class="sub-title">
                    <el-icon><Switch /></el-icon>转换列表
                    <span class="sub-count">{{ (sm.transitions || []).length }}</span>
                  </h4>
                  <el-table :data="sm.transitions || []" stripe size="small">
                    <el-table-column prop="from" label="源状态" min-width="120" />
                    <el-table-column label="" width="40" align="center">
                      <template #default>
                        <el-icon class="arrow-icon"><Right /></el-icon>
                      </template>
                    </el-table-column>
                    <el-table-column prop="to" label="目标状态" min-width="120" />
                    <el-table-column prop="trigger" label="触发条件" min-width="150" show-overflow-tooltip />
                    <el-table-column prop="condition" label="约束" min-width="150" show-overflow-tooltip />
                  </el-table>
                </div>
              </div>
            </article>
          </div>
        </el-tab-pane>

        <!-- API 端点 -->
        <el-tab-pane name="endpoints">
          <template #label>
            <span class="tab-label">
              <el-icon><Connection /></el-icon>API 端点
              <span v-if="endpoints.length" class="tab-count">{{ endpoints.length }}</span>
            </span>
          </template>
          <div class="filter-bar">
            <el-radio-group v-model="methodFilter" size="default">
              <el-radio-button label="">全部</el-radio-button>
              <el-radio-button label="GET">GET</el-radio-button>
              <el-radio-button label="POST">POST</el-radio-button>
              <el-radio-button label="PUT">PUT</el-radio-button>
              <el-radio-button label="DELETE">DELETE</el-radio-button>
              <el-radio-button label="PATCH">PATCH</el-radio-button>
            </el-radio-group>
          </div>
          <el-empty v-if="filteredEndpoints.length === 0" description="无 API 端点数据" />
          <el-table v-else :data="filteredEndpoints" stripe>
            <el-table-column label="方法" width="100">
              <template #default="{ row }">
                <el-tag :type="methodTagType(row.method)" size="small" effect="dark">{{ row.method }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="path" label="路径" min-width="220">
              <template #default="{ row }">
                <code class="mono-text">{{ row.path }}</code>
              </template>
            </el-table-column>
            <el-table-column prop="function" label="函数" min-width="180" show-overflow-tooltip />
            <el-table-column prop="file" label="来源文件" min-width="220" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <!-- 枚举/常量 -->
        <el-tab-pane name="enums">
          <template #label>
            <span class="tab-label">
              <el-icon><List /></el-icon>枚举/常量
              <span v-if="enums.length" class="tab-count">{{ enums.length }}</span>
            </span>
          </template>
          <el-empty v-if="enums.length === 0" description="无枚举/常量数据" />
          <div v-else class="collapse-list">
            <el-collapse v-model="activeEnum">
              <el-collapse-item
                v-for="(en, idx) in enums"
                :key="idx"
                :name="idx"
              >
                <template #title>
                  <span class="collapse-title">
                    <el-icon class="collapse-icon"><Collection /></el-icon>
                    <span class="collapse-name">{{ en.name }}</span>
                    <span v-if="en.file" class="collapse-file">{{ en.file }}</span>
                    <el-tag size="small" effect="plain">{{ (en.values || []).length }} 项</el-tag>
                  </span>
                </template>
                <el-table :data="en.values || []" stripe size="small">
                  <el-table-column prop="name" label="常量名" min-width="180" />
                  <el-table-column prop="value" label="值" min-width="150" />
                </el-table>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-tab-pane>

        <!-- 实体类 -->
        <el-tab-pane name="entities">
          <template #label>
            <span class="tab-label">
              <el-icon><Files /></el-icon>实体类
              <span v-if="entities.length" class="tab-count">{{ entities.length }}</span>
            </span>
          </template>
          <el-empty v-if="entities.length === 0" description="无实体类数据" />
          <div v-else class="collapse-list">
            <el-collapse v-model="activeEntity">
              <el-collapse-item
                v-for="(ent, idx) in entities"
                :key="idx"
                :name="idx"
              >
                <template #title>
                  <span class="collapse-title">
                    <el-icon class="collapse-icon"><Document /></el-icon>
                    <span class="collapse-name">{{ ent.name }}</span>
                    <span v-if="ent.file" class="collapse-file">{{ ent.file }}</span>
                    <el-tag size="small" type="success" effect="plain">{{ (ent.fields || []).length }} 字段</el-tag>
                  </span>
                </template>
                <el-table :data="ent.fields || []" stripe size="small">
                  <el-table-column prop="name" label="字段名" min-width="180" />
                  <el-table-column prop="type" label="类型" min-width="150" />
                </el-table>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-tab-pane>

        <!-- 业务规则 -->
        <el-tab-pane name="businessRules">
          <template #label>
            <span class="tab-label">
              <el-icon><Document /></el-icon>业务规则
              <span v-if="businessRules.length" class="tab-count">{{ businessRules.length }}</span>
            </span>
          </template>
          <el-empty v-if="businessRules.length === 0" description="无业务规则数据" />
          <el-table v-else :data="businessRules" stripe>
            <el-table-column prop="file" label="来源文件" min-width="200" show-overflow-tooltip />
            <el-table-column prop="function" label="函数" min-width="160" show-overflow-tooltip />
            <el-table-column prop="rule" label="规则" min-width="280" show-overflow-tooltip />
            <el-table-column prop="ruleType" label="规则类型" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.ruleType" size="small" type="warning" effect="light">{{ row.ruleType }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 前端分析 -->
        <el-tab-pane name="frontend">
          <template #label>
            <span class="tab-label">
              <el-icon><Monitor /></el-icon>前端分析
              <span v-if="hasFrontendData" class="tab-count dot-only"></span>
            </span>
          </template>
          <el-empty v-if="!hasFrontendData" description="无前端分析数据" />

          <div v-else class="frontend-grid">
            <!-- 表单字段 -->
            <article v-if="frontendForms.length" class="frontend-card">
              <div class="frontend-head">
                <el-icon :size="18"><Document /></el-icon>
                <h3 class="frontend-title">表单字段与校验</h3>
                <span class="frontend-count">{{ frontendForms.length }}</span>
              </div>
              <el-table :data="frontendForms" stripe size="small">
                <el-table-column prop="component" label="组件" width="150" />
                <el-table-column label="字段">
                  <template #default="{ row }">
                    <div class="field-list">
                      <div v-for="f in (row.fields || [])" :key="f.name" class="field-row">
                        <el-tag :type="f.required ? 'danger' : 'info'" size="small">{{ f.name }}</el-tag>
                        <span class="field-type">{{ f.type }}</span>
                        <span v-if="f.label" class="field-label">{{ f.label }}</span>
                        <el-tag
                          v-for="r in (f.rules || [])"
                          :key="r"
                          type="warning"
                          size="small"
                          effect="plain"
                        >{{ r }}</el-tag>
                      </div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="file" label="文件" width="180" show-overflow-tooltip />
              </el-table>
            </article>

            <!-- 组件交互状态 -->
            <article v-if="componentStates.length" class="frontend-card">
              <div class="frontend-head">
                <el-icon :size="18"><Switch /></el-icon>
                <h3 class="frontend-title">组件交互状态</h3>
                <span class="frontend-count">{{ componentStates.length }}</span>
              </div>
              <el-table :data="componentStates" stripe size="small">
                <el-table-column prop="component" label="组件" width="150" />
                <el-table-column label="类型" width="100">
                  <template #default="{ row }">
                    <el-tag :type="stateTagType(row.type)" size="small">{{ row.type }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="stateVar" label="状态变量" width="150" />
                <el-table-column prop="trigger" label="触发方式" min-width="180" show-overflow-tooltip />
                <el-table-column prop="file" label="文件" width="180" show-overflow-tooltip />
              </el-table>
            </article>

            <!-- DOM 选择器 -->
            <article v-if="domSelectors.length" class="frontend-card">
              <div class="frontend-head">
                <el-icon :size="18"><Aim /></el-icon>
                <h3 class="frontend-title">DOM 选择器</h3>
                <span class="frontend-count">{{ domSelectors.length }}</span>
              </div>
              <el-table :data="domSelectors" stripe size="small">
                <el-table-column prop="component" label="组件" width="150" />
                <el-table-column label="选择器">
                  <template #default="{ row }">
                    <div class="selector-list">
                      <div v-for="s in (row.selectors || [])" :key="s.value" class="selector-row">
                        <el-tag :type="selectorTagType(s.type)" size="small">{{ s.type }}</el-tag>
                        <code class="selector-value">{{ s.value }}</code>
                        <span class="selector-element">{{ s.element }}</span>
                      </div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="file" label="文件" width="180" show-overflow-tooltip />
              </el-table>
            </article>

            <!-- 页面跳转关系 -->
            <article v-if="pageFlows.length" class="frontend-card">
              <div class="frontend-head">
                <el-icon :size="18"><Connection /></el-icon>
                <h3 class="frontend-title">页面跳转关系</h3>
                <span class="frontend-count">{{ pageFlows.length }}</span>
              </div>
              <el-table :data="pageFlows" stripe size="small">
                <el-table-column prop="from" label="来源页面" width="150" />
                <el-table-column label="" width="40" align="center">
                  <template #default>
                    <el-icon class="flow-arrow"><Right /></el-icon>
                  </template>
                </el-table-column>
                <el-table-column prop="to" label="目标页面" width="150" />
                <el-table-column prop="trigger" label="触发条件" min-width="200" show-overflow-tooltip />
                <el-table-column prop="component" label="组件" width="150" />
              </el-table>
            </article>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<script setup>
/**
 * 代码分析结果页
 * 展示项目的代码分析数据，包括：
 * - 状态机（含可视化图、状态列表、转换列表）
 * - API 端点（按方法筛选）
 * - 枚举/常量
 * - 实体类
 * - 业务规则
 * - 前端分析（表单、状态、选择器、跳转）
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, Share, Connection, Collection, Files, List,
  Document, Switch, Right, Monitor, Aim, DataAnalysis
} from '@element-plus/icons-vue'
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
const activeEnum = ref([])
const activeEntity = ref([])

// 后端分析结果
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

// 按方法筛选的端点列表
const filteredEndpoints = computed(() => {
  if (!methodFilter.value) return endpoints.value
  return endpoints.value.filter(
    (e) => (e.method || '').toUpperCase() === methodFilter.value
  )
})

// HTTP 方法对应的标签类型
function methodTagType(method) {
  const m = (method || '').toUpperCase()
  if (m === 'GET') return 'success'
  if (m === 'POST') return 'warning'
  if (m === 'DELETE') return 'danger'
  if (m === 'PUT') return ''
  if (m === 'PATCH') return 'info'
  return 'info'
}

// 置信度颜色梯度
function confidenceColor(conf) {
  const v = (conf || 0) * 100
  if (v >= 80) return '#10b981'
  if (v >= 60) return '#f59e0b'
  if (v >= 40) return '#f97316'
  return '#ef4444'
}

// 前端分析状态标签类型
function stateTagType(type) {
  const map = { dialog: 'warning', drawer: 'danger', steps: 'success', tabs: 'info' }
  return map[type] || ''
}

// 前端选择器标签类型
function selectorTagType(type) {
  const map = { 'data-testid': 'success', id: '', ref: 'warning', 'aria-label': 'info' }
  return map[type] ?? 'info'
}

// 加载分析与状态机数据
async function loadData() {
  loading.value = true
  try {
    const [analysisRes, smRes] = await Promise.all([
      getAnalysis(projectId),
      getStateMachines(projectId)
    ])
    analysis.value = analysisRes.data
    stateMachines.value = smRes.data || []
    // 默认展开第一项
    if (enums.value.length) activeEnum.value = [0]
    if (entities.value.length) activeEntity.value = [0]
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
/* ===== 页头 ===== */
.page-header-main {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.title-block {
  display: flex;
  flex-direction: column;
}

/* ===== 统计卡片网格 ===== */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 20px;
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
  transition: all var(--transition-normal);

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--shadow-md);
  }

  .stat-icon {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    border-radius: var(--radius-md);
    color: #fff;
  }

  .stat-value {
    font-size: 26px;
    font-weight: 700;
    line-height: 1.1;
    color: var(--text-primary);
  }

  .stat-label {
    font-size: 13px;
    color: var(--text-tertiary);
    margin-top: 2px;
  }

  &.stat-primary .stat-icon { background: var(--brand-primary); }
  &.stat-success .stat-icon { background: var(--color-success); }
  &.stat-warning .stat-icon { background: var(--color-warning); }
  &.stat-danger .stat-icon { background: var(--color-danger); }
  &.stat-info .stat-icon { background: var(--color-info); }
}

/* ===== 选项卡 ===== */
.analysis-tabs {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 0 20px 20px;
  box-shadow: var(--shadow-xs);

  :deep(.el-tabs__header) {
    margin-bottom: 20px;
  }

  :deep(.el-tabs__nav-wrap::after) {
    background-color: var(--card-border-light);
  }

  :deep(.el-tabs__item) {
    height: 48px;
    font-size: 14px;
    font-weight: 500;
  }
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;

  .el-icon {
    font-size: 14px;
  }
}

.tab-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--radius-full);
  background: var(--el-color-primary-light-9);
  color: var(--brand-primary);
  font-size: 11px;
  font-weight: 600;

  &.dot-only {
    min-width: 8px;
    height: 8px;
    padding: 0;
    background: var(--color-danger);
  }
}

/* ===== 状态机卡片 ===== */
.sm-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

.sm-block {
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  background: var(--bg-surface);
  overflow: hidden;
  transition: border-color var(--transition-normal);

  &:hover {
    border-color: var(--brand-primary-lighter);
  }
}

.sm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  background: linear-gradient(to right, var(--el-color-primary-light-9), transparent);
  border-bottom: 1px solid var(--card-border-light);
}

.sm-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sm-icon {
  color: var(--brand-primary);
}

.sm-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.sm-confidence {
  display: flex;
  align-items: center;
  gap: 12px;
}

.confidence-label {
  font-size: 12px;
  color: var(--text-tertiary);
}

.sm-visual {
  padding: 20px;
  background: #fafbfc;
  border-bottom: 1px solid var(--card-border-light);
}

.sm-tables {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-md);
  padding: 20px;
}

.sm-table-block {
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.sub-title {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  background: #f8fafc;
  border-bottom: 1px solid var(--card-border-light);

  .el-icon {
    font-size: 14px;
    color: var(--brand-primary);
  }
}

.sub-count {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: var(--radius-full);
  background: var(--bg-base);
  color: var(--text-tertiary);
  font-size: 11px;
  font-weight: 500;
}

.arrow-icon {
  color: var(--brand-primary);
  font-weight: bold;
}

/* ===== 筛选条 ===== */
.filter-bar {
  margin-bottom: 16px;
}

/* ===== 折叠列表 ===== */
.collapse-list {
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.collapse-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}

.collapse-icon {
  color: var(--brand-primary);
}

.collapse-name {
  font-weight: 600;
  color: var(--text-primary);
}

.collapse-file {
  font-size: 12px;
  color: var(--text-tertiary);
  font-family: 'Consolas', 'Monaco', monospace;
}

/* ===== 前端分析 ===== */
.frontend-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-md);
}

.frontend-card {
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  background: var(--bg-surface);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
  transition: box-shadow var(--transition-normal);

  &:hover {
    box-shadow: var(--shadow-sm);
  }
}

.frontend-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 18px;
  background: #f8fafc;
  border-bottom: 1px solid var(--card-border-light);

  .el-icon {
    color: var(--brand-primary);
  }
}

.frontend-title {
  flex: 1;
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.frontend-count {
  padding: 2px 10px;
  border-radius: var(--radius-full);
  background: var(--el-color-primary-light-9);
  color: var(--brand-primary);
  font-size: 12px;
  font-weight: 600;
}

.field-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.field-type {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--text-tertiary);
}

.field-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.selector-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.selector-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.selector-value {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--color-warning);
  background: var(--color-warning-bg);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
}

.selector-element {
  font-size: 12px;
  color: var(--text-tertiary);
}

.flow-arrow {
  color: var(--brand-primary);
  font-size: 16px;
  font-weight: bold;
}

.mono-text {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  color: var(--brand-primary);
}

/* ===== 响应式 ===== */
@media (max-width: 992px) {
  .sm-tables {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .sm-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .filter-bar :deep(.el-radio-group) {
    display: flex;
    flex-wrap: wrap;
  }
}
</style>
