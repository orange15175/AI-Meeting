package com.hewei.hzyjy.aimeeting.interview.dao.store;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewQuestionItem;
import com.hewei.hzyjy.aimeeting.interview.dao.mapper.InterviewQuestionItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试题目存取：主问题与追问同表，用 question_no 区分。
 */
@Service
@RequiredArgsConstructor
public class InterviewQuestionStore {

    public static final String KIND_MAIN = "MAIN";
    public static final String KIND_FOLLOW_UP = "FOLLOW_UP";

    private final InterviewQuestionItemMapper questionMapper;

    public List<InterviewQuestionItem> listBySession(String sessionId) {
        return questionMapper.selectList(Wrappers.lambdaQuery(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId)
                .eq(InterviewQuestionItem::getDelFlag, 0)
                .orderByAsc(InterviewQuestionItem::getMainIndex)
                .orderByAsc(InterviewQuestionItem::getFollowUpIndex));
    }

    public List<InterviewQuestionItem> listMain(String sessionId) {
        return questionMapper.selectList(Wrappers.lambdaQuery(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId)
                .eq(InterviewQuestionItem::getKind, KIND_MAIN)
                .eq(InterviewQuestionItem::getDelFlag, 0)
                .orderByAsc(InterviewQuestionItem::getMainIndex));
    }

    public InterviewQuestionItem findByNo(String sessionId, String questionNo) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNo)) {
            return null;
        }
        return questionMapper.selectOne(Wrappers.lambdaQuery(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId)
                .eq(InterviewQuestionItem::getQuestionNo, questionNo.trim())
                .eq(InterviewQuestionItem::getDelFlag, 0));
    }

    /**
     * 题号 -> 题干，按主问题序号与追问序号排序，供前端一次性渲染。
     */
    public Map<String, String> questionsAsMap(String sessionId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (InterviewQuestionItem item : listBySession(sessionId)) {
            result.put(item.getQuestionNo(), item.getContent());
        }
        return result;
    }

    public int countMain(String sessionId) {
        Long count = questionMapper.selectCount(Wrappers.lambdaQuery(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId)
                .eq(InterviewQuestionItem::getKind, KIND_MAIN)
                .eq(InterviewQuestionItem::getDelFlag, 0));
        return count == null ? 0 : count.intValue();
    }

    public int countAll(String sessionId) {
        Long count = questionMapper.selectCount(Wrappers.lambdaQuery(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId)
                .eq(InterviewQuestionItem::getDelFlag, 0));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 重试出题：整批替换主问题。
     *
     * <p>这里必须物理删除：question_no 上有唯一键，软删除会与重新插入的同号题目冲突。
     * 历史回答由调用方在同一事务里一并清理，因为新题库意味着本轮问答作废。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceMainQuestions(String sessionId, List<String> contents) {
        Date now = new Date();
        questionMapper.delete(Wrappers.lambdaQuery(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId));
        if (contents == null || contents.isEmpty()) {
            return;
        }
        for (int i = 0; i < contents.size(); i++) {
            InterviewQuestionItem item = new InterviewQuestionItem();
            item.setSessionId(sessionId);
            item.setQuestionNo(String.valueOf(i + 1));
            item.setKind(KIND_MAIN);
            item.setMainIndex(i + 1);
            item.setFollowUpIndex(0);
            item.setContent(contents.get(i));
            item.setCreateTime(now);
            item.setUpdateTime(now);
            item.setDelFlag(0);
            questionMapper.insert(item);
        }
    }

    /**
     * 回填考察要点（只在服务端使用）。
     */
    public boolean updatePoints(String sessionId, String questionNo, String pointsJson) {
        return questionMapper.update(null, Wrappers.lambdaUpdate(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId)
                .eq(InterviewQuestionItem::getQuestionNo, questionNo)
                .eq(InterviewQuestionItem::getDelFlag, 0)
                .set(InterviewQuestionItem::getPointsJson, pointsJson)
                .set(InterviewQuestionItem::getUpdateTime, new Date())) == 1;
    }

    /**
     * 回填题目对应的岗位要求与证据（只在服务端使用）。
     */
    public boolean updateTargetContext(String sessionId, String questionNo, String targetJson) {
        return questionMapper.update(null, Wrappers.lambdaUpdate(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId)
                .eq(InterviewQuestionItem::getQuestionNo, questionNo)
                .eq(InterviewQuestionItem::getDelFlag, 0)
                .set(InterviewQuestionItem::getTargetContext, targetJson)
                .set(InterviewQuestionItem::getUpdateTime, new Date())) == 1;
    }

    /**
     * 追加一道追问；题号形如 1-F1。
     */
    public InterviewQuestionItem appendFollowUp(
            String sessionId,
            int mainIndex,
            int followUpIndex,
            String content) {
        InterviewQuestionItem parent = questionMapper.selectOne(Wrappers.lambdaQuery(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId)
                .eq(InterviewQuestionItem::getQuestionNo, String.valueOf(mainIndex))
                .eq(InterviewQuestionItem::getDelFlag, 0));
        Date now = new Date();
        InterviewQuestionItem item = new InterviewQuestionItem();
        item.setSessionId(sessionId);
        item.setQuestionNo(mainIndex + "-F" + followUpIndex);
        item.setKind(KIND_FOLLOW_UP);
        item.setMainIndex(mainIndex);
        item.setFollowUpIndex(followUpIndex);
        item.setParentId(parent == null ? null : parent.getId());
        item.setContent(content);
        item.setCreateTime(now);
        item.setUpdateTime(now);
        item.setDelFlag(0);
        questionMapper.insert(item);
        return item;
    }
}
