package com.hewei.hzyjy.aimeeting.interview.application.flow;

import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewFlowStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewFlowStateMachineTest {

    private final InterviewFlowStateMachine stateMachine = new InterviewFlowStateMachine();

    @Test
    void shouldParseMainIndexOfQuestionNo() {
        assertEquals(1, stateMachine.mainIndexOf("1"));
        assertEquals(3, stateMachine.mainIndexOf("3-F2"));
        assertEquals(3, stateMachine.mainIndexOf(" 3-f1 "));
        assertEquals(0, stateMachine.mainIndexOf(""));
        assertEquals(0, stateMachine.mainIndexOf("abc"));
        assertEquals(0, stateMachine.mainIndexOf(null));
    }

    @Test
    void shouldParseFollowUpIndexOfQuestionNo() {
        assertEquals(0, stateMachine.followUpIndexOf("1"));
        assertEquals(1, stateMachine.followUpIndexOf("1-F1"));
        assertEquals(2, stateMachine.followUpIndexOf("3-F2"));
        assertEquals(0, stateMachine.followUpIndexOf(""));
        assertEquals(0, stateMachine.followUpIndexOf("1-F"));
    }

    @Test
    void shouldIdentifyFollowUpQuestion() {
        assertTrue(stateMachine.isFollowUp("1-F1"));
        assertFalse(stateMachine.isFollowUp("1"));
        assertFalse(stateMachine.isFollowUp(""));
    }

    @Test
    void shouldPlanFollowUpWhenRequestedAndWithinBudget() {
        InterviewFlowState current = state("ASKING", "1", 3, 0, 2);
        InterviewNextStep step = stateMachine.plan(current, true, "说说为什么这样设计", 3);

        assertEquals(InterviewNextStep.Kind.FOLLOW_UP, step.getKind());
        assertEquals("1-F1", step.getQuestionNo());
        assertEquals(1, step.getMainIndex());
        assertEquals(1, step.getFollowUpIndex());
    }

    @Test
    void shouldAdvanceToNextFollowUpWhenAlreadyFollowed() {
        InterviewFlowState current = state("FOLLOW_UP", "1-F1", 3, 1, 2);
        InterviewNextStep step = stateMachine.plan(current, true, "再追问一层", 3);

        assertEquals(InterviewNextStep.Kind.FOLLOW_UP, step.getKind());
        assertEquals("1-F2", step.getQuestionNo());
        assertEquals(2, step.getFollowUpIndex());
    }

    @Test
    void shouldAdvanceToNextMainQuestionWhenNoFollowUp() {
        InterviewFlowState current = state("ASKING", "1", 3, 0, 2);
        InterviewNextStep step = stateMachine.plan(current, false, null, 3);

        assertEquals(InterviewNextStep.Kind.NEXT_MAIN, step.getKind());
        assertEquals("2", step.getQuestionNo());
        assertEquals(2, step.getMainIndex());
    }

    @Test
    void shouldFinishWhenLastMainQuestionAnswered() {
        InterviewFlowState current = state("ASKING", "3", 3, 0, 2);
        InterviewNextStep step = stateMachine.plan(current, false, null, 3);

        assertTrue(step.isFinished());
        assertEquals(InterviewNextStep.Kind.FINISH, step.getKind());
    }

    @Test
    void shouldFinishWhenNoMoreFollowUpBudget() {
        InterviewFlowState current = state("FOLLOW_UP", "3-F2", 3, 2, 2);
        InterviewNextStep step = stateMachine.plan(current, true, "没预算了", 3);

        assertTrue(step.isFinished());
    }

    @Test
    void shouldFinishWhenStateIsNull() {
        assertTrue(stateMachine.plan(null, false, null, 3).isFinished());
    }

    @Test
    void shouldIdentifyCompletedAndOutOfRange() {
        assertTrue(stateMachine.isCompleted(state("COMPLETED", "3", 3, 0, 2)));
        assertFalse(stateMachine.isCompleted(state("ASKING", "1", 3, 0, 2)));

        assertTrue(stateMachine.isOutOfRange(state("ASKING", "3", 2, 0, 2)));
        assertFalse(stateMachine.isOutOfRange(state("ASKING", "2", 3, 0, 2)));
    }

    @Test
    void shouldReturnCurrentQuestionNumberOrNullWhenOutOfRange() {
        assertEquals("2", stateMachine.currentQuestionNumber(state("ASKING", "2", 3, 0, 2)));
        assertNull(stateMachine.currentQuestionNumber(state("ASKING", "4", 3, 0, 2)));
    }

    private InterviewFlowState state(String status, String questionNo, int totalQuestions, int followUpCount, int maxFollowUp) {
        InterviewFlowState state = new InterviewFlowState();
        state.setStatus(status);
        state.setCurrentQuestionNumber(questionNo);
        state.setTotalQuestions(totalQuestions);
        state.setFollowUpCount(followUpCount);
        state.setMaxFollowUp(maxFollowUp);
        state.setVersion(1);
        return state;
    }
}
