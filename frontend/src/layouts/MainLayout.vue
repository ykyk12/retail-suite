<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">
        <el-icon size="20"><Shop /></el-icon>
        <span>云小店</span>
      </div>
      <el-menu :default-active="activePath" router class="menu">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <span class="page-name">{{ currentTitle }}</span>
          <el-tag v-if="auth.user" size="small" type="info">门店 #{{ auth.user.storeId }}</el-tag>
          <el-tag v-for="role in auth.user?.roles ?? []" :key="role" size="small">{{ role }}</el-tag>
        </div>
        <div class="header-right">
          <el-button text @click="refreshUser">刷新权限</el-button>
          <el-dropdown @command="onCommand">
            <span class="user">
              <el-icon><UserFilled /></el-icon>
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

      <el-main>
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
  { path: '/purchases', title: '采购进货', icon: 'Van' },
  { path: '/sales', title: '订单查询', icon: 'Tickets' },
  { path: '/reports', title: '报表对账', icon: 'TrendCharts' },
  { path: '/ai-draft', title: 'AI 录单', icon: 'MagicStick' },
  { path: '/assistant', title: '经营助手', icon: 'ChatDotRound' }
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
  background: #1f2d3d;
  color: #fff;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px;
  font-size: 16px;
  font-weight: 600;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.menu {
  border-right: none;
  background: transparent;
}

.menu :deep(.el-menu-item) {
  color: #c0c4cc;
}

.menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: #409eff;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-name {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}
</style>
