<template>
  <div class="finding-metrics">
    <div v-if="scalars.length" class="scalars">
      <el-tag v-for="item in scalars" :key="item.key" size="small" effect="plain">
        {{ label(item.key) }}：{{ format(item.key, item.value) }}
      </el-tag>
    </div>

    <el-table v-if="items.length" :data="items" size="small" border max-height="280">
      <el-table-column v-for="column in columns" :key="column" :prop="column" min-width="120">
        <template #header>{{ label(column) }}</template>
        <template #default="{ row }">{{ format(column, row[column]) }}</template>
      </el-table-column>
    </el-table>

    <div v-if="!scalars.length && !items.length" class="muted">（本次没有可展示的结构化数据）</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 把后端返回的 metrics 渲染成人能看的卡片。
 *
 * 为什么做成通用组件：管家对话的 cards 与巡检日报的 findings.metrics 是同一套结构
 * （标量 + items 明细数组），两边都用它渲染，避免各写一份、字段名改了只改一处。
 */
const props = defineProps<{ metrics: Record<string, unknown> }>()

/** 字段名 → 中文标签：报表里出现英文 key 会显得很敷衍 */
const LABELS: Record<string, string> = {
  productName: '商品',
  product: '商品',
  batchNo: '批次号',
  expiryDate: '到期日',
  productionDate: '生产日期',
  daysToExpiry: '剩余天数',
  quantity: '数量',
  costAmount: '压货金额(元)',
  stock: '当前库存',
  threshold: '预警阈值',
  soldIn7d: '近7天销量',
  soldQuantity: '销量',
  soldAmount: '销售额(元)',
  soldAmount30d: '近30天销售额',
  soldQuantity30d: '近30天销量',
  dailySales: '日均销量',
  suggestQuantity: '建议补货',
  unitCost: '进价(元)',
  purchasePrice: '进价(元)',
  salePrice: '售价(元)',
  grossProfit: '毛利(元)',
  grossMarginPercent: '毛利率(%)',
  marginPercent: '毛利率(%)',
  estimatedAmount: '预估金额(元)',
  stockValue: '压货金额(元)',
  tiedUpAmount: '压货合计(元)',
  daysSinceLastSale: '距上次售出(天)',
  lastSaleDate: '最后售出',
  lastSaleAt: '最后售出',
  daysOfCoverLeft: '还能卖(天)',
  days: '统计天数',
  count: '条目数',
  batchCount: '批次数',
  itemCount: '明细行数',
  totalAmount: '金额合计(元)',
  flowQuantity: '出库数量',
  diff: '差异',
  omittedCount: '未列出条数',
  thresholdPercent: '阈值(%)',
  alertDays: '预警天数',
  hasLoss: '含负毛利',
  requiresManualConfirm: '需人工确认',
  status: '状态',
  orderNo: '单据号'
}

/** 金额类字段：统一保留两位 */
const MONEY_KEYS = new Set([
  'costAmount', 'soldAmount', 'grossProfit', 'estimatedAmount', 'stockValue',
  'tiedUpAmount', 'totalAmount', 'unitCost', 'purchasePrice', 'salePrice',
  'soldAmount30d', 'amount'
])

const metricEntries = computed(() => Object.entries(props.metrics ?? {}))
const items = computed<Array<Record<string, unknown>>>(() => {
  const raw = props.metrics?.items
  return Array.isArray(raw) ? (raw as Array<Record<string, unknown>>) : []
})
const scalars = computed(() =>
  metricEntries.value
    .filter(([key, value]) => key !== 'items' && !isComplex(value))
    .map(([key, value]) => ({ key, value }))
)
const columns = computed(() => (items.value.length ? Object.keys(items.value[0]) : []))

function isComplex(value: unknown) {
  return Array.isArray(value) || (typeof value === 'object' && value !== null)
}

function label(key: string) {
  return LABELS[key] ?? key
}

function format(key: string, value: unknown) {
  if (value === null || value === undefined) {
    return '—'
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否'
  }
  if (typeof value === 'number') {
    return MONEY_KEYS.has(key) ? value.toFixed(2) : String(value)
  }
  return String(value)
}
</script>

<style scoped>
.finding-metrics {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.scalars {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.muted {
  color: #909399;
  font-size: 12px;
}
</style>
