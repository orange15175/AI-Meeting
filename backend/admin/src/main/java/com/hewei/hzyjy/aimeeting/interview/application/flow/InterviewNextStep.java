package com.hewei.hzyjy.aimeeting.interview.application.flow;

import lombok.Getter;

/**
 * 一次答题提交后的下一步动作。
 */
@Getter
public class InterviewNextStep {

    public enum Kind {
        /**
         * 进入同一主问题下的追问。
         */
        FOLLOW_UP,
        /**
         * 推进到下一道主问题。
         */
        NEXT_MAIN,
        /**
         * 主问题已答完，面试结束。
         */
        FINISH
    }

    private final Kind kind;
    private final String questionNo;
    private final Integer mainIndex;
    private final Integer followUpIndex;

    private InterviewNextStep(Kind kind, String questionNo, Integer mainIndex, Integer followUpIndex) {
        this.kind = kind;
        this.questionNo = questionNo;
        this.mainIndex = mainIndex;
        this.followUpIndex = followUpIndex;
    }

    public static InterviewNextStep followUp(String questionNo, int mainIndex, int followUpIndex) {
        return new InterviewNextStep(Kind.FOLLOW_UP, questionNo, mainIndex, followUpIndex);
    }

    public static InterviewNextStep nextMain(String questionNo, int mainIndex) {
        return new InterviewNextStep(Kind.NEXT_MAIN, questionNo, mainIndex, 0);
    }

    public static InterviewNextStep finish() {
        return new InterviewNextStep(Kind.FINISH, null, null, 0);
    }

    public boolean isFinished() {
        return kind == Kind.FINISH;
    }
}
