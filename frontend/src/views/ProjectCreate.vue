<template>
  <div class="project-create">
    <div class="page-header">
      <h2>创建项目</h2>
      <el-button @click="goBack">返回</el-button>
    </div>

    <el-card class="form-card">
      <template #header>
        <div class="card-header">
          <span>项目信息</span>
          <span class="card-header-tip">支持纯 PRD 驱动，代码路径为可选上下文</span>
        </div>
      </template>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
        class="create-form"
      >
        <el-form-item label="项目名称" prop="name">
          <el-input
            v-model="form.name"
            placeholder="请输入项目名称"
            maxlength="200"
            show-word-limit
            clearable
          />
        </el-form-item>
        <el-form-item label="来源类型" prop="sourceType">
          <el-radio-group v-model="form.sourceType">
            <el-radio-button label="local_path">本地路径</el-radio-button>
            <el-radio-button label="git_url">Git 地址</el-radio-button>
            <el-radio-button label="none">无代码（纯 PRD）</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.sourceType === 'local_path'" label="项目路径" prop="sourcePath">
          <el-input
            v-model="form.sourcePath"
            placeholder="请输入项目源码路径，或点击右侧浏览选择"
            clearable
          >
            <template #append>
              <!-- v3.1: 目录选择器插件 -->
              <DirSelector @select="handleDirSelect" />
            </template>
          </el-input>
          <div class="form-item-tip">提示：可手动输入路径，或使用浏览按钮可视化选择目录</div>
        </el-form-item>
        <el-form-item v-else-if="form.sourceType === 'git_url'" label="Git 地址" prop="sourcePath">
          <el-input
            v-model="form.sourcePath"
            placeholder="例如：https://github.com/user/repo.git"
            clearable
          >
            <template #prepend>https://</template>
          </el-input>
        </el-form-item>
        <el-form-item v-else label="项目路径">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="纯 PRD 驱动模式"
            description="无需代码路径，创建后可在详情页通过 PRD 文档直接生成测试用例。"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">
            创建项目
          </el-button>
          <el-button @click="handleReset">重置</el-button>
          <el-button @click="goBack">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createProject } from '@/api/project'
// v3.1: 目录选择器组件
import DirSelector from '@/components/DirSelector.vue'

const router = useRouter()
const formRef = ref()
const submitting = ref(false)

const form = reactive({
  name: '',
  sourceType: 'local_path',
  sourcePath: ''
})

const rules = {
  name: [
    { required: true, message: '请输入项目名称', trigger: 'blur' },
    { min: 1, max: 200, message: '长度在 1 到 200 个字符', trigger: 'blur' }
  ],
  sourcePath: [
    {
      // v3.0: 仅非"无代码"时必填；v3.1: 不同来源类型附加格式校验
      validator: (rule, value, callback) => {
        if (form.sourceType === 'none') {
          callback()
          return
        }
        if (!value || !value.trim()) {
          callback(new Error(form.sourceType === 'git_url' ? '请输入 Git 地址' : '请输入项目路径'))
          return
        }
        // v3.1: Git 地址格式校验
        if (form.sourceType === 'git_url') {
          const gitPattern = /^(https?:\/\/|git@).+\.(git|com|org|net|io)/i
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

// v3.0: 切换来源类型时清空路径（避免不同类型路径串用）
watch(() => form.sourceType, (newType) => {
  form.sourcePath = ''
  // 清除该字段的校验状态
  formRef.value?.clearValidate('sourcePath')
})

// v3.1: 目录选择器回调
function handleDirSelect(path) {
  form.sourcePath = path
  // 选择后主动触发一次校验，清除可能的错误提示
  formRef.value?.validateField('sourcePath')
}

// v3.1: 重置表单
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
    submitting.value = true
    try {
      const res = await createProject({
        name: form.name,
        sourceType: form.sourceType,
        sourcePath: form.sourcePath
      })
      ElMessage.success('项目创建成功')
      router.push(`/projects/${res.data.id}`)
    } catch (e) {
      // 错误已由响应拦截器统一提示
    } finally {
      submitting.value = false
    }
  })
}

function goBack() {
  router.back()
}
</script>

<style scoped>
.project-create {
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
.form-card {
  max-width: 680px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-header-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: normal;
}
.create-form {
  margin-top: 10px;
}
.form-item-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 4px;
}
/* el-input append 内的浏览按钮去掉多余边距 */
:deep(.el-input-group__append) {
  padding: 0;
}
:deep(.el-input-group__append .el-button) {
  border: none;
}
</style>
