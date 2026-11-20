package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.InterviewAiGuardException;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.aimeeting.interview.application.guard.lock.InterviewAiSessionLockService;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewResponseParser;
import com.hewei.hzyjy.aimeeting.llm.LlmRequest;
import com.hewei.hzyjy.aimeeting.llm.document.InterviewMaterialExtractor;
import com.hewei.hzyjy.aimeeting.llm.document.InterviewMaterialExtractor.MaterialType;
import com.hewei.hzyjy.aimeeting.llm.document.LocalFileStorageService;
import com.hewei.hzyjy.aimeeting.llm.prompt.PromptLibrary;
import com.hewei.hzyjy.aimeeting.llm.prompt.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 材料出题：解析简历（可选岗位 JD）、套版本化提示词、直连模型产出结构化题目。
 *
 * <p>产物落 MySQL：材料表保存简历与岗位文本及其结构化上下文，题目表按题号逐行保存，
 * 考察要点与岗位映射单独存列且不下发前端。重新出题会作废本轮问答（见 PreparationCommitService）。</p>
 *
 * <p>岗位 JD 是可选项：不提供时保持原有"仅简历出题"语义；提供时文字与文件二选一，
 * 文件解析失败必须明确报错，不能静默退回无 JD 模式——否则用户以为考的是岗位题，
 * 实际拿到的是通用题。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewQuestionExtractionService {

    private static final String JD_SOURCE_TEXT = "TEXT";
    private static final String JD_SOURCE_FILE = "FILE";
    private static final int JD_TEXT_MAX_CHARS = 10000;
    private static final long JD_FILE_MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> JD_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg", "webp", "bmp");
    private static final String NO_JD_PLACEHOLDER = "（未提供）";

    private final InterviewMaterialExtractor materialExtractor;
    private final LocalFileStorageService localFileStorageService;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewAiSessionLockService interviewAiSessionLockService;
    private final InterviewPreparationCommitService preparationCommitService;
    private final InterviewResponseParser interviewResponseParser;
    private final PromptLibrary promptLibrary;

    @Value("${ai-meeting.interview.extraction.question-count:10}")
    private int questionCount;

    @Value("${ai-meeting.llm.model:unknown}")
    private String modelName;

    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        InterviewQuestionRespDTO response = new InterviewQuestionRespDTO();
        response.setSessionId(reqDTO.getSessionId());
        response.setUserName(reqDTO.getUserName());
        response.setIsSuccess(0);

        String jdError = normalizeAndValidateJd(reqDTO);
        if (jdError != null) {
            response.setErrorMessage(jdError);
            return response;
        }

        RLock heavyLock = null;
        long startTime = System.currentTimeMillis();
        String resumeFilePath = null;
        String resumeText = null;
        JdMaterial jd = JdMaterial.absent();
        try {
            // 同一 session 的提取属于重操作，先拿会话级重锁，避免并发上传造成重复消耗和状态覆盖。
            heavyLock = interviewAiSessionLockService.acquire(
                    reqDTO.getSessionId(), InterviewAiGuardStage.INTERVIEW_EXTRACTION);
            if (heavyLock == null) {
                response.setErrorMessage("AI_OVERLOADED: extraction is processing, please retry");
                return response;
            }

            resumeFilePath = storeResume(reqDTO, response);
            if (resumeFilePath == null) {
                return response;
            }
            reqDTO.setResumeFileUrl(resumeFilePath);
            response.setResumeFileUrl(resumeFilePath);

            resumeText = extractResumeText(reqDTO, response);
            if (resumeText == null) {
                return response;
            }

            jd = resolveJdMaterial(reqDTO, response);
            if (jd.failed) {
                return response;
            }

            PromptTemplate prompt = promptLibrary.get(PromptLibrary.INTERVIEW_QUESTION_EXTRACTION);
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("questionCount", Math.max(1, questionCount));
            variables.put("resumeText", resumeText);
            variables.put("jobDescription", jd.present ? jd.text : NO_JD_PLACEHOLDER);
            LlmRequest request = LlmRequest.of(
                    InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                    prompt.renderSystem(),
                    prompt.renderUser(variables)
            );

            String fullContent = interviewAiInvoker.call(
                    request,
                    InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                    interviewAiInvoker.buildCallKey(
                            InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                            reqDTO.getSessionId(),
                            computeExtractionHash(reqDTO)));

            long responseTime = System.currentTimeMillis() - startTime;
            response.setResponseTime((int) responseTime);

            if (!populateStructuredResponse(reqDTO, response, fullContent, prompt, resumeText, jd, responseTime)) {
                return response;
            }

            response.setIsSuccess(1);
            log.info("Interview question extraction completed, sessionId={}, prompt={}, jdEnabled={}, costMs={}",
                    reqDTO.getSessionId(), prompt.versionLabel(), jd.present, responseTime);
            return response;
        } catch (InterviewAiGuardException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Interview question extraction guarded failure, sessionId={}, code={}, message={}",
                    reqDTO.getSessionId(), e.getErrorCode(), e.getMessage());
            saveFailure(reqDTO, resumeFilePath, resumeText, e.getMessage(), responseTime);
            response.setErrorMessage(e.getMessage());
            response.setIsSuccess(0);
            return response;
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Interview question extraction failed, sessionId={}", reqDTO.getSessionId(), e);
            saveFailure(reqDTO, resumeFilePath, resumeText, e.getMessage(), responseTime);
            response.setErrorMessage("interview question extraction failed: " + e.getMessage());
            response.setIsSuccess(0);
            return response;
        } finally {
            interviewAiSessionLockService.release(heavyLock);
        }
    }

    /**
     * 岗位入参的独立校验：文字与文件互斥、文字长度上限、文件大小与扩展名。
     *
     * <p>必须由后端独立判断，不能依赖前端 disabled 或 accept 属性。</p>
     *
     * @return 校验失败时返回可直接展示的错误信息，通过时返回 null
     */
    private String normalizeAndValidateJd(InterviewQuestionReqDTO reqDTO) {
        String text = StrUtil.trimToNull(reqDTO.getJdText());
        MultipartFile file = reqDTO.getJdFile();
        boolean hasFile = file != null && !file.isEmpty();

        if (text != null && hasFile) {
            return "岗位描述文字与文件只能二选一，请移除其中一个后重试";
        }
        if (text != null && text.length() > JD_TEXT_MAX_CHARS) {
            return "岗位描述文字不能超过 " + JD_TEXT_MAX_CHARS + " 字，请精简后重试";
        }
        if (hasFile) {
            if (file.getSize() > JD_FILE_MAX_BYTES) {
                return "岗位描述文件不能超过 10MB，请压缩或改用粘贴文字";
            }
            String extension = extensionOf(file.getOriginalFilename());
            if (!JD_EXTENSIONS.contains(extension)) {
                return "岗位描述仅支持 PDF、PNG、JPG、WebP、BMP 格式";
            }
        }
        reqDTO.setJdText(text);
        return null;
    }

    /**
     * 解析岗位材料。解析失败返回 {@code failed=true}，由调用方终止本次出题。
     */
    private JdMaterial resolveJdMaterial(InterviewQuestionReqDTO reqDTO, InterviewQuestionRespDTO response) {
        String text = reqDTO.getJdText();
        if (StrUtil.isNotBlank(text)) {
            return JdMaterial.ofText(text);
        }
        MultipartFile file = reqDTO.getJdFile();
        if (file == null || file.isEmpty()) {
            return JdMaterial.absent();
        }

        String fileName = StrUtil.blankToDefault(file.getOriginalFilename(), "job-description");
        String fileUrl;
        try {
            fileUrl = localFileStorageService.store("jd", file);
        } catch (Exception e) {
            log.error("JD file upload failed, sessionId={}", reqDTO.getSessionId(), e);
            response.setErrorMessage("岗位描述文件保存失败，请重试");
            return JdMaterial.failed();
        }

        String parsed;
        try {
            parsed = materialExtractor.extract(file, MaterialType.JOB_DESCRIPTION);
        } catch (ClientException e) {
            // 视觉未配置、超页数等可操作错误：原样透传，让用户知道该怎么改。
            log.warn("JD extraction rejected, sessionId={}, message={}", reqDTO.getSessionId(), e.getMessage());
            response.setErrorMessage(e.getMessage());
            return JdMaterial.failed();
        } catch (Exception e) {
            log.error("JD extraction failed, sessionId={}", reqDTO.getSessionId(), e);
            response.setErrorMessage("岗位描述解析失败：" + e.getMessage());
            return JdMaterial.failed();
        }

        if (StrUtil.isBlank(parsed)) {
            response.setErrorMessage("岗位描述未识别到有效文字，请更换文件或改用粘贴文字");
            return JdMaterial.failed();
        }
        return JdMaterial.ofFile(parsed, fileUrl, fileName);
    }

    private String storeResume(InterviewQuestionReqDTO reqDTO, InterviewQuestionRespDTO response) {
        if (reqDTO.getResumePdf() == null || reqDTO.getResumePdf().isEmpty()) {
            response.setErrorMessage("resume file does not exist");
            return null;
        }
        try {
            return localFileStorageService.store("resume", reqDTO.getResumePdf());
        } catch (Exception e) {
            log.error("Resume upload failed, sessionId={}", reqDTO.getSessionId(), e);
            response.setErrorMessage("failed to upload resume file");
            return null;
        }
    }

    private String extractResumeText(InterviewQuestionReqDTO reqDTO, InterviewQuestionRespDTO response) {
        try {
            String text = materialExtractor.extract(reqDTO.getResumePdf(), MaterialType.RESUME);
            if (StrUtil.isBlank(text)) {
                response.setErrorMessage("resume contains no extractable text");
                log.warn("Resume has no extractable text, sessionId={}", reqDTO.getSessionId());
                return null;
            }
            return text;
        } catch (ClientException e) {
            // 扫描件/图片需要视觉模型但密钥未配置：把可操作的提示直接透传给前端。
            log.warn("Resume extraction needs vision model, sessionId={}, message={}",
                    reqDTO.getSessionId(), e.getMessage());
            response.setErrorMessage(e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to extract resume text, sessionId={}", reqDTO.getSessionId(), e);
            response.setErrorMessage("failed to parse resume: " + e.getMessage());
            return null;
        }
    }

    private boolean populateStructuredResponse(
            InterviewQuestionReqDTO reqDTO,
            InterviewQuestionRespDTO response,
            String fullContent,
            PromptTemplate prompt,
            String resumeText,
            JdMaterial jd,
            long responseTime) {
        Map<String, Object> responseMap = interviewResponseParser.extractStructuredResult(
                fullContent, "questions", "suggestions", "type", "resumeScore");
        if (responseMap == null || responseMap.isEmpty()) {
            response.setErrorMessage("interview question response parse failed");
            log.warn("Interview question response parse failed, sessionId={}, prompt={}",
                    reqDTO.getSessionId(), prompt.versionLabel());
            saveFailure(reqDTO, reqDTO.getResumeFileUrl(), resumeText,
                    "interview question response parse failed", responseTime);
            return false;
        }

        List<String> questions = interviewResponseParser.asStringList(responseMap.get("questions"));
        if (questions.isEmpty()) {
            response.setErrorMessage("model returned empty interview questions");
            log.warn("Interview question extraction returned no questions, sessionId={}", reqDTO.getSessionId());
            saveFailure(reqDTO, reqDTO.getResumeFileUrl(), resumeText,
                    "model returned empty interview questions", responseTime);
            return false;
        }

        List<String> suggestions = interviewResponseParser.asStringList(responseMap.get("suggestions"));
        String interviewType = interviewResponseParser.asString(responseMap.get("type"));
        Integer resumeScore = interviewResponseParser.parseScoreFromResponse(responseMap, "resumeScore");
        Map<String, List<String>> questionPoints = parseQuestionPoints(responseMap.get("questionPoints"));
        Map<String, Object> questionTargets = parseQuestionTargets(responseMap.get("questionTargets"));
        Map<String, Object> jdContext = jd.present ? parseJdContext(responseMap.get("jdContext")) : Map.of();

        try {
            preparationCommitService.commitSucceeded(
                    InterviewPreparationCommitService.PreparationCommand.builder()
                            .sessionId(reqDTO.getSessionId())
                            .resumeFileUrl(reqDTO.getResumeFileUrl())
                            .resumeText(resumeText)
                            .interviewType(interviewType)
                            .resumeScore(resumeScore)
                            .questions(questions)
                            .suggestions(suggestions)
                            .questionPoints(questionPoints)
                            .questionTargets(questionTargets)
                            .jdSource(jd.present ? jd.source : null)
                            .jdFileUrl(jd.present ? jd.fileUrl : null)
                            .jdFileName(jd.present ? jd.fileName : null)
                            .jdText(jd.present ? jd.text : null)
                            .jdContext(jdContext.isEmpty() ? null : jdContext)
                            .resumeContext(buildResumeContext(responseMap))
                            .expectedSessionVersion(reqDTO.getExpectedSessionVersion())
                            .modelName(modelName)
                            .promptVersion(prompt.versionLabel())
                            .rawResponse(fullContent)
                            .responseTime((int) responseTime)
                            .build());
        } catch (Exception ex) {
            log.error("Failed to persist interview preparation, sessionId={}", reqDTO.getSessionId(), ex);
            response.setErrorMessage("failed to save interview questions: " + ex.getMessage());
            saveFailure(reqDTO, reqDTO.getResumeFileUrl(), resumeText,
                    "failed to save interview questions", responseTime);
            return false;
        }

        Map<String, String> questionMap = new LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            questionMap.put(String.valueOf(i + 1), questions.get(i));
        }
        response.setQuestions(questionMap);
        response.setQuestionCount(questions.size());
        if (!suggestions.isEmpty()) {
            Map<String, String> suggestionMap = new LinkedHashMap<>();
            for (int i = 0; i < suggestions.size(); i++) {
                suggestionMap.put(String.valueOf(i + 1), suggestions.get(i));
            }
            response.setSuggestions(suggestionMap);
            response.setSuggestionCount(suggestions.size());
        }
        if (StrUtil.isNotBlank(interviewType)) {
            response.setInterviewType(interviewType);
        }
        if (resumeScore != null) {
            response.setResumeScore(resumeScore);
        }
        response.setJdEnabled(jd.present);
        if (jd.present) {
            response.setJdSource(jd.source);
            response.setJdFileName(jd.fileName);
            response.setJdSummary(buildJdSummary(jdContext, jd.text));
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parseQuestionPoints(Object raw) {
        Map<String, List<String>> points = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return points;
        }
        map.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            List<String> items = interviewResponseParser.asStringList(value);
            if (items != null && !items.isEmpty()) {
                points.put(String.valueOf(key).trim(), items);
            }
        });
        return points;
    }

    /**
     * 题目与岗位要求的映射：只接受对象数组/对象值，无法核验的项直接丢弃，
     * 避免报告把模型编造的关联当成考核证据。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseQuestionTargets(Object raw) {
        Map<String, Object> targets = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return targets;
        }
        map.forEach((key, value) -> {
            if (key == null || !(value instanceof Map<?, ?> target)) {
                return;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            target.forEach((k, v) -> {
                if (k != null && v != null) {
                    String text = StrUtil.trim(String.valueOf(v));
                    if (StrUtil.isNotBlank(text) && !"null".equalsIgnoreCase(text)) {
                        normalized.put(String.valueOf(k), StrUtil.maxLength(text, 300));
                    }
                }
            });
            if (!normalized.isEmpty()) {
                targets.put(String.valueOf(key).trim(), normalized);
            }
        });
        return targets;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJdContext(Object raw) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return context;
        }
        map.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            if (value instanceof List<?> list && list.isEmpty()) {
                return;
            }
            if (value instanceof String text && StrUtil.isBlank(text)) {
                return;
            }
            context.put(String.valueOf(key), value);
        });
        return context;
    }

    private String buildJdSummary(Map<String, Object> jdContext, String jdText) {
        Object title = jdContext.get("jobTitle");
        if (title != null && StrUtil.isNotBlank(String.valueOf(title))) {
            return StrUtil.maxLength(String.valueOf(title).trim(), 120);
        }
        String compact = StrUtil.blankToDefault(jdText, "").replaceAll("\\s+", " ").trim();
        return StrUtil.maxLength(compact, 120);
    }

    private void saveFailure(InterviewQuestionReqDTO reqDTO, String resumeFilePath, String resumeText,
                             String message, long responseTime) {
        try {
            preparationCommitService.commitFailed(
                    reqDTO.getSessionId(), resumeFilePath, resumeText, message, (int) responseTime);
        } catch (Exception ex) {
            log.warn("Failed to save extraction failure record, sessionId={}, error={}",
                    reqDTO.getSessionId(), ex.getMessage());
        }
    }

    private Map<String, Object> buildResumeContext(Map<String, Object> responseMap) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (responseMap == null || responseMap.isEmpty()) {
            return context;
        }
        for (Map.Entry<String, Object> entry : responseMap.entrySet()) {
            String key = entry.getKey();
            if ("questions".equals(key) || "suggestions".equals(key) || "questionPoints".equals(key)
                    || "questionTargets".equals(key) || "jdContext".equals(key)) {
                continue;
            }
            if (entry.getValue() != null) {
                context.put(key, entry.getValue());
            }
        }
        return context;
    }

    /**
     * 出题输入摘要（简历字节 + 岗位文本/文件字节），只用于日志与指标区分重复提取。
     *
     * <p>调用键不再承担"结果复用"职责——分布式 single-flight 已在 P3 移除，
     * 现在重复提交由 MySQL 唯一键与 attemptToken 拦截。</p>
     */
    private String computeExtractionHash(InterviewQuestionReqDTO reqDTO) {
        try {
            StringBuilder raw = new StringBuilder();
            if (reqDTO.getResumePdf() != null && !reqDTO.getResumePdf().isEmpty()) {
                raw.append(DigestUtil.sha256Hex(reqDTO.getResumePdf().getBytes()));
            }
            if (StrUtil.isNotBlank(reqDTO.getJdText())) {
                raw.append('|').append(DigestUtil.sha256Hex(reqDTO.getJdText().trim()));
            } else if (reqDTO.getJdFile() != null && !reqDTO.getJdFile().isEmpty()) {
                raw.append('|').append(DigestUtil.sha256Hex(reqDTO.getJdFile().getBytes()));
            }
            return raw.isEmpty() ? null : DigestUtil.sha256Hex(raw.toString());
        } catch (Exception e) {
            log.warn("Failed to compute extraction hash, sessionId={}, error={}",
                    reqDTO.getSessionId(), e.getMessage());
            return null;
        }
    }

    private String extensionOf(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return "";
        }
        return StrUtil.subAfter(fileName, ".", true).toLowerCase(Locale.ROOT);
    }

    /**
     * 岗位材料：{@code present=false} 表示本场未提供；{@code failed=true} 表示校验或解析失败。
     */
    private static final class JdMaterial {

        private final boolean present;
        private final boolean failed;
        private final String source;
        private final String text;
        private final String fileUrl;
        private final String fileName;

        private JdMaterial(boolean present, boolean failed, String source, String text, String fileUrl,
                           String fileName) {
            this.present = present;
            this.failed = failed;
            this.source = source;
            this.text = text;
            this.fileUrl = fileUrl;
            this.fileName = fileName;
        }

        static JdMaterial absent() {
            return new JdMaterial(false, false, null, null, null, null);
        }

        static JdMaterial failed() {
            return new JdMaterial(false, true, null, null, null, null);
        }

        static JdMaterial ofText(String text) {
            return new JdMaterial(true, false, JD_SOURCE_TEXT, text, null, null);
        }

        static JdMaterial ofFile(String text, String fileUrl, String fileName) {
            return new JdMaterial(true, false, JD_SOURCE_FILE, text, fileUrl, fileName);
        }
    }
}
