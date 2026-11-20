<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type { UploadFile } from "element-plus";
import AppShell from "@/components/AppShell.vue";
import { interviewApi } from "@/api/interview";

const router = useRouter();
const file = ref<File | null>(null);
const submitting = ref(false);
const progressText = ref("");

type JdMode = "none" | "text" | "file";
const jdMode = ref<JdMode>("none");
const jdText = ref("");
const jdFile = ref<File | null>(null);

const ACCEPTED_EXTENSIONS = ["pdf", "png", "jpg", "jpeg", "webp", "bmp"];
const JD_TEXT_MAX = 10000;
const FILE_MAX_BYTES = 10 * 1024 * 1024;

const jdCharCount = computed(() => jdText.value.trim().length);
const jdEnabled = computed(
  () => (jdMode.value === "text" && jdCharCount.value > 0)
    || (jdMode.value === "file" && jdFile.value !== null),
);

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

const switchJdMode = (mode: string | number | boolean) => {
  const next = mode as JdMode;
  if (next !== "text") {
    jdText.value = "";
  }
  if (next !== "file") {
    jdFile.value = null;
  }
  jdMode.value = next;
};

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

  submitting.value = true;
  progressText.value = "正在创建面试会话…";
  try {
    const session = await interviewApi.createSession();
    progressText.value = jdEnabled.value
      ? "正在解析简历与岗位描述并生成题目…（通常需要 10~25 秒）"
      : "正在解析简历并生成题目…（通常需要 5~15 秒）";
    const result = await interviewApi.extractQuestions(session.sessionId, file.value, {
      jdText: jdMode.value === "text" ? jdText.value : undefined,
      jdFile: jdMode.value === "file" ? jdFile.value : null,
    });
    if (result.isSuccess === 0) {
      ElMessage.error(result.errorMessage || "出题失败");
      return;
    }
    ElMessage.success(result.jdEnabled ? "已按岗位要求生成题目" : "题目生成成功");
    router.push(`/interview/room/${session.sessionId}`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "出题失败，请重试");
  } finally {
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
