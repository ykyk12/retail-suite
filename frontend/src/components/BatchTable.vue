<template>
  <el-table :data="rows" size="small" border :max-height="420">
    <el-table-column label="商品" prop="productName" min-width="160" />
    <el-table-column label="批次号" prop="batchNo" width="180" />
    <el-table-column label="生产日期" width="110">
      <template #default="{ row }">{{ row.productionDate ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="到期日" width="110">
      <template #default="{ row }">{{ row.expiryDate ?? '未登记' }}</template>
    </el-table-column>
    <el-table-column v-if="showRemaining" label="剩余/过期" width="120">
      <template #default="{ row }">
        <el-tag v-if="row.expired" size="small" type="danger">已过期</el-tag>
        <el-tag v-else-if="row.daysToExpiry === null || row.daysToExpiry === undefined" size="small" type="info">不追踪</el-tag>
        <el-tag v-else size="small" :type="row.daysToExpiry <= 7 ? 'danger' : 'warning'">
          剩 {{ row.daysToExpiry }} 天
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="数量" prop="quantity" width="90" />
    <el-table-column label="批次成本" width="100">
      <template #default="{ row }">{{ Number(row.costPrice ?? 0).toFixed(2) }}</template>
    </el-table-column>
    <el-table-column label="压货金额" width="110">
      <template #default="{ row }">
        {{ (Number(row.costPrice ?? 0) * Number(row.quantity ?? 0)).toFixed(2) }}
      </template>
    </el-table-column>
    <el-table-column label="备注" prop="remark" min-width="140" />
    <el-table-column v-if="$slots.actions" label="操作" width="120" fixed="right">
      <template #default="{ row }">
        <slot name="actions" :row="row" />
      </template>
    </el-table-column>
    <template #empty>
      <el-empty description="没有符合条件的批次" />
    </template>
  </el-table>
</template>

<script setup lang="ts">
import type { BatchView } from '@/types'

/**
 * 批次表格：临期页、过期页、商品详情的批次列表共用一份渲染，
 * 避免"某个页面忘了显示到期日"这类不一致。
 */
withDefaults(defineProps<{ rows: BatchView[]; showRemaining?: boolean }>(), {
  showRemaining: false
})
</script>
