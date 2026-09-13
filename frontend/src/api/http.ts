import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types'

const TOKEN_KEY = 'retail-suite-token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000
})

instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

instance.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    const message = error?.response?.data?.message
    if (status === 401) {
      // 令牌过期或未登录：清掉本地状态并回登录页（避免"一直转圈但没数据"）
      setToken(null)
      if (!location.hash.includes('/login')) {
        location.hash = '#/login'
      }
      ElMessage.warning(message || '登录已过期，请重新登录')
    } else if (status === 403) {
      ElMessage.error(message || '没有权限执行该操作')
    } else {
      ElMessage.error(message || '请求失败，请稍后重试')
    }
    return Promise.reject(error)
  }
)

/** 统一拆包：后端返回 { success, code, message, data }，业务代码只关心 data */
async function unwrap<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await instance.request<ApiResponse<T>>(config)
  const body = response.data
  if (body && body.success === false) {
    throw new Error(body.message || '请求失败')
  }
  return body.data
}

export const http = {
  get: <T>(url: string, params?: Record<string, unknown>) => unwrap<T>({ url, method: 'GET', params }),
  post: <T>(url: string, data?: unknown) => unwrap<T>({ url, method: 'POST', data }),
  put: <T>(url: string, data?: unknown) => unwrap<T>({ url, method: 'PUT', data }),
  patch: <T>(url: string, params?: Record<string, unknown>) =>
    unwrap<T>({ url, method: 'PATCH', params }),
  delete: <T>(url: string) => unwrap<T>({ url, method: 'DELETE' }),
  /** 导出文件：需要带 Authorization，所以不能用 window.open，要用 axios 拿 blob */
  async download(url: string, params: Record<string, unknown>, fileName: string) {
    const response = await instance.get(url, { params, responseType: 'blob' })
    const blob = new Blob([response.data])
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = fileName
    link.click()
    URL.revokeObjectURL(link.href)
  }
}

export default instance
