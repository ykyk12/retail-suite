import { http } from './http'
import type {
  AgentChatResponse,
  AgentToolInfo,
  AskResponse,
  BatchView,
  CategoryView,
  DailyRow,
  DraftView,
  ExpirySummary,
  InventoryFlowView,
  LoginResponse,
  LowStockItem,
  PageResult,
  ProductView,
  PurchaseView,
  ReconcileResult,
  ReportOverview,
  SaleView,
  StewardActionResult,
  StewardReport,
  TopProduct,
  UserView
} from '@/types'

export const authApi = {
  login: (username: string, password: string) =>
    http.post<LoginResponse>('/auth/login', { username, password }),
  me: () => http.get<UserView>('/auth/me'),
  logout: () => http.post<void>('/auth/logout')
}

export const categoryApi = {
  list: () => http.get<CategoryView[]>('/categories'),
  create: (data: { name: string; sortNo?: number }) => http.post<CategoryView>('/categories', data),
  update: (id: number, data: { name: string; sortNo?: number }) => http.put<void>(`/categories/${id}`, data),
  remove: (id: number) => http.delete<void>(`/categories/${id}`)
}

export const productApi = {
  page: (params: {
    keyword?: string
    categoryId?: number
    lowStockOnly?: boolean
    page?: number
    size?: number
  }) => http.get<PageResult<ProductView>>('/products', params),
  detail: (id: number) => http.get<ProductView>(`/products/${id}`),
  byBarcode: (barcode: string) => http.get<ProductView>(`/products/barcode/${barcode}`),
  create: (data: Record<string, unknown>) => http.post<ProductView>('/products', data),
  update: (id: number, data: Record<string, unknown>) => http.put<ProductView>(`/products/${id}`, data),
  toggleStatus: (id: number, status: number) => http.patch<void>(`/products/${id}/status`, { status })
}

export const inventoryApi = {
  lowStock: () => http.get<LowStockItem[]>('/inventory/low-stock'),
  flows: (productId: number, limit = 20) => http.get<InventoryFlowView[]>(`/inventory/flows/${productId}`, { limit }),
  adjust: (data: { productId: number; delta: number; remark: string }) => http.post<InventoryFlowView>('/inventory/adjust', data),
  /** 批次台账：这一批什么时候到期、还剩多少、成本多少 */
  batches: (productId: number) => http.get<BatchView[]>(`/inventory/batches/${productId}`),
  expiring: (days?: number) =>
    http.get<BatchView[]>('/inventory/expiring', days ? { days } : undefined),
  expired: () => http.get<BatchView[]>('/inventory/expired'),
  expirySummary: (days?: number) =>
    http.get<ExpirySummary>('/inventory/expiry-summary', days ? { days } : undefined),
  /** 批次数量之和与库存总数对不上的商品（数据自查） */
  batchMismatch: () => http.get<Array<Record<string, unknown>>>('/inventory/batch-mismatch'),
  /** 报损出库（过期/破损下架）：按近效期先出扣批次 */
  loss: (data: { productId: number; quantity: number; batchNo?: string; remark: string }) =>
    http.post<InventoryFlowView>('/inventory/loss', data)
}

export const purchaseApi = {
  page: (params: { status?: string; page?: number; size?: number }) =>
    http.get<PageResult<PurchaseView>>('/purchases', params),
  detail: (id: number) => http.get<PurchaseView>(`/purchases/${id}`),
  create: (data: { supplierName?: string; remark?: string; items: Array<{ productId: number; quantity: number; unitCost: number }> }) =>
    http.post<PurchaseView>('/purchases', data),
  confirm: (id: number) => http.post<PurchaseView>(`/purchases/${id}/confirm`),
  cancel: (id: number) => http.post<void>(`/purchases/${id}/cancel`)
}

export const salesApi = {
  checkout: (data: {
    requestId: string
    customerName?: string
    items: Array<{ productId: number; quantity: number; unitPrice?: number }>
    discountAmount?: number
    payMethod: string
    remark?: string
  }) => http.post<SaleView>('/sales/checkout', data),
  page: (params: { status?: string; keyword?: string; page?: number; size?: number }) =>
    http.get<PageResult<SaleView>>('/sales', params),
  detail: (id: number) => http.get<SaleView>(`/sales/${id}`),
  refund: (id: number, data: { items: Array<{ orderItemId: number; quantity: number }>; remark?: string }) =>
    http.post<SaleView>(`/sales/${id}/refund`, data)
}

export const reportApi = {
  overview: (date?: string) => http.get<ReportOverview>('/reports/overview', date ? { date } : undefined),
  daily: (from: string, to: string) => http.get<DailyRow[]>('/reports/daily', { from, to }),
  topProducts: (from: string, to: string, limit = 10) =>
    http.get<TopProduct[]>('/reports/top-products', { from, to, limit }),
  reconcile: (date?: string) => http.get<ReconcileResult>('/reports/reconcile', date ? { date } : undefined),
  rebuild: (date: string) => http.post<DailyRow>(`/reports/daily/${date}/rebuild`),
  exportDaily: (from: string, to: string) =>
    http.download('/reports/export/daily', { from, to }, `日报_${from}_${to}.xlsx`)
}

export const aiApi = {
  capabilities: () => http.get<Record<string, unknown>>('/ai/capabilities'),
  createDraft: (text: string, type = 'PURCHASE') => http.post<DraftView>('/ai/drafts', { text, type }),
  drafts: (limit = 10) => http.get<DraftView[]>('/ai/drafts', { limit }),
  confirm: (id: number, data?: { supplierName?: string; remark?: string }) =>
    http.post<DraftView>(`/ai/drafts/${id}/confirm`, data ?? {}),
  discard: (id: number) => http.post<void>(`/ai/drafts/${id}/discard`),
  /** 兼容旧路径（后端已由管家 Agent 运行时接管，前端新代码请用 agentApi.chat） */
  ask: (question: string) => http.post<AskResponse>('/ai/assistant/ask', { question })
}

/** 管家 Agent：多轮会话 + 工具轨迹；写操作只会产出草稿 */
export const agentApi = {
  chat: (question: string, sessionId?: string) =>
    http.post<AgentChatResponse>('/agent/chat', sessionId ? { question, sessionId } : { question }),
  tools: () => http.get<AgentToolInfo[]>('/agent/tools'),
  /** 开新会话：不带 sessionId 的请求是"接着上次聊"，所以必须让服务端清上下文 */
  resetSession: () => http.post<{ sessionId: string }>('/agent/session/reset')
}

/** 管家巡检日报：主动发现问题 + 一键转采购草稿 */
export const stewardApi = {
  inspect: () => http.post<StewardReport>('/steward/inspect'),
  latest: () => http.get<StewardReport>('/steward/reports/latest'),
  reports: (limit = 10) => http.get<StewardReport[]>('/steward/reports', { limit }),
  detail: (id: number) => http.get<StewardReport>(`/steward/reports/${id}`),
  executeAction: (id: number, code: string, actionType?: string) =>
    http.post<StewardActionResult>(`/steward/reports/${id}/findings/${code}/actions`,
      actionType ? { actionType } : {})
}
