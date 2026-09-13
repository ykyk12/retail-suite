<template>
  <div>
    <el-card shadow="never" class="card-gap">
      <template #header>
        <div class="card-header">
          <span>用一句话录采购单</span>
          <el-tag size="small" :type="llmConfigured ? 'success' : 'info'">
            {{ llmConfigured ? '已接入大模型（解析失败自动回退规则）' : '当前为本地规则解析（未配置模型）' }}
          </el-tag>
        </div>
      </template>

      <el-input
        v-model="text"
        type="textarea"
        :rows="3"
        placeholder="例：进了 10 瓶农夫山泉 单价 1.2；采购 5 箱可口可乐 每箱 3.5 元"
      />
      <div class="examples">
        <span class="text-muted">试试：</span>
        <el-tag
          v-for="example in examples"
          :key="example"
          class="example"
          @click="text = example"
        >
          {{ example }}
        </el-tag>
      </div>
      <div class="actions">
        <el-button type="primary" :loading="parsing" @click="parse">解析成草稿</el-button>
        <span class="text-muted">
          AI 只负责识别；确认前不会产生任何单据，确认后生成的是<b>采购单草稿</b>，入库仍需在采购页确认。
        </span>
      </div>
    </el-card>

    <el-card v-if="current" shadow="never" class="card-gap">
      <template #header>
        <div class="card-header">
          <span>草稿 #{{ current.id }}（来源：{{ current.source === 'LLM' ? '大模型' : '本地规则' }}）</span>
          <el-tag size="small" :type="current.status === 'PENDING' ? 'warning' : 'success'">{{ statusText(current.status) }}</el-tag>
        </div>
      </template>

      <el-alert :type="current.hasUnresolved ? 'warning' : 'info'" :closable="false" :title="current.message" class="card-gap" />

      <el-table :data="current.items" size="small">
        <el-table-column prop="rawText" label="原文" min-width="180" show-overflow-tooltip />
        <el-table-column label="匹配商品" min-width="160">
          <template #default="{ row }">
            <span v-if="row.resolved">{{ row.productName }}</span>
            <span v-else class="text-danger">未识别</span>
          </template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="price" label="单价" width="90" />
        <el-table-column label="提示" min-width="180">
          <template #default="{ row }">
            <span :class="{ 'text-danger': !row.resolved }">{{ row.note }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="actions" style="margin-top: 12px">
        <el-input v-model="supplierName" placeholder="供应商（可选）" style="width: 220px" />
        <el-button
          type="success"
          :disabled="current.hasUnresolved || current.status !== 'PENDING'"
          :loading="confirming"
          @click="confirmDraft"
        >
          确认生成采购单
        </el-button>
        <el-button :disabled="current.status !== 'PENDING'" @click="discardDraft">作废</el-button>
        <span v-if="current.createdRefNo" class="text-muted">已生成：{{ current.createdRefNo }}</span>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>最近草稿</template>
      <el-table :data="drafts" size="small" empty-text="还没有草稿">
        <el-table-column prop="id" label="#" width="70" />
        <el-table-column label="来源" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === 'LLM' ? 'success' : 'info'">{{ row.source }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rawText" label="原文" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ statusText(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="createdRefNo" label="生成单号" width="200" />
        <el-table-column width="90">
          <template #default="{ row }">
            <el-button text type="primary" @click="current = row">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi } from '@/api'
import type { DraftView } from '@/types'

const text = ref('')
const parsing = ref(false)
const confirming = ref(false)
const current = ref<DraftView>()
const drafts = ref<DraftView[]>([])
const supplierName = ref('')
const llmConfigured = ref(false)

const examples = [
  '进了 10 瓶农夫山泉 单价 1.2',
  '采购 5 箱可口可乐 每箱 3.5 元；奥利奥饼干 10 盒 5.8',
  '进货 东方树叶 20 瓶 3.5 元、乐事薯片 10 袋 4.2'
]

async function parse() {
  if (!text.value.trim()) {
    ElMessage.warning('请输入内容')
    return
  }
  parsing.value = true
  try {
    current.value = await aiApi.createDraft(text.value.trim())
    if (current.value.hasUnresolved) {
      ElMessage.warning('有行没匹配到商品，请修改文字后重新解析（AI 不会替你猜商品）')
    } else {
      ElMessage.success('已生成草稿，请核对后确认')
    }
    await loadDrafts()
  } finally {
    parsing.value = false
  }
}

async function confirmDraft() {
  if (!current.value) return
  confirming.value = true
  try {
    current.value = await aiApi.confirm(current.value.id, { supplierName: supplierName.value || undefined })
    ElMessage.success(current.value.message)
    await loadDrafts()
  } finally {
    confirming.value = false
  }
}

async function discardDraft() {
  if (!current.value) return
  await ElMessageBox.confirm('作废这条草稿？不会产生任何单据。', '提示', { type: 'warning' })
  await aiApi.discard(current.value.id)
  current.value = { ...current.value, status: 'DISCARDED' }
  ElMessage.success('已作废')
  await loadDrafts()
}

async function loadDrafts() {
  drafts.value = await aiApi.drafts(10)
}

function statusText(status: string) {
  switch (status) {
    case 'PENDING':
      return '待确认'
    case 'CONFIRMED':
      return '已生成单据'
    case 'DISCARDED':
      return '已作废'
    default:
      return status
  }
}

onMounted(async () => {
  const capabilities = await aiApi.capabilities()
  llmConfigured.value = Boolean(capabilities.llmConfigured)
  await loadDrafts()
})
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.examples {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.example {
  cursor: pointer;
}

.actions {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.text-danger {
  color: #f56c6c;
}
</style>
