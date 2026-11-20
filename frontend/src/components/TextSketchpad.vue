<script setup lang="ts">
import { onMounted, ref, watch } from "vue";

const props = defineProps<{ sessionId: string }>();
const emit = defineEmits<{ (e: "insert", text: string): void }>();

const storageKey = `ai-meeting:sketchpad:${props.sessionId}`;
const content = ref("");

onMounted(() => {
  content.value = localStorage.getItem(storageKey) ?? "";
});

watch(content, (value) => {
  localStorage.setItem(storageKey, value);
});

const insertToAnswer = () => {
  if (content.value.trim()) {
    emit("insert", content.value);
  }
};

const clear = () => {
  content.value = "";
};
</script>

<template>
  <div class="sketchpad">
    <div class="sketchpad-head">
      <span class="sketchpad-title">构思板</span>
      <div>
        <el-button size="small" text @click="insertToAnswer">插入答案</el-button>
        <el-button size="small" text @click="clear">清空</el-button>
      </div>
    </div>
    <el-input
      v-model="content"
      type="textarea"
      :rows="8"
      placeholder="在此记录你的回答提纲…"
    />
    <div class="muted" style="margin-top: 8px">内容自动保存，仅本机可见。</div>
  </div>
</template>

<style scoped>
.sketchpad {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}

.sketchpad-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-3);
}

.sketchpad-title {
  font-weight: 600;
}
</style>
