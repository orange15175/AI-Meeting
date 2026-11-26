package com.hewei.hzyjy.aimeeting.interview.flow.report;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.common.enums.InterviewErrorCodeEnum;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewRecordPageReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewRecordSaveReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewPlaybackItemRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewRecordRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewReviewFeedbackRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.JdCoverageItemRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarDimensionItemRespDTO;
import com.hewei.hzyjy.aimeeting.interview.application.InterviewSessionOwnershipService;
import com.hewei.hzyjy.aimeeting.interview.application.finalize.InterviewFinalizeLockService;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewAnswerSubmission;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewMaterial;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewQuestionItem;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewRecordDO;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.mapper.InterviewRecordMapper;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewMaterialStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewRadarService;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewRecordService;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewSessionService;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewTurnLog;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewJobContextReader;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 面试报告服务。
 *
 * <p>P3 起报告的所有输入都来自 MySQL：会话行（状态/方向/时间）、材料表（简历分与建议）、
 * 题目表（题量与顺序）、提交表（每题分数与评语）。打开报告不再触发任何模型调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewRecordServiceImpl extends ServiceImpl<InterviewRecordMapper, InterviewRecordDO>
        implements InterviewRecordService {

    private static final int MAX_FINALIZE_RETRIES = 3;

    private final InterviewSessionOwnershipService interviewSessionOwnershipService;
    private final InterviewSessionService interviewSessionService;
    private final InterviewFinalizeLockService interviewFinalizeLockService;
    private final InterviewSubmissionStore submissionStore;
    private final InterviewQuestionStore questionStore;
    private final InterviewMaterialStore materialStore;
    private final InterviewRadarService interviewRadarService;
    private final InterviewJobContextReader jobContextReader;

    @Override
    // 【关键节点：报告快照】校验归属，聚合会话/材料/题库/提交，再插入或更新报告；这里不调用模型，也不主动结束会话。
    public void saveInterviewRecord(String sessionId, Long userId, InterviewRecordSaveReqDTO requestParam) {
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException(InterviewErrorCodeEnum.SESSION_ID_EMPTY);
        }
        validateUserId(userId);

        InterviewRecordSaveReqDTO safeRequest = requestParam == null ? new InterviewRecordSaveReqDTO() : requestParam;
        InterviewSession session = interviewSessionOwnershipService.requireOwnedSession(sessionId, userId);
        InterviewMaterial material = materialStore.findBySession(sessionId);

        InterviewRecordDO record = findRecord(userId, sessionId);

        // 第 1 段：计算报告基础字段和逐题记录。自动收口传入空请求，分数主要来自成功主问题提交的平均值。
        Integer totalScore = resolveInterviewScore(sessionId, safeRequest, record);
        String suggestions = resolveInterviewSuggestions(safeRequest, material);
        Integer resumeScore = material == null ? null : material.getResumeScore();
        Integer questionCount = resolveQuestionCount(sessionId);
        String interviewDirection = resolveInterviewDirection(safeRequest, session, material);
        String status = resolveInterviewStatus(session.getStatus());
        List<InterviewTurnLog> turns = loadTurns(sessionId);
        RadarChartDTO radarChart = interviewRadarService.buildRadarChart(resumeScore, totalScore);

        Date now = new Date();
        if (record == null) {
            record = new InterviewRecordDO();
            record.setUserId(userId);
            record.setSessionId(sessionId);
            record.setCreateTime(now);
            record.setDelFlag(0);
        }

        // 第 2 段：优先沿用报告开始时间，否则回退会话开始/创建时间；时长按本次保存时间计算。
        Date sessionStartTime = session.getStartTime() != null
                ? session.getStartTime()
                : (session.getCreateTime() != null ? session.getCreateTime() : now);
        Date startTime = record.getStartTime() != null ? record.getStartTime() : sessionStartTime;
        int durationSeconds = calculateDurationSeconds(startTime, now);

        record.setInterviewScore(totalScore);
        record.setResumeScore(resumeScore);
        record.setInterviewStatus(status);
        record.setQuestionCount(questionCount);
        record.setInterviewSuggestions(suggestions);
        if (StrUtil.isNotBlank(interviewDirection)) {
            record.setInterviewDirection(interviewDirection);
        }
        record.setStartTime(startTime);
        // 只在会话已结束时写 endTime，避免中间态记录被误标记为"已结束"。
        if (InterviewSessionStatus.FINISHED.name().equalsIgnoreCase(status)) {
            record.setEndTime(now);
        }
        record.setDurationSeconds(durationSeconds);
        // 第 3 段：把本次回合、雷达和复盘建议存为 JSON 快照，便于之后读取报告时优先还原。
        record.setSessionSnapshotJson(buildSessionSnapshotJson(
                session, material, resumeScore, totalScore, questionCount,
                interviewDirection, status, suggestions, turns, radarChart));
        record.setUpdateTime(now);

        // 第 4 段：不存在就插入，已存在就更新；并发首次插入触发唯一键时，重新定位已有报告转为更新。
        if (record.getId() == null) {
            try {
                baseMapper.insert(record);
                log.info("Created interview record, userId={}, sessionId={}", userId, sessionId);
            } catch (DuplicateKeyException duplicateKeyException) {
                InterviewRecordDO existingRecord = findRecord(userId, sessionId);
                if (existingRecord == null) {
                    throw duplicateKeyException;
                }
                record.setId(existingRecord.getId());
                if (record.getCreateTime() == null) {
                    record.setCreateTime(existingRecord.getCreateTime());
                }
                baseMapper.updateById(record);
                log.info("Recovered duplicate interview record insert by update, userId={}, sessionId={}",
                        userId, sessionId);
            }
        } else {
            baseMapper.updateById(record);
            log.info("Updated interview record, userId={}, sessionId={}", userId, sessionId);
        }
    }

    @Override
    // 记录列表只查询当前用户未删除报告，按条件筛选和创建时间倒序分页；不在列表里重新出题或评分。
    public IPage<InterviewRecordRespDTO> pageInterviewRecords(Long userId, InterviewRecordPageReqDTO requestParam) {
        validateUserId(userId);

        Page<InterviewRecordDO> page = new Page<>(requestParam.getPageNum(), requestParam.getPageSize());
        LambdaQueryWrapper<InterviewRecordDO> queryWrapper = Wrappers.lambdaQuery(InterviewRecordDO.class)
                .eq(InterviewRecordDO::getUserId, userId)
                .eq(InterviewRecordDO::getDelFlag, 0);

        if (StringUtils.hasText(requestParam.getSessionId())) {
            queryWrapper.eq(InterviewRecordDO::getSessionId, requestParam.getSessionId());
        }
        if (requestParam.getMinScore() != null) {
            queryWrapper.ge(InterviewRecordDO::getInterviewScore, requestParam.getMinScore());
        }
        if (requestParam.getMaxScore() != null) {
            queryWrapper.le(InterviewRecordDO::getInterviewScore, requestParam.getMaxScore());
        }
        if (StringUtils.hasText(requestParam.getInterviewDirection())) {
            queryWrapper.eq(InterviewRecordDO::getInterviewDirection, requestParam.getInterviewDirection());
        }
        queryWrapper.orderByDesc(InterviewRecordDO::getCreateTime);

        Page<InterviewRecordDO> recordPage = baseMapper.selectPage(page, queryWrapper);
        List<InterviewRecordRespDTO> resultList = recordPage.getRecords().stream()
                .map(record -> {
                    InterviewRecordRespDTO respDTO = new InterviewRecordRespDTO();
                    BeanUtils.copyProperties(record, respDTO);
                    if (StrUtil.isNotBlank(record.getInterviewSuggestions())) {
                        respDTO.setInterviewSuggestionsMap(parseInterviewSuggestions(record.getInterviewSuggestions()));
                    }
                    return respDTO;
                })
                .collect(Collectors.toList());

        Page<InterviewRecordRespDTO> resultPage = new Page<>(recordPage.getCurrent(), recordPage.getSize(),
                recordPage.getTotal());
        resultPage.setRecords(resultList);
        return resultPage;
    }

    /**
     * 【面试主线 6/6】报告页按用户和会话查询报告，补充逐题回顾、雷达与岗位考察字段。
     * 报告缺失时会尝试从现有数据库记录生成；这条补建分支不等于显式结束面试。
     */
    @Override
    public InterviewRecordRespDTO getBySessionId(String sessionId, Long userId) {
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException(InterviewErrorCodeEnum.SESSION_ID_EMPTY);
        }
        validateUserId(userId);

        InterviewRecordDO record = findRecord(userId, sessionId);
        if (record == null) {
            // 首次打开报告页时按当前数据生成一份。
            try {
                saveInterviewRecord(sessionId, userId, new InterviewRecordSaveReqDTO());
                record = findRecord(userId, sessionId);
            } catch (Exception ex) {
                log.warn("Auto-create interview record failed, sessionId={}, userId={}", sessionId, userId, ex);
            }
        }
        if (record == null) {
            return null;
        }

        // 复制报告基础字段后，补齐雷达、逐题回顾、复盘建议及 JD 考察信息；快照缺字段时可用当前数据库补齐。
        InterviewRecordRespDTO respDTO = new InterviewRecordRespDTO();
        BeanUtils.copyProperties(record, respDTO);
        if (StrUtil.isNotBlank(record.getInterviewSuggestions())) {
            respDTO.setInterviewSuggestionsMap(parseInterviewSuggestions(record.getInterviewSuggestions()));
        }
        enrichReportFields(sessionId, record, respDTO);
        return respDTO;
    }

    /**
     * 【面试主线 5/6】正常答完或提前结束的统一收口：finalize 锁 → 保存报告 → 标记结束 → 再补终态报告。
     * FromRedis 是保留的历史方法名，实际数据来自 MySQL；这里采用锁和重试，不是包住整个收口的单一数据库事务。
     */
    @Override
    public void saveInterviewRecordFromRedis(String sessionId, Long userId) {
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException(InterviewErrorCodeEnum.SESSION_ID_EMPTY);
        }
        validateUserId(userId);
        RLock finalizeLock = null;
        try {
            // finalize 锁保证同一 session 同时只有一个收口流程在跑，避免 finish/save 并发互相覆盖。
            finalizeLock = interviewFinalizeLockService.acquire(sessionId);
            if (finalizeLock == null) {
                Metrics.counter("finalize_lock_contention_total").increment();
                throw new ClientException("finalize is processing, please retry");
            }
            // 【关键节点：收口重试】最多尝试 3 次，每次重新检查归属和状态；不是把整个循环放在一个数据库事务中。
            for (int attempt = 1; attempt <= MAX_FINALIZE_RETRIES; attempt++) {
                try {
                    InterviewSession session = interviewSessionOwnershipService.requireOwnedSession(sessionId, userId);
                    boolean alreadyFinished = session != null
                            && InterviewSessionStatus.FINISHED.name().equalsIgnoreCase(session.getStatus());

                    // 【关键节点：先保存再结束】先留存报告；若会话尚未结束，结束后再保存一次终态与时间字段。
                    saveInterviewRecord(sessionId, userId, new InterviewRecordSaveReqDTO());
                    if (!alreadyFinished) {
                        interviewSessionService.finishSession(sessionId, userId);
                        saveInterviewRecord(sessionId, userId, new InterviewRecordSaveReqDTO());
                    }
                    log.info("Finalized interview record, sessionId={}, userId={}, attempt={}",
                            sessionId, userId, attempt);
                    return;
                } catch (Exception ex) {
                    Metrics.counter("finalize_retry_total").increment();
                    log.warn("Finalize attempt failed, sessionId={}, userId={}, attempt={}",
                            sessionId, userId, attempt, ex);
                    if (attempt >= MAX_FINALIZE_RETRIES) {
                        throw ex;
                    }
                }
            }
        } catch (ClientException clientException) {
            throw clientException;
        } catch (Exception ex) {
            throw new ClientException("failed to finalize interview record, please retry");
        } finally {
            // 收口锁只在当前线程仍持有时释放；成功、失败和重试耗尽都会经过 finally。
            interviewFinalizeLockService.release(finalizeLock);
        }
    }

    private InterviewRecordDO findRecord(Long userId, String sessionId) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(InterviewRecordDO.class)
                .eq(InterviewRecordDO::getUserId, userId)
                .eq(InterviewRecordDO::getSessionId, sessionId)
                .eq(InterviewRecordDO::getDelFlag, 0));
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ClientException(InterviewErrorCodeEnum.INVALID_USER_ID);
        }
    }

    // 基础字段解析区：分数优先取显式请求值，其次数据库主问题均分，最后沿用已有报告；无依据时保留 null。
    private Integer resolveInterviewScore(
            String sessionId,
            InterviewRecordSaveReqDTO requestParam,
            InterviewRecordDO existingRecord) {
        if (requestParam.getInterviewScore() != null) {
            return requestParam.getInterviewScore();
        }
        Integer score = submissionStore.averageMainScore(sessionId);
        if (score != null) {
            return score;
        }
        return existingRecord == null ? null : existingRecord.getInterviewScore();
    }

    // 报告题量统计包含已生成追问；建议和面试方向在后续辅助方法中按请求/数据库来源顺序选择。
    private Integer resolveQuestionCount(String sessionId) {
        return questionStore.countAll(sessionId);
    }

    private String resolveInterviewSuggestions(InterviewRecordSaveReqDTO requestParam, InterviewMaterial material) {
        if (StrUtil.isNotBlank(requestParam.getInterviewSuggestions())) {
            return requestParam.getInterviewSuggestions();
        }
        if (material == null || StrUtil.isBlank(material.getSuggestionsJson())) {
            return null;
        }
        Map<String, String> suggestionsMap = parseSuggestionsJson(material.getSuggestionsJson());
        return suggestionsMap.isEmpty() ? null : sortAndJoinValues(suggestionsMap);
    }

    private String resolveInterviewDirection(
            InterviewRecordSaveReqDTO requestParam,
            InterviewSession session,
            InterviewMaterial material) {
        if (requestParam != null && StrUtil.isNotBlank(requestParam.getInterviewDirection())) {
            return requestParam.getInterviewDirection().trim();
        }
        if (session != null && StrUtil.isNotBlank(session.getInterviewType())) {
            return session.getInterviewType().trim();
        }
        if (material != null && StrUtil.isNotBlank(material.getInterviewType())) {
            return material.getInterviewType().trim();
        }
        return null;
    }

    // 普通展示转换区：容错解析建议 JSON、按键排序拼接、归一状态并计算时长；不生成新的 AI 评价。
    private Map<String, String> parseSuggestionsJson(String json) {
        if (StrUtil.isBlank(json)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> parsed = JSON.parseObject(
                    json, new TypeReference<LinkedHashMap<String, String>>() {
                    });
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse suggestions json", ex);
            return Collections.emptyMap();
        }
    }

    private String sortAndJoinValues(Map<String, String> suggestionsMap) {
        if (suggestionsMap == null || suggestionsMap.isEmpty()) {
            return null;
        }
        return suggestionsMap.entrySet().stream()
                .sorted((e1, e2) -> {
                    try {
                        return Integer.compare(Integer.parseInt(e1.getKey()), Integer.parseInt(e2.getKey()));
                    } catch (NumberFormatException ex) {
                        return e1.getKey().compareTo(e2.getKey());
                    }
                })
                .map(Map.Entry::getValue)
                .collect(Collectors.joining("; "));
    }

    private String resolveInterviewStatus(String sessionStatus) {
        if (StrUtil.isBlank(sessionStatus)) {
            return InterviewSessionStatus.DRAFT.name();
        }
        try {
            return InterviewSessionStatus.valueOf(sessionStatus).name();
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown interview session status: {}", sessionStatus);
            return sessionStatus;
        }
    }

    private int calculateDurationSeconds(Date startTime, Date endTime) {
        if (startTime == null || endTime == null) {
            return 0;
        }
        long durationMs = endTime.getTime() - startTime.getTime();
        return durationMs <= 0 ? 0 : (int) (durationMs / 1000);
    }

    /**
     * 把提交行还原成"回合"视图，供报告回放与评语聚合使用。
     * 追问的分数不计入总分，累计总分按主问题滚动平均。
     */
    private List<InterviewTurnLog> loadTurns(String sessionId) {
        List<InterviewQuestionItem> questions = questionStore.listBySession(sessionId);
        Map<String, InterviewQuestionItem> questionMap = new LinkedHashMap<>();
        for (InterviewQuestionItem item : questions) {
            questionMap.put(item.getQuestionNo(), item);
        }
        // 以数据库提交为回合来源，把题干、回答、评分和追问标记关联起来；主问题均分随遍历累积。
        List<InterviewAnswerSubmission> submissions = submissionStore.listByQuestionOrder(sessionId);
        List<InterviewTurnLog> turns = new ArrayList<>(submissions.size());
        List<String> questionOrder = new ArrayList<>(questionMap.keySet());
        int mainSum = 0;
        int mainCount = 0;
        for (int i = 0; i < submissions.size(); i++) {
            InterviewAnswerSubmission submission = submissions.get(i);
            InterviewQuestionItem question = questionMap.get(submission.getQuestionNo());
            boolean isFollowUp = question != null
                    ? InterviewQuestionStore.KIND_FOLLOW_UP.equals(question.getKind())
                    : submission.getQuestionNo() != null && submission.getQuestionNo().contains("-F");
            if (!isFollowUp && submission.getScore() != null) {
                mainSum += clampScore(submission.getScore());
                mainCount++;
            }
            // 回放的下一题按现存题库顺序关联，属于报告视图；这里不会真的推进会话。
            String nextQuestionNo = null;
            int orderIndex = questionOrder.indexOf(submission.getQuestionNo());
            if (orderIndex >= 0 && orderIndex + 1 < questionOrder.size()) {
                nextQuestionNo = questionOrder.get(orderIndex + 1);
            }
            turns.add(InterviewTurnLog.builder()
                    .timestamp(submission.getUpdateTime() == null ? null : submission.getUpdateTime().getTime())
                    .requestId(submission.getRequestId())
                    .questionNumber(submission.getQuestionNo())
                    .questionContent(question == null ? null : question.getContent())
                    .answerContent(submission.getAnswerContent())
                    .score(submission.getScore())
                    .totalScore(mainCount == 0 ? null : (int) Math.round((double) mainSum / mainCount))
                    .feedback(submission.getFeedback())
                    .isFollowUp(isFollowUp)
                    .followUpCount(isFollowUp && question != null ? question.getFollowUpIndex() : 0)
                    .nextQuestionNumber(nextQuestionNo)
                    .nextQuestion(nextQuestionNo == null ? null : questionMap.get(nextQuestionNo).getContent())
                    .finished(Boolean.TRUE.equals(toBoolean(submission.getFinished())))
                    .build());
        }
        return turns;
    }

    private Boolean toBoolean(Integer value) {
        return value != null && value == 1;
    }

    // 快照封装区：保存会话元信息、模型/提示词版本、回合、雷达、复盘建议和快照时间；是 JSON 存储结构。
    private String buildSessionSnapshotJson(
            InterviewSession session,
            InterviewMaterial material,
            Integer resumeScore,
            Integer interviewScore,
            Integer questionCount,
            String interviewDirection,
            String interviewStatus,
            String interviewSuggestions,
            List<InterviewTurnLog> turns,
            RadarChartDTO radarChart) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sessionId", session.getSessionId());
        snapshot.put("sessionStatus", session.getStatus());
        snapshot.put("resumeFileUrl", session.getResumeFileUrl());
        snapshot.put("sessionStartTime", session.getStartTime());
        snapshot.put("sessionEndTime", session.getEndTime());
        snapshot.put("resumeScore", resumeScore);
        snapshot.put("interviewScore", interviewScore);
        snapshot.put("questionCount", questionCount);
        snapshot.put("interviewDirection", interviewDirection);
        snapshot.put("interviewType", session.getInterviewType());
        snapshot.put("interviewStatus", interviewStatus);
        snapshot.put("interviewSuggestions", interviewSuggestions);
        snapshot.put("promptVersion", material == null ? null : material.getPromptVersion());
        snapshot.put("modelName", material == null ? null : material.getModelName());
        snapshot.put("turns", turns);
        snapshot.put("radar", radarChart);
        snapshot.put("reviewFeedback", buildReviewFeedback(turns, radarChart, interviewSuggestions));
        snapshot.put("snapshotAt", new Date());
        return JSON.toJSONString(snapshot);
    }

    /**
     * 补齐报告的结构化字段：雷达图、回放列表与复盘建议。
     */
    private void enrichReportFields(String sessionId, InterviewRecordDO record, InterviewRecordRespDTO respDTO) {
        Map<String, Object> snapshot = parseSnapshot(record == null ? null : record.getSessionSnapshotJson());

        // 展示数据优先读报告快照；雷达或回合缺失时，再分别用材料分/主问题均分和数据库提交重建。
        RadarChartDTO radarChart = parseRadarFromSnapshot(snapshot);
        if (radarChart == null) {
            InterviewMaterial material = materialStore.findBySession(sessionId);
            radarChart = interviewRadarService.buildRadarChart(
                    material == null ? null : material.getResumeScore(),
                    submissionStore.averageMainScore(sessionId));
        }
        if (radarChart == null) {
            radarChart = new RadarChartDTO();
        }
        respDTO.setRadarChart(radarChart);
        respDTO.setRadarDimensions(buildRadarDimensions(radarChart));

        List<InterviewTurnLog> turns = parseTurnsFromSnapshot(snapshot);
        if (turns == null || turns.isEmpty()) {
            turns = loadTurns(sessionId);
        }
        respDTO.setPlaybackItems(buildPlaybackItems(turns));
        respDTO.setReviewFeedback(resolveReviewFeedback(snapshot, turns, radarChart, record));
        enrichJdFields(sessionId, respDTO, turns);
    }

    /**
     * 岗位覆盖：只报告"出过题并有作答证据"的要求，未出题或未作答一律标记待验证。
     *
     * <p>不计算"岗位匹配率"——没有标注基准的百分比只会给用户错误结论。</p>
     */
    private void enrichJdFields(String sessionId, InterviewRecordRespDTO respDTO, List<InterviewTurnLog> turns) {
        InterviewMaterial material = materialStore.findBySession(sessionId);
        if (material == null || StrUtil.isBlank(material.getJdSource())) {
            respDTO.setJdCoverage(Collections.emptyList());
            return;
        }
        respDTO.setJdSummary(jobContextReader.readSummary(sessionId, 160));
        respDTO.setJdCoverage(buildJdCoverage(sessionId, material, turns));
    }

    // 【关键节点：岗位考察依据】汇总题目关联要求与 JD 必需技能，按主问题号关联作答证据；有题且有作答才标已考察。
    private List<JdCoverageItemRespDTO> buildJdCoverage(String sessionId, InterviewMaterial material,
                                                        List<InterviewTurnLog> turns) {
        LinkedHashSet<String> requirements = new LinkedHashSet<>();
        Map<String, List<String>> questionNumbers = new LinkedHashMap<>();

        for (InterviewQuestionItem item : questionStore.listMain(sessionId)) {
            String requirement = resolveRequirement(item.getTargetContext());
            if (StrUtil.isBlank(requirement)) {
                continue;
            }
            requirements.add(requirement);
            questionNumbers.computeIfAbsent(requirement, key -> new ArrayList<>()).add(item.getQuestionNo());
        }
        requirements.addAll(parseRequiredSkills(material.getJdContext()));

        List<String> answered = turns == null
                ? Collections.emptyList()
                : turns.stream()
                        .filter(turn -> turn != null && (turn.getScore() != null
                                || StrUtil.isNotBlank(turn.getAnswerContent())))
                        .map(turn -> mainQuestionNumber(turn.getQuestionNumber()))
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toList());

        List<JdCoverageItemRespDTO> coverage = new ArrayList<>();
        for (String requirement : requirements) {
            if (StrUtil.isBlank(requirement) || coverage.size() >= 12) {
                continue;
            }
            List<String> numbers = questionNumbers.getOrDefault(requirement, Collections.emptyList());
            boolean covered = numbers.stream().anyMatch(answered::contains);
            JdCoverageItemRespDTO item = new JdCoverageItemRespDTO();
            item.setRequirement(StrUtil.maxLength(requirement.trim(), 120));
            item.setQuestionNumbers(numbers);
            item.setStatus(covered ? JdCoverageItemRespDTO.STATUS_COVERED : JdCoverageItemRespDTO.STATUS_PENDING);
            coverage.add(item);
        }
        return coverage;
    }

    // 岗位字段解析区：从题目映射中取 requirement，从 JD 上下文取 requiredSkills；损坏字段返回空结果。
    private String resolveRequirement(String targetContextJson) {
        if (StrUtil.isBlank(targetContextJson)) {
            return null;
        }
        try {
            Map<String, Object> target = JSON.parseObject(
                    targetContextJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            Object requirement = target == null ? null : target.get("requirement");
            return requirement == null ? null : StrUtil.trim(String.valueOf(requirement));
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> parseRequiredSkills(String jdContextJson) {
        if (StrUtil.isBlank(jdContextJson)) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> context = JSON.parseObject(
                    jdContextJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            Object skills = context == null ? null : context.get("requiredSkills");
            if (!(skills instanceof List<?> list)) {
                return Collections.emptyList();
            }
            return list.stream()
                    .filter(item -> item != null && StrUtil.isNotBlank(String.valueOf(item)))
                    .map(item -> StrUtil.trim(String.valueOf(item)))
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private String mainQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim();
        int separator = normalized.indexOf("-F");
        return separator > 0 ? normalized.substring(0, separator) : normalized;
    }

    // 报告快照解析区：JSON 或单个子结构不可读时返回空/null，让调用方用数据库或规则补齐。
    private Map<String, Object> parseSnapshot(String snapshotJson) {
        if (StrUtil.isBlank(snapshotJson)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(
                    snapshotJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse session snapshot json", ex);
            return Collections.emptyMap();
        }
    }

    private RadarChartDTO parseRadarFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        Object radarObj = snapshot.get("radar");
        if (radarObj == null) {
            return null;
        }
        try {
            return JSON.parseObject(JSON.toJSONString(radarObj), RadarChartDTO.class);
        } catch (Exception ex) {
            log.warn("Failed to parse radar from snapshot", ex);
            return null;
        }
    }

    private List<InterviewTurnLog> parseTurnsFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return Collections.emptyList();
        }
        Object turnsObj = snapshot.get("turns");
        if (turnsObj == null) {
            return Collections.emptyList();
        }
        try {
            List<InterviewTurnLog> parsedTurns = JSON.parseArray(
                    JSON.toJSONString(turnsObj),
                    InterviewTurnLog.class
            );
            return parsedTurns == null ? Collections.emptyList() : parsedTurns;
        } catch (Exception ex) {
            log.warn("Failed to parse turns from snapshot", ex);
            return Collections.emptyList();
        }
    }

    private InterviewReviewFeedbackRespDTO resolveReviewFeedback(
            Map<String, Object> snapshot,
            List<InterviewTurnLog> turns,
            RadarChartDTO radarChart,
            InterviewRecordDO record) {
        String interviewSuggestions = record == null ? null : record.getInterviewSuggestions();
        InterviewReviewFeedbackRespDTO rebuilt = buildReviewFeedback(turns, radarChart, interviewSuggestions);
        if (hasReviewFeedbackContent(rebuilt)) {
            return rebuilt;
        }
        InterviewReviewFeedbackRespDTO parsed = parseReviewFeedbackFromSnapshot(snapshot);
        return parsed != null ? parsed : rebuilt;
    }

    private InterviewReviewFeedbackRespDTO parseReviewFeedbackFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        Object reviewFeedback = snapshot.get("reviewFeedback");
        if (reviewFeedback == null) {
            return null;
        }
        try {
            return JSON.parseObject(JSON.toJSONString(reviewFeedback), InterviewReviewFeedbackRespDTO.class);
        } catch (Exception ex) {
            log.warn("Failed to parse review feedback from snapshot", ex);
            return null;
        }
    }

    // 复盘文案生成区：基于已有回合评语和雷达，按本地规则组织总体评价、亮点、改进与行动项，不再请求大模型。
    private InterviewReviewFeedbackRespDTO buildReviewFeedback(
            List<InterviewTurnLog> turns,
            RadarChartDTO radarChart,
            String interviewSuggestions) {
        InterviewReviewFeedbackRespDTO reviewFeedback = new InterviewReviewFeedbackRespDTO();
        reviewFeedback.setOverallComment(buildOverallComment(radarChart));
        reviewFeedback.setHighlights(buildHighlights(turns, radarChart));
        List<String> improvementTips = buildImprovementTips(turns, radarChart);
        reviewFeedback.setImprovementTips(improvementTips);
        reviewFeedback.setNextActions(buildNextActions(interviewSuggestions, improvementTips));
        return reviewFeedback;
    }

    private boolean hasReviewFeedbackContent(InterviewReviewFeedbackRespDTO reviewFeedback) {
        if (reviewFeedback == null) {
            return false;
        }
        return StrUtil.isNotBlank(reviewFeedback.getOverallComment())
                || (reviewFeedback.getHighlights() != null && !reviewFeedback.getHighlights().isEmpty())
                || (reviewFeedback.getImprovementTips() != null && !reviewFeedback.getImprovementTips().isEmpty())
                || (reviewFeedback.getNextActions() != null && !reviewFeedback.getNextActions().isEmpty());
    }

    private String buildOverallComment(RadarChartDTO radarChart) {
        Integer finalScoreValue = radarChart == null ? null : radarChart.getPotentialIndex();
        if (finalScoreValue == null) {
            return "暂无有效评分，完成至少一道主问题后可生成综合表现评价。";
        }
        int finalScore = clampScore(finalScoreValue);
        String baseComment;
        if (finalScore >= 85) {
            baseComment = "Overall performance is strong and already competitive for interviews.";
        } else if (finalScore >= 70) {
            baseComment = "Overall performance is solid, with clear strengths and room for further polishing.";
        } else if (finalScore >= 60) {
            baseComment = "Overall performance meets the baseline, but answer structure and role-fit still need improvement.";
        } else {
            baseComment = "Overall performance is below expectation; prioritize role-fit, depth of answers, and communication stability.";
        }

        String strongest = resolveDimensionLabel(findStrongestDimensionKey(radarChart));
        String weakest = resolveDimensionLabel(findWeakestDimensionKey(radarChart));
        if (StrUtil.isBlank(strongest) || StrUtil.isBlank(weakest) || strongest.equals(weakest)) {
            return baseComment;
        }
        return baseComment + " Current strength is " + strongest + ", and prioritize improving " + weakest + ".";
    }

    private List<String> buildHighlights(List<InterviewTurnLog> turns, RadarChartDTO radarChart) {
        List<String> highlights = extractFeedbackLines(turns, 80, Integer.MAX_VALUE, 3);
        if (!highlights.isEmpty()) {
            return highlights;
        }

        LinkedHashSet<String> generated = new LinkedHashSet<>();
        if (radarChart != null && clampScore(radarChart.getResumeScore()) >= 75) {
            generated.add("Your resume is well aligned to the target role, with clear highlights in core experience.");
        }
        if (radarChart != null && clampScore(radarChart.getInterviewPerformance()) >= 75) {
            generated.add("Your answer structure is complete and remains focused on the question.");
        }
        if (radarChart != null && clampScore(radarChart.getProfessionalSkills()) >= 75) {
            generated.add("Your professional skills are communicated clearly with concrete technical understanding.");
        }
        return limitList(generated, 3);
    }

    private List<String> buildImprovementTips(List<InterviewTurnLog> turns, RadarChartDTO radarChart) {
        List<String> improvements = extractFeedbackLines(turns, 0, 69, 3);
        if (!improvements.isEmpty()) {
            return improvements;
        }

        LinkedHashSet<String> generated = new LinkedHashSet<>();
        appendImprovementByDimension(generated, "resume_score", radarChart);
        appendImprovementByDimension(generated, "interview_performance", radarChart);
        appendImprovementByDimension(generated, "professional_skills", radarChart);
        return limitList(generated, 3);
    }

    private void appendImprovementByDimension(LinkedHashSet<String> generated, String dimensionKey, RadarChartDTO radarChart) {
        if (generated.size() >= 3 || radarChart == null) {
            return;
        }
        Integer score = getDimensionScore(radarChart, dimensionKey);
        if (score == null || score >= 75) {
            return;
        }
        switch (dimensionKey) {
            case "resume_score" ->
                    generated.add("Refine resume highlights for the target role and add concrete project outcomes and business impact.");
            case "interview_performance" ->
                    generated.add("Answer more directly, and use a clear structure with case, action, and result.");
            case "professional_skills" ->
                    generated.add("Explain technical decisions in more detail, including trade-offs, challenges, and outcomes.");
            default -> {
            }
        }
    }

    private List<String> buildNextActions(String interviewSuggestions, List<String> improvementTips) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(interviewSuggestions)) {
            actions.addAll(parseInterviewSuggestions(interviewSuggestions).values());
        }
        if (improvementTips != null) {
            actions.addAll(improvementTips);
        }
        return limitList(actions, 3);
    }

    private List<String> extractFeedbackLines(List<InterviewTurnLog> turns, int minScore, int maxScore, int limit) {
        if (turns == null || turns.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (InterviewTurnLog turn : turns) {
            if (turn == null || turn.getScore() == null || StrUtil.isBlank(turn.getFeedback())) {
                continue;
            }
            int score = clampScore(turn.getScore());
            if (score < minScore || score > maxScore) {
                continue;
            }
            for (String sentence : splitFeedback(turn.getFeedback())) {
                if (lines.size() >= limit) {
                    break;
                }
                lines.add(sentence);
            }
            if (lines.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(lines);
    }

    private List<String> splitFeedback(String feedback) {
        if (StrUtil.isBlank(feedback)) {
            return Collections.emptyList();
        }
        String[] rawParts = feedback.split("[;.!?\\r\\n\\u3002\\uff1b\\uff01\\uff1f]+");
        List<String> parts = new ArrayList<>(rawParts.length);
        for (String rawPart : rawParts) {
            String sentence = StrUtil.trim(rawPart);
            if (StrUtil.isBlank(sentence) || sentence.length() < 6) {
                continue;
            }
            parts.add(sentence.endsWith(".") ? sentence : sentence + ".");
        }
        return parts;
    }

    private List<String> limitList(LinkedHashSet<String> items, int limit) {
        if (items == null || items.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(limit);
        for (String item : items) {
            if (StrUtil.isBlank(item)) {
                continue;
            }
            result.add(item);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private String findStrongestDimensionKey(RadarChartDTO radarChart) {
        return findDimensionKey(radarChart, true);
    }

    private String findWeakestDimensionKey(RadarChartDTO radarChart) {
        return findDimensionKey(radarChart, false);
    }

    private String findDimensionKey(RadarChartDTO radarChart, boolean strongest) {
        if (radarChart == null) {
            return null;
        }
        String[] keys = new String[]{"resume_score", "interview_performance", "professional_skills"};
        String targetKey = null;
        int targetScore = strongest ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (String key : keys) {
            Integer score = getDimensionScore(radarChart, key);
            if (score == null) {
                continue;
            }
            if ((strongest && score > targetScore) || (!strongest && score < targetScore)) {
                targetScore = score;
                targetKey = key;
            }
        }
        return targetKey;
    }

    private Integer getDimensionScore(RadarChartDTO radarChart, String key) {
        if (radarChart == null || StrUtil.isBlank(key)) {
            return null;
        }
        return switch (key) {
            case "resume_score" -> radarChart.getResumeScore();
            case "interview_performance" -> radarChart.getInterviewPerformance();
            case "professional_skills" -> radarChart.getProfessionalSkills();
            case "potential_index" -> radarChart.getPotentialIndex();
            default -> null;
        };
    }

    private String resolveDimensionLabel(String dimensionKey) {
        if (StrUtil.isBlank(dimensionKey)) {
            return null;
        }
        return switch (dimensionKey) {
            case "resume_score" -> "简历匹配度";
            case "interview_performance" -> "答题表现";
            case "professional_skills" -> "专业能力呈现";
            case "potential_index" -> "综合潜力";
            default -> null;
        };
    }

    private List<RadarDimensionItemRespDTO> buildRadarDimensions(RadarChartDTO radarChart) {
        if (radarChart == null) {
            return Collections.emptyList();
        }
        List<RadarDimensionItemRespDTO> dimensions = new ArrayList<>(4);
        // 缺失维度不输出，避免前端把"未评分"画成 0 分
        appendDimension(dimensions, "resume_score", "Resume", radarChart.getResumeScore());
        appendDimension(dimensions, "interview_performance", "Interview", radarChart.getInterviewPerformance());
        appendDimension(dimensions, "professional_skills", "Skills", radarChart.getProfessionalSkills());
        appendDimension(dimensions, "potential_index", "Potential", radarChart.getPotentialIndex());
        return dimensions;
    }

    private void appendDimension(List<RadarDimensionItemRespDTO> dimensions, String key, String label, Integer score) {
        if (score == null) {
            return;
        }
        dimensions.add(new RadarDimensionItemRespDTO(key, label, clampScore(score)));
    }

    private List<InterviewPlaybackItemRespDTO> buildPlaybackItems(List<InterviewTurnLog> turns) {
        if (turns == null || turns.isEmpty()) {
            return Collections.emptyList();
        }
        List<InterviewPlaybackItemRespDTO> items = new ArrayList<>(turns.size());
        for (int i = 0; i < turns.size(); i++) {
            InterviewTurnLog turn = turns.get(i);
            if (turn == null) {
                continue;
            }
            InterviewPlaybackItemRespDTO item = new InterviewPlaybackItemRespDTO();
            item.setSeq(i + 1);
            item.setTimestamp(turn.getTimestamp());
            item.setRequestId(turn.getRequestId());
            item.setQuestionNumber(turn.getQuestionNumber());
            item.setQuestionContent(turn.getQuestionContent());
            item.setAnswerContent(turn.getAnswerContent());
            item.setScore(turn.getScore());
            item.setFeedback(turn.getFeedback());
            item.setTotalScore(turn.getTotalScore());
            item.setFollowUpNeeded(turn.getFollowUpNeeded());
            item.setIsFollowUp(turn.getIsFollowUp());
            item.setFollowUpCount(turn.getFollowUpCount());
            item.setNextQuestionNumber(turn.getNextQuestionNumber());
            item.setNextQuestion(turn.getNextQuestion());
            item.setFinished(turn.getFinished());
            items.add(item);
        }
        return items;
    }

    private Integer clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }

    @Override
    public Map<String, String> parseInterviewSuggestions(String suggestionsString) {
        Map<String, String> suggestionsMap = new LinkedHashMap<>();
        if (StrUtil.isBlank(suggestionsString)) {
            return suggestionsMap;
        }

        String[] suggestions = suggestionsString.split(";");
        for (int i = 0; i < suggestions.length; i++) {
            String suggestion = suggestions[i].trim();
            if (StrUtil.isNotBlank(suggestion)) {
                suggestionsMap.put(String.valueOf(i + 1), suggestion);
            }
        }

        log.debug("Parsed interview suggestions, rawLength={}, parsedCount={}",
                suggestionsString.length(), suggestionsMap.size());
        return suggestionsMap;
    }
}
