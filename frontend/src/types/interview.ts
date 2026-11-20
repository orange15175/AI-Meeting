export interface InterviewSessionCreateResult {
  sessionId: string;
  status?: string | null;
}

export interface InterviewQuestionResult {
  id?: string;
  sessionId?: string;
  questions?: Record<string, string>;
  suggestions?: Record<string, string>;
  interviewType?: string;
  resumeFileUrl?: string;
  resumeScore?: number;
  jdEnabled?: boolean | null;
  jdSource?: string | null;
  jdFileName?: string | null;
  jdSummary?: string | null;
  questionCount?: number;
  isSuccess?: number;
  errorMessage?: string;
}

export interface InterviewAnswerResult {
  questionNumber?: string;
  questionContent?: string;
  score?: number;
  totalScore?: number;
  isSuccess?: boolean;
  errorMessage?: string;
  feedback?: string;
  nextQuestion?: string | null;
  nextQuestionNumber?: string | null;
  isFollowUp?: boolean;
  followUpCount?: number;
  finished?: boolean;
}

export interface InterviewConversationItem {
  sessionId: string;
  conversationTitle?: string | null;
  status?: string | null;
  interviewType?: string | null;
  resumeFileUrl?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface InterviewConversationsPage {
  records: InterviewConversationItem[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface InterviewSessionRestore {
  sessionId?: string | null;
  status?: string | null;
  canResume?: boolean | null;
  resumeFileUrl?: string | null;
  resumeScore?: number | null;
  interviewType?: string | null;
  jdEnabled?: boolean | null;
  jdSource?: string | null;
  jdFileName?: string | null;
  jdSummary?: string | null;
  suggestions?: Record<string, string> | null;
}

export interface JdCoverageItem {
  requirement?: string | null;
  questionNumbers?: string[] | null;
  status?: "COVERED" | "PENDING" | string | null;
}

export interface RadarChart {
  resumeScore?: number | null;
  interviewPerformance?: number | null;
  potentialIndex?: number | null;
  professionalSkills?: number | null;
}

export interface RadarDimensionItem {
  key: string;
  label: string;
  score: number;
}

export interface InterviewPlaybackItem {
  seq?: number;
  questionNumber?: string;
  questionContent?: string;
  answerContent?: string;
  score?: number;
  feedback?: string;
  totalScore?: number;
  followUpNeeded?: boolean;
  isFollowUp?: boolean;
  followUpCount?: number;
  finished?: boolean;
}

export interface InterviewReviewFeedback {
  overallComment?: string | null;
  highlights?: string[] | null;
  improvementTips?: string[] | null;
  nextActions?: string[] | null;
}

export interface InterviewRecord {
  id: number;
  sessionId: string;
  interviewScore?: number | null;
  resumeScore?: number | null;
  interviewStatus?: string | null;
  questionCount?: number | null;
  interviewSuggestions?: string | null;
  interviewSuggestionsMap?: Record<string, string> | null;
  interviewDirection?: string | null;
  durationSeconds?: number | null;
  startTime?: string | null;
  endTime?: string | null;
  radarChart?: RadarChart | null;
  radarDimensions?: RadarDimensionItem[] | null;
  playbackItems?: InterviewPlaybackItem[] | null;
  reviewFeedback?: InterviewReviewFeedback | null;
  jdSummary?: string | null;
  jdCoverage?: JdCoverageItem[] | null;
  createTime?: string | null;
}

export interface InterviewRecordsPage {
  records: InterviewRecord[];
  total: number;
  size: number;
  current: number;
  pages: number;
}
