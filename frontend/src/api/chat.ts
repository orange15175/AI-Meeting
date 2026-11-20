import { http, getToken, buildApiUrl } from "./http";
import type {
  AiConversationsPage,
  AiMessageHistory,
  CreateConversationResult,
  ChatStreamCallbacks,
} from "@/types/chat";

export const chatApi = {
  pageConversations: (params?: { current?: number; size?: number }) =>
    http.get<AiConversationsPage>("/ai-meeting/v1/ai/conversations", { params }),

  createConversation: (data: { aiId: number; firstMessage: string }) =>
    http.post<CreateConversationResult>("/ai-meeting/v1/ai/conversations", data),

  getHistory: (sessionId: string) =>
    http.get<AiMessageHistory[]>(
      `/ai-meeting/v1/ai/history/${encodeURIComponent(sessionId)}`,
    ),

  deleteConversation: (sessionId: string) =>
    http.delete<void>(`/ai-meeting/v1/ai/conversations/${encodeURIComponent(sessionId)}`),

  /**
   * 普通聊天 SSE。后端以 text/event-stream 返回 `data:{json}` 帧，
   * json 形如 {"type":"content","content":"..."} 或 {"type":"reasoning_content",...}。
   */
  streamChat: async (
    sessionId: string,
    inputMessage: string,
    aiId: number,
    signal: AbortSignal,
    callbacks: ChatStreamCallbacks,
  ): Promise<void> => {
    const token = getToken();
    const response = await fetch(
      buildApiUrl(`/ai-meeting/v1/ai/sessions/${encodeURIComponent(sessionId)}/chat`),
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ sessionId, inputMessage, aiId }),
        signal,
      },
    );

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      throw new Error(text || `聊天请求失败 (${response.status})`);
    }

    const reader = response.body?.getReader();
    if (!reader) {
      callbacks.onDone?.();
      return;
    }

    const decoder = new TextDecoder("utf-8");
    let buffer = "";

    const processLine = (line: string) => {
      const trimmed = line.trim();
      if (!trimmed.startsWith("data:")) return;
      const data = trimmed.slice(5).trim();
      if (!data) return;
      try {
        const parsed = JSON.parse(data) as {
          type?: string;
          content?: string;
        };
        if (parsed.type === "reasoning_content") {
          callbacks.onReasoning?.(parsed.content ?? "");
        } else if (parsed.content) {
          callbacks.onMessage(parsed.content);
        }
      } catch {
        // 非 JSON 帧（如 [DONE]）忽略
      }
    };

    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const line of lines) processLine(line);
    }
    if (buffer) processLine(buffer);
    callbacks.onDone?.();
  },
};
