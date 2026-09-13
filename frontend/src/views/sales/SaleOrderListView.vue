<template>
  <div>
    <el-card shadow="never" class="card-gap">
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="单号 / 客户" clearable style="width: 220px" @keyup.enter="load" />
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 160px" @change="load">
          <el-option label="已收款" value="PAID" />
          <el-option label="部分退货" value="PARTIAL_REFUNDED" />
          <el-option label="整单退货" value="REFUNDED" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
        <div class="spacer" />
        <el-button type="success" @click="router.push('/pos')">去收银台</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" empty-text="暂无订单">
        <el-table-column prop="orderNo" label="单号" width="220" />
        <el-table-column prop="customerName" label="客户" width="120" />
        <el-table-column prop="itemCount" label="商品数" width="90" />
        <el-table-column label="应收/实收" width="150">
          <template #default="{ row }">
            <span class="money">{{ row.totalAmount }} / <b>{{ row.payAmount }}</b></span>
          </template>
        </el-table-column>
        <el-table-column label="退款" width="100">
          <template #default="{ row }"><span class="money">{{ row.refundAmount }}</span></template>
        </el-table-column>
        <el-table-column prop="payMethod" label="支付" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'PAID' ? 'success' : 'warning'">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openDetail(row)">明细 / 退货</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.size"
        :current-page="query.page"
        @current-change="onPageChange"
      />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`订单 ${detail?.orderNo ?? ''}`" width="700px">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="状态">{{ detail?.statusText }}</el-descriptions-item>
        <el-descriptions-item label="应收">{{ detail?.totalAmount }}</el-descriptions-item>
        <el-descriptions-item label="实收">{{ detail?.payAmount }}</el-descriptions-item>
        <el-descriptions-item label="折扣">{{ detail?.discountAmount }}</el-descriptions-item>
        <el-descriptions-item label="已退">{{ detail?.refundAmount }}</el-descriptions-item>
        <el-descriptions-item label="支付方式">{{ detail?.payMethod }}</el-descriptions-item>
      </el-descriptions>

      <el-table :data="detail?.items ?? []" size="small" style="margin-top: 10px">
        <el-table-column prop="productName" label="商品" />
        <el-table-column prop="unitPrice" label="单价" width="90" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="refundedQuantity" label="已退" width="80" />
        <el-table-column prop="amount" label="金额" width="100" />
        <el-table-column label="本次退货" width="140">
          <template #default="{ row }">
            <el-input-number
              v-model="refundQty[row.id]"
              :min="0"
              :max="row.quantity - row.refundedQuantity"
              size="small"
              :disabled="!canRefund(row)"
            />
          </template>
        </el-table-column>
      </el-table>

      <el-input v-model="refundRemark" placeholder="退货原因（可选）" style="margin-top: 10px" />

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="auth.hasPermission('refund:create')"
          type="warning"
          :loading="saving"
          :disabled="!refundTotal"
          @click="submitRefund"
        >
          确认退货（{{ refundTotal }} 件）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { salesApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { SaleItemView, SaleView } from '@/types'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref<SaleView[]>([])
const total = ref(0)
const query = reactive({ keyword: '', status: undefined as string | undefined, page: 1, size: 10 })

const detailVisible = ref(false)
const detail = ref<SaleView>()
const refundQty = reactive<Record<number, number>>({})
const refundRemark = ref('')

const refundTotal = computed(() =>
  Object.values(refundQty).reduce((sum, value) => sum + (value || 0), 0)
)

async function load() {
  loading.value = true
  try {
    const page = await salesApi.page({
      keyword: query.keyword || undefined,
      status: query.status,
      page: query.page,
      size: query.size
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function onPageChange(page: number) {
  query.page = page
  load()
}

function canRefund(item: SaleItemView) {
  return item.quantity - item.refundedQuantity > 0
}

async function openDetail(row: SaleView) {
  detail.value = await salesApi.detail(row.id)
  Object.keys(refundQty).forEach((key) => delete refundQty[Number(key)])
  refundRemark.value = ''
  detailVisible.value = true
}

async function submitRefund() {
  if (!detail.value) return
  const items = Object.entries(refundQty)
    .filter(([, quantity]) => quantity > 0)
    .map(([itemId, quantity]) => ({ orderItemId: Number(itemId), quantity }))
  if (!items.length) return
  saving.value = true
  try {
    const updated = await salesApi.refund(detail.value.id, { items, remark: refundRemark.value || undefined })
    ElMessage.success(`退货成功，退款 ${updated.refundAmount} 元（状态：${updated.statusText}）`)
    detail.value = updated
    await load()
  } catch {
    // 超退等业务错误由拦截器提示
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.spacer {
  flex: 1;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
