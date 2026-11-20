package com.hewei.hzyjy.aimeeting.common.enums;

import com.hewei.hzyjy.aimeeting.common.convention.errorcode.IErrorCode;

/**
 * Interview domain error codes.
 */
public enum InterviewErrorCodeEnum implements IErrorCode {

    SESSION_ID_EMPTY("B000400", "sessionId cannot be empty"),
    INVALID_USER_ID("B000401", "invalid userId"),
    CONVERSATION_NOT_FOUND("B000402", "conversation does not exist"),
    CONVERSATION_ACCESS_DENIED("B000403", "no permission to access this conversation"),
    INTERVIEW_SESSION_NOT_FOUND("B000412", "interview session does not exist"),
    INTERVIEW_SESSION_ACCESS_DENIED("B000413", "no permission to access this interview session"),
    INTERVIEW_SESSION_INVALID_STATE("B000414", "interview session state is invalid"),
    AI_TIMEOUT("B000415", "AI call timed out, please retry"),
    AI_OVERLOADED("B000416", "AI service is busy, please retry"),
    AI_UNAVAILABLE("B000417", "AI service is unavailable, please retry"),
    REQUEST_CONFLICT("B000418", "requestId reused with different input"),
    STALE_QUESTION("B000419", "stale question number, please refresh current question"),
    SESSION_BUSY("B000420", "another answer is being processed for this session"),
    INTERVIEW_NOT_PREPARED("B000421", "interview questions are not prepared yet"),
    INTERVIEW_ALREADY_STARTED("B000422", "interview has already started, create a new session to regenerate questions"),

    AGENT_CONFIG_NOT_FOUND("B000406", "agent configuration does not exist");

    private final String code;
    private final String message;

    InterviewErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
