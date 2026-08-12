<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <div class="brand-logo">AI</div>
        <h1 class="brand-title">创建账号</h1>
        <p class="brand-desc">注册后即可使用 AI 用例生成系统</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleRegister">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3-32 位字母/数字" size="large" clearable />
        </el-form-item>
        <el-form-item label="昵称" prop="displayName">
          <el-input v-model="form.displayName" placeholder="选填" size="large" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="8-64 位，包含字母和数字"
            size="large"
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            placeholder="再次输入密码"
            size="large"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="auth-submit" :loading="loading" @click="handleRegister">
          注 册
        </el-button>
      </el-form>

      <div class="auth-footer">
        已有账号？
        <router-link to="/login" class="auth-link">去登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  displayName: '',
  password: '',
  confirmPassword: ''
})

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '长度 3-32 位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/,
      message: '密码需 8-64 位且包含字母和数字',
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

async function handleRegister() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await authStore.register({
        username: form.username.trim(),
        password: form.password,
        displayName: form.displayName.trim()
      })
      ElMessage.success('注册成功，已自动登录')
      router.push('/projects')
    } catch {
      // 错误已由响应拦截器统一提示
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped lang="scss">
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-lg);
  background:
    radial-gradient(900px 420px at 15% 0%, rgba(124, 92, 255, 0.12), transparent 55%),
    radial-gradient(900px 420px at 85% 100%, rgba(76, 111, 255, 0.12), transparent 55%);
}

.auth-card {
  width: 100%;
  max-width: 420px;
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-2xl);
  box-shadow: var(--shadow-lg);
  padding: var(--space-2xl) var(--space-xl);
}

.auth-brand {
  text-align: center;
  margin-bottom: var(--space-lg);

  .brand-logo {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 52px;
    height: 52px;
    border-radius: 14px;
    background: var(--brand-gradient);
    color: #fff;
    font-size: 22px;
    font-weight: 800;
    box-shadow: 0 8px 20px rgba(79, 70, 229, 0.35);
  }

  .brand-title {
    font-size: 20px;
    font-weight: 700;
    color: var(--text-primary);
    margin: 14px 0 4px;
  }

  .brand-desc {
    font-size: 13px;
    color: var(--text-tertiary);
    margin: 0;
  }
}

.auth-submit {
  width: 100%;
  margin-top: 4px;
}

.auth-footer {
  margin-top: var(--space-md);
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary);

  .auth-link {
    color: var(--brand-primary);
    font-weight: 600;
  }
}
</style>
