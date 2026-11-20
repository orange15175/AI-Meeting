import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import { getToken } from "@/api/http";

const routes: RouteRecordRaw[] = [
  {
    path: "/auth",
    name: "auth",
    component: () => import("@/views/AuthView.vue"),
    meta: { public: true, title: "登录" },
  },
  {
    path: "/",
    name: "dashboard",
    component: () => import("@/views/DashboardView.vue"),
    meta: { title: "工作台" },
  },
  {
    path: "/interview/new",
    name: "interview-new",
    component: () => import("@/views/NewInterviewView.vue"),
    meta: { title: "新建面试" },
  },
  {
    path: "/interview/prepare/:sessionId",
    name: "interview-prepare",
    component: () => import("@/views/PreparationView.vue"),
    meta: { title: "准备中" },
  },
  {
    path: "/interview/room/:sessionId",
    name: "interview-room",
    component: () => import("@/views/InterviewView.vue"),
    meta: { title: "面试" },
  },
  {
    path: "/interview/records",
    name: "interview-records",
    component: () => import("@/views/RecordsView.vue"),
    meta: { title: "面试记录" },
  },
  {
    path: "/interview/report/:sessionId",
    name: "interview-report",
    component: () => import("@/views/ReportView.vue"),
    meta: { title: "复盘报告" },
  },
  {
    path: "/chat",
    name: "chat",
    component: () => import("@/views/ChatView.vue"),
    meta: { title: "AI 聊天" },
  },
  {
    path: "/:pathMatch(.*)*",
    redirect: "/",
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  const token = getToken();
  if (!to.meta.public && !token) {
    return { name: "auth", query: { redirect: to.fullPath } };
  }
  if (to.meta.public && token) {
    return { name: "dashboard" };
  }
  document.title = `${String(to.meta.title ?? "AI 模拟面试")} · AI 模拟面试`;
  return true;
});

export default router;
