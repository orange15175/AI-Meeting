import { beforeEach, describe, expect, it, vi } from "vitest";

// http.ts 在模块加载时会创建 axios 实例；这里提供最小浏览器全局，
// 让 token 相关纯函数可测，且不需要真实 DOM。
const storage = new Map<string, string>();
vi.stubGlobal("localStorage", {
  getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => storage.set(key, value),
  removeItem: (key: string) => storage.delete(key),
  clear: () => storage.clear(),
});

import {
  buildApiUrl,
  clearToken,
  getToken,
  isApiResult,
  setToken,
  unwrapApiResult,
} from "./http";

describe("token 存取", () => {
  beforeEach(() => storage.clear());

  it("写入后可读取，清除后为 null", () => {
    expect(getToken()).toBeNull();
    setToken("tk-123");
    expect(getToken()).toBe("tk-123");
    clearToken();
    expect(getToken()).toBeNull();
  });
});

describe("buildApiUrl", () => {
  it("给后端路径加 /api 前缀", () => {
    expect(buildApiUrl("/ai-meeting/v1/ai/history/s1")).toBe(
      "/api/ai-meeting/v1/ai/history/s1",
    );
  });
});

describe("isApiResult", () => {
  it("识别 Result 包装", () => {
    expect(isApiResult({ code: "0", data: 1, success: true })).toBe(true);
    expect(isApiResult({ success: false, data: null })).toBe(true);
  });

  it("普通数据与空值不算 Result", () => {
    expect(isApiResult({ data: 1 })).toBe(false);
    expect(isApiResult(null)).toBe(false);
    expect(isApiResult("raw")).toBe(false);
  });
});

describe("unwrapApiResult", () => {
  it("成功 Result 返回 data", () => {
    expect(unwrapApiResult({ code: "0", data: { id: 7 }, success: true })).toEqual({
      id: 7,
    });
  });

  it("success=true 即使 code 非 0 也解包", () => {
    expect(unwrapApiResult({ code: "A000001", data: "ok", success: true })).toBe(
      "ok",
    );
  });

  it("业务失败抛出后端 message（Result<Void> 无 data 字段也必须识别）", () => {
    expect(() =>
      unwrapApiResult({ code: "B000414", message: "重复提交", success: false }),
    ).toThrow("重复提交");
  });

  it("业务失败但无 message 用默认文案", () => {
    expect(() =>
      unwrapApiResult({ code: "B000001", message: null, success: false }),
    ).toThrow("请求失败");
  });

  it("Result<Void> 成功响应即使没有 data 也返回空数据而不抛错", () => {
    expect(unwrapApiResult({ code: "0", success: true })).toBeUndefined();
  });

  it("非包装数据原样返回", () => {
    expect(unwrapApiResult([1, 2, 3])).toEqual([1, 2, 3]);
  });
});
