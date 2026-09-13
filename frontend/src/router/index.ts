import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import MainLayout from '@/layouts/MainLayout.vue'

/**
 * 路由表。
 * meta.permission 与后端 @RequiresPermission 一一对应：
 * 前端用它隐藏菜单与拦截跳转，后端仍然独立校验（前端拦截只是体验优化）。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '经营看板', icon: 'DataLine', permission: 'report:read' }
      },
      {
        path: 'pos',
        name: 'pos',
        component: () => import('@/views/pos/PosView.vue'),
        meta: { title: '收银台', icon: 'ShoppingCart', permission: 'sale:create' }
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/product/ProductListView.vue'),
        meta: { title: '商品管理', icon: 'Goods', permission: 'product:read' }
      },
      {
        path: 'inventory',
        name: 'inventory',
        component: () => import('@/views/inventory/InventoryView.vue'),
        meta: { title: '库存管理', icon: 'Box', permission: 'inventory:read' }
      },
      {
        path: 'expiry',
        name: 'expiry',
        component: () => import('@/views/inventory/ExpiryBatchesView.vue'),
        meta: { title: '临期与批次', icon: 'AlarmClock', permission: 'inventory:read' }
      },
      {
        path: 'purchases',
        name: 'purchases',
        component: () => import('@/views/purchase/PurchaseListView.vue'),
        meta: { title: '采购进货', icon: 'Van', permission: 'purchase:read' }
      },
      {
        path: 'sales',
        name: 'sales',
        component: () => import('@/views/sales/SaleOrderListView.vue'),
        meta: { title: '订单查询', icon: 'Tickets', permission: 'sale:read' }
      },
      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/report/ReportView.vue'),
        meta: { title: '报表对账', icon: 'TrendCharts', permission: 'report:read' }
      },
      {
        path: 'steward-report',
        name: 'steward-report',
        component: () => import('@/views/steward/StewardReportView.vue'),
        meta: { title: '管家日报', icon: 'Bell', permission: 'report:read' }
      },
      {
        path: 'ai-draft',
        name: 'ai-draft',
        component: () => import('@/views/ai/AiDraftView.vue'),
        meta: { title: 'AI 录单', icon: 'MagicStick', permission: 'ai:use' }
      },
      {
        path: 'assistant',
        name: 'assistant',
        component: () => import('@/views/ai/StewardChatView.vue'),
        meta: { title: '管家对话', icon: 'ChatDotRound', permission: 'ai:use' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    return true
  }
  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  const permission = to.meta.permission as string | undefined
  if (permission && !auth.hasPermission(permission)) {
    ElMessage.warning(`当前账号没有「${to.meta.title}」权限`)
    return { path: '/dashboard' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 云小店` : '云小店'
})

export default router
