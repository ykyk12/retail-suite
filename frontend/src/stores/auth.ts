import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { getToken, setToken } from '@/api/http'
import type { UserView } from '@/types'

const USER_KEY = 'retail-suite-user'

/**
 * 登录态与权限。
 * 权限码由后端签发在令牌里、并在 /auth/me 返回，前端只做"按钮/菜单是否显示"，
 * 真正的鉴权始终在后端（前端隐藏按钮不等于安全，这点必须清楚）。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken() as string | null,
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null') as UserView | null
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    permissions: (state) => state.user?.permissions ?? [],
    displayName: (state) => state.user?.realName || state.user?.username || '',
    isAdmin: (state) => (state.user?.roles ?? []).includes('ADMIN')
  },
  actions: {
    hasPermission(code: string) {
      return this.permissions.includes(code)
    },
    async login(username: string, password: string) {
      const result = await authApi.login(username, password)
      this.token = result.token
      this.user = result.user
      setToken(result.token)
      localStorage.setItem(USER_KEY, JSON.stringify(result.user))
    },
    async refreshUser() {
      if (!this.token) return
      const user = await authApi.me()
      this.user = user
      localStorage.setItem(USER_KEY, JSON.stringify(user))
    },
    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 退出接口失败也要清本地状态，否则用户会卡在"看起来还登录着"
      }
      this.token = null
      this.user = null
      setToken(null)
      localStorage.removeItem(USER_KEY)
    }
  }
})
