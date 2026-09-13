<template>
  <div class="steward-chat">
    <el-card shadow="never" class="card-gap">
      <template #header>
        <div class="card-header">
          <span>管家对话</span>
          <div class="header-tags">
            <el-tag size="small" type="info">会话 {{ shortSession }}</el-tag>
            <el-tag v-if="usedTools.length" size="small" type="warning">
              本轮已用 {{ usedTools.length }} 个工具
            </el-tag>
            <el-tooltip content="管家只能调用工具查真实数据；写操作（如生成采购单）只会产出草稿，确认入库仍需人工在采购页操作" placement="bottom">
              <el-tag size="small" type="success">写操作只出草稿</el-tag>
            </el-tooltip>
          </div>
        </div>
      </template>

      <div class="chips">
        <el-tag v-for="item in quickQuestions" :key="item" class="chip" effect="plain" @click="ask(item)">
          {{ item }}
        </el-tag>
      </div>

      <div ref="scrollBox" class="messages">
        <div v-for="(message, index) in messages" :key="index" class="message">
          <div class="question">
            <el-icon><UserFilled /></el-icon>
            <span>{{ message.question }}</span>
          </div>
          <div class="answer">
            <div class="answer-head">
              <el-icon><Service /></el-icon>
              <el-tag size="small" :type="message.source === 'LLM' ? 'success' : 'info'">
                {{ message.source === 'LLM' ? '大模型作答' : '本地规则作答' }}
              </el-tag>
              <el-tag v-for="tool in message.toolsUsed" :key="tool" size="small" type="warning">
                {{ tool }}
              </el-tag>
              <span v-if="message.pending" class="pending">思考中…</span>
            </div>
            <pre class="answer-text">{{ message.answer }}</pre>

            <!-- 工具轨迹：回答里的每个数字都能追到"查了哪个工具、查到什么" -->
            <el-collapse v-if="message.steps.length">
              <el-collapse-item :title="`工具轨迹（${message.steps.length} 步，数据来源可核对）`">
                <el-table :data="message.steps" size="small" border>
                  <el-table-column label="轮次" prop="round" width="70" />
                  <el-table-column label="工具" prop="tool" width="180" />
                  <el-table-column label="参数" min-width="180">
                    <template #default="{ row }">
                      <span class="mono">{{ formatArgs(row.args) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="结果" width="90">
                    <template #default="{ row }">
                      <el-tag size="small" :type="row.success ? 'success' : 'danger'">
                        {{ row.success ? '成功' : '失败' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="需人工确认" width="110">
                    <template #default="{ row }">
                      <el-tag v-if="row.requiresConfirmation" size="small" type="warning">是</el-tag>
                      <span v-else>—</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="耗时" width="90">
                    <template #default="{ row }">{{ row.elapsedMs }} ms</template>
                  </el-table-column>
                  <el-table-column label="返回摘要" min-width="260">
                    <template #default="{ row }">
                      <pre class="observation">{{ row.observation }}</pre>
                    </template>
                  </el-table-column>
                </el-table>
              </el-collapse-item>
            </el-collapse>

            <!-- 建议卡片：把工具返回的结构化数据渲染成可读卡片 -->
            <div v-if="message.cards.length" class="cards">
              <el-card v-for="(card, cardIndex) in message.cards" :key="cardIndex" shadow="hover" class="data-card">
                <FindingMetrics :metrics="card" />
              </el-card>
            </div>
          </div>
        </div>
        <el-empty v-if="!messages.length" description="问点什么吧，例如：哪些商品快过期了？" />
      </div>

      <div class="input-row">
        <el-input
          v-model="question"
          size="large"
          placeholder="例：哪些批次快过期 / 哪些商品要断货了 / 这个商品进价售价毛利多少 / 帮我看看谁在亏本卖"
          @keyup.enter="ask()"
        />
        <el-button type="primary" size="large" :loading="asking" @click="ask()">提问</el-button>
        <el-button size="large" :disabled="asking" @click="resetSession">新会话</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>管家能做什么（按当前账号权限过滤）</span>
          <el-tag size="small" type="info">共 {{ tools.length }} 个工具</el-tag>
        </div>
      </template>
      <el-table :data="tools" size="small" border>
        <el-table-column label="工具" prop="name" width="180" />
        <el-table-column label="用途" prop="description" min-width="320" />
        <el-table-column label="权限码" prop="permission" width="140" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.readOnly ? 'info' : 'warning'">
              {{ row.readOnly ? '只读' : '写（出草稿）' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Service, UserFilled } from '@element-plus/icons-vue'
import { agentApi } from '@/api'
import type { AgentChatResponse, AgentToolInfo, AgentToolCallStep } from '@/types'
import FindingMetrics from '@/components/FindingMetrics.vue'

interface Message {
  question: string
  answer: string
  source: string
  toolsUsed: string[]
  steps: AgentToolCallStep[]
  cards: Array<Record<string, unknown>>
  pending?: boolean
}

const question = ref('')
const asking = ref(false)
const messages = ref<Message[]>([])
const tools = ref<AgentToolInfo[]>([])
const sessionId = ref<string>()
const scrollBox = ref<HTMLElement>()

const quickQuestions = [
  '哪些批次快过期了',
  '哪些商品要断货了',
  '本周卖得最好的商品',
  '哪些商品滞销压货',
  '有没有亏本卖的商品',
  '今天对账有差异吗',
  '哪些商品需要补货'
]

const shortSession = computed(() => (sessionId.value ? sessionId.value.slice(0, 8) : '新建'))
const usedTools = computed(() => messages.value.flatMap((item) => item.toolsUsed))

onMounted(async () => {
  try {
    tools.value = await agentApi.tools()
  } catch {
    // 工具目录拿不到不影响对话（后端仍会按权限拦截）
  }
})

async function ask(preset?: string) {
  const content = (preset ?? question.value).trim()
  if (!content) {
    ElMessage.warning('请输入问题')
    return
  }
  const placeholder: Message = {
    question: content,
    answer: '',
    source: 'RULE',
    toolsUsed: [],
    steps: [],
    cards: [],
    pending: true
  }
  messages.value.push(placeholder)
  question.value = ''
  asking.value = true
  await scrollToBottom()
  try {
    const response: AgentChatResponse = await agentApi.chat(content, sessionId.value)
    sessionId.value = response.sessionId
    Object.assign(placeholder, {
      answer: response.answer,
      source: response.source,
      toolsUsed: response.toolsUsed ?? [],
      steps: response.steps ?? [],
      cards: response.cards ?? [],
      pending: false
    })
  } catch (error) {
    placeholder.pending = false
    placeholder.answer = error instanceof Error ? error.message : '管家暂时无法回答，请稍后再试'
  } finally {
    asking.value = false
    await scrollToBottom()
  }
}

async function resetSession() {
  const created = await agentApi.resetSession()
  sessionId.value = created.sessionId
  messages.value = []
  ElMessage.success('已开始新会话（管家不再记得上一轮上下文）')
}

function formatArgs(args: Record<string, unknown>) {
  const entries = Object.entries(args ?? {})
  return entries.length ? entries.map(([key, value]) => `${key}=${value}`).join(', ') : '—'
}

async function scrollToBottom() {
  await nextTick()
  if (scrollBox.value) {
    scrollBox.value.scrollTop = scrollBox.value.scrollHeight
  }
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
  gap: 6px;
  align-items: center;
}

.chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.chip {
  cursor: pointer;
}

.messages {
  max-height: 520px;
  overflow-y: auto;
}

.message {
  border-bottom: 1px dashed #e4e7ed;
  padding: 10px 0;
}

.question {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  margin-bottom: 6px;
}

.answer-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.pending {
  font-size: 12px;
  color: #909399;
}

.answer-text {
  white-space: pre-wrap;
  font-family: inherit;
  background: #f5f7fa;
  border-radius: 6px;
  padding: 10px;
  margin: 0 0 8px;
  line-height: 1.7;
}

.observation {
  white-space: pre-wrap;
  font-size: 12px;
  color: #606266;
  margin: 0;
  max-height: 160px;
  overflow-y: auto;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.data-card {
  background: #fbfcfe;
}

.input-row {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
</style>
