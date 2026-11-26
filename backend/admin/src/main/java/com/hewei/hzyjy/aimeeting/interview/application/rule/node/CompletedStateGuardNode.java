package com.hewei.hzyjy.aimeeting.interview.application.rule.node;

import com.hewei.hzyjy.aimeeting.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 【关键节点：规则 2】上下文已结束时标记不追问，并终止后续判定；不负责将会话写成结束。
 */
@LiteflowComponent("completedStateGuard")
public class CompletedStateGuardNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isInterviewCompleted()) {
            context.markNoFollowUp("INTERVIEW_COMPLETED", "interview already completed");
        }
    }
}
