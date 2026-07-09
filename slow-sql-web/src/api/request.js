import axios from 'axios'
import { ElMessage } from 'element-plus'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

service.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (!error.response) {
      ElMessage.error('网络连接失败，请检查后端服务是否启动')
    } else {
      const { status, data } = error.response
      if (status >= 500) ElMessage.error(`服务器错误 (${status})`)
      else if (status === 429) ElMessage.warning('请求过于频繁，请稍后重试')
      else if (status === 400) ElMessage.warning(data?.error || '请求参数有误')
    }
    return Promise.reject(error)
  }
)

export default service
