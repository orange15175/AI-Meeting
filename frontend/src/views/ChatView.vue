<script setup lang="ts">
import { nextTick, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import AppShell from "@/components/AppShell.vue";
import { chatApi } from "@/api/chat";
import type { AiConversationItem, AiMessageHistory } from "@/types/chat";

const DEFAULT_AI_ID = 4; // DeepSeek V3（deepseek-chat）

interface ChatMessage {
  role: "user" | "assistant";
  content: string;
  streaming?: boolean;
}

const conversations = ref<AiConversationItem[]>([]);
const activeSessionId = ref<string | null>(null);
const messages = ref<ChatMessage[]>([]);
const input = ref("");
const sending = ref(false);
const loading = ref(false);
const listEl = ref<HTMLElement | null>(null);

let abortController: AbortController | null = null;

const loadConversations = async () => {
  try {
    const page = await chatApi.pageConversations({ current: 1, size: 50 });
    conversations.value = page.records ?? [];
  } catch (error) {
    console.error(error);
  }
};

const loadHistory = async (sessionId: string) => {
  loading.value = true;
  try {
    const history = await chatApi.getHistory(sessionId);
    messages.value = (history ?? []).map((h: AiMessageHistory) => ({
      role: h.messageType === 1 ? "user" : "assistant",
      content: h.messageContent ?? "",
    }));
  } catch (error) {
    console.error(error);
  } finally {
    loading.value = false;
  }
};

const selectConversation = async (sessionId: string) => {
  activeSessionId.value = sessionId;
  await loadHistory(sessionId);
  scrollToBottom();
};

const scrollToBottom = async () => {
  await nextTick();
  listEl.value?.scrollTo({ top: listEl.value.scrollHeight });
};

const send = async () => {
  const text = input.value.trim();
  if (!text || sending.value) return;

  if (!activeSessionId.value) {
    // 无会话时先创建会话，再把首条消息作为首轮发送
    try {
      const created = await chatApi.createConversation({
        aiId: DEFAULT_AI_ID,
        firstMessage: text,
      });
      activeSessionId.value = created.sessionId;
      await loadConversations();
      messages.value = [{ role: "user", content: text }];
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : "创建会话失败");
      return;
    }
  } else {
    messages.value.push({ role: "user", content: text });
  }

  input.value = "";
  await streamReply(text);
};

const streamReply = async (userText: string) => {
  const sessionId = activeSessionId.value;
  if (!sessionId) return;

  sending.value = true;
  const assistantMessage: ChatMessage = { role: "assistant", content: "", streaming: true };
  messages.value.push(assistantMessage);
  abortController = new AbortController();

  try {
    await chatApi.streamChat(sessionId, userText, DEFAULT_AI_ID, abortController.signal, {
      onMessage: (content) => {
        assistantMessage.content += content;
        scrollToBottom();
      },
      onReasoning: (content) => {
        assistantMessage.content += content;
        scrollToBottom();
      },
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      assistantMessage.content += "\n\n[已停止生成]";
    } else {
      assistantMessage.content += "\n\n[生成出错，请重试]";
    }
  } finally {
    assistantMessage.streaming = false;
    sending.value = false;
    abortController = null;
    scrollToBottom();
    loadConversations();
  }
};

const stop = () => {
  abortController?.abort();
};

const newConversation = () => {
  activeSessionId.value = null;
  messages.value = [];
  input.value = "";
};

const deleteConversation = async (sessionId: string) => {
  try {
    await chatApi.deleteConversation(sessionId);
    ElMessage.success("已删除");
    if (activeSessionId.value === sessionId) {
      newConversation();
    }
    await loadConversations();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "删除失败");
  }
};

onMounted(loadConversations);
</script>

<template>
  <AppShell>
    <div class="chat-layout">
      <aside class="conv-panel">
        <div class="conv-head">
          <span class="conv-title">会话</span>
          <el-button size="small" type="primary" text @click="newConversation">
            <el-icon><Plus /></el-icon>新对话
          </el-button>
        </div>
        <div class="conv-list">
          <div
            v-for="item in conversations"
            :key="item.sessionId"
            class="conv-item"
            :class="{ active: item.sessionId === activeSessionId }"
            @click="selectConversation(item.sessionId)"
          >
            <div class="conv-item-main">
              <div class="conv-item-title">{{ item.title || "新对话" }}</div>
              <div class="muted">{{ item.messageCount ?? 0 }} 条消息</div>
            </div>
            <el-button
              size="small"
              text
              @click.stop="deleteConversation(item.sessionId)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-empty v-if="!conversations.length" description="暂无会话" :image-size="60" />
        </div>
      </aside>

      <div class="chat-main">
        <div ref="listEl" class="message-list" v-loading="loading">
          <el-empty
            v-if="!messages.length"
            description="开始一段对话吧"
            :image-size="80"
          />
          <div
            v-for="(msg, index) in messages"
            :key="index"
            class="message"
            :class="msg.role"
          >
            <div class="bubble">{{ msg.content }}<span v-if="msg.streaming" class="cursor">▍</span></div>
          </div>
        </div>

        <div class="chat-input">
          <el-input
            v-model="input"
            type="textarea"
            :rows="3"
            placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
            @keydown.enter.exact.prevent="send"
          />
          <div class="chat-input-actions">
            <span class="muted">DeepSeek 模型</span>
            <div>
              <el-button v-if="sending" @click="stop">停止</el-button>
              <el-button
                v-else
                type="primary"
                :disabled="!input.trim()"
                @click="send"
              >
                发送
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.chat-layout {
  display: flex;
  height: calc(100vh - 64px);
}

.conv-panel {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid var(--color-border);
  background: var(--color-surface);
  display: flex;
  flex-direction: column;
}

.conv-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  border-bottom: 1px solid var(--color-border);
}

.conv-title {
  font-weight: 600;
}

.conv-list {
  flex: 1;
  overflow: auto;
  padding: var(--space-2);
}

.conv-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.conv-item:hover {
  background: var(--color-bg);
}

.conv-item.active {
  background: var(--color-bg);
}

.conv-item-main {
  flex: 1;
  min-width: 0;
}

.conv-item-title {
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.message-list {
  flex: 1;
  overflow: auto;
  padding: var(--space-6);
}

.message {
  display: flex;
  margin-bottom: var(--space-4);
}

.message.user {
  justify-content: flex-end;
}

.message.assistant {
  justify-content: flex-start;
}

.bubble {
  max-width: 70%;
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.message.user .bubble {
  background: var(--color-primary);
  color: #fff;
}

.message.assistant .bubble {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
}

.cursor {
  animation: blink 1s step-start infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.chat-input {
  border-top: 1px solid var(--color-border);
  padding: var(--space-4) var(--space-6);
  background: var(--color-surface);
}

.chat-input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-2);
}
</style>
