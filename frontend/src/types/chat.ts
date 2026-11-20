export interface AiConversationItem {
  sessionId: string;
  username?: string;
  aiId?: number;
  aiName?: string;
  title?: string;
  status?: number;
  messageCount?: number;
  lastMessageTime?: string | null;
  createTime?: string | null;
}

export interface AiConversationsPage {
  records: AiConversationItem[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface AiMessageHistory {
  id?: string;
  sessionId?: string;
  messageType?: number;
  messageContent?: string;
  messageSeq?: number;
  createTime?: string | null;
}

export interface CreateConversationResult {
  sessionId: string;
  conversationTitle?: string;
}

export interface ChatStreamCallbacks {
  onMessage: (content: string) => void;
  onReasoning?: (content: string) => void;
  onDone?: () => void;
  onError?: (error: unknown) => void;
}
