<template>
  <div class="project-create page-container">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <h1 class="page-title">创建项目</h1>
      </div>
    </header>

    <!-- 表单卡片 -->
    <section class="form-section">
      <div class="section-header">
        <div class="section-header-text">
          <h2 class="section-title">项目信息</h2>
          <p class="section-desc">支持纯 PRD 驱动，代码路径为可选上下文</p>
        </div>
        <el-icon :size="28" class="section-icon"><InfoFilled /></el-icon>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="create-form"
      >
        <el-form-item label="项目名称" prop="name">
          <el-input
            v-model="form.name"
            placeholder="请输入项目名称"
            maxlength="200"
            show-word-limit
            clearable
            size="large"
          />
        </el-form-item>

        <!-- v4.3: 所属项目组（可选） -->
        <el-form-item label="所属项目组">
          <el-select
            v-model="form.groupId"
            placeholder="不选则为个人项目"
            clearable
            style="width: 100%"
            size="large"
            :loading="groupsLoading"
          >
            <el-option v-for="g in groups" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
          <div class="form-item-tip">加入项目组后，组内成员可查看该项目；操作权限由组创建者指派</div>
        </el-form-item>

        <el-form-item label="来源类型" prop="sourceType">
          <div class="source-type-grid">
            <label
              v-for="opt in sourceTypes"
              :key="opt.value"
              class="source-type-card"
              :class="{ active: form.sourceType === opt.value }"
            >
              <input
                type="radio"
                v-model="form.sourceType"
                :value="opt.value"
                class="sr-only"
              />
              <el-icon :size="22"><component :is="opt.icon" /></el-icon>
              <div class="card-text">
                <div class="card-title">{{ opt.label }}</div>
                <div class="card-desc">{{ opt.desc }}</div>
              </div>
              <el-icon v-if="form.sourceType === opt.value" class="check-mark" :size="18">
                <CircleCheckFilled />
              </el-icon>
            </label>
          </div>
        </el-form-item>

        <el-form-item
          v-if="form.sourceType === 'local_path'"
          label="项目路径"
          prop="sourcePath"
        >
          <div class="path-input-group">
            <el-input
              v-model="form.sourcePath"
              placeholder="请输入项目源码路径，或点击右侧浏览选择"
              clearable
              size="large"
              class="path-input"
            />
            <DirSelector @select="handleDirSelect" />
          </div>
          <div class="field-tip">
            <el-icon><InfoFilled /></el-icon>
            可手动输入路径，或使用浏览按钮可视化选择目录
          </div>
        </el-form-item>

        <el-form-item
          v-else-if="form.sourceType === 'git_url'"
          label="Git 地址"
          prop="sourcePath"
        >
          <el-input
            v-model="form.sourcePath"
            placeholder="例如：https://github.com/user/repo.git"
            clearable
            size="large"
          >
            <template #prepend>https://</template>
          </el-input>
        </el-form-item>

        <el-form-item v-else label="项目路径">
          <div class="info-banner">
            <el-icon :size="20"><MagicStick /></el-icon>
            <div>
              <div class="banner-title">纯 PRD 驱动模式</div>
              <div class="banner-desc">无需代码路径，创建后可在详情页通过 PRD 文档直接生成测试用例</div>
            </div>
          </div>
        </el-form-item>

        <el-form-item>
          <div class="form-actions">
            <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit">
              创建项目
            </el-button>
            <el-button size="large" @click="handleReset">重置</el-button>
            <el-button size="large" text @click="goBack">取消</el-button>
            <el-popover v-model:visible="preconfigVisible" placement="bottom-end" :width="560" trigger="click">
              <template #reference>
                <el-button size="large" :icon="MoreFilled">更多</el-button>
              </template>
              <div class="preconfig-popover">
                <div class="preconfig-title">Cookie 配置</div>
                <div class="preconfig-tip">name 是 Cookie 名称，value 是对应的值；domain 填目标站点域名</div>
                <div v-for="(cookie, idx) in cookies" :key="idx" class="cookie-card">
                  <div class="cookie-fields">
                    <div class="cookie-field">
                      <span class="cookie-label">名称</span>
                      <el-input v-model="cookie.name" placeholder="如 JSESSIONID" />
                    </div>
                    <div class="cookie-field">
                      <span class="cookie-label">值</span>
                      <el-input v-model="cookie.value" placeholder="Cookie 值" />
                    </div>
                    <div class="cookie-field">
                      <span class="cookie-label">域名/URL</span>
                      <el-input v-model="cookie.domain" placeholder="host.docker.internal" />
                    </div>
                  </div>
                  <el-button :icon="Delete" text type="danger" @click="removeCookie(idx)">删除</el-button>
                </div>
                <div class="preconfig-actions">
                  <el-button size="small" :icon="Plus" @click="addCookie">添加 Cookie</el-button>
                  <el-button size="small" @click="applyExampleCookie">示例</el-button>
                  <div style="flex: 1"></div>
                  <el-button size="small" type="primary" @click="saveCookieConfig">保存</el-button>
                </div>
                <div class="preconfig-tip">预留：未来其他前置配置会加在这里</div>
              </div>
            </el-popover>
          </div>
        </el-form-item>
      </el-form>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, watch, markRaw } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, InfoFilled, MagicStick,
  FolderOpened, Link, CircleCheckFilled, MoreFilled, Delete, Plus
} from '@element-plus/icons-vue'
import { createProject } from '@/api/project'
import { listGroups } from '@/api/group'
import DirSelector from '@/components/DirSelector.vue'
import { onMounted } from 'vue'

const router = useRouter()
const formRef = ref()
const submitting = ref(false)
const preconfigVisible = ref(false)
const cookies = ref([])

const form = reactive({
  name: '',
  groupId: '',
  sourceType: 'local_path',
  sourcePath: ''
})
const groups = ref([])
const groupsLoading = ref(false)

const sourceTypes = [
  { value: 'local_path', label: '本地路径', desc: '从本地代码目录读取', icon: markRaw(FolderOpened) },
  { value: 'git_url', label: 'Git 地址', desc: '从远程仓库克隆', icon: markRaw(Link) },
  { value: 'none', label: '无代码（纯 PRD）', desc: '基于 PRD 直接生成', icon: markRaw(MagicStick) }
]

const rules = {
  name: [
    { required: true, message: '请输入项目名称', trigger: 'blur' },
    { min: 1, max: 200, message: '长度在 1 到 200 个字符', trigger: 'blur' }
  ],
  sourcePath: [
    {
      validator: (rule, value, callback) => {
        if (form.sourceType === 'none') {
          callback()
          return
        }
        if (!value || !value.trim()) {
          callback(new Error(form.sourceType === 'git_url' ? '请输入 Git 地址' : '请输入项目路径'))
          return
        }
        if (form.sourceType === 'git_url') {
          const gitPattern = /^(https?|ssh|git):\/\/\S+$|^git@\S+:.+$/i
          if (!gitPattern.test(value.trim())) {
            callback(new Error('Git 地址格式不正确，例如：https://github.com/user/repo.git'))
            return
          }
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

watch(() => form.sourceType, () => {
  form.sourcePath = ''
  formRef.value?.clearValidate('sourcePath')
})

function handleDirSelect(path) {
  form.sourcePath = path
  formRef.value?.validateField('sourcePath')
}

function handleReset() {
  formRef.value?.resetFields()
  form.name = ''
  form.sourceType = 'local_path'
  form.sourcePath = ''
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    let executionCookies = []
    if (cookies.value.length > 0) {
      const missing = cookies.value.find(
        (c) => !c.name || !c.name.trim() || !c.value || !c.value.trim() || !c.domain || !c.domain.trim()
      )
      if (missing) {
        ElMessage.error('Cookie 存在未填写的字段')
        return
      }
      executionCookies = cookies.value.map((c) => {
        const base = { name: c.name.trim(), value: c.value.trim() }
        const domain = c.domain.trim()
        if (domain.startsWith('http://') || domain.startsWith('https://')) {
          base.url = domain
        } else {
          base.domain = domain
        }
        return base
      })
    }
    submitting.value = true
    try {
      const res = await createProject({
        name: form.name,
        groupId: form.groupId || undefined,
        sourceType: form.sourceType,
        sourcePath: form.sourcePath,
        executionCookies
      })
      ElMessage.success('项目创建成功')
      router.push(`/projects/${res.data.id}`)
    } finally {
      submitting.value = false
    }
  })
}

function applyExampleCookie() {
  cookies.value = [
    { name: 'JSESSIONID', value: 'your-session-value', domain: 'host.docker.internal' }
  ]
}

function addCookie() {
  cookies.value.push({ name: '', value: '', domain: '' })
}

function removeCookie(idx) {
  cookies.value.splice(idx, 1)
}

function saveCookieConfig() {
  if (cookies.value.length === 0) {
    preconfigVisible.value = false
    return
  }
  const missing = cookies.value.find(
    (c) => !c.name || !c.name.trim() || !c.value || !c.value.trim() || !c.domain || !c.domain.trim()
  )
  if (missing) {
    ElMessage.error('请填写完整的 Cookie 名称、值和域名')
    return
  }
  preconfigVisible.value = false
  ElMessage.success(`已保存 ${cookies.value.length} 条 Cookie`)
}

async function loadGroups() {
  groupsLoading.value = true
  try {
    const res = await listGroups()
    groups.value = res.data || []
  } catch {
    groups.value = []
  } finally {
    groupsLoading.value = false
  }
}

onMounted(loadGroups)

function goBack() {
  router.back()
}
</script>

<style scoped lang="scss">
.project-create {
  padding: var(--space-lg) var(--space-xl);
  max-width: 880px;
  margin: 0 auto;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

/* ===== 表单区 ===== */
.form-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-lg) var(--space-xl);
  background: linear-gradient(135deg, var(--el-color-primary-light-9) 0%, transparent 100%);
  border-bottom: 1px solid var(--card-border-light);

  .section-title {
    font-size: 18px;
    font-weight: 600;
    color: var(--text-primary);
    margin: 0 0 4px 0;
  }

  .section-desc {
    font-size: 13px;
    color: var(--text-tertiary);
    margin: 0;
  }

  .section-icon {
    color: var(--brand-primary);
    opacity: 0.6;
  }
}

.create-form {
  padding: var(--space-xl);
}

/* ===== 来源类型选择卡 ===== */
.source-type-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-md);
  width: 100%;
}

.source-type-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 2px solid var(--card-border);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: all var(--transition-fast);
  background: var(--bg-surface);

  &:hover {
    border-color: var(--brand-primary-lighter);
    background: var(--el-color-primary-light-9);
  }

  &.active {
    border-color: var(--brand-primary);
    background: var(--el-color-primary-light-9);
    box-shadow: 0 0 0 4px rgba(79, 70, 229, 0.08);
  }

  .card-text {
    flex: 1;
    min-width: 0;
  }

  .card-title {
    font-size: 14px;
    font-weight: 600;
    color: var(--text-primary);
  }

  .card-desc {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 2px;
  }

  .check-mark {
    color: var(--brand-primary);
  }
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

/* ===== 路径输入 ===== */
.path-input-group {
  display: flex;
  gap: var(--space-sm);
  width: 100%;
  align-items: center;
}

.path-input {
  flex: 1;
}

.field-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 6px;
}

/* ===== 信息横幅 ===== */
.info-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: linear-gradient(135deg, var(--el-color-primary-light-9), transparent);
  border: 1px solid var(--el-color-primary-light-8);
  border-radius: var(--radius-md);
  color: var(--brand-primary);
  width: 100%;

  .banner-title {
    font-weight: 600;
    color: var(--text-primary);
    font-size: 14px;
  }

  .banner-desc {
    font-size: 12px;
    color: var(--text-secondary);
    margin-top: 2px;
  }
}

/* ===== 表单操作 ===== */
.form-actions {
  display: flex;
  gap: var(--space-sm);
  padding-top: var(--space-md);
}

.preconfig-popover {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.preconfig-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.preconfig-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cookie-card {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--card-border-light);
  border-radius: 8px;
  background: #f8fafc;
}

.cookie-fields {
  display: grid;
  grid-template-columns: 1fr 1.2fr 1fr;
  gap: 8px;
  flex: 1;
}

.cookie-field {
  min-width: 0;
}

.cookie-label {
  display: block;
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 4px;
}

.preconfig-tip {
  font-size: 12px;
  color: var(--text-tertiary);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .project-create {
    padding: var(--space-md);
  }

  .create-form {
    padding: var(--space-md);
  }

  .source-type-grid {
    grid-template-columns: 1fr;
  }

  .path-input-group {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
