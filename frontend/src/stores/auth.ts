import { defineStore } from "pinia";
import { authApi } from "@/api/auth";
import { getToken, setToken, clearToken } from "@/api/http";
import type { UserLoginReqDTO, UserRegisterReqDTO } from "@/types/auth";

interface AuthState {
  username: string;
  isAdmin: boolean;
  token: string | null;
}

export const useAuthStore = defineStore("auth", {
  state: (): AuthState => ({
    username: "",
    isAdmin: false,
    token: getToken(),
  }),

  getters: {
    isLoggedIn: (state) => !!state.token && !!state.username,
  },

  actions: {
    async login(data: UserLoginReqDTO) {
      const result = await authApi.login(data);
      setToken(result.token);
      this.token = result.token;
      this.username = result.username;
      this.isAdmin = result.isAdmin;
    },

    async register(data: UserRegisterReqDTO) {
      await authApi.register(data);
    },

    async restoreSession() {
      const token = getToken();
      if (!token) return;
      try {
        const result = await authApi.checkLogin();
        if (result.isLogin && result.username) {
          this.token = result.token ?? token;
          this.username = result.username;
        } else {
          this.clear();
        }
      } catch {
        this.clear();
      }
    },

    async logout() {
      try {
        await authApi.logout();
      } catch {
        // 忽略登出接口异常，本地仍清理
      }
      this.clear();
    },

    clear() {
      clearToken();
      this.token = null;
      this.username = "";
      this.isAdmin = false;
    },
  },
});
