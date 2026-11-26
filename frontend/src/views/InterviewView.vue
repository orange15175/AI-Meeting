<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import AppShell from "@/components/AppShell.vue";
import TextSketchpad from "@/components/TextSketchpad.vue";
import { interviewApi } from "@/api/interview";

const route = useRoute();
const router = useRouter();
// 从房间 URL 固定本场会话 ID；当前题、评分和结束请求都使用它，不能由前端新建另一个 ID。
const sessionId = String(route.params.sessionId ?? "");

// 房间状态分组：当前题与追问标记、用户回答、本轮评分反馈、主问题均分，以及加载/提交标记。
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
// 【关键节点：提交标识】成功响应后才更换 requestId，网络失败保留原 ID 便于后端识别重试。
// requestId 只标识请求；服务端仍会检查题号和输入摘要，修改回答后重试不等于同一份输入。
let requestId = genRequestId();

// 【面试主线 3/6】首次进入或刷新时读取已提交的当前题，不重新生成题库。
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

// 首次加载结果适配：服务端已结束则进入报告，否则展示返回的当前题；不会在浏览器内自行递增题号。
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

// 【面试主线 4/6】提交当前题 → 展示评分 → 按后端 nextQuestion 切换主问题或追问题。
const submit = async () => {
  if (!answer.value.trim()) {
    ElMessage.warning("请先填写回答");
    return;
  }
  submitting.value = true;
  try {
    // 第 1 段：提交当前题号、去掉首尾空白的回答与请求 ID；服务端负责判断旧题、重复提交和并发。
    const result = await interviewApi.answer(sessionId, {
      questionNumber: questionNumber.value,
      answerContent: answer.value.trim(),
      requestId,
    });
    // 第 2 段：拿到成功响应后展示评分、清空回答并更换下一轮请求 ID；失败进入 catch 时保留原输入与 ID。
    score.value = result.score ?? null;
    feedback.value = result.feedback ?? "";
    totalScore.value = result.totalScore ?? null;
    answer.value = "";
    requestId = genRequestId();

    if (result.finished) {
      ElMessage.success("面试已完成");
      // 【面试主线 5/6】题目答完与报告收口分两步，仍需调用 finish 保存报告。
      await interviewApi.finish(sessionId);
      setTimeout(goReport, 800);
    } else {
      // 【关键节点：采用服务端路由】下一题可能是主问题或 3-F1 这类追问，完全采用后端返回的题号和题干。
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

// 【面试主线 5/6 · 提前结束分支】确认后复用 finish；已答题可复盘，剩余题不会在这里自动作答。
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

// 普通输入辅助：将草稿板文字追加到回答区，真正提交仍需用户点击提交回答。
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
