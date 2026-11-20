package com.hewei.hzyjy.aimeeting.interview.application;

import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewSessionRestoreRespDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.RadarChartDTO;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewMaterial;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewMaterialStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewSubmissionStore;
import com.hewei.hzyjy.aimeeting.interview.flow.report.InterviewResumePreviewService;
import com.hewei.hzyjy.aimeeting.interview.flow.session.InterviewSessionFacade;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewRadarService;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewRecordService;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewSessionService;
import com.hewei.hzyjy.aimeeting.interview.service.model.InterviewSessionStatus;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewJobContextReader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InterviewSessionFacadeTest {

    private InterviewSessionFacade newFacade(
            InterviewWorkflowService workflowService,
            InterviewSessionService sessionService,
            InterviewRecordService recordService,
            InterviewResumePreviewService previewService,
            InterviewRadarService radarService,
            InterviewMaterialStore materialStore,
            InterviewQuestionStore questionStore,
            InterviewSubmissionStore submissionStore) {
        return new InterviewSessionFacade(
                workflowService,
                sessionService,
                recordService,
                previewService,
                radarService,
                materialStore,
                questionStore,
                submissionStore,
                mock(InterviewJobContextReader.class)
        );
    }

    @Test
    void shouldRestoreSessionStateFromMaterial() {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewRadarService radarService = mock(InterviewRadarService.class);
        InterviewMaterialStore materialStore = mock(InterviewMaterialStore.class);
        InterviewQuestionStore questionStore = mock(InterviewQuestionStore.class);
        InterviewSubmissionStore submissionStore = mock(InterviewSubmissionStore.class);
        InterviewSessionFacade facade = newFacade(workflowService, sessionService, recordService,
                previewService, radarService, materialStore, questionStore, submissionStore);

        InterviewSession session = new InterviewSession();
        session.setSessionId("interview-1");
        session.setStatus(InterviewSessionStatus.READY.name());
        when(sessionService.requireOwnedSession("interview-1", 101L)).thenReturn(session);

        InterviewMaterial material = new InterviewMaterial();
        material.setResumeFileUrl("https://example.com/resume.pdf");
        material.setInterviewType("backend");
        material.setResumeScore(88);
        material.setSuggestionsJson("{\"1\":\"Keep answers focused\"}");
        when(materialStore.findBySession("interview-1")).thenReturn(material);

        InterviewSessionRestoreRespDTO response = facade.restoreInterviewSession("interview-1", 101L);

        assertEquals("interview-1", response.getSessionId());
        assertEquals(InterviewSessionStatus.READY.name(), response.getStatus());
        assertTrue(response.getCanResume());
        assertEquals("https://example.com/resume.pdf", response.getResumeFileUrl());
        assertEquals("backend", response.getInterviewType());
        assertEquals(88, response.getResumeScore());
        assertEquals(Map.of("1", "Keep answers focused"), response.getSuggestions());
        verifyNoInteractions(recordService);
    }

    @Test
    void shouldBuildRadarFromMaterialAndSubmissionsWithoutWritingRecord() {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewRadarService radarService = mock(InterviewRadarService.class);
        InterviewMaterialStore materialStore = mock(InterviewMaterialStore.class);
        InterviewQuestionStore questionStore = mock(InterviewQuestionStore.class);
        InterviewSubmissionStore submissionStore = mock(InterviewSubmissionStore.class);
        InterviewSessionFacade facade = newFacade(workflowService, sessionService, recordService,
                previewService, radarService, materialStore, questionStore, submissionStore);

        InterviewSession session = new InterviewSession();
        session.setSessionId("interview-2");
        session.setStatus(InterviewSessionStatus.IN_PROGRESS.name());
        when(sessionService.requireOwnedSession("interview-2", 202L)).thenReturn(session);

        InterviewMaterial material = new InterviewMaterial();
        material.setResumeScore(88);
        when(materialStore.findBySession("interview-2")).thenReturn(material);
        when(submissionStore.averageMainScore("interview-2")).thenReturn(85);

        RadarChartDTO radarChart = new RadarChartDTO();
        radarChart.setInterviewPerformance(85);
        when(radarService.buildRadarChart(88, 85)).thenReturn(radarChart);

        RadarChartDTO response = facade.getRadarChartData("interview-2", 202L);

        assertSame(radarChart, response);
        verify(radarService).buildRadarChart(88, 85);
        verifyNoInteractions(recordService);
    }

    @Test
    void shouldMarkFinishedSessionAsNotResumable() {
        InterviewWorkflowService workflowService = mock(InterviewWorkflowService.class);
        InterviewSessionService sessionService = mock(InterviewSessionService.class);
        InterviewRecordService recordService = mock(InterviewRecordService.class);
        InterviewResumePreviewService previewService = mock(InterviewResumePreviewService.class);
        InterviewRadarService radarService = mock(InterviewRadarService.class);
        InterviewMaterialStore materialStore = mock(InterviewMaterialStore.class);
        InterviewQuestionStore questionStore = mock(InterviewQuestionStore.class);
        InterviewSubmissionStore submissionStore = mock(InterviewSubmissionStore.class);
        InterviewSessionFacade facade = newFacade(workflowService, sessionService, recordService,
                previewService, radarService, materialStore, questionStore, submissionStore);

        InterviewSession session = new InterviewSession();
        session.setSessionId("interview-3");
        session.setStatus(InterviewSessionStatus.FINISHED.name());
        when(sessionService.requireOwnedSession("interview-3", 303L)).thenReturn(session);
        when(materialStore.findBySession("interview-3")).thenReturn(null);

        InterviewSessionRestoreRespDTO response = facade.restoreInterviewSession("interview-3", 303L);

        assertFalse(response.getCanResume());
        assertEquals(InterviewSessionStatus.FINISHED.name(), response.getStatus());
        verifyNoInteractions(recordService);
    }
}
