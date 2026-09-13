<template>
  <div class="assistant">
    <el-card shadow="never" class="card-gap">
      <template #header>
        <div class="card-header">
          <span>经营助手</span>
          <el-tag size="small" type="info">只读问答：不会修改任何数据</el-tag>
        </div>
      </template>

      <div class="chips">
        <el-tag v-for="item in quickQuestions" :key="item" class="chip" @click="ask(item)">{{ item }}</el-tag>
      </div>

      <div class="messages">
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
                工具：{{ tool }}
              </el-tag>
            </div>
            <pre class="answer-text">{{ message.answer }}</pre>
            <el-collapse v-if="message.steps.length">
              <el-collapse-item title="推理/取数过程（便于核对数据来源）">
                <div v-for="(step, stepIndex) in message.steps" :key="stepIndex" class="step">{{ step }}</div>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
        <el-empty v-if="!messages.length" description="问点什么吧，例如：今天卖了多少？" />
      </div>

      <div class="input-row">
        <el-input
          v-model="question"
          size="large"
          placeholder="例：今天卖了多少 / 哪些商品需要补货 / 本周卖得最好的商品 / 今天对账有差异吗"
          @keyup.enter="ask()"
        />
        <el-button type="primary" size="large" :loading="asking" @click="ask()">提问</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiApi } from '@/api'
import type { AskResponse } from '@/types'

interface Message extends AskResponse {
  question: string
}

const question = ref('')
const asking = ref(false)
const messages = ref<Message[]>([])

const quickQuestions = [
  '今天卖了多少',
  '昨天营业额是多少',
  '哪些商品需要补货',
  '本周卖得最好的商品',
  '今天对账有差异吗'
]

async function ask(preset?: string) {
  const content = (preset ?? question.value).trim()
  if (!content) {
    ElMessage.warning('请输入问题')
    return
  }
  asking.value = true
  try {
    const answer = await aiApi.ask(content)
    messages.value.unshift({ ...answer, question: content })
    question.value = ''
  } finally {
    asking.value = false
  }
}
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
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
}

.answer-text {
  white-space: pre-wrap;
  font-family: inherit;
  background: #f5f7fa;
  border-radius: 6px;
  padding: 10px;
  margin: 0;
}

.step {
  font-size: 12px;
  color: #909399;
  line-height: 1.8;
}

.input-row {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
</style>
