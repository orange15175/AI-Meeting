package com.hewei.hzyjy.aimeeting.llm.document;

import cn.hutool.core.codec.Base64;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.llm.VisionChatClient;
import com.hewei.hzyjy.aimeeting.llm.VisionImage;
import com.hewei.hzyjy.aimeeting.llm.VisionProperties;
import com.hewei.hzyjy.aimeeting.llm.document.InterviewMaterialExtractor.MaterialType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewMaterialExtractorTest {

    private final PdfTextExtractor pdfTextExtractor = mock(PdfTextExtractor.class);
    private final VisionChatClient visionChatClient = mock(VisionChatClient.class);
    private final VisionProperties visionProperties = new VisionProperties();

    private InterviewMaterialExtractor newExtractor() {
        return new InterviewMaterialExtractor(pdfTextExtractor, visionChatClient, visionProperties);
    }

    private MockMultipartFile pdfFile(String name, byte[] content) {
        return new MockMultipartFile("resumePdf", name, "application/pdf", content);
    }

    private byte[] blankPdfOfPages(int pages) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    @Test
    void textPdfUsesLocalExtractorWithoutVision() throws Exception {
        MockMultipartFile pdf = pdfFile("resume.pdf", "dummy".getBytes(StandardCharsets.UTF_8));
        when(pdfTextExtractor.extract(any(byte[].class))).thenReturn("候选人：三年 Java 经验");

        String text = newExtractor().extract(pdf, MaterialType.RESUME);

        assertEquals("候选人：三年 Java 经验", text);
        verify(visionChatClient, never()).recognize(anyString(), any());
    }

    @Test
    void imageFileUsesVisionClient() throws Exception {
        visionProperties.setApiKey("test-key");
        when(visionChatClient.isConfigured()).thenReturn(true);
        when(visionChatClient.recognize(anyString(), any())).thenReturn("识别出的简历文字");
        MockMultipartFile image = new MockMultipartFile(
                "resumePdf", "resume.png", "image/png", "fake-image-bytes".getBytes(StandardCharsets.UTF_8));

        String text = newExtractor().extract(image, MaterialType.RESUME);

        assertEquals("识别出的简历文字", text);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<VisionImage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(visionChatClient).recognize(anyString(), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("image/png", captor.getValue().get(0).mimeType());
        assertEquals(Base64.encode("fake-image-bytes".getBytes(StandardCharsets.UTF_8)),
                captor.getValue().get(0).base64Data());
    }

    @Test
    void jobDescriptionUsesJdSpecificPrompt() throws Exception {
        visionProperties.setApiKey("test-key");
        when(visionChatClient.isConfigured()).thenReturn(true);
        when(visionChatClient.recognize(anyString(), any())).thenReturn("岗位：Java 后端工程师");
        MockMultipartFile image = new MockMultipartFile(
                "jdFile", "jd.png", "image/png", "fake".getBytes(StandardCharsets.UTF_8));

        String text = newExtractor().extract(image, MaterialType.JOB_DESCRIPTION);

        assertEquals("岗位：Java 后端工程师", text);
        verify(visionChatClient).recognize(contains("招聘岗位描述"), any());
    }

    @Test
    void imageWithoutVisionConfigThrowsActionableMessage() {
        when(visionChatClient.isConfigured()).thenReturn(false);
        MockMultipartFile image = new MockMultipartFile(
                "resumePdf", "resume.jpg", "image/jpeg", "fake".getBytes(StandardCharsets.UTF_8));

        ClientException ex = assertThrows(ClientException.class,
                () -> newExtractor().extract(image, MaterialType.RESUME));

        assertTrue(ex.getMessage().contains("VISION_API_KEY"));
    }

    @Test
    void scannedPdfWithoutVisionConfigThrowsActionableMessage() throws Exception {
        when(pdfTextExtractor.extract(any(byte[].class))).thenReturn("");
        when(visionChatClient.isConfigured()).thenReturn(false);
        MockMultipartFile pdf = pdfFile("scan.pdf", "dummy".getBytes(StandardCharsets.UTF_8));

        ClientException ex = assertThrows(ClientException.class,
                () -> newExtractor().extract(pdf, MaterialType.RESUME));

        assertTrue(ex.getMessage().contains("VISION_API_KEY"));
        verify(visionChatClient, never()).recognize(anyString(), any());
    }

    @Test
    void scannedPdfOverPageLimitIsRejectedInsteadOfTruncated() throws Exception {
        when(pdfTextExtractor.extract(any(byte[].class))).thenReturn("");
        when(visionChatClient.isConfigured()).thenReturn(true);
        visionProperties.setMaxImagePages(3);
        MockMultipartFile pdf = pdfFile("scan.pdf", blankPdfOfPages(5));

        ClientException ex = assertThrows(ClientException.class,
                () -> newExtractor().extract(pdf, MaterialType.JOB_DESCRIPTION));

        assertTrue(ex.getMessage().contains("超过单次识别的"));
        verify(visionChatClient, never()).recognize(anyString(), any());
    }

    @Test
    void unsupportedExtensionIsTreatedAsPdf() throws Exception {
        when(pdfTextExtractor.extract(any(byte[].class))).thenReturn("文本");
        MockMultipartFile file = new MockMultipartFile(
                "resumePdf", "resume.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        assertEquals("文本", newExtractor().extract(file, MaterialType.RESUME));
        verify(pdfTextExtractor).extract(any(byte[].class));
    }
}
