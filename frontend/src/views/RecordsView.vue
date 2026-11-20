<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import AppShell from "@/components/AppShell.vue";
import { interviewApi } from "@/api/interview";
import { formatDateTime } from "@/lib/format";
import type { InterviewRecord } from "@/types/interview";

const router = useRouter();
const loading = ref(false);
const records = ref<InterviewRecord[]>([]);
const total = ref(0);
const current = ref(1);
const size = ref(10);

const load = async () => {
  loading.value = true;
  try {
    const page = await interviewApi.pageRecords({ current: current.value, size: size.value });
    records.value = page.records ?? [];
    total.value = page.total ?? 0;
  } catch (error) {
    console.error(error);
  } finally {
    loading.value = false;
  }
};

const onPageChange = (page: number) => {
  current.value = page;
  load();
};

const viewReport = (sessionId: string) =>
  router.push(`/interview/report/${sessionId}`);

const statusText = (status?: string | null) => {
  const map: Record<string, string> = {
    INIT: "初始",
    IN_PROGRESS: "进行中",
    FINISHED: "已完成",
    EVALUATED: "已评分",
  };
  return status ? map[status] ?? status : "-";
};

onMounted(load);
</script>

<template>
  <AppShell>
    <div class="page">
      <h1 class="page-title">面试记录</h1>
      <p class="page-subtitle">查看历史面试与复盘报告。</p>

      <div class="card" v-loading="loading">
        <el-table :data="records" style="width: 100%">
          <el-table-column label="面试方向" min-width="160">
            <template #default="{ row }">{{ row.interviewDirection || "综合" }}</template>
          </el-table-column>
          <el-table-column label="面试分" width="100" align="center">
            <template #default="{ row }">
              <span class="score">{{ row.interviewScore ?? "—" }}</span>
            </template>
          </el-table-column>
          <el-table-column label="简历分" width="100" align="center">
            <template #default="{ row }">
              <span class="score">{{ row.resumeScore ?? "—" }}</span>
            </template>
          </el-table-column>
          <el-table-column label="题数" width="80" align="center">
            <template #default="{ row }">{{ row.questionCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">{{ statusText(row.interviewStatus) }}</template>
          </el-table-column>
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="center">
            <template #default="{ row }">
              <el-button size="small" @click="viewReport(row.sessionId)">查看报告</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            layout="prev, pager, next, total"
            :total="total"
            :page-size="size"
            :current-page="current"
            @current-change="onPageChange"
          />
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.pager {
  margin-top: var(--space-4);
  display: flex;
  justify-content: flex-end;
}
</style>
