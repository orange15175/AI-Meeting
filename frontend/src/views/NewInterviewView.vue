<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElRadioButton, ElRadioGroup } from "element-plus";
import { Document } from "@element-plus/icons-vue";
import type { UploadFile } from "element-plus";
import AppShell from "@/components/AppShell.vue";
import { interviewApi } from "@/api/interview";

const router = useRouter();
// 页面状态分组：简历文件、提交中标记和进度提示；文件先留在浏览器内存，点击开始面试后才真正发往后端。
const file = ref<File | null>(null);
const submitting = ref(false);
const progressText = ref("");

// JD 输入三选一：不提供、文字、文件；模式决定展示的控件及实际上传的字段。
type JdMode = "none" | "text" | "file";
const jdMode = ref<JdMode>("none");
const jdText = ref("");
const jdFile = ref<File | null>(null);

// 出题选项：题量与难度只是提示词参数，区间与枚举校验都在后端，前端不做"唯一真相"。
type Difficulty = "AUTO" | "BASIC" | "ADVANCED" | "CHALLENGE";
const QUESTION_COUNTS = [5, 10, 15, 20] as const;
const DEFAULT_QUESTION_COUNT = 10;
const DIFFICULTY_OPTIONS: { value: Difficulty; label: string; hint: string }[] = [
  { value: "AUTO", label: "智能匹配", hint: "按简历里可见的经历与职责决定起点；信息不足时从基础到中等。" },
  { value: "BASIC", label: "基础", hint: "侧重概念与常见应用，适合转岗或首次准备。" },
  { value: "ADVANCED", label: "进阶", hint: "侧重原理、设计与线上排障。" },
  { value: "CHALLENGE", label: "挑战", hint: "侧重复杂场景、边界条件与方案取舍。" },
];
const questionCount = ref<number>(DEFAULT_QUESTION_COUNT);
const difficulty = ref<Difficulty>("AUTO");
const difficultyHint = computed(
  () => DIFFICULTY_OPTIONS.find((item) => item.value === difficulty.value)?.hint ?? "",
);

// 单选组的值类型是 string | number | boolean，这里显式换算，避免把布尔值塞进题量。
const switchQuestionCount = (value: string | number | boolean | undefined) => {
  const next = Number(value);
  if (!Number.isInteger(next) || next <= 0) return;
  questionCount.value = next;
};

const switchDifficulty = (value: string | number | boolean | undefined) => {
  const next = DIFFICULTY_OPTIONS.find((item) => item.value === value);
  if (!next) return;
  difficulty.value = next.value;
};

const ACCEPTED_EXTENSIONS = ["pdf", "png", "jpg", "jpeg", "webp", "bmp"];
const JD_TEXT_MAX = 10000;
const FILE_MAX_BYTES = 10 * 1024 * 1024;

const jdCharCount = computed(() => jdText.value.trim().length);
// 此值仅表示当前是否有 JD 内容，用于进度提示；最终后端是否启用 JD 以响应的 jdEnabled 为准。
const jdEnabled = computed(
  () => (jdMode.value === "text" && jdCharCount.value > 0)
    || (jdMode.value === "file" && jdFile.value !== null),
);

// 普通前置校验：扩展名和 10MB 大小限制，简历与 JD 共用；后端仍需独立校验，不能只信任浏览器。
const validateFile = (raw: File, label: string): boolean => {
  const ext = raw.name.toLowerCase().split(".").pop() ?? "";
  if (!ACCEPTED_EXTENSIONS.includes(ext)) {
    ElMessage.error(`${label}仅支持 PDF、PNG、JPG、WebP、BMP 格式`);
    return false;
  }
  if (raw.size > FILE_MAX_BYTES) {
    ElMessage.error(`${label}大小不能超过 10MB`);
    return false;
  }
  return true;
};

// auto-upload=false 时 Element Plus 不会触发 before-upload，
// 必须在 on-change 里取文件，否则 file.value 永远为空、无法开始面试。
const handleChange = (uploadFile: UploadFile) => {
  const raw = uploadFile.raw;
  if (!raw) return;
  if (!validateFile(raw, "简历")) {
    file.value = null;
    return;
  }
  file.value = raw;
};

const onRemove = () => {
  file.value = null;
};

// 切换模式时清空另一种输入，避免界面只显示文字、实际请求却夹带旧文件；组件事件无有效值时忽略。
const switchJdMode = (mode: string | number | boolean | undefined) => {
  if (mode !== "none" && mode !== "text" && mode !== "file") return;
  const next = mode;
  if (next !== "text") {
    jdText.value = "";
  }
  if (next !== "file") {
    jdFile.value = null;
  }
  jdMode.value = next;
};

// JD 文件选择只保存 raw File；auto-upload=false 时不会自动请求后端，仍由 start 统一提交。
const handleJdChange = (uploadFile: UploadFile) => {
  const raw = uploadFile.raw;
  if (!raw) return;
  if (!validateFile(raw, "岗位描述文件")) {
    jdFile.value = null;
    return;
  }
  jdFile.value = raw;
};

const onJdRemove = () => {
  jdFile.value = null;
};

// 【面试主线 1/6 → 2/6】先创建绑定用户的会话，再带 sessionId 上传简历和可选 JD。
// 当前页面等待同步出题结果；成功后直接进入面试房间，不通过准备页轮询出题。
const start = async () => {
  if (!file.value) {
    ElMessage.warning("请先选择简历文件");
    return;
  }
  if (jdMode.value === "text" && jdCharCount.value > JD_TEXT_MAX) {
    ElMessage.error(`岗位描述不能超过 ${JD_TEXT_MAX} 字，请精简后重试`);
    return;
  }
  if (jdMode.value === "file" && !jdFile.value) {
    ElMessage.warning("请上传岗位描述文件，或切换为不提供 / 粘贴文字");
    return;
  }

  // 第 1 段：通过本地校验后进入提交中状态，禁用上传和模式切换，显示当前处理阶段。
  submitting.value = true;
  progressText.value = "正在创建面试会话…";
  try {
    // 【关键节点：先创建后上传】这里只取得会话 ID；后续简历和 JD 都绑定到这个 ID，失败后本次不会进入房间。
    const session = await interviewApi.createSession();
    progressText.value = `正在${jdEnabled.value ? "解析简历与岗位描述并" : "解析简历并"}生成 ${questionCount.value} 道题目…（${
      questionCount.value > 10 ? "通常需要 15~35 秒" : "通常需要 5~25 秒"
    }）`;
    // 第 2 段：按当前模式只传一种 JD，并带上题量与难度；同步等待后端完成材料解析、模型出题和数据库提交。
    const result = await interviewApi.extractQuestions(session.sessionId, file.value, {
      jdText: jdMode.value === "text" ? jdText.value : undefined,
      jdFile: jdMode.value === "file" ? jdFile.value : null,
      questionCount: questionCount.value,
      difficulty: difficulty.value,
    });
    // 【关键节点：业务成功检查】HTTP 请求成功不等于出题成功，还需判断响应内的 isSuccess；失败时留在当前页展示原因。
    if (result.isSuccess === 0) {
      ElMessage.error(result.errorMessage || "出题失败");
      return;
    }
    ElMessage.success(result.jdEnabled ? "已按岗位要求生成题目" : "题目生成成功");
    // 【关键节点：页面交接】收到出题成功响应后才进房间；房间会重新查询数据库当前题。
    router.push(`/interview/room/${session.sessionId}`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "出题失败，请重试");
  } finally {
    // 无论请求成功或失败，finally 都恢复交互并清空临时进度提示。
    submitting.value = false;
    progressText.value = "";
  }
};
</script>

<template>
  <AppShell>
    <div class="page">
      <h1 class="page-title">新建面试</h1>
      <p class="page-subtitle">
        上传简历（PDF 或图片），AI 面试官将根据你的经历生成个性化面试题；填写目标岗位 JD 可让题目更贴近招聘要求。
      </p>

      <div class="card upload-card">
        <h2 class="section-title">简历</h2>
        <el-upload
          drag
          :auto-upload="false"
          :show-file-list="true"
          :limit="1"
          :disabled="submitting"
          accept=".pdf,.png,.jpg,.jpeg,.webp,.bmp"
          :on-change="handleChange"
          :on-remove="onRemove"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">将简历拖到此处，或<em>点击选择文件</em></div>
          <template #tip>
            <div class="el-upload__tip">
              支持 PDF、图片（PNG/JPG/WebP/BMP），扫描件可识别，大小不超过 10MB
            </div>
          </template>
        </el-upload>
      </div>

      <div class="card upload-card settings-card">
        <div class="section-head">
          <h2 class="section-title">面试设置</h2>
          <span class="muted">难度只影响提问深度，不改变评分标准</span>
        </div>

        <div class="setting-row">
          <span class="setting-label">题目数量</span>
          <el-radio-group
            :model-value="questionCount"
            :disabled="submitting"
            @change="switchQuestionCount"
          >
            <el-radio-button
              v-for="count in QUESTION_COUNTS"
              :key="count"
              :value="count"
            >
              {{ count }} 题
            </el-radio-button>
          </el-radio-group>
        </div>

        <div class="setting-row">
          <span class="setting-label">难度倾向</span>
          <el-radio-group
            :model-value="difficulty"
            :disabled="submitting"
            @change="switchDifficulty"
          >
            <el-radio-button
              v-for="item in DIFFICULTY_OPTIONS"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </div>
        <div class="muted hint">{{ difficultyHint }}</div>
      </div>

      <div class="card upload-card jd-card">
        <div class="section-head">
          <h2 class="section-title">目标岗位（可选）</h2>
          <span class="muted">不填则按简历生成通用面试题</span>
        </div>

        <el-radio-group
          :model-value="jdMode"
          :disabled="submitting"
          @change="switchJdMode"
        >
          <el-radio-button value="none">不提供</el-radio-button>
          <el-radio-button value="text">粘贴文字</el-radio-button>
          <el-radio-button value="file">上传文件</el-radio-button>
        </el-radio-group>

        <div v-if="jdMode === 'text'" class="jd-input">
          <el-input
            v-model="jdText"
            type="textarea"
            :rows="6"
            :maxlength="JD_TEXT_MAX"
            show-word-limit
            :disabled="submitting"
            placeholder="粘贴岗位职责与任职要求，例如：负责交易链路研发；熟悉 Java、MySQL、Redis，具备高并发经验…"
          />
          <div class="muted hint">去掉首尾空白后为空，视为不提供岗位描述。</div>
        </div>

        <div v-else-if="jdMode === 'file'" class="jd-input">
          <el-upload
            drag
            :auto-upload="false"
            :show-file-list="true"
            :limit="1"
            :disabled="submitting"
            accept=".pdf,.png,.jpg,.jpeg,.webp,.bmp"
            :on-change="handleJdChange"
            :on-remove="onJdRemove"
          >
            <el-icon class="el-icon--upload"><Document /></el-icon>
            <div class="el-upload__text">拖入岗位 JD 文件，或<em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">
                支持 PDF 与图片，扫描件可识别，单文件不超过 10MB；与粘贴文字二选一
              </div>
            </template>
          </el-upload>
        </div>

        <div class="actions">
          <el-button type="primary" size="large" :loading="submitting" @click="start">
            开始面试
          </el-button>
          <span v-if="progressText" class="muted progress">{{ progressText }}</span>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.upload-card {
  max-width: 640px;
}

.jd-card {
  margin-top: var(--space-4);
}

.settings-card {
  margin-top: var(--space-4);
}

.setting-row {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-top: var(--space-3);
  flex-wrap: wrap;
}

.setting-label {
  min-width: 5em;
  font-size: 0.9rem;
  color: var(--color-text-secondary);
}

.section-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.section-title {
  margin: 0;
  font-size: 1rem;
}

.jd-input {
  margin-top: var(--space-3);
}

.hint {
  margin-top: var(--space-2);
  font-size: 0.85rem;
}

.actions {
  margin-top: var(--space-6);
  display: flex;
  align-items: center;
}

.progress {
  margin-left: 12px;
}
</style>
