package com.hewei.hzyjy.aimeeting.interview.application.rule.node;

import com.hewei.hzyjy.aimeeting.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 【关键节点：规则 4】未被前置守卫终止时，采纳评分模型的追问建议。
 */
@LiteflowComponent("aiSuggestionJudge")
public class AiSuggestionJudgeNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) {
            return;
        }
        if (context.isFollowUpNeededFromAi()) {
            context.markNeedFollowUp("AI_SUGGESTED", "follow-up suggested by ai result");
        }
    }
}
