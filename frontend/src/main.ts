import { createApp } from "vue";
import type { Component } from "vue";
import { createPinia } from "pinia";
import "element-plus/dist/index.css";
import {
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElPagination,
  ElTabPane,
  ElTable,
  ElTableColumn,
  ElTabs,
  ElTag,
  ElUpload,
} from "element-plus";
import {
  ArrowDown,
  ChatDotRound,
  Delete,
  EditPen,
  HomeFilled,
  Loading,
  Plus,
  Tickets,
  UploadFilled,
  User,
} from "@element-plus/icons-vue";
import App from "./App.vue";
import router from "./router";
import "./styles/main.css";

const app = createApp(App);

app.use(createPinia());
app.use(router);

// 按需注册：只挂载模板实际使用的 Element Plus 组件，
// 避免 app.use(ElementPlus) 把整套组件打进主包。
const components: Record<string, Component> = {
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElPagination,
  ElTabPane,
  ElTable,
  ElTableColumn,
  ElTabs,
  ElTag,
  ElUpload,
};
for (const [name, component] of Object.entries(components)) {
  app.component(name, component);
}

// 侧栏导航用字符串 :is 动态解析，因此图标仍需全局注册；
// 但只注册实际用到的 10 个，不再整包引入。
const icons = {
  ArrowDown,
  ChatDotRound,
  Delete,
  EditPen,
  HomeFilled,
  Loading,
  Plus,
  Tickets,
  UploadFilled,
  User,
};
for (const [name, component] of Object.entries(icons)) {
  app.component(name, component);
}

app.mount("#app");
