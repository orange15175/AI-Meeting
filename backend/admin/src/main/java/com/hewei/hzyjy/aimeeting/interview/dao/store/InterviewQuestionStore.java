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

    // 按主问题序号、追问序号排序，使 3、3-F1、3-F2 排在 4 之前；不能直接按字符串题号排序。
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

    // 当前题查询使用 sessionId + questionNo，既隔离不同面试，也能定位主问题和追问题。
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

    // 统计本场全部有效题目，包含已生成追问；不要与会话 totalQuestions 的主问题总数混淆。
    public int countAll(String sessionId) {
        Long count = questionMapper.selectCount(Wrappers.lambdaQuery(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId)
                .eq(InterviewQuestionItem::getDelFlag, 0));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 一行待落库的主问题。
     *
     * <p>题号由出题侧生成后传进来，不在这里按数组下标重算：分类、难度、要点都要和题干挂在同一行，
     * 两处各自编号就会重演"空题被过滤后题号错位"的老问题。</p>
     */
    public record MainQuestion(String questionNo, String content, String category, String difficulty) {
    }

    /**
     * 重试出题：整批替换主问题。
     *
     * <p>这里必须物理删除：question_no 上有唯一键，软删除会与重新插入的同号题目冲突。
     * 历史回答由调用方在同一事务里一并清理，因为新题库意味着本轮问答作废。</p>
     *
     * <p>分类与难度随题干一次插入，而不是插入后再逐题 UPDATE：三列必须同源同序，
     * 分开写既有中间态（题在、分类空），也多出 N 次写放大。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceMainQuestions(String sessionId, List<MainQuestion> questions) {
        Date now = new Date();
        // 【关键节点：整轮替换】删除本场旧题（包括追问题），再从 1 开始插入新主问题；在外层准备事务中与提交清理配套。
        questionMapper.delete(Wrappers.lambdaQuery(InterviewQuestionItem.class)
                .eq(InterviewQuestionItem::getSessionId, sessionId));
        if (questions == null || questions.isEmpty()) {
            return;
        }
        // 新题统一为 MAIN，followUpIndex 为 0；mainIndex 取列表位置，questionNo 由调用方保证 1..n 同序。
        for (int i = 0; i < questions.size(); i++) {
            MainQuestion row = questions.get(i);
            InterviewQuestionItem item = new InterviewQuestionItem();
            item.setSessionId(sessionId);
            item.setQuestionNo(row.questionNo());
            item.setKind(KIND_MAIN);
            item.setMainIndex(i + 1);
            item.setFollowUpIndex(0);
            item.setContent(row.content());
            item.setCategory(row.category());
            item.setDifficulty(row.difficulty());
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
        // 追问关联原主问题作为 parent；仅插入题干和编号，不在这里修改会话游标，由外层答题事务统一推进。
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
