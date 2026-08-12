<template>
  <div class="testcase-list page-container" v-loading="loading">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <h1 class="page-title">测试用例</h1>
      </div>
      <div class="page-actions">
        <el-button :icon="Setting" @click="handleOpenGenParams">生成参数</el-button>
        <el-button type="warning" :icon="Plus" :disabled="streaming" @click="handleOpenAppendDialog">
          追加生成
        </el-button>
        <el-button
          type="primary"
          :icon="RefreshRight"
          :loading="regenerating"
          :disabled="streaming"
          @click="handleRegenerate"
        >
          重新生成
        </el-button>
        <el-button :icon="Share" :loading="generatingMap" @click="handleGenerateMindmap">
          生成脑图
        </el-button>
        <el-button v-if="mindmapGenerated" type="success" :icon="View" @click="handleViewMindmap">
          查看脑图
        </el-button>
        <!-- v3.11: 执行历史入口 -->
        <el-button :icon="Clock" @click="goExecutions">执行历史</el-button>
      </div>
    </header>

    <!-- 统计卡片 -->
    <div class="stats-grid">
      <div class="stat-card stat-total">
        <div class="stat-icon"><el-icon :size="20"><Files /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.total }}</div>
          <div class="stat-label">总计</div>
        </div>
      </div>
      <div class="stat-card stat-positive">
        <div class="stat-icon"><el-icon :size="20"><CircleCheck /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.positive }}</div>
          <div class="stat-label">正向</div>
        </div>
      </div>
      <div class="stat-card stat-negative">
        <div class="stat-icon"><el-icon :size="20"><CircleClose /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.negative }}</div>
          <div class="stat-label">异常</div>
        </div>
      </div>
      <div class="stat-card stat-boundary">
        <div class="stat-icon"><el-icon :size="20"><Aim /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.boundary }}</div>
          <div class="stat-label">边界</div>
        </div>
      </div>
      <div class="stat-card stat-data">
        <div class="stat-icon"><el-icon :size="20"><Coin /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.data }}</div>
          <div class="stat-label">数据</div>
        </div>
      </div>
    </div>

    <!-- 筛选卡片 -->
    <section class="filter-section">
      <div class="filter-grid">
        <el-select
          v-model="filters.module"
          placeholder="模块筛选"
          clearable
          size="default"
          @change="handleFilter"
        >
          <el-option v-for="m in moduleOptions" :key="m" :label="m" :value="m" />
        </el-select>
        <el-select
          v-model="filters.type"
          placeholder="类型筛选"
          clearable
          size="default"
          @change="handleFilter"
        >
          <el-option label="正向" value="positive" />
          <el-option label="异常" value="negative" />
          <el-option label="边界" value="boundary" />
          <el-option label="数据" value="data" />
        </el-select>
        <el-select
          v-model="filters.priority"
          placeholder="优先级筛选"
          clearable
          size="default"
        >
          <el-option label="P0" value="P0" />
          <el-option label="P1" value="P1" />
          <el-option label="P2" value="P2" />
          <el-option label="P3" value="P3" />
        </el-select>
        <el-select
          v-model="filters.reviewStatus"
          placeholder="评审状态筛选"
          clearable
          size="default"
          @change="handleFilter"
        >
          <el-option label="草稿" value="draft" />
          <el-option label="已评审" value="reviewed" />
          <el-option label="已批准" value="approved" />
          <el-option label="已拒绝" value="rejected" />
        </el-select>
        <!-- v3.12: 执行状态筛选 -->
        <el-select
          v-model="filters.executionStatus"
          placeholder="执行状态筛选"
          clearable
          size="default"
          @change="handleFilter"
        >
          <el-option label="未执行" value="not_executed" />
          <el-option label="执行中" value="running" />
          <el-option label="通过" value="passed" />
          <el-option label="失败" value="failed" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          placeholder="搜索用例标题/模块"
          clearable
          size="default"
          @keyup.enter="handleFilter"
          @clear="handleFilter"
        >
          <template #append>
            <el-button :icon="Search" @click="handleFilter" />
          </template>
        </el-input>
      </div>
    </section>

    <!-- 状态横幅 -->
    <Transition name="slide-down">
      <div v-if="streaming" class="streaming-banner">
        <div class="streaming-icon">
          <el-icon class="is-loading" :size="20"><Loading /></el-icon>
        </div>
        <div class="streaming-body">
          <div class="streaming-title">{{ streamingAlertTitle }}</div>
          <div class="streaming-progress">{{ streamProgress }}</div>
        </div>
        <el-button
          type="danger"
          size="small"
          :loading="cancelling"
          @click="handleCancelGenerate"
        >
          取消生成
        </el-button>
      </div>
    </Transition>

    <el-alert
      v-if="progressText && !streaming"
      :title="progressText"
      type="info"
      :closable="false"
      show-icon
      class="info-alert"
    />

    <el-alert
      v-if="generationError"
      :title="`生成失败: ${generationError}`"
      type="error"
      :closable="false"
      show-icon
      class="info-alert"
    />

    <!-- 批量操作工具栏 -->
    <section v-if="selectedRows.length > 0" class="batch-toolbar">
      <span class="batch-count">已选 {{ selectedRows.length }} 条</span>
      <div class="batch-actions">
        <el-button type="danger" :icon="Delete" @click="handleBatchDelete">
          批量删除
        </el-button>
        <el-button type="success" :icon="VideoPlay" @click="openBatchExecuteDialog">
          批量执行
        </el-button>
        <el-button :icon="Download" @click="handleExportSelected">
          导出选中
        </el-button>
        <el-dropdown @command="handleReviewCommand">
          <el-button :icon="Check">
            批量评审<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="reviewed">标记为已评审</el-dropdown-item>
              <el-dropdown-item command="approved">标记为已批准</el-dropdown-item>
              <el-dropdown-item command="rejected">标记为已拒绝</el-dropdown-item>
              <el-dropdown-item command="draft">重置为草稿</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </section>

    <!-- 主操作工具栏 -->
    <section class="action-toolbar">
      <div class="action-left">
        <el-button :icon="Upload" @click="triggerImportXmind">导入 XMind</el-button>
        <!-- v3.16: XMind 导入模板 -->
        <el-button :icon="Download" @click="downloadXmindTemplate">模板</el-button>
        <el-button type="success" :icon="Plus" @click="handleCreateTestCase">新增用例</el-button>
        <!-- v3.12: 快捷批量执行 -->
        <el-button
          type="danger"
          plain
          :icon="RefreshRight"
          :disabled="failedCount === 0"
          @click="handleRerunFailed"
        >
          重跑失败<span v-if="failedCount > 0">（{{ failedCount }}）</span>
        </el-button>
        <el-button
          type="success"
          plain
          :icon="CircleCheck"
          :disabled="approvedCount === 0"
          @click="handleExecuteApproved"
        >
          执行已批准<span v-if="approvedCount > 0">（{{ approvedCount }}）</span>
        </el-button>
      </div>
      <div class="action-right">
        <!-- v3.13: 导出下拉（选中优先，未选中导出全部） -->
        <el-dropdown @command="handleExportCommand">
          <el-button :icon="Download">
            导出<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="json">导出 JSON</el-dropdown-item>
              <el-dropdown-item command="csv">导出 CSV</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <!-- v3.13: 导入 JSON -->
        <el-button :icon="Upload" @click="triggerImportJson">导入 JSON</el-button>
        <!-- v3.13: 跨项目复制 -->
        <el-button :icon="CopyDocument" :disabled="selectedRows.length === 0" @click="openCopyDialog">
          复制到
        </el-button>
        <!-- v3.15: 测试集/回归集 -->
        <el-button :icon="Files" :disabled="selectedRows.length === 0" @click="openSaveSuiteDialog">
          保存为测试集
        </el-button>
        <el-button :icon="Operation" @click="openSuiteDialog">测试集</el-button>
        <!-- v3.15: 多执行环境 -->
        <el-button :icon="Connection" @click="openEnvDialog">执行环境</el-button>
        <input
          ref="xmindFileInput"
          type="file"
          accept=".xmind"
          style="display: none"
          @change="handleImportXmind"
        />
        <input
          ref="jsonFileInput"
          type="file"
          accept=".json"
          style="display: none"
          @change="handleImportJson"
        />
      </div>
    </section>

    <!-- v3.11: 覆盖率关联用例筛选横幅 -->
    <div v-if="idFilter.length > 0" class="coverage-filter-banner">
      <el-icon :size="16"><Filter /></el-icon>
      <span class="coverage-filter-text">已按覆盖率关联用例筛选（{{ idFilter.length }} 条）</span>
      <el-button type="primary" link size="small" @click="clearCoverageFilter">
        清除筛选
      </el-button>
    </div>

    <!-- 用例树状表格 -->
    <section class="table-section">
      <el-table
        :data="treeData"
        row-key="id"
        :tree-props="{ children: 'children' }"
        :row-class-name="rowClassName"
        default-expand-all
        highlight-current-row
        @row-click="handleRowClick"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="45" />
        <el-table-column prop="id" label="编号" width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.isModule"></span>
            <span v-else-if="streaming" class="text-muted">生成中</span>
            <span v-else class="case-id">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.isModule" class="module-label">
              <el-icon class="module-icon" :size="16"><FolderOpened /></el-icon>
              <span class="module-name">{{ row.title }}</span>
              <span class="module-count">{{ row.count }}</span>
            </span>
            <span v-else class="case-title">{{ row.title }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag v-if="!row.isModule" :type="typeTagType(row.type)" size="small" effect="light">
              {{ typeText(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="70">
          <template #default="{ row }">
            <el-tag v-if="!row.isModule" :type="priorityTagType(row.priority)" size="small" effect="plain">
              {{ row.priority }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="前置条件" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.isModule"></span>
            <span v-else-if="row.preconditions && row.preconditions.length" class="detail-summary">
              {{ row.preconditions[0] }}{{ row.preconditions.length > 1 ? ` +${row.preconditions.length - 1}` : '' }}
            </span>
            <span v-else class="text-muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="测试步骤" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.isModule"></span>
            <span v-else-if="row.steps && row.steps.length" class="detail-summary">
              {{ row.steps[0] }}{{ row.steps.length > 1 ? ` +${row.steps.length - 1}` : '' }}
            </span>
            <span v-else class="text-muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="预期结果" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.isModule"></span>
            <span v-else-if="row.expectedResults && row.expectedResults.length" class="detail-summary">
              {{ row.expectedResults[0] }}{{ row.expectedResults.length > 1 ? ` +${row.expectedResults.length - 1}` : '' }}
            </span>
            <span v-else class="text-muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="质量" width="90">
          <template #default="{ row }">
            <el-progress
              v-if="!row.isModule && row.qualityScore > 0"
              :percentage="row.qualityScore"
              :color="qualityColor(row.qualityScore)"
              :stroke-width="12"
            />
            <span v-else-if="!row.isModule" class="text-muted">未评分</span>
          </template>
        </el-table-column>
        <!-- v3.12: 执行状态列 -->
        <el-table-column label="执行" width="80">
          <template #default="{ row }">
            <span v-if="!row.isModule" class="status-pill" :class="`status-${executionStatusKey(row.executionStatus)}`">
              <i class="status-dot"></i>{{ executionStatusText(row.executionStatus) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="评审" width="70">
          <template #default="{ row }">
            <el-tag v-if="!row.isModule" :type="reviewTagType(row.reviewStatus)" size="small" effect="light">
              {{ reviewText(row.reviewStatus) }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 覆盖率面板 -->
    <el-collapse v-model="coverageExpanded" class="coverage-collapse">
      <el-collapse-item name="coverage">
        <template #title>
          <div class="coverage-title">
            <el-icon :size="16"><DataAnalysis /></el-icon>
            <span>覆盖率度量</span>
            <el-tag
              v-if="coverage"
              :type="overallCoverageTag"
              size="small"
              effect="light"
              class="coverage-title-tag"
            >
              状态机 {{ Math.round(coverage.stateTransition.rate * 100) }}% / 接口 {{ Math.round(coverage.apiEndpoint.rate * 100) }}%
            </el-tag>
          </div>
        </template>
        <div v-if="coverage" class="coverage-grid">
          <div class="coverage-item">
            <div class="coverage-head">
              <span class="coverage-label">状态转换覆盖率</span>
              <span class="coverage-rate">{{ Math.round(coverage.stateTransition.rate * 100) }}%</span>
            </div>
            <el-progress
              :percentage="Math.round(coverage.stateTransition.rate * 100)"
              :color="coverageColor(coverage.stateTransition.rate)"
              :stroke-width="14"
            />
            <div class="coverage-detail">
              已覆盖 {{ coverage.stateTransition.covered }} / {{ coverage.stateTransition.total }} 条转换
            </div>
          </div>
          <div class="coverage-item">
            <div class="coverage-head">
              <span class="coverage-label">接口覆盖率</span>
              <span class="coverage-rate">{{ Math.round(coverage.apiEndpoint.rate * 100) }}%</span>
            </div>
            <el-progress
              :percentage="Math.round(coverage.apiEndpoint.rate * 100)"
              :color="coverageColor(coverage.apiEndpoint.rate)"
              :stroke-width="14"
            />
            <div class="coverage-detail">
              已覆盖 {{ coverage.apiEndpoint.covered }} / {{ coverage.apiEndpoint.total }} 个接口
            </div>
          </div>
        </div>
        <el-empty
          v-else
          description="暂无覆盖率数据。运行代码分析并生成用例后，这里会显示状态机和接口的测试覆盖情况。"
          :image-size="80"
        />
      </el-collapse-item>
    </el-collapse>

    <CoverageMatrix
      v-if="coverageMatrix"
      :matrix="coverageMatrix"
      @filter-by-ids="handleFilterByIds"
    />

    <!-- 用例详情/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      width="900px"
      :title="currentTestCase ? currentTestCase.title : '用例详情'"
      class="case-dialog"
      :style="{ maxWidth: '95vw' }"
    >
      <test-case-card
        v-if="currentTestCase"
        :test-case="currentTestCase"
        :default-target-url="defaultTargetUrl"
        :editable="true"
        :can-go-prev="currentIndex > 0"
        :can-go-next="currentIndex < testCases.length - 1"
        @save="handleSaveTestCase"
        @close="dialogVisible = false"
        @delete="handleDeleteTestCase"
        @prev="handlePrev"
        @next="handleNext"
        @versions="handleOpenVersions"
      />
    </el-dialog>

    <!-- 新增用例对话框 -->
    <TestCaseCard
      v-if="createDialogVisible"
      :visible="createDialogVisible"
      :test-case="{}"
      mode="create"
      @create="handleSaveNewTestCase"
      @close="createDialogVisible = false"
    />

    <!-- 历史版本抽屉 -->
    <TestCaseVersionDrawer
      v-model:visible="versionDrawerVisible"
      :project-id="projectId"
      :testcase-id="currentTestCase?.id"
      :current-test-case="currentTestCase"
      @rollback="handleVersionRollback"
    />

    <!-- 批量执行对话框 -->
    <el-dialog
      v-model="batchExecuteDialogVisible"
      title="批量执行测试用例"
      width="480px"
    >
      <el-form label-width="100px">
        <el-form-item label="选中用例数">
          <span>{{ selectedRows.length }} 条</span>
        </el-form-item>
        <el-form-item label="待测页面URL">
          <el-input
            v-model="batchTargetUrl"
            placeholder="http://localhost:5173"
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchExecuteDialogVisible = false">取消</el-button>
        <el-button
          type="success"
          :icon="VideoPlay"
          :loading="batchExecuting"
          @click="confirmBatchExecute"
        >
          确认执行
        </el-button>
      </template>
    </el-dialog>

    <!-- v3.13: 跨项目复制对话框 -->
    <el-dialog v-model="copyDialogVisible" title="复制用例到其他项目" width="480px">
      <el-form label-width="100px">
        <el-form-item label="选中用例数">
          <span>{{ selectedRows.length }} 条</span>
        </el-form-item>
        <el-form-item label="目标项目">
          <el-select
            v-model="copyTargetProjectId"
            placeholder="请选择目标项目"
            style="width: 100%"
          >
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="copying" @click="confirmCopy">确认复制</el-button>
      </template>
    </el-dialog>

    <!-- v3.15: 测试集管理对话框 -->
    <el-dialog v-model="suiteDialogVisible" title="测试集管理" width="560px">
      <div class="suite-create">
        <el-input
          v-model="newSuiteName"
          placeholder="输入测试集名称"
          maxlength="50"
          style="flex: 1"
          @keyup.enter="confirmCreateSuite"
        />
        <el-button
          type="primary"
          :disabled="selectedRows.length === 0 || !newSuiteName.trim()"
          @click="confirmCreateSuite"
        >
          保存当前选中（{{ selectedRows.length }}）
        </el-button>
      </div>
      <el-empty v-if="suites.length === 0" description="暂无测试集" :image-size="80" />
      <div v-else v-for="suite in suites" :key="suite.id" class="suite-item">
        <div class="suite-info">
          <span class="suite-name">{{ suite.name }}</span>
          <span class="suite-meta">{{ suite.caseCount }} 条 · {{ formatDate(suite.createdAt) }}</span>
        </div>
        <div class="suite-actions">
          <el-button
            type="success"
            link
            :icon="VideoPlay"
            :loading="runningSuiteId === suite.id"
            @click="runSuite(suite)"
          >
            执行
          </el-button>
          <el-button type="danger" link :icon="Delete" @click="removeSuite(suite)">删除</el-button>
        </div>
      </div>
    </el-dialog>

    <!-- v3.15: 多执行环境对话框 -->
    <el-dialog v-model="envDialogVisible" title="执行环境" width="600px">
      <div class="env-list">
        <div v-for="(env, idx) in envForm.environments" :key="idx" class="env-row">
          <el-radio v-model="envForm.active" :value="env.name" />
          <el-input v-model="env.name" placeholder="环境名，如 dev/staging/prod" style="width: 150px" />
          <el-input v-model="env.url" placeholder="http://localhost:5173" style="flex: 1" />
          <el-button :icon="Delete" text type="danger" @click="removeEnv(idx)" />
        </div>
        <el-empty v-if="envForm.environments.length === 0" description="尚未配置环境" :image-size="60" />
      </div>
      <div class="env-actions">
        <el-button :icon="Plus" @click="addEnv">添加环境</el-button>
        <div style="flex: 1"></div>
        <el-button @click="envDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingEnvs" @click="saveEnvs">保存</el-button>
      </div>
    </el-dialog>

    <!-- 生成参数对话框 -->
    <el-dialog
      v-model="showGenParamsDialog"
      title="生成参数"
      width="480px"
    >
      <el-form :model="genParams" label-width="100px">
        <el-form-item label="用例密度">
          <el-radio-group v-model="genParams.caseDensity">
            <el-radio-button label="low">精简</el-radio-button>
            <el-radio-button label="medium">标准</el-radio-button>
            <el-radio-button label="high">详尽</el-radio-button>
          </el-radio-group>
          <div class="form-tip">控制每个状态转换/需求项的用例数量</div>
        </el-form-item>
        <el-form-item label="创造性">
          <el-slider
            v-model="genParams.temperature"
            :min="0.2"
            :max="0.6"
            :step="0.1"
            :marks="{ 0.2: '严谨', 0.4: '标准', 0.6: '发散' }"
          />
          <div class="form-tip">LLM 温度，越低越稳定一致，越高越多样发散</div>
        </el-form-item>
        <el-form-item label="聚焦类型">
          <el-checkbox-group v-model="genParams.focusTypes">
            <el-checkbox label="positive">正向</el-checkbox>
            <el-checkbox label="negative">异常</el-checkbox>
            <el-checkbox label="boundary">边界</el-checkbox>
            <el-checkbox label="data">数据</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">不选 = 全部类型；勾选后仅生成对应类型用例（v3.13 起强制过滤）</div>
        </el-form-item>
        <!-- v3.12: 默认执行 URL -->
        <el-form-item label="默认执行URL">
          <el-input
            v-model="genParams.defaultTargetUrl"
            placeholder="例如 http://localhost:5173"
            clearable
          />
          <div class="form-tip">执行用例时自动带入的默认地址，可在执行对话框覆盖</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showGenParamsDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="savingParams"
          @click="handleSaveGenParams"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 追加生成类型选择对话框 -->
    <el-dialog
      v-model="showAppendDialog"
      title="追加生成"
      width="420px"
    >
      <el-form label-width="80px">
        <el-form-item label="追加类型">
          <el-radio-group v-model="appendType">
            <el-radio label="">全部类型</el-radio>
            <el-radio label="positive">正向</el-radio>
            <el-radio label="negative">异常</el-radio>
            <el-radio label="boundary">边界</el-radio>
            <el-radio label="data">数据</el-radio>
          </el-radio-group>
          <div class="form-tip">
            追加生成不会删除现有用例，新用例 ID 从现有最大 +1 续号。
            与现有用例标题重复的新用例会被自动去重。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAppendDialog = false">取消</el-button>
        <el-button
          type="warning"
          :icon="Plus"
          :loading="streaming"
          @click="handleConfirmAppend"
        >
          开始追加生成
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, Delete, Download, Upload, Check, ArrowDown, VideoPlay,
  Setting, Plus, View, RefreshRight, Share, Files, CircleCheck,
  CircleClose, Aim, Coin, FolderOpened, ArrowLeft, Loading, DataAnalysis,
  Clock, Filter, CopyDocument, Operation, Connection
} from '@element-plus/icons-vue'
import {
  listTestCases, streamGenerate, streamGenerateAppend,
  cancelGenerate, createTestCase, deleteTestCase,
  batchDeleteTestCases, importXmind, reviewTestCases,
  exportTestCases, importTestCases, copyToProject
} from '@/api/testcase'
import {
  getProject, getGenerationParams, updateGenerationParams, listProjects,
  getExecutionEnvironments, updateExecutionEnvironments
} from '@/api/project'
import {
  createSuite, listSuites, deleteSuite, executeSuite
} from '@/api/suite'
import { generateMindmap } from '@/api/mindmap'
import { executeBatch } from '@/api/execution'
import { useProjectStore } from '@/stores/project'
import TestCaseCard from '@/components/TestCaseCard.vue'
import TestCaseVersionDrawer from '@/components/TestCaseVersionDrawer.vue'
import CoverageMatrix from '@/components/CoverageMatrix.vue'
import { getCoverageMatrix } from '@/api/coverage'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const projectId = route.params.id

const loading = ref(false)
const regenerating = ref(false)
const generatingMap = ref(false)
const pollingMessage = ref('')
const generationError = ref('')
const progressText = computed(
  () => projectStore.progressMessage || pollingMessage.value
)

const streaming = ref(false)
const streamProgress = ref('')
const streamedCases = ref([])
const cancelling = ref(false)
let streamEs = null
const currentGenMode = ref(null)

const streamingAlertTitle = computed(() => {
  if (!streaming.value) return ''
  const count = streamedCases.value.length
  const countText = count === 0 ? '正在接收 LLM 流式响应...' : `已收到 ${count} 条`
  if (currentGenMode.value === 'append') {
    return `正在追加生成测试用例... ${countText}`
  }
  return `正在生成测试用例... ${countText}`
})

const testCases = ref([])
const allTestCases = ref([])
const coverage = ref(null)
const coverageExpanded = ref(['coverage'])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filters = reactive({
  module: '', type: '', priority: '', keyword: '', reviewStatus: '', executionStatus: ''
})
// v3.11: 覆盖率矩阵关联用例的内存 ID 筛选（独立于 filters，避免依赖 keyword 搜索）
const idFilter = ref([])
// v3.12: 项目默认执行 URL
const defaultTargetUrl = ref('')

const dialogVisible = ref(false)
const currentTestCase = ref(null)

const selectedRows = ref([])
const xmindFileInput = ref(null)
const mindmapGenerated = ref(false)

const coverageMatrix = ref(null)

async function loadCoverageMatrix() {
  try {
    const res = await getCoverageMatrix(projectId)
    coverageMatrix.value = res.data
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

function handleSelectionChange(rows) {
  // v3.13: 过滤模块行，避免 module-xxx 进入批量操作
  selectedRows.value = rows.filter((r) => !r.isModule)
}

const moduleOptions = computed(() => {
  const set = new Set()
  allTestCases.value.forEach((tc) => {
    if (tc.module) set.add(tc.module)
  })
  return Array.from(set)
})

const stats = computed(() => {
  const s = { total: total.value, positive: 0, negative: 0, boundary: 0, data: 0 }
  allTestCases.value.forEach((tc) => {
    if (tc.type === 'positive') s.positive++
    else if (tc.type === 'negative') s.negative++
    else if (tc.type === 'boundary') s.boundary++
    else if (tc.type === 'data') s.data++
  })
  return s
})

const displayTestCases = computed(() => {
  let base
  if (streaming.value) {
    base = currentGenMode.value === 'append'
      ? [...streamedCases.value, ...testCases.value]
      : streamedCases.value
  } else {
    base = testCases.value
  }
  if (idFilter.value.length > 0) {
    base = base.filter((tc) => idFilter.value.includes(tc.id))
  }
  if (filters.priority) {
    base = base.filter((tc) => tc.priority === filters.priority)
  }
  return base
})

const treeData = computed(() => {
  const cases = displayTestCases.value
  const moduleMap = new Map()
  cases.forEach((tc) => {
    const mod = tc.module || '未分类'
    if (!moduleMap.has(mod)) moduleMap.set(mod, [])
    moduleMap.get(mod).push(tc)
  })
  const tree = []
  moduleMap.forEach((children, mod) => {
    tree.push({
      id: `module-${mod}`,
      isModule: true,
      title: mod,
      count: children.length,
      module: mod,
      children,
    })
  })
  return tree
})

function rowClassName({ row }) {
  return row.isModule ? 'module-row' : 'case-row'
}

function typeTagType(type) {
  return { positive: 'success', negative: 'danger', boundary: 'warning', data: 'info' }[type] || 'info'
}

function typeText(type) {
  return { positive: '正向', negative: '异常', boundary: '边界', data: '数据' }[type] || type
}

function priorityTagType(priority) {
  if (priority === 'P0') return 'danger'
  if (priority === 'P1') return 'warning'
  if (priority === 'P2') return ''
  return 'info'
}

function reviewTagType(status) {
  return { draft: 'info', reviewed: 'warning', approved: 'success', rejected: 'danger' }[status] || 'info'
}

function reviewText(status) {
  return { draft: '草稿', reviewed: '已评审', approved: '已批准', rejected: '已拒绝' }[status] || status || '草稿'
}

// v3.12: 执行状态展示
function executionStatusKey(status) {
  const key = status || 'not_executed'
  return ['not_executed', 'running', 'passed', 'failed'].includes(key) ? key : 'not_executed'
}

function executionStatusText(status) {
  return { not_executed: '未执行', running: '执行中', passed: '通过', failed: '失败' }[status] || '未执行'
}

// v3.12: 快捷执行统计（基于当前已加载列表）
const failedCount = computed(() => testCases.value.filter((tc) => tc.executionStatus === 'failed').length)
const approvedCount = computed(() => testCases.value.filter((tc) => tc.reviewStatus === 'approved').length)

// v3.12: 重跑失败用例
function handleRerunFailed() {
  const failed = testCases.value.filter((tc) => tc.executionStatus === 'failed')
  if (failed.length === 0) {
    ElMessage.warning('没有失败用例')
    return
  }
  selectedRows.value = failed
  openBatchExecuteDialog()
}

// v3.12: 执行已批准用例
function handleExecuteApproved() {
  const approved = testCases.value.filter((tc) => tc.reviewStatus === 'approved')
  if (approved.length === 0) {
    ElMessage.warning('没有已批准用例')
    return
  }
  selectedRows.value = approved
  openBatchExecuteDialog()
}

async function handleReviewCommand(command) {
  const ids = selectedRows.value.map((tc) => tc.id)
  const text = { reviewed: '已评审', approved: '已批准', rejected: '已拒绝', draft: '草稿' }[command]
  try {
    await ElMessageBox.confirm(
      `确定将选中的 ${ids.length} 条用例标记为「${text}」吗？`,
      '确认批量评审',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  // v3.16: 审计——记录评审人
  let reviewer = 'local'
  try {
    const { value } = await ElMessageBox.prompt(
      '请输入评审人名称（留空使用 local）',
      '批量评审',
      { confirmButtonText: '确定', cancelButtonText: '取消', inputValue: '' }
    )
    reviewer = (value && value.trim()) ? value.trim() : 'local'
  } catch {
    return
  }
  try {
    const res = await reviewTestCases(projectId, ids, command, reviewer)
    ElMessage.success(`评审人 ${reviewer}：已更新 ${res.data.updated} 条用例状态为「${text}」`)
    await Promise.all([loadList(), loadAllForStats()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

function coverageColor(rate) {
  if (rate >= 0.8) return '#10b981'
  if (rate >= 0.5) return '#f59e0b'
  return '#ef4444'
}

// 总体覆盖率徽章颜色（取两个覆盖率的较低值）
const overallCoverageTag = computed(() => {
  if (!coverage.value) return 'info'
  const minRate = Math.min(
    coverage.value.stateTransition.rate,
    coverage.value.apiEndpoint.rate
  )
  if (minRate >= 0.8) return 'success'
  if (minRate >= 0.5) return 'warning'
  return 'danger'
})

function qualityColor(score) {
  if (score >= 80) return '#10b981'
  if (score >= 50) return '#f59e0b'
  return '#ef4444'
}

async function loadList() {
  loading.value = true
  try {
    const params = { page: 1, pageSize: 9999 }
    if (filters.module) params.module = filters.module
    if (filters.type) params.type = filters.type
    if (filters.keyword) params.keyword = filters.keyword
    if (filters.reviewStatus) params.reviewStatus = filters.reviewStatus
    if (filters.executionStatus) params.executionStatus = filters.executionStatus
    const res = await listTestCases(projectId, params)
    const data = res.data || {}
    testCases.value = data.testCases || []
    total.value = data.total || 0
    page.value = data.page || page.value
    pageSize.value = data.pageSize || pageSize.value
    coverage.value = data.coverage || null
  } finally {
    loading.value = false
  }
}

async function loadAllForStats() {
  const res = await listTestCases(projectId, { page: 1, pageSize: 9999 })
  allTestCases.value = res.data?.testCases || []
}

function handleFilter() {
  page.value = 1
  loadList()
}

function handleRowClick(row) {
  if (row.isModule) return
  currentTestCase.value = row
  dialogVisible.value = true
}

const currentIndex = computed(() => {
  if (!currentTestCase.value) return -1
  return testCases.value.findIndex((tc) => tc.id === currentTestCase.value.id)
})

function handlePrev() {
  if (currentIndex.value > 0) {
    currentTestCase.value = testCases.value[currentIndex.value - 1]
  }
}

function handleNext() {
  if (currentIndex.value < testCases.value.length - 1) {
    currentTestCase.value = testCases.value[currentIndex.value + 1]
  }
}

async function handleDeleteTestCase(testcaseId) {
  try {
    await deleteTestCase(projectId, testcaseId)
    ElMessage.success('用例已删除')
    dialogVisible.value = false
    await Promise.all([loadList(), loadAllForStats()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

async function handleSaveTestCase() {
  dialogVisible.value = false
  await Promise.all([loadList(), loadAllForStats()])
}

const createDialogVisible = ref(false)

function handleCreateTestCase() {
  createDialogVisible.value = true
}

async function handleSaveNewTestCase(formData) {
  try {
    await createTestCase(projectId, formData)
    ElMessage.success('用例创建成功')
    createDialogVisible.value = false
    await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
  } catch (e) {
    ElMessage.error('创建用例失败: ' + (e.message || ''))
  }
}

async function handleBatchDelete() {
  const count = selectedRows.value.length
  try {
    await ElMessageBox.confirm(
      `确定要删除选中的 ${count} 条用例吗？此操作不可撤销。`,
      '确认批量删除',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const ids = selectedRows.value.map((tc) => tc.id)
    const res = await batchDeleteTestCases(projectId, ids)
    ElMessage.success(`已删除 ${res.data} 条用例`)
    selectedRows.value = []
    await Promise.all([loadList(), loadAllForStats()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

async function handleExportSelected() {
  const ids = selectedRows.value.map((tc) => tc.id)
  try {
    await generateMindmap(projectId, { testcaseIds: ids })
    ElMessage.success('选中用例脑图生成成功')
    mindmapGenerated.value = true
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

function triggerImportXmind() {
  xmindFileInput.value?.click()
}

// v3.16: 下载 XMind 导入模板
function downloadXmindTemplate() {
  window.open(`/api/projects/${projectId}/testcases/xmind-template`, '_blank')
}

async function handleImportXmind(e) {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    const res = await importXmind(projectId, file)
    ElMessage.success(`导入完成：成功 ${res.data.imported} 条，跳过 ${res.data.skipped} 条`)
    // v3.16: 跳过明细
    const details = res.data?.skippedDetails
    if (Array.isArray(details) && details.length > 0) {
      const html = details
        .slice(0, 10)
        .map((d) => `· ${d.title || '(无标题)'}：${d.reason}`)
        .join('<br/>')
      ElMessageBox.alert(
        html + (details.length > 10 ? `<br/>… 共 ${details.length} 条跳过` : ''),
        `跳过 ${details.length} 条用例`,
        { dangerouslyUseHTMLString: true, confirmButtonText: '知道了', type: 'warning' }
      )
    }
    await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
  e.target.value = ''
}

// ===== v3.13: 用例资产（导入 JSON / 导出 / 跨项目复制） =====
const jsonFileInput = ref(null)

function triggerImportJson() {
  jsonFileInput.value?.click()
}

async function handleImportJson(e) {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    const res = await importTestCases(projectId, file)
    ElMessage.success(`导入完成：成功 ${res.data.imported} 条，跳过 ${res.data.skipped} 条`)
    await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
  } catch {
    // 错误已由响应拦截器统一提示
  }
  e.target.value = ''
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

async function handleExportCommand(format) {
  try {
    const ids = selectedRows.value.map((tc) => tc.id)
    const { data: blob, fileName } = await exportTestCases(
      projectId,
      format,
      ids.length ? ids : undefined
    )
    downloadBlob(blob, fileName)
  } catch (e) {
    ElMessage.error('导出失败: ' + (e.message || ''))
  }
}

const copyDialogVisible = ref(false)
const copyTargetProjectId = ref('')
const projects = ref([])
const copying = ref(false)

async function openCopyDialog() {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先选择要复制的用例')
    return
  }
  copyTargetProjectId.value = ''
  try {
    const res = await listProjects()
    projects.value = res.data || []
  } catch {
    // 错误已由响应拦截器统一提示
  }
  copyDialogVisible.value = true
}

async function confirmCopy() {
  if (!copyTargetProjectId.value) {
    ElMessage.warning('请选择目标项目')
    return
  }
  copying.value = true
  try {
    const ids = selectedRows.value.map((tc) => tc.id)
    const res = await copyToProject(projectId, ids, copyTargetProjectId.value)
    ElMessage.success(`已复制 ${res.data?.copied ?? ids.length} 条用例到目标项目`)
    copyDialogVisible.value = false
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    copying.value = false
  }
}

// ===== v3.15: 测试集/回归集 =====
const suiteDialogVisible = ref(false)
const suites = ref([])
const newSuiteName = ref('')
const runningSuiteId = ref('')

function formatDate(val) {
  if (!val) return '-'
  const d = new Date(val)
  if (isNaN(d.getTime())) return '-'
  return d.toLocaleString('zh-CN', { hour12: false })
}

function openSaveSuiteDialog() {
  newSuiteName.value = ''
  suiteDialogVisible.value = true
  loadSuites()
}

async function openSuiteDialog() {
  suiteDialogVisible.value = true
  await loadSuites()
}

async function loadSuites() {
  try {
    const res = await listSuites(projectId)
    suites.value = res.data || []
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

async function confirmCreateSuite() {
  const ids = selectedRows.value.map((tc) => tc.id)
  if (ids.length === 0 || !newSuiteName.value.trim()) return
  try {
    await createSuite(projectId, { name: newSuiteName.value.trim(), caseIds: ids })
    ElMessage.success('测试集已保存')
    newSuiteName.value = ''
    await loadSuites()
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

async function runSuite(suite) {
  runningSuiteId.value = suite.id
  try {
    const res = await executeSuite(
      projectId,
      suite.id,
      defaultTargetUrl.value || 'http://localhost:5173'
    )
    ElMessage.success(`已启动测试集执行，共 ${res.data?.caseCount ?? 0} 条用例`)
    suiteDialogVisible.value = false
    if (res.data?.batchId) {
      router.push(`/projects/${projectId}/batches/${res.data.batchId}`)
    }
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    runningSuiteId.value = ''
  }
}

async function removeSuite(suite) {
  try {
    await ElMessageBox.confirm(
      `确定删除测试集「${suite.name}」吗？`,
      '确认删除',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteSuite(projectId, suite.id)
    ElMessage.success('测试集已删除')
    await loadSuites()
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

// ===== v3.15: 多执行环境 =====
const envDialogVisible = ref(false)
const envForm = reactive({ environments: [], active: '' })
const savingEnvs = ref(false)

async function openEnvDialog() {
  try {
    const res = await getExecutionEnvironments(projectId)
    envForm.environments = res.data?.environments || []
    envForm.active = res.data?.active || ''
  } catch {
    // 错误已由响应拦截器统一提示
  }
  envDialogVisible.value = true
}

function addEnv() {
  envForm.environments.push({ name: '', url: '' })
}

function removeEnv(idx) {
  envForm.environments.splice(idx, 1)
}

async function saveEnvs() {
  const cleaned = envForm.environments.filter(
    (e) => e.name && e.name.trim() && e.url && e.url.trim()
  )
  if (cleaned.length === 0) {
    ElMessage.warning('请至少配置一个环境')
    return
  }
  if (!cleaned.some((e) => e.name === envForm.active)) {
    ElMessage.warning('请选择激活环境')
    return
  }
  savingEnvs.value = true
  try {
    await updateExecutionEnvironments(projectId, {
      environments: cleaned,
      active: envForm.active
    })
    ElMessage.success('执行环境已保存')
    envDialogVisible.value = false
    // 激活环境 URL 已由后端同步为默认执行 URL
    await loadDefaultTargetUrl()
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    savingEnvs.value = false
  }
}

function handleFilterByIds(ids) {
  idFilter.value = ids || []
  filters.keyword = ''
}

// v3.11: 清除覆盖率关联用例筛选
function clearCoverageFilter() {
  idFilter.value = []
}

// v3.11: 跳转执行历史
function goExecutions() {
  router.push(`/projects/${projectId}/executions`)
}

const versionDrawerVisible = ref(false)
function handleOpenVersions() {
  versionDrawerVisible.value = true
}

const batchExecuteDialogVisible = ref(false)
const batchTargetUrl = ref('http://localhost:5173')
const batchExecuting = ref(false)

function openBatchExecuteDialog() {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先选择要执行的用例')
    return
  }
  batchTargetUrl.value = defaultTargetUrl.value || 'http://localhost:5173'
  batchExecuteDialogVisible.value = true
}

async function confirmBatchExecute() {
  if (!batchTargetUrl.value || !batchTargetUrl.value.trim()) {
    ElMessage.warning('请输入待测页面URL')
    return
  }
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先选择要执行的用例')
    return
  }
  batchExecuting.value = true
  try {
    const caseIds = selectedRows.value.map((tc) => tc.id)
    const res = await executeBatch(projectId, caseIds, batchTargetUrl.value.trim())
    const batchId = res.data?.batchId
    batchExecuteDialogVisible.value = false
    ElMessage.success(`已启动批量执行，共 ${caseIds.length} 条用例`)
    if (batchId) {
      router.push(`/projects/${projectId}/batches/${batchId}`)
    }
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    batchExecuting.value = false
  }
}

async function handleVersionRollback() {
  await Promise.all([loadList(), loadAllForStats()])
  if (currentTestCase.value?.id) {
    try {
      const res = await listTestCases(projectId, { page: 1, pageSize: 9999 })
      const updated = (res.data?.testCases || []).find((tc) => tc.id === currentTestCase.value.id)
      if (updated) currentTestCase.value = updated
    } catch {
      // 忽略，列表已刷新
    }
  }
}

async function handleRegenerate() {
  try {
    await ElMessageBox.confirm(
      '即将重新生成测试用例，当前所有用例（含人工修改）将被覆盖删除。确定要继续吗？',
      '确认重新生成',
      { confirmButtonText: '确定生成', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  generationError.value = ''
  streaming.value = true
  currentGenMode.value = 'regenerate'
  streamProgress.value = '正在启动生成...'
  streamedCases.value = []
  regenerating.value = true

  streamEs = streamGenerate(projectId, {
    onProgress: (msg) => {
      streamProgress.value = msg
    },
    onCase: (tc) => {
      streamedCases.value.unshift(tc)
    },
    onComplete: async (total) => {
      ElMessage.success(`用例生成完成，共 ${total} 条`)
      streaming.value = false
      currentGenMode.value = null
      streamProgress.value = ''
      regenerating.value = false
      cancelling.value = false
      page.value = 1
      await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
    },
    onCancelled: async (msg) => {
      ElMessage.warning(msg || '生成已取消，旧用例已保留')
      streaming.value = false
      currentGenMode.value = null
      streamProgress.value = ''
      regenerating.value = false
      cancelling.value = false
      await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
    },
    onError: (msg) => {
      streaming.value = false
      currentGenMode.value = null
      streamProgress.value = ''
      regenerating.value = false
      cancelling.value = false
      generationError.value = msg
      ElMessage.error('用例生成失败')
    }
  })
}

const showAppendDialog = ref(false)
const appendType = ref('')

function handleOpenAppendDialog() {
  if (streaming.value) {
    ElMessage.warning('正在生成中，请等待当前任务完成')
    return
  }
  appendType.value = ''
  showAppendDialog.value = true
}

function handleConfirmAppend() {
  showAppendDialog.value = false
  startAppendStream(appendType.value)
}

function startAppendStream(type) {
  generationError.value = ''
  streaming.value = true
  currentGenMode.value = 'append'
  streamProgress.value = '正在启动追加生成...'
  streamedCases.value = []

  streamEs = streamGenerateAppend(projectId, type, {
    onProgress: (msg) => {
      streamProgress.value = msg
    },
    onCase: (tc) => {
      streamedCases.value.unshift(tc)
    },
    onComplete: async (data) => {
      const appended = data?.appended ?? 0
      const dropped = data?.dropped ?? 0
      if (appended === 0) {
        ElMessage.warning(`未追加新用例（生成 ${data?.total ?? 0} 条，全部被去重/过滤）`)
      } else {
        ElMessage.success(`追加 ${appended} 条用例，去重/过滤 ${dropped} 条`)
      }
      streaming.value = false
      currentGenMode.value = null
      streamProgress.value = ''
      cancelling.value = false
      page.value = 1
      await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
    },
    onCancelled: async (msg) => {
      ElMessage.warning(msg || '追加生成已取消，现有用例已保留')
      streaming.value = false
      currentGenMode.value = null
      streamProgress.value = ''
      cancelling.value = false
      await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix()])
    },
    onError: (msg) => {
      streaming.value = false
      currentGenMode.value = null
      streamProgress.value = ''
      cancelling.value = false
      generationError.value = msg
      ElMessage.error('追加生成失败')
    }
  })
}

async function handleCancelGenerate() {
  try {
    await ElMessageBox.confirm(
      '确定要取消生成吗？已生成的用例将被丢弃，旧用例会保留。',
      '确认取消',
      { confirmButtonText: '确定取消', cancelButtonText: '继续生成', type: 'warning' }
    )
  } catch {
    return
  }
  cancelling.value = true
  try {
    await cancelGenerate(projectId)
  } catch {
    ElMessage.error('取消请求失败')
    cancelling.value = false
  }
}

async function handleGenerateMindmap() {
  generatingMap.value = true
  try {
    await generateMindmap(projectId)
    ElMessage.success('脑图生成成功')
    mindmapGenerated.value = true
  } finally {
    generatingMap.value = false
  }
}

function handleViewMindmap() {
  router.push(`/projects/${projectId}/mindmap`)
}

const showGenParamsDialog = ref(false)
const savingParams = ref(false)
const genParams = ref({
  caseDensity: 'medium',
  temperature: 0.4,
  focusTypes: [],
  defaultTargetUrl: ''
})

async function handleOpenGenParams() {
  showGenParamsDialog.value = true
  try {
    const res = await getGenerationParams(projectId)
    if (res.data) {
      genParams.value = {
        caseDensity: res.data.caseDensity || 'medium',
        temperature: typeof res.data.temperature === 'number' ? res.data.temperature : 0.4,
        focusTypes: Array.isArray(res.data.focusTypes) ? res.data.focusTypes : [],
        defaultTargetUrl: res.data.defaultTargetUrl || ''
      }
      defaultTargetUrl.value = genParams.value.defaultTargetUrl
    }
  } catch {
    // 拉取失败用默认值
  }
}

// v3.12: 加载默认执行 URL
async function loadDefaultTargetUrl() {
  try {
    const res = await getGenerationParams(projectId)
    defaultTargetUrl.value = res.data?.defaultTargetUrl || ''
  } catch {
    // 静默失败，保持默认值
  }
}

async function handleSaveGenParams() {
  savingParams.value = true
  try {
    await updateGenerationParams(projectId, genParams.value)
    ElMessage.success('生成参数已保存，下次重新生成时生效')
    showGenParamsDialog.value = false
  } catch {
    ElMessage.error('保存失败')
  } finally {
    savingParams.value = false
  }
}

function goBack() {
  router.push(`/projects/${projectId}`)
}

onMounted(async () => {
  await Promise.all([loadList(), loadAllForStats(), loadCoverageMatrix(), loadDefaultTargetUrl()])
  if (route.query.generate === '1') {
    router.replace({ path: route.path })
    try {
      const res = await getProject(projectId)
      const status = res.data?.status
      if (status === 'analyzing' || status === 'generating') return
      handleRegenerate()
    } catch {
      // 状态获取失败则忽略，不自动触发
    }
  }
})

onUnmounted(() => {
  projectStore.stopPolling()
  if (streamEs) {
    streamEs.close()
    streamEs = null
  }
})
</script>

<style scoped lang="scss">
.testcase-list {
  padding: var(--space-lg) var(--space-xl);
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

/* ===== 统计卡 ===== */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: var(--space-md) var(--space-lg);
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
  transition: all var(--transition-normal);

  &:hover {
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
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
    font-size: 24px;
    font-weight: 700;
    line-height: 1.1;
  }

  .stat-label {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 2px;
  }
}

.stat-total .stat-icon { background: linear-gradient(135deg, #818cf8, #4f46e5); }
.stat-total .stat-value { color: var(--brand-primary); }
.stat-positive .stat-icon { background: linear-gradient(135deg, #34d399, #10b981); }
.stat-positive .stat-value { color: var(--color-success); }
.stat-negative .stat-icon { background: linear-gradient(135deg, #f87171, #ef4444); }
.stat-negative .stat-value { color: var(--color-danger); }
.stat-boundary .stat-icon { background: linear-gradient(135deg, #fbbf24, #f59e0b); }
.stat-boundary .stat-value { color: var(--color-warning); }
.stat-data .stat-icon { background: linear-gradient(135deg, #a78bfa, #8b5cf6); }
.stat-data .stat-value { color: #8b5cf6; }

/* ===== 筛选区 ===== */
.filter-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: var(--space-md);
  margin-bottom: var(--space-md);
  box-shadow: var(--shadow-xs);
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--space-sm);

  .el-select,
  .el-input {
    width: 100%;
  }
}

/* ===== 流式生成横幅 ===== */
.streaming-banner {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  background: linear-gradient(135deg, var(--color-info-bg), transparent);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: var(--radius-lg);
  margin-bottom: var(--space-md);

  .streaming-icon {
    color: var(--brand-primary);

    .is-loading {
      animation: spin 1s linear infinite;
    }
  }

  .streaming-body {
    flex: 1;
    min-width: 0;
  }

  .streaming-title {
    font-weight: 600;
    font-size: 14px;
    color: var(--text-primary);
  }

  .streaming-progress {
    font-size: 12px;
    color: var(--text-secondary);
    margin-top: 2px;
  }
}

.info-alert {
  margin-bottom: var(--space-md);
}

/* ===== 工具栏 ===== */
.batch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: var(--brand-gradient);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-md);
  color: #fff;

  .batch-count {
    font-weight: 600;
    font-size: 14px;
  }

  .batch-actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;

    .el-button {
      background: rgba(255, 255, 255, 0.95);
      border-color: transparent;

      &:hover {
        background: #fff;
      }
    }
  }
}

.action-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-md);

  .action-left {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }

  /* v3.13: 右侧资产操作 */
  .action-right {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
}

/* ===== v3.11: 覆盖率关联用例筛选横幅 ===== */
.coverage-filter-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: var(--color-info-bg);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-md);
  color: var(--brand-primary);

  .coverage-filter-text {
    flex: 1;
    font-size: 13px;
    font-weight: 500;
  }
}

/* ===== 表格区 ===== */
.table-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
  margin-bottom: var(--space-md);

  :deep(.el-table) {
    border: none;

    .module-row {
      background-color: var(--bg-base);
      font-weight: 600;

      td.el-table__cell {
        background-color: var(--bg-base);
      }
    }

    .case-row {
      cursor: pointer;
    }
  }
}

.case-id {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--text-secondary);
}

.case-title {
  color: var(--text-primary);
}

/* v3.12: 执行状态胶囊 */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 500;

  .status-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  &.status-passed { color: var(--color-success); }
  &.status-failed { color: var(--color-danger); }
  &.status-running { color: var(--color-warning); }
  &.status-not_executed { color: var(--text-tertiary); }
}

.module-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--brand-primary);

  .module-icon {
    color: var(--brand-primary);
  }

  .module-name {
    font-weight: 600;
    color: var(--text-primary);
  }

  .module-count {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 20px;
    height: 18px;
    padding: 0 6px;
    border-radius: var(--radius-full);
    background: var(--el-color-primary-light-9);
    color: var(--brand-primary);
    font-size: 11px;
    font-weight: 600;
  }
}

.detail-summary {
  color: var(--text-secondary);
  display: inline-block;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.text-muted {
  color: var(--text-tertiary);
  font-size: 12px;
}

/* ===== 覆盖率 ===== */
.coverage-collapse {
  margin-top: var(--space-md);
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

/* 折叠面板标题区 */
.coverage-collapse :deep(.el-collapse-item__header) {
  padding: 0 16px;
  height: 52px;
  font-size: 15px;
  font-weight: 600;
  border-bottom: 1px solid var(--card-border);
}

.coverage-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}

.coverage-collapse :deep(.el-collapse-item__content) {
  padding: 16px;
}

/* 标题行：图标 + 文字 + 徽章 */
.coverage-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-primary);
}

.coverage-title-tag {
  margin-left: 4px;
  font-weight: 500;
}

/* 网格布局：两列覆盖率卡 */
.coverage-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
}

/* 单个覆盖率卡 */
.coverage-item {
  background: var(--bg-base);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  min-width: 0;
  overflow: hidden;

  .coverage-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 8px;
  }

  .coverage-label {
    font-size: 13px;
    color: var(--text-secondary);
    font-weight: 500;
  }

  .coverage-rate {
    font-size: 18px;
    font-weight: 700;
    color: var(--text-primary);
    font-variant-numeric: tabular-nums;
  }

  .coverage-detail {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 6px;
  }
}

.form-tip {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 4px;
  line-height: 1.4;
}

/* ===== v3.15: 测试集与执行环境 ===== */
.suite-create {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.suite-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
  margin-bottom: 8px;
  background: var(--bg-base);
}

.suite-name {
  font-weight: 600;
  color: var(--text-primary);
  font-size: 13px;
}

.suite-meta {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-left: 8px;
}

.suite-actions {
  display: flex;
  gap: 8px;
}

.env-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}

.env-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.env-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ===== 过渡动画 ===== */
.slide-down-enter-active, .slide-down-leave-active {
  transition: all var(--transition-normal);
  overflow: hidden;
}

.slide-down-enter-from, .slide-down-leave-to {
  opacity: 0;
  max-height: 0;
  margin-bottom: -16px;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .testcase-list {
    padding: var(--space-md);
  }

  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .batch-toolbar {
    flex-direction: column;
    gap: 8px;
    align-items: stretch;
  }
}
</style>
