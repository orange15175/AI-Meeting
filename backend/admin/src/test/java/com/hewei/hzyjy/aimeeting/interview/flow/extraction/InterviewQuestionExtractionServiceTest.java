package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.aimeeting.interview.application.guard.lock.InterviewAiSessionLockService;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.aimeeting.llm.LlmRequest;
import com.hewei.hzyjy.aimeeting.llm.document.InterviewMaterialExtractor;
import com.hewei.hzyjy.aimeeting.llm.document.InterviewMaterialExtractor.MaterialType;
import com.hewei.hzyjy.aimeeting.llm.document.LocalFileStorageService;
import com.hewei.hzyjy.aimeeting.llm.prompt.PromptLibrary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 出题服务的验收路径：校验失败 → 一次修复 → 转换落库；两次都失败则不写题库。
 *
 * <p>用真实校验器、真实转换器与真实提示词文件（从 classpath 加载），只把模型、存储和事务边界换成 mock，
 * 这样测的是"校验与修复的编排"，而不是 mock 之间的对话。</p>
 */
class InterviewQuestionExtractionServiceTest {

    private static final String RESUME_TEXT = "负责订单与库存模块，使用 Redis 缓存商品库存。";

    /**
     * 典型招聘文案：命中"岗位职责/任职要求"等固定小节名。
     */
    private static final String JD_TEXT = """
            岗位职责：
            1. 负责公司核心交易系统的后端开发与维护；
            2. 参与技术方案设计，保障系统高可用。
            任职要求：
            1. 本科及以上学历，3 年以上 Java 开发经验；
            2. 熟悉 Spring Cloud、MySQL、Redis。
            加分项：有开源项目贡献。
            工作地点：上海
            """;

    /**
     * 另一份完整简历：用于验证"JD 槽位误放简历"与"两处放同一份材料"两条路径。
     */
    private static final String OTHER_RESUME = """
            张三
            求职意向：Java 后端开发工程师
            教育经历
            2021.09-2025.06  某某大学  软件工程  本科
            主修课程：数据结构、操作系统、数据库原理
            项目经历
            电商后台：负责订单与库存模块，使用 Redis 缓存库存，峰值 QPS 3000。
            实习经历
            某厂后端实习，参与支付对账的任务调度改造。
            专业技能
            熟悉 Java、MySQL、Redis、Spring Boot。
            自我评价
            工程习惯良好，能独立定位线上问题。
            获奖情况
            校级优秀学生干部
            """;

    private InterviewMaterialExtractor materialExtractor;
    private LocalFileStorageService fileStorageService;
    private InterviewAiInvoker aiInvoker;
    private InterviewAiSessionLockService sessionLockService;
    private InterviewPreparationCommitService commitService;
    private SimpleMeterRegistry meterRegistry;
    private InterviewQuestionExtractionService service;

    @BeforeEach
    void setUp() throws Exception {
        materialExtractor = mock(InterviewMaterialExtractor.class);
        fileStorageService = mock(LocalFileStorageService.class);
        aiInvoker = mock(InterviewAiInvoker.class);
        sessionLockService = mock(InterviewAiSessionLockService.class);
        commitService = mock(InterviewPreparationCommitService.class);
        meterRegistry = new SimpleMeterRegistry();

        service = new InterviewQuestionExtractionService(
                materialExtractor,
                fileStorageService,
                aiInvoker,
                sessionLockService,
                commitService,
                new InterviewQuestionBatchValidator(),
                new InterviewQuestionBatchConverter(),
                new MaterialRoleDetector(),
                new PromptLibrary(new DefaultResourceLoader()),
                meterRegistry);
        ReflectionTestUtils.setField(service, "questionCount", 2);
        ReflectionTestUtils.setField(service, "minQuestionCount", 1);
        ReflectionTestUtils.setField(service, "maxQuestionCount", 20);
        ReflectionTestUtils.setField(service, "modelName", "test-model");
        ReflectionTestUtils.setField(service, "maxTokensPerQuestion", 400);
        ReflectionTestUtils.setField(service, "maxTokensFloor", 4096);
        ReflectionTestUtils.setField(service, "maxTokensCeiling", 8192);

        when(fileStorageService.store(anyString(), any())).thenReturn("resume/2026/demo.pdf");
        when(materialExtractor.extract(any(), any())).thenReturn(RESUME_TEXT);
        when(sessionLockService.acquire(anyString(), any())).thenReturn(mock(RLock.class));
        when(commitService.commitSucceeded(any())).thenReturn(true);
    }

    @Test
    @DisplayName("首次输出数量不符时发起一次修复，修复通过后按数组顺序落库")
    void repairsOnceAndCommits() throws Exception {
        when(aiInvoker.call(any(), any(), any()))
                .thenReturn(invalidBatchJson())
                .thenReturn(validBatchJson());

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(request());

        assertThat(response.getIsSuccess()).isEqualTo(1);
        assertThat(response.getQuestionCount()).isEqualTo(2);
        assertThat(response.getQuestions()).containsOnlyKeys("1", "2");

        ArgumentCaptor<InterviewPreparationCommitService.PreparationCommand> captor =
                ArgumentCaptor.forClass(InterviewPreparationCommitService.PreparationCommand.class);
        verify(commitService, times(1)).commitSucceeded(captor.capture());
        InterviewPreparationCommitService.PreparationCommand command = captor.getValue();
        assertThat(command.getQuestions()).hasSize(2);
        assertThat(command.getQuestionPoints().get("2")).containsExactly("说明不一致来源", "给出兜底方案");
        assertThat(command.getPromptVersion()).contains("@v4");
        // 落库的是修复后的文本，而不是第一次那份被拒的输出
        assertThat(command.getRawResponse()).contains("第二题：如何保证缓存一致性？");

        verify(aiInvoker, times(2)).call(any(), any(), any());
        assertThat(meterRegistry.get("extraction_repair_total").tag("outcome", "succeeded").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("extraction_validation_fail_total").tag("phase", "first").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("修复后仍不合规时不落库，返回可识别错误")
    void failsWithoutCommitWhenRepairStillInvalid() throws Exception {
        when(aiInvoker.call(any(), any(), any()))
                .thenReturn(invalidBatchJson())
                .thenReturn(invalidBatchJson());

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(request());

        assertThat(response.getIsSuccess()).isEqualTo(0);
        assertThat(response.getErrorMessage()).contains("未通过校验");
        verify(commitService, never()).commitSucceeded(any());
        verify(commitService, times(1)).commitFailed(anyString(), any(), any(), anyString(), any());
        assertThat(meterRegistry.get("extraction_repair_total").tag("outcome", "failed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("首次输出合规时只调用一次模型，不触发修复")
    void commitsDirectlyWhenValid() throws Exception {
        when(aiInvoker.call(any(), any(), any())).thenReturn(validBatchJson());

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(request());

        assertThat(response.getIsSuccess()).isEqualTo(1);
        verify(aiInvoker, times(1)).call(any(), any(), any());
        verify(commitService, times(1)).commitSucceeded(any());

        // 提示词变量必须真的被替换：模板与服务的变量名对不上时会静默变成空字符串，这里把它钉住。
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(aiInvoker).call(requestCaptor.capture(), any(), any());
        String userPrompt = requestCaptor.getValue().getUserPrompt();
        assertThat(userPrompt).contains("恰好 2 道主问题").contains(RESUME_TEXT).contains("智能匹配").contains("（未提供）");
        // 2 题预算 = 2000 + 400×2 = 2800，被 floor 抬到 4096
        assertThat(requestCaptor.getValue().getMaxTokens()).isEqualTo(4096);
    }

    @Test
    @DisplayName("JD 文字与文件同时提供时直接拒绝，不进入模型调用")
    void rejectsJdTextAndFileAtTheSameTime() {
        InterviewQuestionReqDTO req = request();
        req.setJdText("岗位要求：熟悉 Redis");
        req.setJdFile(new MockMultipartFile("jdFile", "jd.pdf", "application/pdf", "dummy".getBytes()));

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(req);

        assertThat(response.getIsSuccess()).isZero();
        assertThat(response.getErrorMessage()).contains("二选一");
        verifyNoInteractions(aiInvoker, commitService);
    }

    @Test
    @DisplayName("JD 文字超过上限时拒绝，不进入模型调用")
    void rejectsOversizedJdText() {
        InterviewQuestionReqDTO req = request();
        req.setJdText("岗".repeat(10001));

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(req);

        assertThat(response.getIsSuccess()).isZero();
        assertThat(response.getErrorMessage()).contains("10000");
        verifyNoInteractions(aiInvoker, commitService);
    }

    @Test
    @DisplayName("提供 JD 文字时落库岗位来源，题目引用 JD 原文")
    void persistsJdSourceWhenTextProvided() throws Exception {
        when(aiInvoker.call(any(), any(), any())).thenReturn(jdBatchJson());
        InterviewQuestionReqDTO req = request();
        req.setJdText("  岗位要求：熟悉 MySQL 与 Redis  ");

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(req);

        assertThat(response.getIsSuccess()).isEqualTo(1);
        assertThat(response.getJdEnabled()).isTrue();
        assertThat(response.getJdSource()).isEqualTo("TEXT");

        ArgumentCaptor<InterviewPreparationCommitService.PreparationCommand> captor =
                ArgumentCaptor.forClass(InterviewPreparationCommitService.PreparationCommand.class);
        verify(commitService, times(1)).commitSucceeded(captor.capture());
        InterviewPreparationCommitService.PreparationCommand command = captor.getValue();
        assertThat(command.getJdSource()).isEqualTo("TEXT");
        assertThat(command.getJdText()).isEqualTo("岗位要求：熟悉 MySQL 与 Redis");
        assertThat(command.getJdContext()).containsEntry("jobTitle", "Java 后端工程师");
        assertThat(targetOf(command.getQuestionTargets(), "1"))
                .containsEntry("source", "JD")
                .containsEntry("requirement", "熟悉 MySQL 与 Redis");
    }

    @Test
    @DisplayName("JD 文件解析不出文字时明确失败，不静默退回无 JD 出题")
    void failsWhenJdFileHasNoText() throws Exception {
        when(fileStorageService.store(eq("jd"), any())).thenReturn("jd/2026/demo.png");
        when(materialExtractor.extract(any(), eq(MaterialType.JOB_DESCRIPTION))).thenReturn("  ");
        InterviewQuestionReqDTO req = request();
        req.setJdFile(new MockMultipartFile("jdFile", "jd.png", "image/png", "dummy".getBytes()));

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(req);

        assertThat(response.getIsSuccess()).isZero();
        assertThat(response.getErrorMessage()).contains("未识别到有效文字");
        verify(commitService, never()).commitSucceeded(any());
        verify(aiInvoker, never()).call(any(), any(), any());
    }

    @Test
    @DisplayName("难度选择按中文标签渲染进提示词，未选时走智能匹配")
    void rendersSelectedDifficultyIntoPrompt() throws Exception {
        when(aiInvoker.call(any(), any(), any())).thenReturn(validBatchJson());
        InterviewQuestionReqDTO req = request();
        req.setDifficulty("进阶");

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(req);

        assertThat(response.getIsSuccess()).isEqualTo(1);
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(aiInvoker).call(requestCaptor.capture(), any(), any());
        String userPrompt = requestCaptor.getValue().getUserPrompt();
        // 模板正文本身就解释了"智能匹配"的规则，所以只断言变量位被替换成了所选难度的文案。
        assertThat(userPrompt).contains("进阶（侧重原理").doesNotContain("智能匹配（按简历可见");
    }

    @Test
    @DisplayName("题量越界时直接拒绝，不占锁、不调用模型")
    void rejectsOutOfRangeQuestionCount() throws Exception {
        InterviewQuestionReqDTO req = request();
        req.setQuestionCount("100");

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(req);

        assertThat(response.getIsSuccess()).isZero();
        assertThat(response.getErrorMessage()).contains("1 到 20");
        verifyNoInteractions(aiInvoker, commitService);
        verify(sessionLockService, never()).acquire(anyString(), any());
    }

    @Test
    @DisplayName("难度无法识别时直接拒绝，不调用模型")
    void rejectsUnknownDifficulty() throws Exception {
        InterviewQuestionReqDTO req = request();
        req.setDifficulty("地狱");

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(req);

        assertThat(response.getIsSuccess()).isZero();
        assertThat(response.getErrorMessage()).contains("难度只能是");
        verifyNoInteractions(aiInvoker, commitService);
        verify(sessionLockService, never()).acquire(anyString(), any());
    }

    private String jdBatchJson() {
        return """
                {"questions":[
                {"content":"岗位要求提到 Redis，缓存与数据库不一致时你会如何处理？","category":"JOB","difficulty":"MEDIUM",
                "topic":"缓存一致性","source":"JD","evidence":"熟悉 MySQL 与 Redis","requirement":"熟悉 MySQL 与 Redis",
                "scoringPoints":["说明不一致来源","给出兜底方案"]},
                {"content":"解释一下索引失效的常见原因。","category":"FUNDAMENTAL","difficulty":"EASY",
                "topic":"索引","source":"NONE","evidence":"","requirement":"",
                "scoringPoints":["说明失效场景","给出验证方式"]}],
                "jdContext":{"jobTitle":"Java 后端工程师"},"suggestions":["建议一","建议二","建议三"],
                "type":"Java 后端","resumeScore":75,"resumeSummary":"三年 Java 后端经验"}
                """;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> targetOf(Map<String, Object> targets, String questionNo) {
        return (Map<String, Object>) targets.get(questionNo);
    }

    @Test
    @DisplayName("简历位置放了岗位描述：直接拒绝，不调用模型")
    void rejectsJobDescriptionInResumeSlot() throws Exception {
        when(materialExtractor.extract(any(), eq(MaterialType.RESUME))).thenReturn(JD_TEXT);

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(request());

        assertThat(response.getIsSuccess()).isNotEqualTo(1);
        assertThat(response.getErrorMessage()).contains("简历位置的内容看起来是岗位描述", "岗位职责");
        verifyNoInteractions(aiInvoker);
        verify(commitService, never()).commitSucceeded(any());
    }

    @Test
    @DisplayName("岗位描述位置放了简历：拒绝并指向正确的输入框")
    void rejectsResumeTextInJdSlot() throws Exception {
        InterviewQuestionReqDTO request = request();
        request.setJdText(OTHER_RESUME);

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(request);

        assertThat(response.getIsSuccess()).isNotEqualTo(1);
        assertThat(response.getErrorMessage()).contains("岗位描述的内容看起来是简历", "教育经历");
        verifyNoInteractions(aiInvoker);
    }

    @Test
    @DisplayName("两个位置放了同一份内容：提示重复而不是继续出题")
    void rejectsDuplicatedMaterial() throws Exception {
        when(materialExtractor.extract(any(), eq(MaterialType.RESUME))).thenReturn(OTHER_RESUME);
        InterviewQuestionReqDTO request = request();
        request.setJdText(OTHER_RESUME);

        InterviewQuestionRespDTO response = service.extractInterviewQuestions(request);

        assertThat(response.getIsSuccess()).isNotEqualTo(1);
        assertThat(response.getErrorMessage()).contains("几乎相同");
        verifyNoInteractions(aiInvoker);
    }

    private InterviewQuestionReqDTO request() {
        InterviewQuestionReqDTO request = new InterviewQuestionReqDTO();
        request.setSessionId("session-1");
        request.setResumePdf(new MockMultipartFile(
                "resumePdf", "resume.pdf", "application/pdf", "%PDF-1.4".getBytes()));
        return request;
    }

    /**
     * 少给一道题，触发 count 类阻断错误。
     */
    private String invalidBatchJson() {
        return """
                {"questions":[{"content":"第一题：如何避免超卖？","category":"PROJECT","difficulty":"MEDIUM",
                "topic":"库存","source":"RESUME","evidence":"负责订单与库存模块","requirement":"",
                "scoringPoints":["说明风险","给出方案"]}],
                "jdContext":{},"suggestions":["建议一","建议二","建议三"],"type":"Java 后端",
                "resumeScore":75,"resumeSummary":"三年 Java 后端经验"}
                """;
    }

    private String validBatchJson() {
        return """
                {"questions":[
                {"content":"第一题：如何避免超卖？","category":"PROJECT","difficulty":"MEDIUM",
                "topic":"库存","source":"RESUME","evidence":"负责订单与库存模块","requirement":"",
                "scoringPoints":["说明风险","给出方案"]},
                {"content":"第二题：如何保证缓存一致性？","category":"FUNDAMENTAL","difficulty":"EASY",
                "topic":"缓存","source":"NONE","evidence":"","requirement":"",
                "scoringPoints":["说明不一致来源","给出兜底方案"]}],
                "jdContext":{},"suggestions":["建议一","建议二","建议三"],"type":"Java 后端",
                "resumeScore":75,"resumeSummary":"三年 Java 后端经验"}
                """;
    }
}
