/** 后端统一响应体（与 com.retailsuite.common.ApiResponse 对应） */
export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T
  traceId?: string
}

export interface PageResult<T> {
  total: number
  page: number
  size: number
  records: T[]
}

export interface UserView {
  id: number
  storeId: number
  username: string
  realName?: string
  roles: string[]
  permissions: string[]
}

export interface LoginResponse {
  token: string
  expiresInSeconds: number
  user: UserView
}

export interface CategoryView {
  id: number
  name: string
  sortNo: number
  status: number
}

export interface ProductView {
  id: number
  name: string
  categoryId?: number
  categoryName?: string
  barcode?: string
  spec?: string
  unit?: string
  purchasePrice: number
  salePrice: number
  stock: number
  lowStockThreshold: number
  lowStock: boolean
  status: number
  updatedAt?: string
}

export interface InventoryFlowView {
  id: number
  productId: number
  productName?: string
  type: string
  quantity: number
  beforeStock: number
  afterStock: number
  refType?: string
  refNo?: string
  remark?: string
  createdAt?: string
}

export interface LowStockItem {
  productId: number
  name: string
  barcode?: string
  stock: number
  lowStockThreshold: number
  unit?: string
}

export interface PurchaseItemView {
  id: number
  productId: number
  productName?: string
  quantity: number
  unitCost: number
  amount: number
}

export interface PurchaseView {
  id: number
  orderNo: string
  supplierName?: string
  itemCount: number
  totalAmount: number
  status: string
  statusText: string
  remark?: string
  confirmedAt?: string
  createdAt?: string
  items: PurchaseItemView[]
}

export interface SaleItemView {
  id: number
  productId: number
  productName?: string
  barcode?: string
  quantity: number
  refundedQuantity: number
  unitPrice: number
  amount: number
}

export interface SaleView {
  id: number
  orderNo: string
  requestId: string
  customerName?: string
  itemCount: number
  totalAmount: number
  discountAmount: number
  payAmount: number
  refundAmount: number
  payMethod: string
  status: string
  statusText: string
  createdAt?: string
  duplicated: boolean
  items: SaleItemView[]
}

export interface ReportOverview {
  date: string
  orderCount: number
  itemCount: number
  salesAmount: number
  refundAmount: number
  netAmount: number
  grossProfit: number
  avgOrderAmount: number
}

export interface DailyRow {
  date: string
  orderCount: number
  itemCount: number
  salesAmount: number
  refundAmount: number
  netAmount: number
  grossProfit: number
}

export interface TopProduct {
  productId: number
  productName: string
  quantity: number
  amount: number
  grossProfit: number
}

export interface ReconcileRow {
  productId: number
  productName: string
  soldQuantity: number
  flowQuantity: number
  diff: number
}

export interface ReconcileResult {
  date: string
  consistent: boolean
  checkedProducts: number
  diffs: ReconcileRow[]
}

export interface DraftItemView {
  rawText: string
  productKeyword?: string
  productId?: number
  productName?: string
  barcode?: string
  quantity?: number
  price?: number
  resolved: boolean
  note: string
}

export interface DraftView {
  id: number
  draftType: string
  status: string
  source: string
  rawText: string
  items: DraftItemView[]
  hasUnresolved: boolean
  message: string
  createdRefNo?: string
}

export interface AskResponse {
  answer: string
  source: string
  toolsUsed: string[]
  steps: string[]
}
