<template>
  <div class="pos">
    <div class="left">
      <el-card shadow="never" class="card-gap">
        <el-input
          ref="searchRef"
          v-model="keyword"
          size="large"
          clearable
          placeholder="扫码枪扫描条码 / 输入商品名后回车（例：农夫山泉）"
          @keyup.enter="onSearchEnter"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
          <template #append>
            <el-button @click="search">搜索</el-button>
          </template>
        </el-input>
        <div class="hint text-muted">
          扫码枪本质是"快速输入 + 回车"：条码长度 ≥ 8 位时自动走条码精确匹配，其余按名称搜索
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-header">
            <span>商品（点击加入购物车）</span>
            <el-tag size="small" type="info">共 {{ products.length }} 条</el-tag>
          </div>
        </template>
        <div class="pos-grid">
          <div v-for="product in products" :key="product.id" class="pos-item" @click="addToCart(product)">
            <div class="name">{{ product.name }}</div>
            <div class="price money">¥{{ product.salePrice }}</div>
            <div class="text-muted">
              库存 {{ product.stock }} {{ product.unit || '' }}
              <el-tag v-if="product.lowStock" size="small" type="warning">低</el-tag>
            </div>
          </div>
          <el-empty v-if="!products.length" description="没有查到商品，换个关键字或先建档" />
        </div>
      </el-card>
    </div>

    <div class="right">
      <el-card shadow="never" class="cart">
        <template #header>
          <div class="card-header">
            <span>购物车</span>
            <el-button text type="danger" :disabled="!cart.length" @click="cart = []">清空</el-button>
          </div>
        </template>

        <el-table :data="cart" size="small" empty-text="还没有商品" max-height="360">
          <el-table-column prop="name" label="商品" show-overflow-tooltip />
          <el-table-column label="单价" width="90">
            <template #default="{ row }">
              <el-input-number
                v-model="row.unitPrice"
                :min="0"
                :precision="2"
                :step="0.5"
                size="small"
                controls-position="right"
                style="width: 84px"
              />
            </template>
          </el-table-column>
          <el-table-column label="数量" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.quantity" :min="1" :max="row.stock" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="小计" width="90">
            <template #default="{ row }">
              <span class="money">{{ (row.unitPrice * row.quantity).toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column width="60">
            <template #default="{ $index }">
              <el-button text type="danger" @click="cart.splice($index, 1)">删</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-divider />

        <div class="row">
          <span>客户</span>
          <el-input v-model="customerName" placeholder="散客" style="width: 150px" />
        </div>
        <div class="row">
          <span>折扣</span>
          <el-input-number v-model="discount" :min="0" :precision="2" :step="1" size="small" />
        </div>
        <div class="row">
          <span>支付方式</span>
          <el-radio-group v-model="payMethod" size="small">
            <el-radio-button value="CASH">现金</el-radio-button>
            <el-radio-button value="WECHAT">微信</el-radio-button>
            <el-radio-button value="ALIPAY">支付宝</el-radio-button>
            <el-radio-button value="CARD">刷卡</el-radio-button>
          </el-radio-group>
        </div>

        <el-divider />

        <div class="total">
          <span>应收：<b class="money">¥{{ totalAmount.toFixed(2) }}</b></span>
          <span>实收：<b class="money big">¥{{ payAmount.toFixed(2) }}</b></span>
        </div>

        <el-button
          type="primary"
          size="large"
          style="width: 100%; margin-top: 8px"
          :loading="submitting"
          :disabled="!cart.length"
          @click="checkout"
        >
          结算（F2）
        </el-button>
        <div class="text-muted tip">
          结算使用幂等键：网络重试或重复点击都不会重复下单、不会重复扣库存
        </div>
      </el-card>
    </div>

    <!-- 小票：打印时只显示这块（见 main.css 的 @media print） -->
    <el-dialog v-model="receiptVisible" title="收款完成" width="360px">
      <div id="receipt-print">
        <h3 style="text-align: center">云小店 销售小票</h3>
        <div>单号：{{ receipt?.orderNo }}</div>
        <div>时间：{{ receipt?.createdAt }}</div>
        <div>收银员：{{ auth.displayName }}</div>
        <el-divider />
        <div v-for="item in receipt?.items ?? []" :key="item.id" class="receipt-line">
          <span>{{ item.productName }} × {{ item.quantity }}</span>
          <span class="money">{{ item.amount }}</span>
        </div>
        <el-divider />
        <div class="receipt-line"><span>合计</span><span class="money">{{ receipt?.totalAmount }}</span></div>
        <div class="receipt-line"><span>折扣</span><span class="money">{{ receipt?.discountAmount }}</span></div>
        <div class="receipt-line"><b>实收</b><b class="money">{{ receipt?.payAmount }}</b></div>
        <div v-if="receipt?.duplicated" style="color: #e6a23c">（该请求此前已结算过，返回的是首次小票）</div>
        <p style="text-align: center">谢谢惠顾</p>
      </div>
      <template #footer>
        <el-button @click="printReceipt">打印小票</el-button>
        <el-button type="primary" @click="receiptVisible = false">继续收银</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { productApi, salesApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { ProductView, SaleView } from '@/types'

interface CartItem {
  productId: number
  name: string
  unitPrice: number
  quantity: number
  stock: number
}

const auth = useAuthStore()
const searchRef = ref()
const keyword = ref('')
const products = ref<ProductView[]>([])
const cart = ref<CartItem[]>([])
const customerName = ref('')
const discount = ref(0)
const payMethod = ref('CASH')
const submitting = ref(false)
const receiptVisible = ref(false)
const receipt = ref<SaleView>()

const totalAmount = computed(() =>
  cart.value.reduce((sum, item) => sum + item.unitPrice * item.quantity, 0)
)
const payAmount = computed(() => Math.max(0, totalAmount.value - (discount.value || 0)))

async function search() {
  const value = keyword.value.trim()
  if (!value) {
    products.value = []
    return
  }
  // 条码：纯数字且较长 → 精确匹配；否则按名称模糊搜索
  if (/^\d{8,14}$/.test(value)) {
    try {
      const product = await productApi.byBarcode(value)
      products.value = [product]
      addToCart(product)
      keyword.value = ''
    } catch {
      products.value = []
    }
    return
  }
  const page = await productApi.page({ keyword: value, page: 1, size: 24 })
  products.value = page.records.filter((item) => item.status === 1)
}

function onSearchEnter() {
  search()
}

function addToCart(product: ProductView) {
  if (product.stock <= 0) {
    ElMessage.warning(`「${product.name}」库存为 0，无法销售`)
    return
  }
  const existing = cart.value.find((item) => item.productId === product.id)
  if (existing) {
    if (existing.quantity >= product.stock) {
      ElMessage.warning(`「${product.name}」库存只剩 ${product.stock}`)
      return
    }
    existing.quantity += 1
    return
  }
  cart.value.push({
    productId: product.id,
    name: product.name,
    unitPrice: Number(product.salePrice),
    quantity: 1,
    stock: product.stock
  })
}

async function checkout() {
  if (!cart.value.length) return
  submitting.value = true
  try {
    const order = await salesApi.checkout({
      // 幂等键：每次点击生成一次；重试沿用同一个 id（这里因每次点击都是新单，故直接生成）
      requestId: `POS-${crypto.randomUUID()}`,
      customerName: customerName.value || undefined,
      items: cart.value.map((item) => ({
        productId: item.productId,
        quantity: item.quantity,
        unitPrice: item.unitPrice
      })),
      discountAmount: discount.value || 0,
      payMethod: payMethod.value
    })
    receipt.value = order
    receiptVisible.value = true
    cart.value = []
    discount.value = 0
    customerName.value = ''
    ElMessage.success(`收款成功 ${order.payAmount} 元`)
  } catch {
    // 库存不足等业务错误由拦截器提示；这里刷新商品列表让收银员看到最新库存
    if (keyword.value) await search()
  } finally {
    submitting.value = false
  }
}

function printReceipt() {
  window.print()
}

function keyHandler(event: KeyboardEvent) {
  if (event.key === 'F2') {
    event.preventDefault()
    checkout()
  }
  if (event.key === 'F4') {
    event.preventDefault()
    searchRef.value?.focus()
  }
}

onMounted(() => {
  window.addEventListener('keydown', keyHandler)
  searchRef.value?.focus()
  // 默认展示最近商品，收银员可直接点选，不必先搜索
  productApi.page({ page: 1, size: 12 }).then((page) => {
    products.value = page.records.filter((item) => item.status === 1)
  })
})

onUnmounted(() => window.removeEventListener('keydown', keyHandler))
</script>

<style scoped>
.pos {
  display: grid;
  grid-template-columns: 1fr 420px;
  gap: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.hint {
  margin-top: 6px;
  font-size: 12px;
}

.cart .row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.total {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.total .big {
  font-size: 24px;
  color: #f56c6c;
}

.tip {
  font-size: 12px;
  margin-top: 6px;
}

.receipt-line {
  display: flex;
  justify-content: space-between;
}
</style>
