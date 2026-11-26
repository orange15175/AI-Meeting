<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppShell from "@/components/AppShell.vue";
import { interviewApi } from "@/api/interview";
import type { InterviewSessionRestore } from "@/types/interview";

const route = useRoute();
const router = useRouter();
const sessionId = String(route.params.sessionId ?? "");
const loading = ref(true);
const state = ref<InterviewSessionRestore | null>(null);

// 【恢复分支】准备页只读取一次已保存状态，不负责异步任务轮询。
// 新建面试的正常路径由 NewInterviewView 同步出题后直接进入房间。
onMounted(async () => {
  try {
    state.value = await interviewApi.restore(sessionId);
  } catch (error) {
    console.error(error);
  } finally {
    loading.value = false;
  }
});

const enter = () => router.push(`/interview/room/${sessionId}`);
const retry = () => router.push("/interview/new");
</script>

<template>
  <AppShell>
    <div class="page" v-loading="loading">
      <div class="card center">
        <el-icon class="big-icon" v-if="!loading"><Loading /></el-icon>
        <h1 class="page-title">准备面试</h1>
        <p class="muted">
          状态：{{ state?.status ?? "加载中…" }}
        </p>
        <div class="actions">
          <el-button
            v-if="state?.canResume"
            type="primary"
            @click="enter"
          >
            进入面试
          </el-button>
          <el-button v-else @click="retry">重新准备</el-button>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.center {
  max-width: 480px;
  margin: 0 auto;
  text-align: center;
  padding: var(--space-12);
}

.big-icon {
  font-size: 48px;
  color: var(--color-primary);
  margin-bottom: var(--space-4);
}

.actions {
  margin-top: var(--space-6);
  display: flex;
  justify-content: center;
  gap: var(--space-3);
}
</style>
