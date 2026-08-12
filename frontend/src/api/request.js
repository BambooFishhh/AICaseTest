import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器
request.interceptors.request.use(
  (config) => {
    // v4.0: 自动携带 Bearer token
    const token = localStorage.getItem('aicase-token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 兼容二进制流（文件下载等）
    if (response.config.responseType === 'blob' || response.config.responseType === 'arraybuffer') {
      return res
    }
    // 业务成功：code === 0 或 2xx
    if (res && (res.code === 0 || (res.code >= 200 && res.code < 300))) {
      return { code: res.code, message: res.message, data: res.data }
    }
    // 业务失败
    const message = (res && res.message) || '请求失败'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  },
  (error) => {
    let message = '网络异常，请稍后重试'
    if (error.response) {
      const status = error.response.status
      const respData = error.response.data
      // v4.0: 401 → 清理登录态并跳转登录页（登录/注册页除外）
      if (status === 401) {
        localStorage.removeItem('aicase-token')
        localStorage.removeItem('aicase-user')
        const path = window.location.pathname
        if (path !== '/login' && path !== '/register') {
          window.location.href = `/login?redirect=${encodeURIComponent(path + window.location.search)}`
        }
      }
      if (respData && respData.message) {
        message = respData.message
      } else {
        switch (status) {
          case 400:
            message = '请求参数错误'
            break
          case 401:
            message = '未授权，请重新登录'
            break
          case 403:
            message = '拒绝访问'
            break
          case 404:
            message = '请求资源不存在'
            break
          case 500:
            message = '服务器内部错误'
            break
          case 502:
            message = '网关错误'
            break
          case 503:
            message = '服务暂时不可用'
            break
          case 504:
            message = '请求超时'
            break
          default:
            message = `请求失败，状态码：${status}`
        }
      }
    } else if (error.code === 'ECONNABORTED') {
      message = '请求超时，请稍后重试'
    } else if (error.message) {
      message = error.message
    }
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default request
