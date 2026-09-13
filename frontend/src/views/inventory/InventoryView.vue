<template>
  <div>
    <el-card shadow="never" class="card-gap">
      <template #header>
        <div class="card-header">
          <span>库存预警（低于各自补货阈值）</span>
          <el-tag :type="lowStock.length ? 'warning' : 'success'" size="small">
            {{ lowStock.length ? `${lowStock.length} 个商品需要补货` : '库存正常' }}
          </el-tag>
        </div>
      </template>
      <el-table :data="lowStock" size="small" empty-text="没有需要补货的商品">
        <el-table-column prop="name" label="商品" show-overflow-tooltip />
        <el-table-column prop="barcode" label="条码" width="150" />
        <el-table-column label="当前/阈值" width="140">
          <template #default="{ row }">
            <b class="warn">{{ row.stock }}</b> / {{ row.lowStockThreshold }}
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column width="140">
          <template #default>
            <el-button text type="primary" @click="goAiDraft">一句话补货</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>库存流水（回答"库存为什么变了"）</span>
          <div class="picker">
            <el-select
              v-model="selectedProductId"
              filterable
              remote
              clearable
              placeholder="搜索商品后查看流水"
              :remote-method="searchProducts"
              :loading="searching"
              style="width: 260px"
              @change="loadFlows"
            >
              <el-option
                v-for="item in options"
                :key="item.id"
                :label="`${item.name}（库存 ${item.stock}）`"
                :value="item.id"
              />
            </el-select>
          </div>
        </div>
      </template>

      <el-table :data="flows" v-loading="loadingFlows" size="small" empty-text="选择商品后显示流水">
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.quantity > 0 ? 'success' : 'danger'">
              {{ typeText(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="变动" width="90">
          <template #default="{ row }">
            <span class="money" :class="row.quantity > 0 ? 'up' : 'down'">
              {{ row.quantity > 0 ? '+' : '' }}{{ row.quantity }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="变动前 → 后" width="140">
          <template #default="{ row }">{{ row.beforeStock }} → {{ row.afterStock }}</template>
        </el-table-column>
        <el-table-column prop="refType" label="来源" width="110" />
        <el-table-column prop="refNo" label="来源单号" width="180" />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { inventoryApi, productApi } from '@/api'
import type { InventoryFlowView, LowStockItem, ProductView } from '@/types'

const router = useRouter()
const lowStock = ref<LowStockItem[]>([])
const options = ref<ProductView[]>([])
const searching = ref(false)
const selectedProductId = ref<number>()
const flows = ref<InventoryFlowView[]>([])
const loadingFlows = ref(false)

async function loadLowStock() {
  lowStock.value = await inventoryApi.lowStock()
}

async function searchProducts(keyword: string) {
  searching.value = true
  try {
    const page = await productApi.page({ keyword: keyword || undefined, page: 1, size: 20 })
    options.value = page.records
  } finally {
    searching.value = false
  }
}

async function loadFlows() {
  if (!selectedProductId.value) {
    flows.value = []
    return
  }
  loadingFlows.value = true
  try {
    flows.value = await inventoryApi.flows(selectedProductId.value, 50)
  } finally {
    loadingFlows.value = false
  }
}

function typeText(type: string) {
  switch (type) {
    case 'IN':
      return '入库'
    case 'OUT':
      return '出库'
    case 'ADJUST':
      return '盘点'
    default:
      return type
  }
}

function goAiDraft() {
  router.push('/ai-draft')
}

onMounted(async () => {
  await loadLowStock()
  await searchProducts('')
})
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.picker {
  display: flex;
  gap: 8px;
}

.warn {
  color: #e6a23c;
}

.up {
  color: #67c23a;
}

.down {
  color: #f56c6c;
}
</style>
