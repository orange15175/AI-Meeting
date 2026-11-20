/** 将后端返回的时间（ISO 或 yyyy-MM-dd HH:mm:ss）格式化为易读字符串。 */
export const formatDateTime = (value?: string | null): string => {
  if (!value) return "-";
  const normalized = value.includes("T") ? value.replace("T", " ") : value;
  const trimmed = normalized.split(".")[0];
  // 去掉可能的时区后缀（如 +00:00）
  return trimmed.replace(/\s*\+\d{2}:\d{2}$/, "");
};
