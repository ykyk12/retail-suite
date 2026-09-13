<template>
  <div>
    <el-card shadow="never" class="card-gap">
      <div class="toolbar">
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已入库" value="CONFIRMED" />
          <el-option label="已取消" value="CANCELED" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
        <div class="spacer" />
        <el-button v-if="auth.hasPermission('purchase:write')" type="success" @click="openCreate">
          新建采购单
        </el-button>
      </div>
      <div class="text-muted tip">
        采购单只是"登记进货"，确认入库后库存才会增加并写入库存流水——这样账实才能对得上。
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" empty-text="暂无采购单">
        <el-table-column prop="orderNo" label="单号" width="220" />
        <el-table-column prop="supplierName" label="供应商" width="140" />
        <el-table-column prop="itemCount" label="明细" width="80" />
        <el-table-column prop="totalAmount" label="金额" width="110">
          <template #default="{ row }"><span class="money">{{ row.totalAmount }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : row.status === 'DRAFT' ? 'warning' : 'info'">
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column prop="confirmedAt" label="入库时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openDetail(row)">明细</el-button>
            <el-button
              v-if="row.status === 'DRAFT' && auth.hasPermission('purchase:write')"
              text
              type="success"
              @click="confirmOrder(row)"
            >
              确认入库
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT' && auth.hasPermission('purchase:write')"
              text
              type="danger"
              @click="cancelOrder(row)"
            >
              取消
            </el-button>
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

    <!-- 新建采购单 -->
    <el-dialog v-model="createVisible" title="新建采购单" width="720px">
      <el-form label-width="80px">
        <el-form-item label="供应商">
          <el-input v-model="form.supplierName" style="width: 260px" placeholder="可选" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选，例如：3 月常规进货" />
        </el-form-item>
      </el-form>

      <el-table :data="form.items" size="small">
        <el-table-column label="商品" min-width="260">
          <template #default="{ row }">
            <el-select
              v-model="row.productId"
              filterable
              remote
              placeholder="搜索商品"
              :remote-method="searchProducts"
              style="width: 100%"
              @change="(id: number) => onProductPicked(row, id)"
            >
              <el-option v-for="item in options" :key="item.id" :label="`${item.name}（进价 ${item.purchasePrice}）`" :value="item.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="1" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="进价" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.unitCost" :min="0" :precision="2" :step="0.5" size="small" />
          </template>
        </el-table-column>
        <el-table-column width="70">
          <template #default="{ $index }">
            <el-button text type="danger" @click="form.items.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button text type="primary" @click="addItem">+ 添加一行</el-button>

      <template #footer>
        <span class="text-muted" style="float: left">共 {{ form.items.length }} 行</span>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">保存为草稿</el-button>
      </template>
    </el-dialog>

    <!-- 明细 -->
    <el-dialog v-model="detailVisible" :title="`采购单 ${detail?.orderNo ?? ''}`" width="640px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="供应商">{{ detail?.supplierName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail?.statusText }}</el-descriptions-item>
        <el-descriptions-item label="金额">{{ detail?.totalAmount }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detail?.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="detail?.items ?? []" size="small" style="margin-top: 10px">
        <el-table-column prop="productName" label="商品" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="unitCost" label="进价" width="90" />
        <el-table-column prop="amount" label="金额" width="100" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi, purchaseApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { ProductView, PurchaseView } from '@/types'

interface DraftItem {
  productId?: number
  quantity: number
  unitCost: number
}

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref<PurchaseView[]>([])
const total = ref(0)
const query = reactive({ status: undefined as string | undefined, page: 1, size: 10 })

const createVisible = ref(false)
const form = reactive<{ supplierName: string; remark: string; items: DraftItem[] }>({
  supplierName: '',
  remark: '',
  items: [{ quantity: 1, unitCost: 0 }]
})
const options = ref<ProductView[]>([])
const detailVisible = ref(false)
const detail = ref<PurchaseView>()

async function load() {
  loading.value = true
  try {
    const page = await purchaseApi.page({ status: query.status, page: query.page, size: query.size })
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

async function searchProducts(keyword: string) {
  const page = await productApi.page({ keyword: keyword || undefined, page: 1, size: 20 })
  options.value = page.records
}

function onProductPicked(row: DraftItem, id: number) {
  const product = options.value.find((item) => item.id === id)
  if (product) {
    row.unitCost = Number(product.purchasePrice)
  }
}

function addItem() {
  form.items.push({ quantity: 1, unitCost: 0 })
}

function openCreate() {
  form.supplierName = ''
  form.remark = ''
  form.items = [{ quantity: 1, unitCost: 0 }]
  createVisible.value = true
  searchProducts('')
}

async function create() {
  const items = form.items.filter((item) => item.productId)
  if (!items.length) {
    ElMessage.warning('至少添加一行商品')
    return
  }
  saving.value = true
  try {
    await purchaseApi.create({
      supplierName: form.supplierName || undefined,
      remark: form.remark || undefined,
      items: items.map((item) => ({
        productId: item.productId as number,
        quantity: item.quantity,
        unitCost: item.unitCost
      }))
    })
    ElMessage.success('采购单已保存为草稿，确认入库后库存才会增加')
    createVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function confirmOrder(row: PurchaseView) {
  await ElMessageBox.confirm(`确认将 ${row.orderNo} 入库？库存会立即增加并写入流水。`, '确认入库', { type: 'warning' })
  const updated = await purchaseApi.confirm(row.id)
  ElMessage.success(`已入库，共 ${updated.itemCount} 行明细`)
  await load()
}

async function cancelOrder(row: PurchaseView) {
  await ElMessageBox.confirm(`取消采购单 ${row.orderNo}？`, '提示', { type: 'warning' })
  await purchaseApi.cancel(row.id)
  ElMessage.success('已取消')
  await load()
}

async function openDetail(row: PurchaseView) {
  detail.value = await purchaseApi.detail(row.id)
  detailVisible.value = true
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

.tip {
  margin-top: 8px;
  font-size: 12px;
}
</style>
