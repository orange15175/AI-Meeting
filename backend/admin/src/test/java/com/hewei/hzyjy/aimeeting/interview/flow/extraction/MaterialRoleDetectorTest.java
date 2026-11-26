package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 材料角色识别：只在高置信度时判定，特征不足一律放行。
 */
class MaterialRoleDetectorTest {

    private final MaterialRoleDetector detector = new MaterialRoleDetector();

    private static final String RESUME = """
            刘君
            求职意向：Java 后端开发工程师
            联系电话：13800000000  电子邮箱：liujun@example.com
            教育经历
            2023.09-2027.06  某某大学  计算机科学与技术  本科
            主修课程：数据结构、操作系统、计算机网络
            项目经历
            AI 面试平台：基于 Spring Boot 与 Vue3 实现出题、评分与追问链路，使用 Redis 做会话锁与幂等登记。
            专业技能
            熟悉 Java、MySQL、Redis，了解 LiteFlow 规则引擎。
            自我评价
            学习能力强，喜欢钻研底层原理。
            获奖情况
            2024 年校级程序设计竞赛二等奖
            """;

    private static final String JD = """
            岗位职责：
            1. 负责公司核心交易系统的后端开发与维护；
            2. 参与技术方案设计，保障系统高可用。
            任职要求：
            1. 本科及以上学历，3 年以上 Java 开发经验；
            2. 熟悉 Spring Cloud、MySQL、Redis；
            3. 有高并发经验者优先考虑。
            加分项：有开源项目贡献。
            工作地点：上海
            """;

    @Test
    @DisplayName("典型简历判为 RESUME_LIKE")
    void detectsResume() {
        MaterialRoleDetector.Detection detection = detector.detect(RESUME);

        assertThat(detection.role()).isEqualTo(MaterialRoleDetector.Role.RESUME_LIKE);
        assertThat(detection.resumeHits()).contains("教育经历", "项目经历", "自我评价");
        assertThat(detection.resumeScore()).isGreaterThan(detection.jdScore());
    }

    @Test
    @DisplayName("典型招聘文案判为 JD_LIKE")
    void detectsJobDescription() {
        MaterialRoleDetector.Detection detection = detector.detect(JD);

        assertThat(detection.role()).isEqualTo(MaterialRoleDetector.Role.JD_LIKE);
        assertThat(detection.jdHits()).contains("岗位职责", "任职要求");
        assertThat(detection.jdScore()).isGreaterThan(detection.resumeScore());
    }

    @Test
    @DisplayName("英文招聘文案同样能识别")
    void detectsEnglishJobDescription() {
        String english = """
                Job Description
                Responsibilities
                - Build and maintain backend services for the trading platform.
                Requirements
                - 3+ years of Java experience, familiar with MySQL and Redis.
                Nice to have: Kubernetes.
                """;

        assertThat(detector.detect(english).role()).isEqualTo(MaterialRoleDetector.Role.JD_LIKE);
    }

    @Test
    @DisplayName("特征不足的技术片段放行，不强行归类")
    void passesAmbiguousText() {
        // 这类句子在简历和 JD 里都会出现，判定必须有结论就会误杀一半用户。
        String ambiguous = "负责订单与库存模块，使用 Redis 缓存商品库存。";

        assertThat(detector.detect(ambiguous).role()).isEqualTo(MaterialRoleDetector.Role.UNKNOWN);
    }

    @Test
    @DisplayName("极短招聘信息不误判：没有固定小节名就不拦")
    void passesVeryShortJobPosting() {
        String shortJd = "招 Java 后端，3 年经验，熟悉 Spring Boot。";

        assertThat(detector.detect(shortJd).role()).isEqualTo(MaterialRoleDetector.Role.UNKNOWN);
    }

    @Test
    @DisplayName("简历里引用了一段岗位职责，仍以简历为主")
    void resumeQuotingJdStaysResume() {
        String quoting = RESUME + "\n工作经历\n岗位职责：负责订单系统重构，配合产品完成需求评审。\n";

        MaterialRoleDetector.Detection detection = detector.detect(quoting);

        assertThat(detection.jdScore()).isPositive();
        assertThat(detection.role()).isEqualTo(MaterialRoleDetector.Role.RESUME_LIKE);
    }

    @Test
    @DisplayName("真实简历只写「教育信息」「技能」也能识别：弱词不能抵消强标题")
    void detectsRealResumeWithNonStandardHeadings() {
        // 取自线上简历原件：小节名是「教育信息」「技能」，不是词表默认的「教育经历」「专业技能」；
        // 项目描述里又写了「面试流程设计」，让 JD 侧白捡 2 分。若按总分差判定，4-2=2 达不到 margin，
        // 材料会被判成 UNKNOWN 放行——这正是这张简历被放进 JD 槽却照样出题的原因。
        String realResume = """
                姓名：刘君（在校）
                电话：17317281964
                邮箱：1517583776@qq.com

                 教育信息

                上海建桥学院 — 计算机科学与技术（本科） 2027.07 毕业

                 技能

                熟悉 Java 基础、集合、异常、类加载机制，熟悉 Python 基础语法及 FastAPI 框架。
                熟悉 Spring、Spring Boot、MyBatis-Plus，了解 IoC、AOP 与循环依赖处理。
                熟悉 Redis 常用数据结构、缓存策略、分布式锁、Lua 脚本。

                 项目经历

                智能面试 agent 平台 【GitHub：orange15175/AI-Meeting】
                面试流程设计：将答题拆分为评分、追问和题目推进，使用 LiteFlow 判断是否追问。
                答题幂等与并发控制：使用有限租期的 Redisson 会话锁协调答题。
                """;

        MaterialRoleDetector.Detection detection = detector.detect(realResume);

        assertThat(detection.role()).isEqualTo(MaterialRoleDetector.Role.RESUME_LIKE);
        assertThat(detection.resumeHits()).contains("教育信息", "项目经历");
        // JD 侧确实被「面试流程」带出了分，但强标题优先，不能被这点弱词否决。
        assertThat(detection.jdHits()).contains("面试流程");
    }

    @Test
    @DisplayName("JD 里的「项目经验」是弱特征，不会把招聘文案判成简历")
    void jdMentioningProjectExperienceStaysJd() {
        String jd = """
                岗位职责：负责交易系统后端开发与维护。
                任职要求：3 年以上 Java 项目经验，熟悉 Spring Boot 与 MySQL。
                """;

        assertThat(detector.detect(jd).role()).isEqualTo(MaterialRoleDetector.Role.JD_LIKE);
    }

    @Test
    @DisplayName("同一份内容被判为重复")
    void detectsIdenticalContent() {
        assertThat(detector.sameContent(RESUME, RESUME)).isTrue();
    }

    @Test
    @DisplayName("简历与岗位描述不判为重复")
    void differentMaterialsAreNotDuplicates() {
        assertThat(detector.sameContent(JD, RESUME)).isFalse();
    }

    @Test
    @DisplayName("把简历的一整段原样贴进 JD 也能识别为重复")
    void detectsContainedContent() {
        String excerpt = RESUME.substring(0, 200);

        assertThat(detector.sameContent(excerpt, RESUME)).isTrue();
    }

    @Test
    @DisplayName("文本过短时相似度不可信，直接放行")
    void shortTextIsNeverDuplicate() {
        assertThat(detector.sameContent("熟悉 Redis", "熟悉 Redis")).isFalse();
    }

    @Test
    @DisplayName("空文本不崩溃")
    void handlesBlankInput() {
        assertThat(detector.detect(null).role()).isEqualTo(MaterialRoleDetector.Role.UNKNOWN);
        assertThat(detector.detect("   ").role()).isEqualTo(MaterialRoleDetector.Role.UNKNOWN);
        assertThat(detector.sameContent(null, RESUME)).isFalse();
    }
}
