package com.hewei.hzyjy.aimeeting.interview.application.flow;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewFlowState;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewFlowStatus;
import org.springframework.stereotype.Component;

/**
 * 面试流程规则：把"当前题 + 追问判定"换算成下一步游标。
 *
 * <p>P3 起流程游标以 MySQL 会话行为准，本类只做纯计算，不再读写缓存。
 * 真正的并发保护由会话行的版本条件更新负责。</p>
 */
@Component
public class InterviewFlowStateMachine {

    // 从数据库会话行映射出计算用状态快照；保留 version 供之后提交校验，本方法不会保存状态。
    public InterviewFlowState toFlow(InterviewSession session) {
        if (session == null) {
            return null;
        }
        InterviewFlowState state = new InterviewFlowState();
        state.setStatus(StrUtil.blankToDefault(session.getFlowStatus(), InterviewFlowStatus.INIT.name()));
        state.setCurrentQuestionNumber(session.getCurrentQuestionNo());
        state.setTotalQuestions(session.getTotalQuestions());
        state.setFollowUpCount(session.getFollowUpCount());
        state.setMaxFollowUp(session.getMaxFollowUp());
        state.setVersion(session.getVersion());
        state.setCurrentIndex(mainIndexOf(session.getCurrentQuestionNo()) - 1);
        return state;
    }

    // 题内终态依据 flowStatus 判断；整场生命周期 status 的可恢复/结束检查在会话服务层完成。
    public boolean isCompleted(InterviewFlowState state) {
        return state != null && InterviewFlowStatus.from(state.getStatus()).isTerminal();
    }

    public boolean isOutOfRange(InterviewFlowState state) {
        if (state == null) {
            return false;
        }
        Integer total = state.getTotalQuestions();
        int mainIndex = mainIndexOf(state.getCurrentQuestionNumber());
        return total != null && total > 0 && mainIndex > total;
    }

    public String currentQuestionNumber(InterviewFlowState state) {
        if (state == null || isOutOfRange(state)) {
            return null;
        }
        return StrUtil.trimToNull(state.getCurrentQuestionNumber());
    }

    /**
     * 【关键节点：路由规划】优先追问，其次下一主问题，末题结束；只返回计划，由提交服务推进数据库游标。
     * 例如主问题 3 可进入 3-F1，再进入 3-F2；达到追问上限后进入 4，超出主问题总数则结束。
     */
    public InterviewNextStep plan(
            InterviewFlowState current,
            boolean needFollowUp,
            String followUpQuestion,
            int totalQuestions) {
        if (current == null) {
            return InterviewNextStep.finish();
        }
        int mainIndex = mainIndexOf(current.getCurrentQuestionNumber());
        if (mainIndex <= 0) {
            mainIndex = 1;
        }
        // 先解析当前主问题序号、追问次数和上限，再依次判断追问、下一主问题、结束三个分支。
        int maxFollowUp = current.getMaxFollowUp() == null || current.getMaxFollowUp() <= 0
                ? 2
                : current.getMaxFollowUp();
        int followUpIndex = followUpIndexOf(current.getCurrentQuestionNumber());

        // 分支 1：决策要求追问、题干非空且次数未超限，才规划同一主问题的下一次追问。
        if (needFollowUp && StrUtil.isNotBlank(followUpQuestion) && followUpIndex < maxFollowUp) {
            int nextFollowUpIndex = followUpIndex + 1;
            return InterviewNextStep.followUp(
                    mainIndex + "-F" + nextFollowUpIndex, mainIndex, nextFollowUpIndex);
        }

        // 分支 2/3：未产生追问就推进主问题；已经越过最后一题则规划 FINISH，等待事务提交该计划。
        int nextMainIndex = mainIndex + 1;
        if (totalQuestions <= 0 || nextMainIndex > totalQuestions) {
            return InterviewNextStep.finish();
        }
        return InterviewNextStep.nextMain(String.valueOf(nextMainIndex), nextMainIndex);
    }

    /**
     * 题号的主问题序号：1 / 3-F2 都返回其主问题序号。
     */
    public int mainIndexOf(String questionNo) {
        if (StrUtil.isBlank(questionNo)) {
            return 0;
        }
        String normalized = questionNo.trim().toUpperCase();
        int separator = normalized.indexOf("-F");
        String mainPart = separator > 0 ? normalized.substring(0, separator) : normalized;
        try {
            return Math.max(Integer.parseInt(mainPart.trim()), 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public int followUpIndexOf(String questionNo) {
        if (StrUtil.isBlank(questionNo)) {
            return 0;
        }
        String normalized = questionNo.trim().toUpperCase();
        int separator = normalized.indexOf("-F");
        if (separator < 0 || separator + 2 >= normalized.length()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(normalized.substring(separator + 2).trim()), 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public boolean isFollowUp(String questionNo) {
        return followUpIndexOf(questionNo) > 0;
    }
}
