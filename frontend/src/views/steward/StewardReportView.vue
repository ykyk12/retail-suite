<template>
  <div class="steward-report">
    <el-card shadow="never" class="card-gap">
      <template #header>
        <div class="card-header">
          <span>管家日报</span>
          <div class="header-tags">
            <el-tag v-if="report" size="small" type="info">{{ report.reportDate }} · {{ sourceText(report.source) }}</el-tag>
            <el-button size="small" type="primary" :loading="inspecting" @click="runInspect">立即巡检</el-button>
          </div>
        </div>
      </template>

      <el-alert
        class="tip"
        type="info"
        :closable="false"
        show-icon
        title="巡检在每天开门前自动执行一次；报告只做分析与建议，写操作（生成采购单）只会产出草稿，确认入库仍需人工在采购页操作"
      />

      <div v-if="report" class="headline">
        <el-icon class="headline-icon"><BellFilled /></el-icon>
        <span>{{ report.headline }}</span>
        <el-tag size="small" type="danger" v-if="report.highCount">{{ report.highCount }} 项需立即处理</el-tag>
      </div>
      <el-empty v-else :description="emptyText">
        <el-button type="primary" @click="runInspect">生成今天的巡检报告</el-button>
      </el-empty>
    </el-card>

    <el-row v-if="report" :gutter="12">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <span>巡检发现（{{ report.findings.length }} 条）</span>
          </template>

          <div v-for="finding in sortedFindings" :key="finding.code" class="finding" :class="`finding-${finding.severity.toLowerCase()}`">
            <div class="finding-head">
              <el-tag size="small" :type="severityType(finding.severity)">{{ severityText(finding.severity) }}</el-tag>
              <span class="finding-title">{{ finding.title }}</span>
              <span class="finding-code">{{ finding.code }}</span>
            </div>
            <pre class="finding-detail">{{ finding.detail }}</pre>
            <FindingMetrics v-if="hasMetrics(finding)" :metrics="finding.metrics" class="finding-metrics" />
            <div v-if="finding.actions.length" class="finding-actions">
              <el-tooltip v-for="action in finding.actions" :key="action.type" :content="action.hint" placement="top">
                <el-button
                  size="small"
                  type="primary"
                  :loading="actionLoading === `${finding.code}:${action.type}`"
                  @click="executeAction(finding, action)"
                >
                  {{ action.label }}
                </el-button>
              </el-tooltip>
              <span class="hint">{{ finding.actions[0].hint }}</span>
            </div>
          </div>

          <el-empty v-if="!report.findings.length" description="这次巡检没有发现问题：库存、保质期、账目都对得上" />
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="never">
          <template #header>
            <span>历史报告</span>
          </template>
          <el-timeline>
            <el-timeline-item
              v-for="item in history"
              :key="item.id"
              :timestamp="`${item.reportDate} · ${sourceText(item.source)}`"
              :type="item.highCount ? 'danger' : 'success'"
              :hollow="item.id !== report.id"
            >
              <a class="history-link" @click="loadReport(item.id)">{{ item.headline }}</a>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-if="!history.length" description="暂无历史报告" />
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="resultVisible" title="已生成采购单草稿" width="520px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="库存没有变化——请到「采购进货」页面核对明细并点「确认入库」，库存才会增加"
      />
      <pre class="result-text">{{ actionResult }}</pre>
      <template #footer>
        <el-button @click="resultVisible = false">留在本页</el-button>
        <el-button type="primary" @click="goPurchase">去采购页确认入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { BellFilled } from '@element-plus/icons-vue'
import { stewardApi } from '@/api'
import type { StewardAction, StewardFinding, StewardReport } from '@/types'
import FindingMetrics from '@/components/FindingMetrics.vue'

const router = useRouter()
const report = ref<StewardReport>()
const history = ref<StewardReport[]>([])
const inspecting = ref(false)
const actionLoading = ref('')
const resultVisible = ref(false)
const actionResult = ref('')
const emptyText = ref('还没有巡检报告')

/** 需立即处理的排最前，其余按严重度与标题稳定排序 */
const sortedFindings = computed(() => {
  const weight: Record<string, number> = { HIGH: 0, WARN: 1, INFO: 2 }
  return [...(report.value?.findings ?? [])].sort(
    (a, b) => (weight[a.severity] ?? 9) - (weight[b.severity] ?? 9) || a.code.localeCompare(b.code)
  )
})

onMounted(async () => {
  await loadHistory()
  try {
    report.value = await stewardApi.latest()
  } catch {
    emptyText.value = '今天还没生成过报告（定时任务每天开门前执行，也可以手动触发）'
  }
})

async function loadHistory() {
  try {
    history.value = await stewardApi.reports(10)
  } catch {
    history.value = []
  }
}

async function loadReport(id: number) {
  report.value = await stewardApi.detail(id)
}

async function runInspect() {
  inspecting.value = true
  try {
    report.value = await stewardApi.inspect()
    await loadHistory()
    ElMessage.success(`巡检完成：${report.value.findingCount} 项发现，其中 ${report.value.highCount} 项需立即处理`)
  } finally {
    inspecting.value = false
  }
}

async function executeAction(finding: StewardFinding, action: StewardAction) {
  if (!report.value) {
    return
  }
  actionLoading.value = `${finding.code}:${action.type}`
  try {
    const result = await stewardApi.executeAction(report.value.id, finding.code, action.type)
    actionResult.value = result.message
    resultVisible.value = true
  } finally {
    actionLoading.value = ''
  }
}

function goPurchase() {
  resultVisible.value = false
  router.push('/purchases')
}

function hasMetrics(finding: StewardFinding) {
  const metrics = finding.metrics ?? {}
  return Object.keys(metrics).length > 0
}

function severityType(severity: string) {
  if (severity === 'HIGH') {
    return 'danger'
  }
  return severity === 'WARN' ? 'warning' : 'info'
}

function severityText(severity: string) {
  if (severity === 'HIGH') {
    return '需立即处理'
  }
  return severity === 'WARN' ? '需要关注' : '提示'
}

function sourceText(source: string) {
  return source === 'SCHEDULED' ? '定时巡检' : '手动巡检'
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

.tip {
  margin-bottom: 12px;
}

.headline {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}

.headline-icon {
  color: #e6a23c;
}

.finding {
  border: 1px solid #ebeef5;
  border-left: 4px solid #dcdfe6;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 10px;
}

.finding-high {
  border-left-color: #f56c6c;
  background: #fef0f0;
}

.finding-warn {
  border-left-color: #e6a23c;
  background: #fdf6ec;
}

.finding-info {
  border-left-color: #909399;
}

.finding-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.finding-title {
  font-weight: 600;
}

.finding-code {
  font-size: 12px;
  color: #c0c4cc;
  font-family: Consolas, Monaco, monospace;
}

.finding-detail {
  white-space: pre-wrap;
  font-family: inherit;
  font-size: 13px;
  color: #606266;
  margin: 8px 0;
  line-height: 1.7;
}

.finding-metrics {
  margin-bottom: 8px;
}

.finding-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hint {
  font-size: 12px;
  color: #909399;
}

.history-link {
  cursor: pointer;
  color: #409eff;
  font-size: 13px;
}

.result-text {
  white-space: pre-wrap;
  font-family: inherit;
  background: #f5f7fa;
  border-radius: 6px;
  padding: 10px;
  margin-top: 12px;
  line-height: 1.7;
}
</style>
