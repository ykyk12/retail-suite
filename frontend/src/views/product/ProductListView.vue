<template>
  <div>
    <el-card shadow="never" class="card-gap">
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="商品名 / 条码" clearable style="width: 220px" @keyup.enter="load" />
        <el-select v-model="query.categoryId" placeholder="全部分类" clearable style="width: 140px">
          <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-checkbox v-model="query.lowStockOnly" @change="load">只看低库存</el-checkbox>
        <el-button type="primary" @click="load">查询</el-button>
        <div class="spacer" />
        <el-button v-if="auth.hasPermission('product:write')" type="success" @click="openCreate">新建商品</el-button>
        <el-button v-if="auth.hasPermission('category:write')" @click="categoryDialog = true">分类管理</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" size="default" empty-text="暂无商品">
        <el-table-column prop="name" label="商品" show-overflow-tooltip />
        <el-table-column prop="barcode" label="条码" width="140" />
        <el-table-column prop="categoryName" label="分类" width="100" />
        <el-table-column prop="spec" label="规格" width="90" />
        <el-table-column label="进价/售价" width="130">
          <template #default="{ row }">
            <span class="money">{{ row.purchasePrice }} / <b>{{ row.salePrice }}</b></span>
          </template>
        </el-table-column>
        <el-table-column label="库存" width="110">
          <template #default="{ row }">
            <span class="money" :class="{ warn: row.lowStock }">{{ row.stock }}</span>
            <el-tag v-if="row.lowStock" size="small" type="warning" style="margin-left: 4px">低</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '在售' : '停售' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPermission('product:write')"
              text
              type="primary"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="auth.hasPermission('inventory:adjust')"
              text
              type="warning"
              @click="openAdjust(row)"
            >
              改库存
            </el-button>
            <el-button
              v-if="auth.hasPermission('product:write')"
              text
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? '停售' : '上架' }}
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

    <!-- 新增/编辑 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑商品' : '新建商品'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="商品名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="条码">
          <el-input v-model="form.barcode" placeholder="扫码枪可直接扫入，门店内唯一" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" clearable placeholder="可选" style="width: 100%">
            <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="规格 / 单位">
          <el-input v-model="form.spec" style="width: 45%" placeholder="如 550ml" />
          <el-input v-model="form.unit" style="width: 45%; margin-left: 10px" placeholder="如 瓶" />
        </el-form-item>
        <el-form-item label="进价">
          <el-input-number v-model="form.purchasePrice" :min="0" :precision="2" :step="0.5" />
        </el-form-item>
        <el-form-item label="售价">
          <el-input-number v-model="form.salePrice" :min="0" :precision="2" :step="0.5" />
        </el-form-item>
        <el-form-item v-if="!form.id" label="期初库存">
          <el-input-number v-model="form.initStock" :min="0" />
          <span class="text-muted" style="margin-left: 8px">会写入一条库存流水</span>
        </el-form-item>
        <el-form-item label="库存阈值">
          <el-input-number v-model="form.lowStockThreshold" :min="0" />
          <span class="text-muted" style="margin-left: 8px">低于该值进入补货预警</span>
        </el-form-item>
        <el-form-item label="保质期">
          <el-input-number v-model="form.shelfLifeDays" :min="0" :max="3650" />
          <span class="text-muted" style="margin-left: 8px">
            天（留空表示不追踪；填了之后入库会按"生产日期 + 保质期"推算到期日，并进入临期预警）
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 库存调整 -->
    <el-dialog v-model="adjustVisible" title="库存调整（盘点）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="商品">{{ adjustTarget?.name }}</el-form-item>
        <el-form-item label="当前库存">{{ adjustTarget?.stock }}</el-form-item>
        <el-form-item label="调整数量">
          <el-input-number v-model="adjustForm.delta" :step="1" />
          <span class="text-muted" style="margin-left: 8px">正数盘盈、负数盘亏</span>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="adjustForm.remark" placeholder="如：破损报废 2 件（必填，便于对账追责）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAdjust">提交</el-button>
      </template>
    </el-dialog>

    <!-- 分类管理 -->
    <el-dialog v-model="categoryDialog" title="分类管理" width="460px">
      <div class="toolbar">
        <el-input v-model="newCategoryName" placeholder="新分类名称" style="width: 200px" />
        <el-button type="primary" @click="createCategory">添加</el-button>
      </div>
      <el-table :data="categories" size="small" style="margin-top: 10px">
        <el-table-column prop="name" label="分类" />
        <el-table-column prop="sortNo" label="排序" width="80" />
        <el-table-column width="90">
          <template #default="{ row }">
            <el-button text type="danger" @click="removeCategory(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categoryApi, inventoryApi, productApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { CategoryView, ProductView } from '@/types'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref<ProductView[]>([])
const categories = ref<CategoryView[]>([])
const total = ref(0)
const query = reactive({ keyword: '', categoryId: undefined as number | undefined, lowStockOnly: false, page: 1, size: 10 })

const formVisible = ref(false)
const form = reactive<Record<string, any>>({})
const adjustVisible = ref(false)
const adjustTarget = ref<ProductView>()
const adjustForm = reactive({ delta: 0, remark: '' })
const categoryDialog = ref(false)
const newCategoryName = ref('')

async function load() {
  loading.value = true
  try {
    const page = await productApi.page({
      keyword: query.keyword || undefined,
      categoryId: query.categoryId,
      lowStockOnly: query.lowStockOnly || undefined,
      page: query.page,
      size: query.size
    })
    rows.value = page.records
    total.value = page.total
    categories.value = await categoryApi.list()
  } finally {
    loading.value = false
  }
}

function onPageChange(page: number) {
  query.page = page
  load()
}

function openCreate() {
  Object.assign(form, {
    id: undefined,
    name: '',
    barcode: '',
    categoryId: undefined,
    spec: '',
    unit: '',
    purchasePrice: 0,
    salePrice: 0,
    initStock: 0,
    lowStockThreshold: 10,
    shelfLifeDays: undefined
  })
  formVisible.value = true
}

function openEdit(row: ProductView) {
  Object.assign(form, {
    id: row.id,
    name: row.name,
    barcode: row.barcode,
    categoryId: row.categoryId,
    spec: row.spec,
    unit: row.unit,
    purchasePrice: Number(row.purchasePrice),
    salePrice: Number(row.salePrice),
    lowStockThreshold: row.lowStockThreshold,
    shelfLifeDays: row.shelfLifeDays ?? undefined
  })
  formVisible.value = true
}

async function save() {
  if (!form.name) {
    ElMessage.warning('请填写商品名称')
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await productApi.update(form.id, {
        name: form.name,
        barcode: form.barcode || null,
        categoryId: form.categoryId ?? null,
        spec: form.spec,
        unit: form.unit,
        purchasePrice: form.purchasePrice,
        salePrice: form.salePrice,
        lowStockThreshold: form.lowStockThreshold,
        shelfLifeDays: form.shelfLifeDays ?? null
      })
      ElMessage.success('已保存')
    } else {
      await productApi.create({
        name: form.name,
        barcode: form.barcode || null,
        categoryId: form.categoryId ?? null,
        spec: form.spec,
        unit: form.unit,
        purchasePrice: form.purchasePrice,
        salePrice: form.salePrice,
        initStock: form.initStock,
        lowStockThreshold: form.lowStockThreshold,
        shelfLifeDays: form.shelfLifeDays ?? null
      })
      ElMessage.success('已新建商品')
    }
    formVisible.value = false
    await load()
  } catch {
    // 拦截器已提示（例如条码重复）
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: ProductView) {
  await productApi.toggleStatus(row.id, row.status === 1 ? 0 : 1)
  ElMessage.success(row.status === 1 ? '已停售' : '已上架')
  await load()
}

function openAdjust(row: ProductView) {
  adjustTarget.value = row
  adjustForm.delta = 0
  adjustForm.remark = ''
  adjustVisible.value = true
}

async function submitAdjust() {
  if (!adjustTarget.value) return
  if (!adjustForm.remark.trim()) {
    ElMessage.warning('必须填写调整原因')
    return
  }
  saving.value = true
  try {
    await inventoryApi.adjust({
      productId: adjustTarget.value.id,
      delta: adjustForm.delta,
      remark: adjustForm.remark
    })
    ElMessage.success('库存已调整并写入流水')
    adjustVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function createCategory() {
  if (!newCategoryName.value.trim()) return
  await categoryApi.create({ name: newCategoryName.value.trim() })
  newCategoryName.value = ''
  categories.value = await categoryApi.list()
  ElMessage.success('分类已添加')
}

async function removeCategory(row: CategoryView) {
  await ElMessageBox.confirm(`删除分类「${row.name}」？分类下有商品时会被拒绝。`, '提示', { type: 'warning' })
  await categoryApi.remove(row.id)
  categories.value = await categoryApi.list()
  await load()
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

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.warn {
  color: #e6a23c;
  font-weight: 600;
}
</style>
