package com.hewei.hzyjy.aimeeting.interview.flow.report;

import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ServiceException;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewMaterial;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewSession;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewMaterialStore;
import com.hewei.hzyjy.aimeeting.interview.service.InterviewSessionService;
import com.hewei.hzyjy.aimeeting.llm.document.LocalFileStorageService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 简历原件预览。
 *
 * <p>P2 起简历存在本地（相对 base-dir 的路径），因此这里直接读文件；
 * 历史数据里若还是 http(s) 绝对地址，保留远程下载兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewResumePreviewService {

    private final LocalFileStorageService localFileStorageService;
    private final InterviewMaterialStore materialStore;
    private final InterviewSessionService interviewSessionService;

    public ResumePreviewResource loadResumePreview(String sessionId) {
        InterviewMaterial material = materialStore.findBySession(sessionId);
        String resumeFileUrl = material == null ? null : material.getResumeFileUrl();
        if (StrUtil.isBlank(resumeFileUrl)) {
            InterviewSession session = interviewSessionService.getBySessionId(sessionId);
            resumeFileUrl = session == null ? null : session.getResumeFileUrl();
        }
        if (StrUtil.isBlank(resumeFileUrl)) {
            throw new ClientException("Resume preview source is missing");
        }

        String source = resumeFileUrl.trim();
        byte[] content = source.startsWith("http")
                ? new LegacyRemoteLoader().load(source)
                : readLocal(source, sessionId);
        if (content == null || content.length == 0) {
            throw new ServiceException("Failed to load resume preview: empty content");
        }
        return new ResumePreviewResource(content, resolveContentType(content), resolveFileName(source, sessionId));
    }

    private byte[] readLocal(String relativePath, String sessionId) {
        try {
            Path target = localFileStorageService.resolve(relativePath);
            if (!Files.exists(target)) {
                throw new ClientException("Resume file does not exist");
            }
            return Files.readAllBytes(target);
        } catch (ClientException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Failed to read resume file, sessionId={}, path={}, error={}",
                    sessionId, relativePath, ex.getMessage());
            throw new ServiceException("Failed to load resume preview: " + ex.getMessage());
        }
    }

    private String resolveFileName(String sourceUrl, String sessionId) {
        String segment = StrUtil.subAfter(sourceUrl, "/", true);
        if (StrUtil.isNotBlank(segment)) {
            return segment.endsWith(".pdf") ? segment : segment + ".pdf";
        }
        return "resume-" + sessionId + ".pdf";
    }

    private String resolveContentType(byte[] content) {
        return isPdf(content) ? "application/pdf" : "application/octet-stream";
    }

    private boolean isPdf(byte[] content) {
        return content != null
                && content.length >= 5
                && content[0] == 0x25
                && content[1] == 0x50
                && content[2] == 0x44
                && content[3] == 0x46
                && content[4] == 0x2D;
    }

    /**
     * 仅用于兼容历史数据中的远程地址。
     */
    private static final class LegacyRemoteLoader {
        private static final okhttp3.OkHttpClient CLIENT = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        byte[] load(String url) {
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().build();
            try (okhttp3.Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new ServiceException("Failed to download resume preview, HTTP status: " + response.code());
                }
                return response.body().bytes();
            } catch (ServiceException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ServiceException("Failed to download resume preview: " + ex.getMessage());
            }
        }
    }

    @Getter
    public static final class ResumePreviewResource {
        private final byte[] content;
        private final String contentType;
        private final String fileName;

        public ResumePreviewResource(byte[] content, String contentType, String fileName) {
            this.content = content;
            this.contentType = contentType;
            this.fileName = fileName;
        }
    }
}
