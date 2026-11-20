<script setup lang="ts">
import { onMounted } from "vue";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();

onMounted(() => {
  // 启动时尝试用本地 token 恢复登录态；失败则静默留在未登录态。
  auth.restoreSession().catch(() => undefined);
});
</script>

<template>
  <router-view />
</template>
