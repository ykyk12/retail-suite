<template>
  <div class="expiry">
    <el-card shadow="never" class="card-gap">
      <template #header>
        <div class="card-header">
          <span>临期与批次</span>
          <div class="header-tags">
            <el-tag size="small" type="info">临期阈值 {{ summary?.alertDays ?? '—' }} 天</el-tag>
            <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
          </div>
        </div>
      </template>

      <el-row :gutter="12">
        <el-col :span="8">
          <div class="stat stat-warn">
            <div class="stat-label">临期批次（要促销）</div>
            <div class="stat-value">{{ summary?.expiringBatchCount ?? 0 }} 批 / {{ summary?.expiringQuantity ?? 0 }} 件</div>
            <div class="stat-sub">压货 {{ money(summary?.expiringAmount) }} 元（按批次成本）</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat stat-danger">
            <div class="stat-label">已过期（必须下架报损）</div>
            <div class="stat-value">{{ summary?.expiredBatchCount ?? 0 }} 批 / {{ summary?.expiredQuantity ?? 0 }} 件</div>
            <div class="stat-sub">压货 {{ money(summary?.expiredAmount) }} 元（继续卖就是食品安全事故）</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat" :class="mismatches.length ? 'stat-danger' : 'stat-ok'">
            <div class="stat-label">批次与库存不符</div>
            <div class="stat-value">{{ mismatches.length }} 个商品</div>
            <div class="stat-sub">批次数量之和 ≠ 库存总数，需盘点修正</div>
          </div>
        </el-col>
      </el-row>

      <el-alert
        v-if="!canLoss"
        class="tip"
        type="info"
        :closable="false"
        title="当前账号没有 inventory:loss 权限，可以查看但不能做报损"
        show-icon
      />
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane :label="`临期批次（${expiring.length}）`" name="expiring">
          <BatchTable :rows="expiring" show-remaining />
        </el-tab-pane>
        <el-tab-pane :label="`已过期（${expired.length}）`" name="expired">
          <BatchTable :rows="expired" show-remaining>
            <template #actions="{ row }">
              <el-button size="small" type="danger" :disabled="!canLoss" @click="openLoss(row)">下架报损</el-button>
            </template>
          </BatchTable>
        </el-tab-pane>
        <el-tab-pane :label="`批次与库存不符（${mismatches.length}）`" name="mismatch">
          <el-table :data="mismatches" size="small" border>
            <el-table-column v-for="column in mismatchColumns" :key="column" :prop="column" :label="column" min-width="140" />
            <template #empty>
              <el-empty description="批次数量与库存总数一致，账实相符" />
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="lossVisible" title="下架报损（出库并扣批次）" width="460px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="商品">{{ lossForm.productName }}</el-descriptions-item>
        <el-descriptions-item label="批次号">{{ lossForm.batchNo || '自动按近效期先出' }}</el-descriptions-item>
        <el-descriptions-item label="到期日">{{ lossForm.expiryDate || '无' }}</el-descriptions-item>
        <el-descriptions-item label="可用数量">{{ lossForm.available }}</el-descriptions-item>
      </el-descriptions>
      <el-form class="loss-form" label-width="90px">
        <el-form-item label="报损数量">
          <el-input-number v-model="lossForm.quantity" :min="1" :max="lossForm.available" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="lossForm.remark" placeholder="例：已过期待销毁 / 破损" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lossVisible = false">取消</el-button>
        <el-button type="danger" :loading="lossLoading" @click="submitLoss">确认报损</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { inventoryApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { BatchView, ExpirySummary } from '@/types'
import BatchTable from '@/components/BatchTable.vue'

const auth = useAuthStore()
const loading = ref(false)
const activeTab = ref('expiring')
const expiring = ref<BatchView[]>([])
const expired = ref<BatchView[]>([])
const mismatches = ref<Array<Record<string, unknown>>>([])
const summary = ref<ExpirySummary>()

const canLoss = computed(() => auth.hasPermission('inventory:loss'))
const mismatchColumns = computed(() => (mismatches.value.length ? Object.keys(mismatches.value[0]) : []))

const lossVisible = ref(false)
const lossLoading = ref(false)
const lossForm = reactive({
  productId: 0,
  productName: '',
  batchNo: '',
  expiryDate: '',
  available: 1,
  quantity: 1,
  remark: '已过期待销毁'
})

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  try {
    const [expiringRows, expiredRows, summaryData, mismatchRows] = await Promise.all([
      inventoryApi.expiring(),
      inventoryApi.expired(),
      inventoryApi.expirySummary(),
      inventoryApi.batchMismatch()
    ])
    expiring.value = expiringRows
    expired.value = expiredRows
    summary.value = summaryData
    mismatches.value = mismatchRows
  } finally {
    loading.value = false
  }
}

function openLoss(batch: BatchView) {
  lossForm.productId = batch.productId
  lossForm.productName = batch.productName
  lossForm.batchNo = batch.batchNo
  lossForm.expiryDate = batch.expiryDate ?? ''
  lossForm.available = batch.quantity
  lossForm.quantity = batch.quantity
  lossForm.remark = '已过期待销毁'
  lossVisible.value = true
}

async function submitLoss() {
  if (!lossForm.remark.trim()) {
    ElMessage.warning('请填写报损原因（要留痕）')
    return
  }
  lossLoading.value = true
  try {
    await inventoryApi.loss({
      productId: lossForm.productId,
      quantity: lossForm.quantity,
      batchNo: lossForm.batchNo || undefined,
      remark: lossForm.remark
    })
    ElMessage.success('报损完成：库存与该批次数量已同步扣减')
    lossVisible.value = false
    await loadAll()
  } finally {
    lossLoading.value = false
  }
}

function money(value?: number | null) {
  return value === undefined || value === null ? '0.00' : Number(value).toFixed(2)
}
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-tags {
  display: flex;
  gap: 8px;
  align-items: center;
}

.stat {
  border-radius: 8px;
  padding: 12px 14px;
  background: #f5f7fa;
  border-left: 4px solid #dcdfe6;
}

.stat-warn {
  border-left-color: #e6a23c;
}

.stat-danger {
  border-left-color: #f56c6c;
}

.stat-ok {
  border-left-color: #67c23a;
}

.stat-label {
  font-size: 13px;
  color: #606266;
}

.stat-value {
  font-size: 20px;
  font-weight: 600;
  margin: 4px 0;
}

.stat-sub {
  font-size: 12px;
  color: #909399;
}

.tip {
  margin-top: 12px;
}

.loss-form {
  margin-top: 12px;
}
</style>
