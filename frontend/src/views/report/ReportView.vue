<template>
  <div>
    <el-card shadow="never" class="card-gap">
      <div class="toolbar">
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          @change="load"
        />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button @click="rebuild">重算该区间汇总</el-button>
        <el-button :loading="exporting" @click="exportExcel">导出 Excel</el-button>
        <div class="spacer" />
        <el-tag v-if="overview" type="info" size="small">
          数据口径：报表读日汇总表，点"重算"可刷新到最新
        </el-tag>
      </div>
    </el-card>

    <div class="cards">
      <el-card shadow="never">
        <div class="metric-label">区间净销售额</div>
        <div class="metric money">{{ sum('netAmount') }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="metric-label">区间订单数</div>
        <div class="metric">{{ sum('orderCount') }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="metric-label">区间退款额</div>
        <div class="metric money">{{ sum('refundAmount') }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="metric-label">区间毛利</div>
        <div class="metric money">{{ sum('grossProfit') }}</div>
      </el-card>
    </div>

    <el-card shadow="never" class="card-gap">
      <template #header>日报明细（来自汇总表）</template>
      <el-table :data="daily" size="small" empty-text="没有数据，可点上方重算">
        <el-table-column prop="date" label="日期" width="120" />
        <el-table-column prop="orderCount" label="订单" width="80" />
        <el-table-column prop="itemCount" label="件数" width="80" />
        <el-table-column prop="salesAmount" label="销售额" width="110" />
        <el-table-column prop="refundAmount" label="退款" width="110" />
        <el-table-column prop="netAmount" label="净销售额" width="120" />
        <el-table-column prop="grossProfit" label="毛利" width="110" />
      </el-table>
    </el-card>

    <el-row :gutter="12">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>TOP 商品（按销售额）</template>
          <el-table :data="topProducts" size="small" empty-text="暂无销售">
            <el-table-column prop="productName" label="商品" show-overflow-tooltip />
            <el-table-column prop="quantity" label="数量" width="80" />
            <el-table-column prop="amount" label="销售额" width="100" />
            <el-table-column prop="grossProfit" label="毛利" width="90" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>对账（销售数量 vs 库存出库）</span>
              <el-tag v-if="reconcile" :type="reconcile.consistent ? 'success' : 'danger'" size="small">
                {{ reconcile.consistent ? '账实一致' : `${reconcile.diffs.length} 项差异` }}
              </el-tag>
            </div>
          </template>
          <div class="text-muted tip">
            两侧都由系统自动写入，正常情况下必须完全一致；出现差异说明有人绕过了收银动了库存。
          </div>
          <el-table :data="reconcile?.diffs ?? []" size="small" empty-text="没有差异">
            <el-table-column prop="productName" label="商品" />
            <el-table-column prop="soldQuantity" label="销售" width="80" />
            <el-table-column prop="flowQuantity" label="出库" width="80" />
            <el-table-column prop="diff" label="差异" width="80" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { reportApi } from '@/api'
import type { DailyRow, ReconcileResult, ReportOverview, TopProduct } from '@/types'

const range = ref<[string, string]>([
  dayjs().subtract(6, 'day').format('YYYY-MM-DD'),
  dayjs().format('YYYY-MM-DD')
])
const daily = ref<DailyRow[]>([])
const topProducts = ref<TopProduct[]>([])
const reconcile = ref<ReconcileResult>()
const overview = ref<ReportOverview>()
const exporting = ref(false)

function sum(field: keyof DailyRow) {
  return daily.value.reduce((total, row) => total + Number(row[field] || 0), 0).toFixed(2)
}

async function load() {
  const [from, to] = range.value
  const [dailyData, topData, reconcileData, overviewData] = await Promise.all([
    reportApi.daily(from, to),
    reportApi.topProducts(from, to, 10),
    reportApi.reconcile(to),
    reportApi.overview(to)
  ])
  daily.value = dailyData
  topProducts.value = topData
  reconcile.value = reconcileData
  overview.value = overviewData
}

async function rebuild() {
  const [from, to] = range.value
  let day = dayjs(from)
  let count = 0
  while (!day.isAfter(dayjs(to), 'day')) {
    await reportApi.rebuild(day.format('YYYY-MM-DD'))
    day = day.add(1, 'day')
    count += 1
  }
  ElMessage.success(`已重算 ${count} 天汇总`)
  await load()
}

async function exportExcel() {
  const [from, to] = range.value
  exporting.value = true
  try {
    await reportApi.exportDaily(from, to)
    ElMessage.success('导出已开始下载')
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}

.metric-label {
  color: #909399;
  font-size: 13px;
}

.metric {
  font-size: 22px;
  font-weight: 700;
  margin-top: 6px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tip {
  font-size: 12px;
  margin-bottom: 8px;
}
</style>
