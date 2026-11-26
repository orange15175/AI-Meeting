package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 材料角色识别：判断一段文字更像「简历」还是「岗位描述」。
 *
 * <p>为什么需要它：JD 槽位原本只校验互斥、长度、大小、后缀，<b>不看内容含义</b>。
 * 把简历放进 JD 位置时，模型不会拒绝——它会把这堆经历当成岗位要求，产出
 * "根据你的项目经历里那条 Redis 缓存来出题"的题目，岗位映射也全部指向候选人自己。
 * 全流程不报错，只是题目质量静默劣化，和"空题被过滤后题号错位"属于同一类问题。</p>
 *
 * <p>为什么不用模型判断：这里要挡的是<b>误传文件</b>，不是语义理解。多一次模型调用意味着
 * 多几十秒等待、多一份计费，还要在关键路径上引入新的失败态。改用可解释的特征打分：
 * 每个命中词都是用户能看懂的理由（提示语里会列出命中项），判定过程可复现、可单测。</p>
 *
 * <p>策略是<b>宁放过不误杀</b>：分数不够就返回 {@link Role#UNKNOWN} 放行。短 JD
 * （"招 Java 后端，3 年经验"）本来就没什么特征词，拦下来才是错的。</p>
 */
@Component
public class MaterialRoleDetector {

    /**
     * 内容角色判定结果。
     */
    public enum Role {
        /** 明显是简历。 */
        RESUME_LIKE,
        /** 明显是岗位描述。 */
        JD_LIKE,
        /** 特征不足，放行。 */
        UNKNOWN
    }

    /**
     * 判定结果：带上双方得分与命中词，便于拼出"命中：教育经历、项目经历"这类可核对提示。
     */
    public record Detection(Role role, int jdScore, int resumeScore, List<String> jdHits, List<String> resumeHits) {

        public boolean is(Role expected) {
            return role == expected;
        }
    }

    /**
     * 达成某个角色所需的最低分。单个强特征（如"岗位职责"4 分）不足以独立判定，
     * 但两个强特征（8 分）就够了。
     */
    private static final int ROLE_SCORE_THRESHOLD = 4;

    /**
     * 两个角色得分的最小差距。差距太小说明这段文字同时具备两边特征（例如简历里
     * 抄了一段"岗位职责"），此时应当放行而不是替用户做决定。
     */
    private static final int ROLE_SCORE_MARGIN = 3;

    /**
     * 「小节名级」特征的权重下限。达到该权重的词（教育经历、项目经历、岗位职责……）
     * 是文档结构性标题，一份材料里出现就基本确定体裁；低于它的词（薪资范围、加分项）
     * 只是零散用语，两边都可能出现。
     *
     * <p>为什么要区分：真实简历常常只写「教育信息」「技能」而不是「教育经历」「专业技能」，
     * 整份材料可能只命中一个简历强特征；而简历的项目描述里又随手写了「面试流程设计」，
     * 于是 JD 侧白捡 2 分。按总分差判定时 4-2=2 达不到 margin，材料被判成 UNKNOWN 放行。
     * 强特征优先于弱词，才能挡住这种"一个强标题被一个巧合弱词抵消"的情况。</p>
     */
    private static final int STRONG_SIGNAL_WEIGHT = 3;

    /**
     * 判定"同一份内容"的相似度阈值。0.75 能容忍 OCR 差异与少量编辑，又不会把
     * 两份不同的技术材料判成同一份。
     */
    private static final double SAME_CONTENT_THRESHOLD = 0.75;

    /** 3-gram：中文按字符切，对分词错误免疫。 */
    private static final int SHINGLE_SIZE = 3;

    /**
     * 文本太短时 3-gram 数量不足，相似度不可信（"熟悉 Redis" 可能整段出现在任何材料里）。
     */
    private static final int MIN_SHINGLES = 60;

    /**
     * 岗位描述特征词及权重。只收"几乎只出现在招聘文案里"的表达：
     * "负责"、"熟悉"、"精通"这类简历也在用的动词一律不收，否则误判率会失控。
     */
    private static final Map<String, Integer> JD_SIGNALS = signals(
            "岗位职责", 4,
            "职位描述", 4,
            "岗位描述", 4,
            "工作职责", 4,
            "任职要求", 4,
            "任职资格", 4,
            "职位要求", 4,
            "岗位要求", 4,
            "岗位说明", 3,
            "岗位名称", 3,
            "职位名称", 3,
            "你将负责", 3,
            "我们希望你", 3,
            "汇报对象", 2,
            "面试流程", 2,
            "加分项", 2,
            "优先考虑", 2,
            "工作地点", 2,
            "上班时间", 2,
            "五险一金", 2,
            "福利待遇", 2,
            "薪资范围", 2,
            "薪资待遇", 2,
            "招聘人数", 2,
            "简历投递", 2,
            "投递方式", 2,
            "公司地址", 2,
            "团队介绍", 1,
            "公司简介", 1,
            "job description", 4,
            "responsibilities", 3,
            "requirements", 3,
            "qualifications", 3,
            "nice to have", 2,
            "we are looking for", 2);

    /**
     * 简历特征词及权重。同样避开通用动词，只收简历的固定小节名与个人信息项。
     */
    private static final Map<String, Integer> RESUME_SIGNALS = signals(
            "教育经历", 4,
            "教育背景", 4,
            "教育信息", 4,
            "项目经历", 4,
            "实习经历", 4,
            "自我评价", 4,
            "个人信息", 4,
            "个人简历", 4,
            "工作经历", 3,
            "校园经历", 3,
            "在校经历", 3,
            "专业技能", 3,
            "技能特长", 3,
            "个人优势", 3,
            "基本信息", 3,
            "主修课程", 3,
            "获奖情况", 3,
            "荣誉奖项", 3,
            "毕业院校", 3,
            "绩点", 3,
            "gpa", 3,
            // 「项目经验」「实习经验」「技术栈」招聘文案也在用（"3 年项目经验""技术栈：Java"），
            // 只给弱权重，避免 JD 里一句话就把整篇判成简历。
            "项目经验", 2,
            "实习经验", 2,
            "项目描述", 2,
            "技术栈", 2,
            "培训经历", 2,
            "求职意向", 2,
            "求职目标", 2,
            "期望薪资", 2,
            "出生年月", 2,
            "政治面貌", 2,
            "联系电话", 2,
            "电子邮箱", 2,
            "毕业论文", 2);

    /**
     * 识别文本更像哪一类材料。
     */
    public Detection detect(String text) {
        String normalized = normalize(text);
        if (StrUtil.isBlank(normalized)) {
            return new Detection(Role.UNKNOWN, 0, 0, List.of(), List.of());
        }

        List<String> jdHits = new ArrayList<>();
        List<String> resumeHits = new ArrayList<>();
        int jdScore = score(normalized, JD_SIGNALS, jdHits);
        int resumeScore = score(normalized, RESUME_SIGNALS, resumeHits);
        return new Detection(
                decide(jdScore, resumeScore, strongCount(JD_SIGNALS, jdHits), strongCount(RESUME_SIGNALS, resumeHits)),
                jdScore, resumeScore, List.copyOf(jdHits), List.copyOf(resumeHits));
    }

    /**
     * 角色判定，分三层：
     *
     * <ol>
     *   <li>只有一侧出现结构标题（强特征），另一侧只有零散用语 → 直接采信结构标题。
     *       这一层挡的是"简历只写了「教育信息」「项目经历」，而项目描述里恰好出现「面试流程」"，
     *       总分差被弱词抹平的情况。</li>
     *   <li>两侧都有结构标题（简历里整段抄了「岗位职责」），或两侧都只有零散用语
     *       （短 JD 只剩「五险一金」「薪资范围」）→ 退回总分差 + 最小差距判断。</li>
     *   <li>仍然分不出 → UNKNOWN 放行。宁可漏拦，也不替用户改材料。</li>
     * </ol>
     */
    private Role decide(int jdScore, int resumeScore, int jdStrong, int resumeStrong) {
        if (jdStrong > 0 && resumeStrong == 0) {
            return Role.JD_LIKE;
        }
        if (resumeStrong > 0 && jdStrong == 0) {
            return Role.RESUME_LIKE;
        }
        if (jdScore - resumeScore >= ROLE_SCORE_MARGIN && jdScore >= ROLE_SCORE_THRESHOLD) {
            return Role.JD_LIKE;
        }
        if (resumeScore - jdScore >= ROLE_SCORE_MARGIN && resumeScore >= ROLE_SCORE_THRESHOLD) {
            return Role.RESUME_LIKE;
        }
        return Role.UNKNOWN;
    }

    private int strongCount(Map<String, Integer> signals, List<String> hits) {
        int count = 0;
        for (String hit : hits) {
            if (signals.getOrDefault(hit, 0) >= STRONG_SIGNAL_WEIGHT) {
                count++;
            }
        }
        return count;
    }

    /**
     * 两段文字是否基本是同一份内容（用户把同一个文件放进了两个输入框）。
     *
     * <p>用 3-gram 的 Jaccard 与"包含度"取大者：前者处理"整体相同"，后者处理
     * "把简历的一大段原样贴进 JD"——此时 JD 的每个 3-gram 都能在简历里找到，
     * Jaccard 会被简历的长文本稀释，只有包含度能看出来。</p>
     */
    public boolean sameContent(String left, String right) {
        Set<String> a = shingles(left);
        Set<String> b = shingles(right);
        if (a.size() < MIN_SHINGLES || b.size() < MIN_SHINGLES) {
            return false;
        }
        int intersection = 0;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String shingle : smaller) {
            if (larger.contains(shingle)) {
                intersection++;
            }
        }
        double jaccard = intersection / (double) (a.size() + b.size() - intersection);
        double containment = intersection / (double) smaller.size();
        return Math.max(jaccard, containment) >= SAME_CONTENT_THRESHOLD;
    }

    private int score(String normalized, Map<String, Integer> signals, List<String> hits) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : signals.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                total += entry.getValue();
                hits.add(entry.getKey());
            }
        }
        return total;
    }

    private Set<String> shingles(String text) {
        String flat = normalize(text).replaceAll("\\s+", "");
        Set<String> result = new HashSet<>();
        if (flat.length() < SHINGLE_SIZE) {
            return result;
        }
        for (int i = 0; i + SHINGLE_SIZE <= flat.length(); i++) {
            result.add(flat.substring(i, i + SHINGLE_SIZE));
        }
        return result;
    }

    /**
     * NFKC 抹平全角/半角与合字差异（PDF 抽取常见），小写化让英文特征词不区分大小写。
     */
    private String normalize(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static Map<String, Integer> signals(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        // 用 LinkedHashMap 保序：命中词列表会原样出现在用户提示里，顺序必须稳定且可预期。
        return Collections.unmodifiableMap(map);
    }
}
