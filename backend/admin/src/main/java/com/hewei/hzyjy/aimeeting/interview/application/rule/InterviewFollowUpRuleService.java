package com.hewei.hzyjy.aimeeting.interview.application.rule;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.interview.config.InterviewRuleEngineConfiguration;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 【关键节点：追问规则】通过 LiteFlow 综合终态、追问次数、分数、遗漏点与 AI 建议。
 * 这里只决定是否需要追问，不生成追问题干，也不直接更新题号。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewFollowUpRuleService {

    @Resource
    private final FlowExecutor flowExecutor;
    private final InterviewRuleEngineConfiguration ruleConfiguration;

    // 规则入口：补上下文 → 执行配置链 → 汇总决策；上下文缺失时直接返回不追问，不调用模型。
    public InterviewFollowUpRuleDecision decide(InterviewFollowUpRuleContext context) {
        if (context == null) {
            return InterviewFollowUpRuleDecision.noFollowUp(
                    Math.max(resolveDefaultMaxFollowUp(), 1),
                    "RULE_CONTEXT_MISSING",
                    "rule context is missing",
                    resolveDefaultChainId(),
                    ruleConfiguration.getRuleVersion(),
                    true
            );
        }

        // 补齐本次规则链 ID、版本、追问上限和低分阈值，并新建决策对象，避免上次判定结果残留。
        hydrateContext(context);
        if (!Boolean.TRUE.equals(ruleConfiguration.getEnable())) {
            return fallbackDecision(context, "RULE_ENGINE_DISABLED", "rule engine disabled");
        }

        try {
            // 【关键节点：LiteFlow 执行】节点顺序由 interview-followup-chain.xml 定义；节点共享上下文，守卫与判定共同产出追问决策。
            LiteflowResponse response = executeChain(context.getChainId(), context);
            if (response == null || !response.isSuccess()) {
                Throwable cause = response == null ? null : response.getCause();
                throw new IllegalStateException("LiteFlow execution failed", cause);
            }
            context.finalizeDecision(false);
            return context.getDecision();
        } catch (Exception ex) {
            // 配置允许降级时退回“AI 建议且未超上限”；否则抛给上层，最终行为还要看流水线兜底分支。
            if (Boolean.TRUE.equals(ruleConfiguration.getFailOpen())) {
                log.warn(
                        "LiteFlow follow-up decision failed, fallback to legacy policy, sessionId={}, chainId={}, questionNumber={}",
                        context.getSessionId(),
                        context.getChainId(),
                        context.getQuestionNumber(),
                        ex
                );
                return fallbackDecision(context, "RULE_ENGINE_FALLBACK", "fallback to legacy follow-up policy");
            }
            throw new IllegalStateException("LiteFlow follow-up decision failed", ex);
        }
    }

    LiteflowResponse executeChain(String chainId, InterviewFollowUpRuleContext context) {
        return flowExecutor.execute2Resp(chainId, null, context);
    }

    private void hydrateContext(InterviewFollowUpRuleContext context) {
        context.setChainId(resolveDefaultChainId());
        context.setRuleVersion(ruleConfiguration.getRuleVersion());
        context.setResolvedMaxFollowUp(resolveMaxFollowUp(context.getMaxFollowUp()));
        context.setLowScoreThreshold(resolveLowScoreThreshold());
        context.setDecision(new InterviewFollowUpRuleDecision());
        context.setTerminated(false);
    }

    // 降级策略仅采用“AI 建议追问且未超上限”；它不执行正常链中的低分和遗漏点触发规则。
    private InterviewFollowUpRuleDecision fallbackDecision(
            InterviewFollowUpRuleContext context,
            String fallbackReasonCode,
            String fallbackReasonText) {
        int resolvedMax = resolveMaxFollowUp(context.getMaxFollowUp());
        boolean underLimit = context.getFollowUpCount() < resolvedMax;
        boolean needFollowUp = context.isFollowUpNeededFromAi() && underLimit;
        String reasonCode = needFollowUp
                ? "AI_SUGGESTED"
                : (underLimit ? fallbackReasonCode : "FOLLOW_UP_LIMIT_REACHED");
        String reasonText = needFollowUp
                ? "follow_up_needed from ai result"
                : (underLimit ? fallbackReasonText : "follow-up limit reached");
        InterviewFollowUpRuleDecision decision = InterviewFollowUpRuleDecision.noFollowUp(
                resolvedMax,
                reasonCode,
                reasonText,
                resolveDefaultChainId(),
                ruleConfiguration.getRuleVersion(),
                true
        );
        decision.setNeedFollowUp(needFollowUp);
        return decision;
    }

    // 普通配置兜底：链 ID、追问上限、低分阈值缺省时分别取默认值；这些值用于规则判断，不是模型参数。
    private String resolveDefaultChainId() {
        return StrUtil.isNotBlank(ruleConfiguration.getDefaultChainId())
                ? ruleConfiguration.getDefaultChainId()
                : "default_followup_chain";
    }

    private int resolveMaxFollowUp(int fallbackMaxFollowUp) {
        if (fallbackMaxFollowUp > 0) {
            return fallbackMaxFollowUp;
        }
        return resolveDefaultMaxFollowUp();
    }

    private int resolveDefaultMaxFollowUp() {
        Integer configured = ruleConfiguration.getDefaultMaxFollowUp();
        return configured != null && configured > 0 ? configured : 2;
    }

    private int resolveLowScoreThreshold() {
        Integer defaultThreshold = ruleConfiguration.getDefaultLowScoreThreshold();
        return defaultThreshold != null && defaultThreshold >= 0 ? defaultThreshold : 60;
    }
}
