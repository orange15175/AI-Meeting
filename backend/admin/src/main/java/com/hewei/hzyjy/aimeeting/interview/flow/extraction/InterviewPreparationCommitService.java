package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewMaterial;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewMaterialStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewProgressCommit;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSessionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewFlowStatus;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出题落库：材料、题目与本轮问答的重置放在同一事务。
 *
 * <p>为什么重置：重新出题意味着题库变了，旧的题号与回答不再对应，
 * 必须一并作废，否则会出现"第 3 题是新的，但分数还是旧的"的错位。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPreparationCommitService {

    private final InterviewMaterialStore materialStore;
    private final InterviewQuestionStore questionStore;
    private final InterviewSubmissionStore submissionStore;
    private final InterviewSessionStore sessionStore;

    @Data
    @Builder
    public static class PreparationCommand {
        private String sessionId;
        private String resumeFileUrl;
        private String resumeText;
        private String interviewType;
        private Integer resumeScore;
        /**
         * 整批主问题：题号、题干、分类、难度、要点按同一数组下标对齐。
         */
        private List<InterviewQuestionStore.MainQuestion> questions;
        private List<String> suggestions;
        private Map<String, List<String>> questionPoints;
        private Map<String, Object> questionTargets;
        private String jdSource;
        private String jdFileUrl;
        private String jdFileName;
        private String jdText;
        private Map<String, Object> jdContext;
        private Map<String, Object> resumeContext;
        private Integer expectedSessionVersion;
        private String modelName;
        private String promptVersion;
        private String rawResponse;
        private Integer responseTime;
    }

    /**
     * 【关键节点：准备事务】先按起始 version 更新会话游标，再替换本轮材料/题库及问答。
     * 任一步失败整笔回滚；模型调用不应放入本事务，以免长时间占用数据库连接和锁。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean commitSucceeded(PreparationCommand command) {
        // 第 1 段：计算题量并按起始版本重置当前题到第 1 题；版本冲突在替换题库前抛出。
        Integer totalQuestions = command.getQuestions() == null ? 0 : command.getQuestions().size();
        applySessionReset(command, totalQuestions);
        // 第 2 段：重新出题时清除本场旧提交；本操作在事务内，后续任何失败都会连同删除一起回滚。
        submissionStore.deleteBySession(command.getSessionId());

        // 第 3 段：保存本轮材料快照、替换主问题，再按题号回填内部考察点和岗位映射，确保同一轮数据一致。
        saveMaterial(command, InterviewMaterialStore.STATUS_SUCCEEDED, null);
        questionStore.replaceMainQuestions(command.getSessionId(), command.getQuestions());
        applyQuestionPoints(command);
        applyQuestionTargets(command);
        log.info("Interview preparation committed, sessionId={}, questionCount={}, jdEnabled={}",
                command.getSessionId(), totalQuestions, StrUtil.isNotBlank(command.getJdSource()));
        return true;
    }

    /**
     * 失败落库：已有成功快照时只补诊断字段。
     *
     * <p>重试失败不能把上一轮成功的简历/岗位内容覆盖掉，否则用户手里既没有新题，
     * 也丢了旧题对应的材料依据。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void commitFailed(String sessionId, String resumeFileUrl, String resumeText,
                             String errorMessage, Integer responseTime) {
        InterviewMaterial existing = materialStore.findBySession(sessionId);
        if (existing != null && InterviewMaterialStore.STATUS_SUCCEEDED.equals(existing.getParseStatus())) {
            materialStore.updateFailureDiagnostics(sessionId, errorMessage, responseTime);
            log.info("Preparation failed after a successful snapshot, keep snapshot, sessionId={}", sessionId);
            return;
        }
        // 没有成功快照时，保存已有简历路径/文本及失败诊断；不能把这条 FAILED 记录当作可用题库。
        PreparationCommand command = PreparationCommand.builder()
                .sessionId(sessionId)
                .resumeFileUrl(resumeFileUrl)
                .resumeText(resumeText)
                .responseTime(responseTime)
                .build();
        saveMaterial(command, InterviewMaterialStore.STATUS_FAILED, errorMessage);
    }

    // 【关键节点：准备版本校验】优先使用 Facade 在调用模型前记录的 version；没有传入时才读当前版本。
    private void applySessionReset(PreparationCommand command, int totalQuestions) {
        Integer expectedVersion = command.getExpectedSessionVersion();
        if (expectedVersion == null) {
            InterviewSession session = sessionStore.findBySessionId(command.getSessionId());
            if (session == null) {
                return;
            }
            expectedVersion = session.getVersion();
        }
        boolean advanced = sessionStore.commitProgress(InterviewProgressCommit.builder()
                .sessionId(command.getSessionId())
                .expectedVersion(expectedVersion)
                .flowStatus(InterviewFlowStatus.ASKING.name())
                .currentQuestionNo(totalQuestions > 0 ? "1" : null)
                .totalQuestions(totalQuestions)
                .followUpCount(0)
                .build());
        if (!advanced) {
            // 模型调用期间会话被推进过：宁可整体失败，也不要用旧游标覆盖新状态。
            throw new IllegalStateException("session progress changed during preparation, sessionId="
                    + command.getSessionId());
        }
    }

    private void saveMaterial(PreparationCommand command, String status, String errorMessage) {
        // 材料字段分组：先存解析状态和简历基础信息，再序列化上下文、建议和模型原始输出，最后单独覆盖 JD 列。
        InterviewMaterial material = new InterviewMaterial();
        material.setSessionId(command.getSessionId());
        material.setParseStatus(status);
        material.setResumeFileUrl(command.getResumeFileUrl());
        material.setResumeText(command.getResumeText());
        material.setInterviewType(command.getInterviewType());
        material.setResumeScore(command.getResumeScore());
        material.setModelName(command.getModelName());
        material.setPromptVersion(command.getPromptVersion());
        material.setResponseTime(command.getResponseTime());
        material.setErrorMessage(StrUtil.maxLength(errorMessage, 480));
        if (command.getResumeContext() != null && !command.getResumeContext().isEmpty()) {
            material.setResumeContext(JSON.toJSONString(command.getResumeContext()));
        }
        if (command.getSuggestions() != null && !command.getSuggestions().isEmpty()) {
            material.setSuggestionsJson(JSON.toJSONString(toIndexedMap(command.getSuggestions())));
        }
        if (StrUtil.isNotBlank(command.getRawResponse())) {
            material.setRawResponse(command.getRawResponse());
        }
        InterviewMaterial existing = materialStore.findBySession(command.getSessionId());
        // parseVersion 是材料解析轮次，不是会话乐观锁 version；每次保存材料时单独递增。
        material.setParseVersion(existing == null || existing.getParseVersion() == null
                ? 1
                : existing.getParseVersion() + 1);
        materialStore.save(material);
        // 岗位列必须整体覆盖：从"有 JD"退回"无 JD"时，updateById 会跳过 null 字段而留下旧岗位。
        materialStore.updateJdColumns(
                command.getSessionId(),
                command.getJdSource(),
                command.getJdFileUrl(),
                command.getJdFileName(),
                command.getJdText(),
                command.getJdContext() == null || command.getJdContext().isEmpty()
                        ? null
                        : JSON.toJSONString(command.getJdContext()));
    }

    /**
     * 回填题目对应的岗位要求与证据，供评分与复盘追溯；缺失时保持 NULL。
     */
    private void applyQuestionTargets(PreparationCommand command) {
        Map<String, Object> targets = command.getQuestionTargets();
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : targets.entrySet()) {
            if (StrUtil.isBlank(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            questionStore.updateTargetContext(
                    command.getSessionId(),
                    String.valueOf(entry.getKey()).trim(),
                    JSON.toJSONString(entry.getValue()));
        }
    }

    /**
     * 题目插入后按题号回填考察要点，避免把要点混进题干下发前端。
     */
    private void applyQuestionPoints(PreparationCommand command) {
        Map<String, List<String>> points = command.getQuestionPoints();
        if (points == null || points.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : points.entrySet()) {
            if (StrUtil.isBlank(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            questionStore.updatePoints(
                    command.getSessionId(),
                    String.valueOf(entry.getKey()).trim(),
                    JSON.toJSONString(entry.getValue()));
        }
    }

    // 普通展示数据转换：把建议列表变为从 1 开始的字符串键映射，跳过空建议。
    private Map<String, String> toIndexedMap(List<String> values) {
        Map<String, String> indexed = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (StrUtil.isNotBlank(value)) {
                indexed.put(String.valueOf(i + 1), value.trim());
            }
        }
        return indexed;
    }
}
