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
  /** 保质期天数；不填表示不追踪保质期（日用品） */
  shelfLifeDays?: number
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
  /** 生产日期与保质期：确认入库时用它推算批次到期日 */
  productionDate?: string
  shelfLifeDays?: number
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

// ---------------------------------------------------------------------------
// 批次与保质期（M1）
// ---------------------------------------------------------------------------

export interface BatchView {
  id: number
  batchNo: string
  productId: number
  productName: string
  productionDate?: string
  expiryDate?: string
  quantity: number
  costPrice: number
  remark?: string
  /** 距到期天数；无到期日时为 null */
  daysToExpiry?: number | null
  expired: boolean
}

export interface ExpirySummary {
  alertDays: number
  expiringBatchCount: number
  expiringQuantity: number
  expiringAmount: number
  expiredBatchCount: number
  expiredQuantity: number
  expiredAmount: number
}

// ---------------------------------------------------------------------------
// 管家 Agent 与巡检日报（M2/M3）
// ---------------------------------------------------------------------------

/** 一次工具调用的轨迹（前端用它展示"它到底查了什么"） */
export interface AgentToolCallStep {
  round: number
  tool: string
  args: Record<string, unknown>
  success: boolean
  observation: string
  elapsedMs: number
  /** 写操作：需要人工确认（当前只会生成草稿） */
  requiresConfirmation: boolean
}

export interface AgentChatResponse {
  sessionId: string
  answer: string
  /** LLM（大模型作答）或 RULE（本地规则兜底） */
  source: string
  toolsUsed: string[]
  steps: AgentToolCallStep[]
  cards: Array<Record<string, unknown>>
}

export interface AgentToolInfo {
  name: string
  description: string
  parameters: Record<string, string>
  permission: string
  readOnly: boolean
  requiresConfirmation: boolean
}

/** 巡检发现上的可执行动作 */
export interface StewardAction {
  type: string
  label: string
  executable: boolean
  hint: string
  payload: Record<string, unknown>
}

export interface StewardFinding {
  code: string
  /** HIGH 需立即处理 / WARN 需要关注 / INFO 提示 */
  severity: string
  title: string
  detail: string
  metrics: Record<string, unknown>
  actions: StewardAction[]
}

export interface StewardReport {
  id: number
  reportDate: string
  source: string
  generatedAt: string
  headline: string
  findingCount: number
  highCount: number
  findings: StewardFinding[]
}

export interface StewardActionResult {
  actionType: string
  message: string
  data: Record<string, unknown>
}
