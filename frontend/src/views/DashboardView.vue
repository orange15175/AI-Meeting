<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import AppShell from "@/components/AppShell.vue";
import { interviewApi } from "@/api/interview";
import type { InterviewConversationItem, InterviewRecord } from "@/types/interview";

const router = useRouter();
const loading = ref(false);
const conversations = ref<InterviewConversationItem[]>([]);
const records = ref<InterviewRecord[]>([]);

const load = async () => {
  loading.value = true;
  try {
    const [convPage, recordPage] = await Promise.all([
      interviewApi.pageConversations({ current: 1, size: 5 }),
      interviewApi.pageRecords({ current: 1, size: 5 }),
    ]);
    conversations.value = convPage.records ?? [];
    records.value = recordPage.records ?? [];
  } catch (error) {
    console.error(error);
  } finally {
    loading.value = false;
  }
};

onMounted(load);

const startNew = () => router.push("/interview/new");
const continueInterview = (sessionId: string) =>
  router.push(`/interview/room/${sessionId}`);
const viewReport = (sessionId: string) =>
  router.push(`/interview/report/${sessionId}`);

const statusText = (status?: string | null) => {
  const map: Record<string, string> = {
    DRAFT: "草稿",
    PREPARING: "准备中",
    PREPARATION_FAILED: "准备失败",
    IN_PROGRESS: "进行中",
    ASKING: "进行中",
    FOLLOW_UP: "进行中",
    FINISHED: "已完成",
    CANCELLED: "已取消",
  };
  return status ? map[status] ?? status : "-";
};
</script>

<template>
  <AppShell>
    <div class="page" v-loading="loading">
      <div class="hero card">
        <h1 class="page-title">欢迎使用 AI 模拟面试</h1>
        <p class="page-subtitle">上传简历，AI 面试官将为你生成个性化题目并逐题评分、追问、复盘。</p>
        <el-button type="primary" size="large" @click="startNew">
          <el-icon style="margin-right: 6px"><Plus /></el-icon>新建面试
        </el-button>
      </div>

      <div class="grid">
        <section class="card">
          <h2 class="section-title">最近的面试</h2>
          <el-empty v-if="!conversations.length" description="暂无面试" :image-size="80" />
          <div v-else class="list">
            <div
              v-for="item in conversations"
              :key="item.sessionId"
              class="list-item"
            >
              <div class="list-item-main">
                <div class="list-item-title">{{ item.conversationTitle || "未命名面试" }}</div>
                <div class="muted">{{ item.interviewType || "待出题" }} · {{ statusText(item.status) }}</div>
              </div>
              <el-button
                v-if="item.status === 'IN_PROGRESS' || item.status === 'ASKING' || item.status === 'FOLLOW_UP'"
                size="small"
                type="primary"
                @click="continueInterview(item.sessionId)"
              >
                继续
              </el-button>
              <el-button
                v-else-if="item.status === 'FINISHED'"
                size="small"
                @click="viewReport(item.sessionId)"
              >
                查看报告
              </el-button>
            </div>
          </div>
        </section>

        <section class="card">
          <h2 class="section-title">最近记录</h2>
          <el-empty v-if="!records.length" description="暂无记录" :image-size="80" />
          <div v-else class="list">
            <div v-for="item in records" :key="item.id" class="list-item">
              <div class="list-item-main">
                <div class="list-item-title">
                  面试分
                  <span class="score">{{ item.interviewScore ?? "—" }}</span>
                  · 简历分
                  <span class="score">{{ item.resumeScore ?? "—" }}</span>
                </div>
                <div class="muted">{{ item.questionCount ?? 0 }} 题 · {{ item.interviewDirection || "综合" }}</div>
              </div>
              <el-button size="small" @click="viewReport(item.sessionId)">查看报告</el-button>
            </div>
          </div>
        </section>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.hero {
  margin-bottom: var(--space-6);
}

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-6);
}

@media (max-width: 900px) {
  .grid {
    grid-template-columns: 1fr;
  }
}

.section-title {
  font-size: 16px;
  margin: 0 0 var(--space-4);
}

.list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-border);
}

.list-item:last-child {
  border-bottom: none;
}

.list-item-title {
  font-weight: 500;
  margin-bottom: 2px;
}
</style>
