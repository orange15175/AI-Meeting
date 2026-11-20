<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import AppShell from "@/components/AppShell.vue";
import TextSketchpad from "@/components/TextSketchpad.vue";
import { interviewApi } from "@/api/interview";

const route = useRoute();
const router = useRouter();
const sessionId = String(route.params.sessionId ?? "");

const loading = ref(true);
const submitting = ref(false);
const questionNumber = ref("");
const questionContent = ref("");
const isFollowUp = ref(false);
const totalScore = ref<number | null>(null);
const answer = ref("");
const feedback = ref("");
const score = ref<number | null>(null);

const genRequestId = () => {
  if (typeof crypto !== "undefined" && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
};
let requestId = genRequestId();

const loadCurrentQuestion = async () => {
  loading.value = true;
  try {
    const result = await interviewApi.getCurrentQuestion(sessionId);
    applyQuestion(result);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载题目失败");
  } finally {
    loading.value = false;
  }
};

const applyQuestion = (result: {
  questionNumber?: string;
  questionContent?: string;
  isFollowUp?: boolean;
  totalScore?: number;
  finished?: boolean;
}) => {
  if (result.finished) {
    goReport();
    return;
  }
  questionNumber.value = result.questionNumber ?? "";
  questionContent.value = result.questionContent ?? "";
  isFollowUp.value = !!result.isFollowUp;
  totalScore.value = result.totalScore ?? null;
};

const goReport = () => router.push(`/interview/report/${sessionId}`);

const submit = async () => {
  if (!answer.value.trim()) {
    ElMessage.warning("请先填写回答");
    return;
  }
  submitting.value = true;
  try {
    const result = await interviewApi.answer(sessionId, {
      questionNumber: questionNumber.value,
      answerContent: answer.value.trim(),
      requestId,
    });
    score.value = result.score ?? null;
    feedback.value = result.feedback ?? "";
    totalScore.value = result.totalScore ?? null;
    answer.value = "";
    requestId = genRequestId();

    if (result.finished) {
      ElMessage.success("面试已完成");
      await interviewApi.finish(sessionId);
      setTimeout(goReport, 800);
    } else {
      questionNumber.value = result.nextQuestionNumber ?? "";
      questionContent.value = result.nextQuestion ?? "";
      isFollowUp.value = !!result.isFollowUp;
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交失败，请重试");
  } finally {
    submitting.value = false;
  }
};

const finishEarly = async () => {
  try {
    await ElMessageBox.confirm(
      "提前结束后，未作答的题目将不计分，确认结束本次面试吗？",
      "提前结束",
      { confirmButtonText: "确认结束", cancelButtonText: "继续面试", type: "warning" },
    );
  } catch {
    return;
  }
  try {
    await interviewApi.finish(sessionId);
    ElMessage.success("面试已结束");
    goReport();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "结束失败");
  }
};

const onInsertSketch = (text: string) => {
  answer.value = answer.value ? `${answer.value}\n${text}` : text;
};

const badgeText = computed(() => (isFollowUp.value ? "追问" : "主问题"));

onMounted(loadCurrentQuestion);
</script>

<template>
  <AppShell>
    <div class="page" v-loading="loading">
      <div class="interview-layout">
        <div class="main-col">
          <div class="card question-card">
            <div class="question-meta">
              <el-tag :type="isFollowUp ? 'warning' : 'primary'" size="small">
                {{ badgeText }}
              </el-tag>
              <span class="muted">题号 {{ questionNumber || "-" }}</span>
              <span v-if="totalScore != null" class="muted" style="margin-left: auto">
                累计总分 <span class="score">{{ totalScore }}</span>
              </span>
            </div>
            <h2 class="question-content">{{ questionContent || "暂无题目" }}</h2>
          </div>

          <div class="card answer-card">
            <div class="answer-head">
              <span class="section-title">你的回答</span>
            </div>
            <el-input
              v-model="answer"
              type="textarea"
              :rows="8"
              maxlength="5000"
              show-word-limit
              placeholder="请在这里输入你的回答…"
            />
            <div class="answer-actions">
              <el-button
                type="primary"
                size="large"
                :loading="submitting"
                :disabled="!answer.trim()"
                @click="submit"
              >
                提交回答
              </el-button>
              <el-button size="large" @click="finishEarly">提前结束</el-button>
            </div>
          </div>

          <div v-if="feedback" class="card feedback-card">
            <div class="feedback-head">
              <span class="section-title">本轮反馈</span>
              <span v-if="score != null" class="score score-lg">{{ score }} 分</span>
            </div>
            <p class="feedback-content">{{ feedback }}</p>
          </div>
        </div>

        <div class="side-col">
          <TextSketchpad :session-id="sessionId" @insert="onInsertSketch" />
          <div class="card tip-card">
            <h3 class="section-title">答题提示</h3>
            <ul class="tip-list">
              <li>结合简历中的项目经历作答，突出你的真实贡献。</li>
              <li>回答尽量具体，说明技术选型、难点与解决方案。</li>
              <li>追问会围绕你回答中的薄弱点展开。</li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.interview-layout {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: var(--space-6);
  align-items: start;
}

@media (max-width: 900px) {
  .interview-layout {
    grid-template-columns: 1fr;
  }
}

.question-card,
.answer-card,
.feedback-card {
  margin-bottom: var(--space-6);
}

.question-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.question-content {
  font-size: 18px;
  line-height: 1.7;
  margin: 0;
  white-space: pre-wrap;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}

.answer-head,
.feedback-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.answer-actions {
  margin-top: var(--space-4);
  display: flex;
  gap: var(--space-3);
}

.feedback-content {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.7;
}

.score-lg {
  font-size: 18px;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}

.tip-list {
  margin: 0;
  padding-left: 18px;
  color: var(--color-text-secondary);
}

.tip-list li {
  margin-bottom: var(--space-2);
}
</style>
