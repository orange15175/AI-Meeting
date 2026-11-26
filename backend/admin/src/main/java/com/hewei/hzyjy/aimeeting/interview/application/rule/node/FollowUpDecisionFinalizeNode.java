package com.hewei.hzyjy.aimeeting.interview.application.rule.node;

import com.hewei.hzyjy.aimeeting.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 【关键节点：规则 7】补齐决策原因、规则版本、链 ID 和上限，形成供流水线使用的结果。
 */
@LiteflowComponent("followUpDecisionFinalize")
public class FollowUpDecisionFinalizeNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        context.finalizeDecision(false);
    }
}
