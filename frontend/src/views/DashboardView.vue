<template>
  <div>
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label"><el-icon><Money /></el-icon>今日净销售额</div>
        <div class="stat-value money">{{ overview?.netAmount ?? 0 }} <small>元</small></div>
        <div class="stat-sub">销售额 {{ overview?.salesAmount ?? 0 }}，退款 {{ overview?.refundAmount ?? 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label"><el-icon><Tickets /></el-icon>今日订单</div>
        <div class="stat-value">{{ overview?.orderCount ?? 0 }} <small>笔</small></div>
        <div class="stat-sub">商品 {{ overview?.itemCount ?? 0 }} 件，客单价 {{ overview?.avgOrderAmount ?? 0 }} 元</div>
      </div>
      <div class="stat-card is-ok">
        <div class="stat-label"><el-icon><TrendCharts /></el-icon>今日毛利</div>
        <div class="stat-value money">{{ overview?.grossProfit ?? 0 }} <small>元</small></div>
        <div class="stat-sub">按销售时冻结的成本价计算</div>
      </div>
      <div class="stat-card" :class="lowStock.length > 0 ? 'is-danger' : 'is-ok'">
        <div class="stat-label"><el-icon><AlarmClock /></el-icon>库存预警</div>
        <div class="stat-value" :class="{ warn: lowStock.length > 0 }">
          {{ lowStock.length }} <small>个商品</small>
        </div>
        <div class="stat-sub">低于各自补货阈值</div>
      </div>
    </div>

    <el-row :gutter="12">
      <el-col :span="14">
        <el-card shadow="never" class="card-gap">
          <template #header>
            <div class="card-header">
              <span>近 7 日净销售额</span>
              <el-button text @click="rebuildToday">重算今日汇总</el-button>
            </div>
          </template>
          <div ref="chartRef" style="height: 260px"></div>
          <div v-if="!daily.length" class="text-muted">暂无汇总数据：可点右上角"重算今日汇总"或等定时任务跑完</div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never" class="card-gap">
          <template #header>畅销商品（近 7 日）</template>
          <el-table :data="topProducts" size="small" empty-text="暂无销售数据">
            <el-table-column prop="productName" label="商品" show-overflow-tooltip />
            <el-table-column prop="quantity" label="数量" width="70" />
            <el-table-column prop="amount" label="销售额" width="90" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>库存预警（需要补货）</span>
          <el-button text type="primary" @click="router.push('/ai-draft')">用一句话录采购单</el-button>
        </div>
      </template>
      <el-table :data="lowStock" size="small" empty-text="库存都很充足">
        <el-table-column prop="name" label="商品" show-overflow-tooltip />
        <el-table-column prop="stock" label="当前库存" width="100" />
        <el-table-column prop="lowStockThreshold" label="阈值" width="80" />
        <el-table-column prop="unit" label="单位" width="80" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { inventoryApi, reportApi } from '@/api'
import type { DailyRow, LowStockItem, ReportOverview, TopProduct } from '@/types'

const router = useRouter()
const overview = ref<ReportOverview>()
const lowStock = ref<LowStockItem[]>([])
const topProducts = ref<TopProduct[]>([])
const daily = ref<DailyRow[]>([])
const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined

async function load() {
  const today = dayjs().format('YYYY-MM-DD')
  const from = dayjs().subtract(6, 'day').format('YYYY-MM-DD')
  const [overviewData, lowStockData, topData, dailyData] = await Promise.all([
    reportApi.overview(today),
    inventoryApi.lowStock(),
    reportApi.topProducts(from, today, 5),
    reportApi.daily(from, today)
  ])
  overview.value = overviewData
  lowStock.value = lowStockData
  topProducts.value = topData
  daily.value = dailyData
  renderChart()
}

function renderChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: daily.value.map((row) => row.date.slice(5)) },
    yAxis: { type: 'value' },
    series: [
      {
        name: '净销售额',
        type: 'line',
        smooth: true,
        areaStyle: {},
        data: daily.value.map((row) => row.netAmount)
      }
    ]
  })
}

async function rebuildToday() {
  await reportApi.rebuild(dayjs().format('YYYY-MM-DD'))
  ElMessage.success('已重算今日汇总')
  await load()
}

const resize = () => chart?.resize()

onMounted(() => {
  load()
  window.addEventListener('resize', resize)
})

onUnmounted(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})

watch(daily, renderChart)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
