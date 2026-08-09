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
</style>
