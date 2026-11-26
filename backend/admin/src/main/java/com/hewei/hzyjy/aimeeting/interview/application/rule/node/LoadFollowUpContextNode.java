package com.hewei.hzyjy.aimeeting.interview.application.rule.node;

import com.hewei.hzyjy.aimeeting.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 【关键节点：规则 1】补齐有效的追问上限，供后续节点共享。
 */
@LiteflowComponent("loadFollowUpContext")
public class LoadFollowUpContextNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        int resolvedMax = context.getResolvedMaxFollowUp() > 0 ? context.getResolvedMaxFollowUp() : Math.max(context.getMaxFollowUp(), 1);
        context.setResolvedMaxFollowUp(resolvedMax);
    }
}
