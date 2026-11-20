<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const navItems = [
  { path: "/", label: "工作台", icon: "HomeFilled" },
  { path: "/interview/new", label: "新建面试", icon: "EditPen" },
  { path: "/interview/records", label: "面试记录", icon: "Tickets" },
  { path: "/chat", label: "AI 聊天", icon: "ChatDotRound" },
];

const activePath = computed(() => route.path);

const handleLogout = async () => {
  await auth.logout();
  router.push("/auth");
};
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-logo">AI</span>
        <span class="brand-name">模拟面试</span>
      </div>
      <nav class="nav">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: activePath === item.path }"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="topbar-title">{{ String(route.meta.title ?? "") }}</div>
        <div class="topbar-user">
          <el-dropdown trigger="click">
            <span class="user-chip">
              <el-icon><User /></el-icon>
              <span>{{ auth.username || "未登录" }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <main class="content">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100%;
  min-height: 100vh;
}

.sidebar {
  width: 224px;
  flex-shrink: 0;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  padding: var(--space-6) var(--space-3);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-3);
  margin-bottom: var(--space-8);
}

.brand-logo {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-3);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  transition: all 0.15s;
}

.nav-item:hover {
  background: var(--color-bg);
  color: var(--color-text);
}

.nav-item.active {
  background: var(--color-primary);
  color: #fff;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  height: 64px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-6);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}

.topbar-title {
  font-size: 16px;
  font-weight: 600;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  color: var(--color-text);
}

.user-chip:hover {
  background: var(--color-bg);
}

.content {
  flex: 1;
  overflow: auto;
}

@media (max-width: 768px) {
  .sidebar {
    width: 64px;
    padding: var(--space-6) var(--space-2);
  }
  .brand-name,
  .nav-item span {
    display: none;
  }
  .nav-item {
    justify-content: center;
  }
}
</style>
