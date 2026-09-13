import { http } from './http'
import type {
  AskResponse,
  CategoryView,
  DailyRow,
  DraftView,
  InventoryFlowView,
  LoginResponse,
  LowStockItem,
  PageResult,
  ProductView,
  PurchaseView,
  ReconcileResult,
  ReportOverview,
  SaleView,
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
  adjust: (data: { productId: number; delta: number; remark: string }) => http.post<InventoryFlowView>('/inventory/adjust', data)
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
  ask: (question: string) => http.post<AskResponse>('/ai/assistant/ask', { question })
}
