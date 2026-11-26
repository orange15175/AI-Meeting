import { http } from "./http";
import type {
  InterviewAnswerResult,
  InterviewConversationsPage,
  InterviewQuestionResult,
  InterviewRecord,
  InterviewRecordsPage,
  InterviewSessionCreateResult,
  InterviewSessionRestore,
  RadarChart,
} from "@/types/interview";

const LONG_TIMEOUT = 180000;

/**
 * 面试 API 对照入口：1 创建会话 → 2 出题 → 3 读当前题 → 4 提交回答 → 5 结束 → 6 读取报告。
 * http 客户端统一添加 /api 前缀；本文件中的路径与后端 Controller 的 /api 路径对应。
 */
export const interviewApi = {
  // 泛型是期望接收的返回类型；实际请求体为 {}，登录用户由 http 请求拦截器添加的 token 标识。
  createSession: () =>
    http.post<InterviewSessionCreateResult>("/ai-meeting/v1/interview/sessions", {}),

  pageConversations: (params: { current?: number; size?: number; status?: string }) =>
    http.get<InterviewConversationsPage>("/ai-meeting/v1/interview/conversations", {
      params,
    }),

  // 【面试主线 2/6】multipart 提交简历、当前模式的 JD 与出题选项；耗时操作使用独立超时预算。
  extractQuestions: (
    sessionId: string,
    resumePdf: File,
    options?: {
      jdText?: string;
      jdFile?: File | null;
      /** 本场题量，后端会把关区间（默认 5~20），不传则用服务端默认值 */
      questionCount?: number;
      /** 难度倾向枚举名：AUTO / BASIC / ADVANCED / CHALLENGE */
      difficulty?: string;
    },
  ) => {
    // multipart 字段名必须和后端 @RequestParam 一致：resumePdf 必传，jdText/jdFile 可选且互斥。
    const form = new FormData();
    form.append("resumePdf", resumePdf);
    // 非空 JD 文字优先；没有文字才附加文件；两者都没有时只提交简历，JD 从来不是必填项。
    const jdText = options?.jdText?.trim();
    if (jdText) {
      form.append("jdText", jdText);
    } else if (options?.jdFile) {
      form.append("jdFile", options.jdFile);
    }
    // 选项只在有值时提交：不传时后端用配置默认值与「智能匹配」，避免前端把默认值写死成协议的一部分。
    if (options?.questionCount) {
      form.append("questionCount", String(options.questionCount));
    }
    if (options?.difficulty) {
      form.append("difficulty", options.difficulty);
    }
    return http.post<InterviewQuestionResult>(
      `/ai-meeting/v1/interview/sessions/${encodeURIComponent(sessionId)}/interview-questions`,
      form,
      { timeout: LONG_TIMEOUT, headers: { "Content-Type": "multipart/form-data" } },
    );
  },

  // 【面试主线 4/6】返回本轮评分和 nextQuestion；题号的决定权在后端，前端不自行加一。
  answer: (
    sessionId: string,
    payload: { questionNumber: string; answerContent?: string; requestId?: string },
  ) =>
    http.post<InterviewAnswerResult>(
      `/ai-meeting/v1/interview/sessions/${encodeURIComponent(sessionId)}/interview/answer-json`,
      payload,
      { timeout: LONG_TIMEOUT },
    ),

  getCurrentQuestion: (sessionId: string) =>
    http.get<InterviewAnswerResult>(
      `/ai-meeting/v1/interview/sessions/${encodeURIComponent(sessionId)}/current-question`,
    ),

  restore: (sessionId: string) =>
    http.get<InterviewSessionRestore>(
      `/ai-meeting/v1/interview/sessions/${encodeURIComponent(sessionId)}/restore`,
    ),

  getRadarChart: (sessionId: string) =>
    http.get<RadarChart>(
      `/ai-meeting/v1/interview/sessions/${encodeURIComponent(sessionId)}/radar-chart`,
    ),

  finish: (sessionId: string) =>
    http.put<void>(
      `/ai-meeting/v1/interview/sessions/${encodeURIComponent(sessionId)}/finish`,
      {},
    ),

  getRecord: (sessionId: string) =>
    http.get<InterviewRecord>(
      `/ai-meeting/v1/interview/interview/record/${encodeURIComponent(sessionId)}`,
    ),

  pageRecords: (params: { current?: number; size?: number }) =>
    http.get<InterviewRecordsPage>("/ai-meeting/v1/interview/interview/records", {
      params,
    }),
};
