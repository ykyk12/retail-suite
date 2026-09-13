<template>
  <el-container class="layout">
    <el-aside width="224px" class="aside">
      <div class="brand">
        <div class="brand-mark"><el-icon size="18"><Shop /></el-icon></div>
        <div class="brand-text">
          <div class="brand-name">云小店</div>
          <div class="brand-sub">零售管家 · 进销存</div>
        </div>
      </div>
      <el-menu :default-active="activePath" router class="menu">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
      <div class="aside-foot">
        <div class="foot-row">
          <span class="foot-label">门店</span>
          <span class="foot-value">#{{ auth.user?.storeId ?? '-' }}</span>
        </div>
        <div class="foot-row">
          <span class="foot-label">账号</span>
          <span class="foot-value">{{ auth.displayName }}</span>
        </div>
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <span class="page-name">{{ currentTitle }}</span>
          <el-tag v-for="role in auth.user?.roles ?? []" :key="role" size="small" effect="light">
            {{ role }}
          </el-tag>
        </div>
        <div class="header-right">
          <el-button text @click="refreshUser">刷新权限</el-button>
          <el-dropdown @command="onCommand">
            <span class="user">
              <span class="avatar">{{ (auth.displayName || '?').slice(0, 1) }}</span>
              {{ auth.displayName }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

interface MenuItem {
  path: string
  title: string
  icon: string
}

/** 菜单项来自路由表自己声明的权限：没权限的菜单直接不显示（后端仍会独立校验） */
const allMenus: MenuItem[] = [
  { path: '/dashboard', title: '经营看板', icon: 'DataLine' },
  { path: '/pos', title: '收银台', icon: 'ShoppingCart' },
  { path: '/products', title: '商品管理', icon: 'Goods' },
  { path: '/inventory', title: '库存管理', icon: 'Box' },
  { path: '/expiry', title: '临期与批次', icon: 'AlarmClock' },
  { path: '/purchases', title: '采购进货', icon: 'Van' },
  { path: '/sales', title: '订单查询', icon: 'Tickets' },
  { path: '/reports', title: '报表对账', icon: 'TrendCharts' },
  { path: '/steward-report', title: '管家日报', icon: 'Bell' },
  { path: '/ai-draft', title: 'AI 录单', icon: 'MagicStick' },
  { path: '/assistant', title: '管家对话', icon: 'ChatDotRound' }
]

const permissionOf = (path: string): string | undefined =>
  (router.getRoutes().find((item) => item.path === path)?.meta?.permission as string) ?? undefined

const menus = computed(() =>
  allMenus.filter((item) => {
    const permission = permissionOf(item.path)
    return !permission || auth.hasPermission(permission)
  })
)

const activePath = computed(() => route.path)
const currentTitle = computed(() => (route.meta.title as string) ?? '云小店')

async function refreshUser() {
  try {
    await auth.refreshUser()
    ElMessage.success('权限已刷新')
  } catch {
    // 拦截器已提示
  }
}

async function onCommand(command: string) {
  if (command !== 'logout') return
  await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  await auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #161f33 0%, #1d2740 100%);
  color: #fff;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--brand) 0%, #7aa2ff 100%);
  color: #fff;
  box-shadow: 0 6px 16px rgba(47, 107, 255, 0.35);
}

.brand-name {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: 0.5px;
}

.brand-sub {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.55);
  margin-top: 2px;
}

.menu {
  flex: 1;
  border-right: none;
  background: transparent;
  padding: 10px 8px;
  overflow-y: auto;
}

.menu :deep(.el-menu-item) {
  color: rgba(255, 255, 255, 0.68);
  height: 42px;
  line-height: 42px;
  margin-bottom: 4px;
  border-radius: 10px;
}

.menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: linear-gradient(90deg, rgba(47, 107, 255, 0.95) 0%, rgba(47, 107, 255, 0.65) 100%);
  box-shadow: 0 6px 16px rgba(47, 107, 255, 0.28);
}

.aside-foot {
  padding: 12px 16px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 12px;
}

.foot-row {
  display: flex;
  justify-content: space-between;
  color: rgba(255, 255, 255, 0.6);
  line-height: 1.9;
}

.foot-value {
  color: rgba(255, 255, 255, 0.92);
  font-weight: 500;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  box-shadow: 0 1px 2px rgba(20, 32, 60, 0.03);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-name {
  font-size: 16px;
  font-weight: 650;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 999px;
  transition: background 0.16s ease;
}

.user:hover {
  background: var(--brand-weak);
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--brand-weak);
  color: var(--brand-strong);
  font-weight: 600;
  font-size: 13px;
}

.main {
  padding: 18px 20px 28px;
  background: var(--bg);
}
</style>
