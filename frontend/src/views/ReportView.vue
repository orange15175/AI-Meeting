<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import AppShell from "@/components/AppShell.vue";
import { interviewApi } from "@/api/interview";
import type { InterviewRecord } from "@/types/interview";

const route = useRoute();
const router = useRouter();
const sessionId = String(route.params.sessionId ?? "");

const loading = ref(true);
const record = ref<InterviewRecord | null>(null);

const load = async () => {
  loading.value = true;
  try {
    record.value = await interviewApi.getRecord(sessionId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载报告失败");
  } finally {
    loading.value = false;
  }
};

onMounted(load);

const radar = computed(() => record.value?.radarChart ?? null);
const dimensions = computed(() => record.value?.radarDimensions ?? []);
const playback = computed(() => record.value?.playbackItems ?? []);
const review = computed(() => record.value?.reviewFeedback ?? null);
const jdSummary = computed(() => record.value?.jdSummary ?? null);
const jdCoverage = computed(() => record.value?.jdCoverage ?? []);

const answeredCount = computed(
  () => playback.value.filter((item) => item.answerContent && !item.isFollowUp).length,
);

const scoreCards = computed(() => [
  { label: "面试表现", value: radar.value?.interviewPerformance ?? null },
  { label: "简历评估", value: radar.value?.resumeScore ?? null },
  { label: "综合指数", value: radar.value?.potentialIndex ?? null },
  { label: "专业技能", value: radar.value?.professionalSkills ?? null },
]);

const back = () => router.push("/interview/records");
</script>

<template>
  <AppShell>
    <div class="page" v-loading="loading">
      <div class="report-head">
        <div>
          <h1 class="page-title">复盘报告</h1>
          <p class="page-subtitle">
            已完成 {{ answeredCount }} 个主问题 · 共 {{ record?.questionCount ?? playback.length }} 题
          </p>
        </div>
        <el-button @click="back">返回记录</el-button>
      </div>

      <div class="score-grid">
        <div v-for="card in scoreCards" :key="card.label" class="card score-card">
          <div class="score-label">{{ card.label }}</div>
          <div class="score-value">
            {{ card.value == null ? "—" : card.value }}
          </div>
        </div>
      </div>

      <div v-if="dimensions.length" class="card section">
        <h2 class="section-title">能力维度</h2>
        <div v-for="dim in dimensions" :key="dim.key" class="dim">
          <div class="dim-label">
            <span>{{ dim.label }}</span>
            <span class="muted">{{ dim.score ?? 0 }}</span>
          </div>
          <div class="dim-bar">
            <div class="dim-fill" :style="{ width: `${Math.min(100, dim.score ?? 0)}%` }" />
          </div>
        </div>
      </div>

      <div v-if="review" class="card section">
        <h2 class="section-title">综合评价</h2>
        <p class="overall">{{ review.overallComment }}</p>
        <div v-if="review.highlights?.length" class="sub-block">
          <h3 class="sub-title">亮点</h3>
          <ul><li v-for="(h, i) in review.highlights" :key="i">{{ h }}</li></ul>
        </div>
        <div v-if="review.improvementTips?.length" class="sub-block">
          <h3 class="sub-title">改进建议</h3>
          <ul><li v-for="(t, i) in review.improvementTips" :key="i">{{ t }}</li></ul>
        </div>
        <div v-if="review.nextActions?.length" class="sub-block">
          <h3 class="sub-title">下一步</h3>
          <ul><li v-for="(a, i) in review.nextActions" :key="i">{{ a }}</li></ul>
        </div>
      </div>

      <div v-if="jdSummary || jdCoverage.length" class="card section">
        <h2 class="section-title">岗位要求考察</h2>
        <p v-if="jdSummary" class="jd-summary muted">{{ jdSummary }}</p>
        <el-empty v-if="!jdCoverage.length" description="本次未记录可追溯的岗位要求" />
        <div v-else class="jd-list">
          <div v-for="(item, i) in jdCoverage" :key="`${item.requirement}-${i}`" class="jd-item">
            <span class="jd-req">{{ item.requirement }}</span>
            <el-tag :type="item.status === 'COVERED' ? 'success' : 'info'" size="small">
              {{ item.status === "COVERED" ? "已考察" : "待验证" }}
            </el-tag>
            <span v-if="item.questionNumbers?.length" class="muted jd-nos">
              题号 {{ item.questionNumbers.join("、") }}
            </span>
          </div>
        </div>
      </div>

      <div class="card section">
        <h2 class="section-title">逐题回顾</h2>
        <el-empty v-if="!playback.length" description="暂无答题记录" />
        <div v-else class="qa-list">
          <div v-for="item in playback" :key="item.seq ?? item.questionNumber" class="qa-item">
            <div class="qa-q">
              <el-tag :type="item.isFollowUp ? 'warning' : 'primary'" size="small">
                {{ item.isFollowUp ? "追问" : "主问题" }}
              </el-tag>
              <span class="qa-no">{{ item.questionNumber }}</span>
              <span class="qa-text">{{ item.questionContent }}</span>
              <span v-if="item.score != null" class="score" style="margin-left: auto">
                {{ item.score }} 分
              </span>
            </div>
            <div v-if="item.answerContent" class="qa-a">
              <span class="muted">你的回答：</span>{{ item.answerContent }}
            </div>
            <div v-if="item.feedback" class="qa-f">
              <span class="muted">反馈：</span>{{ item.feedback }}
            </div>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.report-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.score-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

@media (max-width: 760px) {
  .score-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

.score-card {
  text-align: center;
}

.score-label {
  color: var(--color-text-secondary);
  margin-bottom: var(--space-2);
}

.score-value {
  font-size: 32px;
  font-weight: 700;
  color: var(--color-primary);
}

.section {
  margin-bottom: var(--space-6);
}

.section-title {
  font-size: 16px;
  margin: 0 0 var(--space-4);
}

.sub-title {
  font-size: 14px;
  margin: 0 0 var(--space-2);
}

.dim {
  margin-bottom: var(--space-3);
}

.dim-label {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}

.dim-bar {
  height: 8px;
  background: var(--color-border);
  border-radius: 4px;
  overflow: hidden;
}

.dim-fill {
  height: 100%;
  background: var(--color-primary);
  border-radius: 4px;
}

.overall {
  line-height: 1.7;
  white-space: pre-wrap;
}

.sub-block {
  margin-top: var(--space-4);
}

.sub-block ul {
  margin: 0;
  padding-left: 18px;
  color: var(--color-text-secondary);
}

.qa-item {
  padding: var(--space-4) 0;
  border-bottom: 1px solid var(--color-border);
}

.qa-item:last-child {
  border-bottom: none;
}

.qa-q {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.qa-text {
  font-weight: 500;
}

.qa-a,
.qa-f {
  margin: var(--space-2) 0 0 52px;
  line-height: 1.6;
}

.qa-f {
  color: var(--color-text-secondary);
}

.jd-summary {
  margin: 0 0 var(--space-3);
  line-height: 1.6;
}

.jd-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.jd-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-2) 0;
  border-bottom: 1px dashed var(--color-border);
}

.jd-item:last-child {
  border-bottom: none;
}

.jd-req {
  flex: 1;
  line-height: 1.6;
}

.jd-nos {
  white-space: nowrap;
}
</style>
