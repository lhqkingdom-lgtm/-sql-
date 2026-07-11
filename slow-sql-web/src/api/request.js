import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

// Response interceptor — unwrap data
http.interceptors.response.use(
  (resp) => resp.data,
  (err) => {
    const msg = err.response?.data?.error || err.message || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

export default http
