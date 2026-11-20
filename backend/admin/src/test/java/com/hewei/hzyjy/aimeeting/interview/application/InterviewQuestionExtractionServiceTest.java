package com.hewei.hzyjy.aimeeting.interview.application;

import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.aimeeting.interview.application.guard.lock.InterviewAiSessionLockService;
import com.hewei.hzyjy.aimeeting.interview.flow.extraction.InterviewPreparationCommitService;
import com.hewei.hzyjy.aimeeting.interview.flow.extraction.InterviewQuestionExtractionService;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewResponseParser;
import com.hewei.hzyjy.aimeeting.llm.document.InterviewMaterialExtractor;
import com.hewei.hzyjy.aimeeting.llm.document.InterviewMaterialExtractor.MaterialType;
import com.hewei.hzyjy.aimeeting.llm.document.LocalFileStorageService;
import com.hewei.hzyjy.aimeeting.llm.prompt.PromptLibrary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InterviewQuestionExtractionServiceTest {

    private final InterviewAiInvoker interviewAiInvoker = mock(InterviewAiInvoker.class);
    private final InterviewAiSessionLockService lockService = mock(InterviewAiSessionLockService.class);
    private final InterviewPreparationCommitService preparationCommitService = mock(InterviewPreparationCommitService.class);
    private final InterviewMaterialExtractor materialExtractor = mock(InterviewMaterialExtractor.class);
    private final LocalFileStorageService fileStorageService = mock(LocalFileStorageService.class);
    private final InterviewQuestionReqDTO request = newRequest();

    private InterviewQuestionExtractionService newService() {
        return new InterviewQuestionExtractionService(
                materialExtractor,
                fileStorageService,
                interviewAiInvoker,
                lockService,
                preparationCommitService,
                new InterviewResponseParser(),
                new PromptLibrary(new DefaultResourceLoader())
        );
    }

    @Test
    void shouldFailWhenModelReturnsEmptyQuestions() throws Exception {
        stubHappyPathDependencies();
        when(interviewAiInvoker.call(any(), eq(InterviewAiGuardStage.INTERVIEW_EXTRACTION), any()))
                .thenReturn("{\"questions\":[],\"suggestions\":[],\"type\":\"\",\"resumeScore\":0}");

        InterviewQuestionRespDTO response = newService().extractInterviewQuestions(request);

        assertEquals(0, response.getIsSuccess());
        assertTrue(response.getErrorMessage().contains("empty interview questions"));
        verify(preparationCommitService, never()).commitSucceeded(any());
    }

    @Test
    void shouldPersistStructuredResultWhenModelReturnsValidJson() throws Exception {
        stubHappyPathDependencies();
        when(interviewAiInvoker.call(any(), eq(InterviewAiGuardStage.INTERVIEW_EXTRACTION), any()))
                .thenReturn(validModelResponse());

        InterviewQuestionRespDTO response = newService().extractInterviewQuestions(request);

        assertEquals(1, response.getIsSuccess());
        assertEquals(2, response.getQuestionCount());
        assertEquals("Java 后端", response.getInterviewType());
        assertEquals(82, response.getResumeScore());
        assertFalse(response.getJdEnabled());

        InterviewPreparationCommitService.PreparationCommand command = capturedCommand();
        assertEquals("session-1", command.getSessionId());
        assertEquals(2, command.getQuestions().size());
        assertEquals("请说明订单中心如何防止超卖？", command.getQuestions().get(0));
        assertEquals(List.of("重点考察分布式事务"), command.getSuggestions());
        assertEquals(82, command.getResumeScore());
        assertEquals("Java 后端", command.getInterviewType());
        assertNull(command.getJdSource());
        assertEquals(1, command.getQuestionTargets().size());
    }

    @Test
    void shouldRejectJdTextAndFileAtTheSameTime() throws Exception {
        request.setJdText("岗位要求：熟悉 Redis");
        request.setJdFile(new MockMultipartFile(
                "jdFile", "jd.pdf", "application/pdf", "dummy".getBytes(StandardCharsets.UTF_8)));

        InterviewQuestionRespDTO response = newService().extractInterviewQuestions(request);

        assertEquals(0, response.getIsSuccess());
        assertTrue(response.getErrorMessage().contains("二选一"));
        verifyNoInteractions(lockService, preparationCommitService);
    }

    @Test
    void shouldRejectOversizedJdText() throws Exception {
        request.setJdText("岗".repeat(10001));

        InterviewQuestionRespDTO response = newService().extractInterviewQuestions(request);

        assertEquals(0, response.getIsSuccess());
        assertTrue(response.getErrorMessage().contains("10000"));
        verifyNoInteractions(preparationCommitService);
    }

    @Test
    void shouldPersistJdSourceWhenTextProvided() throws Exception {
        stubHappyPathDependencies();
        request.setJdText("  岗位要求：熟悉 MySQL 与 Redis  ");
        when(interviewAiInvoker.call(any(), eq(InterviewAiGuardStage.INTERVIEW_EXTRACTION), any()))
                .thenReturn(validModelResponse());

        InterviewQuestionRespDTO response = newService().extractInterviewQuestions(request);

        assertEquals(1, response.getIsSuccess());
        assertTrue(response.getJdEnabled());
        assertEquals("TEXT", response.getJdSource());

        InterviewPreparationCommitService.PreparationCommand command = capturedCommand();
        assertEquals("TEXT", command.getJdSource());
        assertEquals("岗位要求：熟悉 MySQL 与 Redis", command.getJdText());
        assertNull(command.getJdFileUrl());
    }

    @Test
    void shouldFailExplicitlyWhenJdFileHasNoText() throws Exception {
        when(fileStorageService.store(eq("resume"), any())).thenReturn("uploads/resume/202611/abc.pdf");
        when(fileStorageService.store(eq("jd"), any())).thenReturn("uploads/jd/202611/jd.png");
        when(materialExtractor.extract(any(), eq(MaterialType.RESUME))).thenReturn("候选人简历：三年 Java 后端经验");
        when(materialExtractor.extract(any(), eq(MaterialType.JOB_DESCRIPTION))).thenReturn("  ");
        RLock heavyLock = mock(RLock.class);
        when(lockService.acquire("session-1", InterviewAiGuardStage.INTERVIEW_EXTRACTION)).thenReturn(heavyLock);
        request.setJdFile(new MockMultipartFile(
                "jdFile", "jd.png", "image/png", "dummy".getBytes(StandardCharsets.UTF_8)));

        InterviewQuestionRespDTO response = newService().extractInterviewQuestions(request);

        assertEquals(0, response.getIsSuccess());
        assertTrue(response.getErrorMessage().contains("未识别到有效文字"));
        verify(preparationCommitService, never()).commitSucceeded(any());
        verify(interviewAiInvoker, never()).call(any(), any(), any());
    }

    private InterviewPreparationCommitService.PreparationCommand capturedCommand() {
        ArgumentCaptor<InterviewPreparationCommitService.PreparationCommand> captor =
                ArgumentCaptor.forClass(InterviewPreparationCommitService.PreparationCommand.class);
        verify(preparationCommitService).commitSucceeded(captor.capture());
        return captor.getValue();
    }

    private String validModelResponse() {
        return """
                {"questions":["请说明订单中心如何防止超卖？","Redis 预扣库存失败时如何回滚？"],
                 "questionPoints":{"1":["预扣与落库顺序"]},
                 "questionTargets":{"1":{"requirement":"高并发库存一致性","source":"JD","evidence":"岗位要求高并发"}},
                 "jdContext":{},
                 "suggestions":["重点考察分布式事务"],"type":"Java 后端","resumeScore":82,
                 "resumeSummary":"三年 Java 后端经验"}
                """;
    }

    private void stubHappyPathDependencies() throws Exception {
        when(fileStorageService.store(eq("resume"), any())).thenReturn("uploads/resume/202611/abc.pdf");
        when(materialExtractor.extract(any(), eq(MaterialType.RESUME))).thenReturn("候选人简历：三年 Java 后端经验");
        RLock heavyLock = mock(RLock.class);
        when(lockService.acquire("session-1", InterviewAiGuardStage.INTERVIEW_EXTRACTION)).thenReturn(heavyLock);
    }

    private InterviewQuestionReqDTO newRequest() {
        InterviewQuestionReqDTO request = new InterviewQuestionReqDTO();
        request.setSessionId("session-1");
        request.setUserName("tester");
        request.setResumePdf(new MockMultipartFile(
                "resumePdf",
                "resume.pdf",
                "application/pdf",
                "dummy".getBytes(StandardCharsets.UTF_8)
        ));
        return request;
    }
}
