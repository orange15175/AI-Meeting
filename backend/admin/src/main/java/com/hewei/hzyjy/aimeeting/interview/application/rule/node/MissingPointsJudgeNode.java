package com.hewei.hzyjy.aimeeting.interview.application.rule.node;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 【关键节点：规则 6】回答存在遗漏点或追问提示时触发追问；具体追问题干由生成服务负责。
 */
@LiteflowComponent("missingPointsJudge")
public class MissingPointsJudgeNode extends NodeComponent {

    @Override
    public void process() {
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        if (context.isTerminated()) {
            return;
        }
        boolean hasMissingPoints = CollUtil.isNotEmpty(context.getMissingPoints());
        boolean hasFollowUpHint = StrUtil.isNotBlank(context.getFollowUpQuestionHint());
        if (hasMissingPoints || hasFollowUpHint) {
            context.markNeedFollowUp("MISSING_POINTS", "missing points or follow-up hint detected");
        }
    }
}
