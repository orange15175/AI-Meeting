import { describe, expect, it } from "vitest";
import { formatDateTime } from "./format";

describe("formatDateTime", () => {
  it("空值返回占位符", () => {
    expect(formatDateTime()).toBe("-");
    expect(formatDateTime(null)).toBe("-");
    expect(formatDateTime("")).toBe("-");
  });

  it("把 ISO 的 T 换成空格并去掉毫秒", () => {
    expect(formatDateTime("2026-11-19T12:35:51.123")).toBe("2026-11-19 12:35:51");
  });

  it("去掉时区后缀", () => {
    expect(formatDateTime("2026-11-19T12:35:51+08:00")).toBe("2026-11-19 12:35:51");
    expect(formatDateTime("2026-11-19 12:35:51 +00:00")).toBe("2026-11-19 12:35:51");
  });

  it("已格式化的时间原样保留", () => {
    expect(formatDateTime("2026-11-19 12:35:51")).toBe("2026-11-19 12:35:51");
  });
});
