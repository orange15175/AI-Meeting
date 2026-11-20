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

export const interviewApi = {
  createSession: () =>
    http.post<InterviewSessionCreateResult>("/ai-meeting/v1/interview/sessions", {}),

  pageConversations: (params: { current?: number; size?: number; status?: string }) =>
    http.get<InterviewConversationsPage>("/ai-meeting/v1/interview/conversations", {
      params,
    }),

  extractQuestions: (
    sessionId: string,
    resumePdf: File,
    options?: { jdText?: string; jdFile?: File | null },
  ) => {
    const form = new FormData();
    form.append("resumePdf", resumePdf);
    const jdText = options?.jdText?.trim();
    if (jdText) {
      form.append("jdText", jdText);
    } else if (options?.jdFile) {
      form.append("jdFile", options.jdFile);
    }
    return http.post<InterviewQuestionResult>(
      `/ai-meeting/v1/interview/sessions/${encodeURIComponent(sessionId)}/interview-questions`,
      form,
      { timeout: LONG_TIMEOUT, headers: { "Content-Type": "multipart/form-data" } },
    );
  },

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
