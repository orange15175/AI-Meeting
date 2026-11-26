package com.hewei.hzyjy.aimeeting.interview.application.rule.node;

import com.hewei.hzyjy.aimeeting.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 【关键节点：规则 3】达到追问次数上限则终止判定，后续 AI 建议或低分不能覆盖这个结果。
 */
@LiteflowComponent("followUpLimitGuard")
public class FollowUpLimitGuardNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) {
            return;
        }
        if (context.getFollowUpCount() >= context.getResolvedMaxFollowUp()) {
            context.markNoFollowUp("FOLLOW_UP_LIMIT_REACHED", "follow-up limit reached");
        }
    }
}
