<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useAuthStore } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const mode = ref<"login" | "register">("login");
const loading = ref(false);

const form = reactive({
  username: "",
  password: "",
  confirm: "",
  realName: "",
});

const submit = async () => {
  if (!form.username.trim() || !form.password.trim()) {
    ElMessage.warning("请填写用户名和密码");
    return;
  }
  if (mode.value === "register" && form.password !== form.confirm) {
    ElMessage.warning("两次输入的密码不一致");
    return;
  }

  loading.value = true;
  try {
    if (mode.value === "login") {
      await auth.login({ username: form.username.trim(), password: form.password });
      ElMessage.success("登录成功");
    } else {
      await auth.register({
        username: form.username.trim(),
        password: form.password,
        realName: form.realName.trim() || undefined,
      });
      ElMessage.success("注册成功，请登录");
      mode.value = "login";
      form.password = "";
      form.confirm = "";
      return;
    }
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/";
    router.push(redirect);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "操作失败");
  } finally {
    loading.value = false;
  }
};
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <span class="brand-logo">AI</span>
        <h1>AI 模拟面试</h1>
        <p class="muted">面向 Java 求职者的文字模拟面试平台</p>
      </div>

      <el-tabs v-model="mode" class="auth-tabs">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="真实姓名（可选）">
          <el-input v-model="form.realName" placeholder="请输入真实姓名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="确认密码">
          <el-input
            v-model="form.confirm"
            type="password"
            show-password
            placeholder="请再次输入密码"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button
          type="primary"
          class="submit-btn"
          :loading="loading"
          @click="submit"
        >
          {{ mode === "login" ? "登录" : "注册" }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg);
}

.auth-card {
  width: 400px;
  max-width: 90vw;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-8);
}

.auth-brand {
  text-align: center;
  margin-bottom: var(--space-6);
}

.brand-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: #fff;
  font-weight: 700;
  font-size: 20px;
  margin-bottom: var(--space-3);
}

.auth-brand h1 {
  margin: 0 0 var(--space-2);
  font-size: 20px;
}

.submit-btn {
  width: 100%;
}
</style>
