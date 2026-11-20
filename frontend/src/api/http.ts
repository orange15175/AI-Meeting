import axios, { type AxiosInstance, type AxiosRequestConfig } from "axios";
import { ElMessage } from "element-plus";

export interface ApiResult<T = unknown> {
  code: string;
  message: string | null;
  data: T;
  requestId: string | null;
  success: boolean;
}

const TOKEN_KEY = "ai-meeting:token";

export const getToken = (): string | null => localStorage.getItem(TOKEN_KEY);
export const setToken = (token: string) => localStorage.setItem(TOKEN_KEY, token);
export const clearToken = () => localStorage.removeItem(TOKEN_KEY);

const instance: AxiosInstance = axios.create({
  baseURL: "/api",
  timeout: 30000,
  headers: { "Content-Type": "application/json;charset=utf-8" },
});

instance.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

const redirectToLogin = () => {
  if (window.location.pathname !== "/auth") {
    window.location.href = "/auth";
  }
};

instance.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status;
    if (status === 401 || status === 403) {
      clearToken();
      ElMessage.error("登录已失效，请重新登录");
      redirectToLogin();
    }
    const message =
      error?.response?.data?.message || error?.message || "请求失败";
    return Promise.reject(new Error(message));
  },
);

/**
 * 判断是否为后端 Result 包装。
 *
 * <p>后端 {@code Results.success()} / {@code failure()} 返回 {@code Result<Void>}，
 * 其 data 可能为 null 或被 Jackson 的 NON_NULL 策略省略；因此不能要求 data 字段存在，
 * 否则成功/失败响应会被误判为裸数据而吞掉业务错误码。以 code/success 判定更可靠。</p>
 */
export const isApiResult = (payload: unknown): payload is ApiResult => {
  if (!payload || typeof payload !== "object") return false;
  const candidate = payload as Record<string, unknown>;
  return (
    typeof candidate.code === "string" ||
    typeof candidate.success === "boolean"
  );
};

/**
 * 解包后端 Result 包装。成功时返回 data，业务失败时抛出带后端 message 的 Error。
 * 抽成纯函数便于单测覆盖契约，不改变请求层行为。
 */
export const unwrapApiResult = <T>(payload: unknown): T => {
  if (isApiResult(payload)) {
    if (payload.code === "0" || payload.success) {
      return payload.data as T;
    }
    throw new Error(payload.message || "请求失败");
  }
  return payload as T;
};

async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await instance.request<ApiResult<T> | T>(config);
  return unwrapApiResult<T>(response.data);
}

export const http = {
  get: <T>(url: string, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: "GET", url }),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: "POST", url, data }),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: "PUT", url, data }),
  delete: <T>(url: string, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: "DELETE", url }),
};

export const buildApiUrl = (path: string): string => `/api${path}`;
