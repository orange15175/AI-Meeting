package com.hewei.hzyjy.aimeeting.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hewei.hzyjy.aimeeting.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.aimeeting.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.InterviewAiGuardException;
import com.hewei.hzyjy.aimeeting.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.aimeeting.interview.application.guard.lock.InterviewAiSessionLockService;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewQuestionStore;
import com.hewei.hzyjy.aimeeting.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.aimeeting.llm.LlmRequest;
import com.hewei.hzyjy.aimeeting.llm.document.InterviewMaterialExtractor;
import com.hewei.hzyjy.aimeeting.llm.document.InterviewMaterialExtractor.MaterialType;
import com.hewei.hzyjy.aimeeting.llm.document.LocalFileStorageService;
import com.hewei.hzyjy.aimeeting.llm.prompt.PromptLibrary;
import com.hewei.hzyjy.aimeeting.llm.prompt.PromptTemplate;
import io.micrometer.core.instrument.MeterRegistry;
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

    /**
     * 题目之外的固定输出开销（建议、画像、岗位上下文）预留的 token 数。
     */
    private static final int OUTPUT_META_RESERVE_TOKENS = 2000;

    /**
     * 修复提示词里回灌原始结果的最大长度，避免重复消耗输入预算。
     */
    private static final int REPAIR_PREVIOUS_RESULT_MAX_CHARS = 20000;

    private final InterviewMaterialExtractor materialExtractor;
    private final LocalFileStorageService localFileStorageService;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewAiSessionLockService interviewAiSessionLockService;
    private final InterviewPreparationCommitService preparationCommitService;
    private final InterviewQuestionBatchValidator questionBatchValidator;
    private final InterviewQuestionBatchConverter questionBatchConverter;
    private final MaterialRoleDetector materialRoleDetector;
    private final PromptLibrary promptLibrary;
    private final MeterRegistry meterRegistry;

    /**
     * 未指定题量时的默认值；请求里带了 questionCount 时以请求为准。
     */
    @Value("${ai-meeting.interview.extraction.question-count:10}")
    private int questionCount;

    /**
     * 允许的题量区间：下限保证"一场面试"的完整性，上限受单次输出 token 与锁租期约束。
     */
    @Value("${ai-meeting.interview.extraction.question-count-min:5}")
    private int minQuestionCount;

    @Value("${ai-meeting.interview.extraction.question-count-max:20}")
    private int maxQuestionCount;

    /**
     * 单题输出预算：题量上去以后固定 6000 tokens 会截断 JSON，按题量线性放大再由上下限夹住。
     */
    @Value("${ai-meeting.interview.extraction.max-tokens-per-question:400}")
    private int maxTokensPerQuestion;

    @Value("${ai-meeting.interview.extraction.max-tokens-floor:4096}")
    private int maxTokensFloor;

    @Value("${ai-meeting.interview.extraction.max-tokens-ceiling:8192}")
    private int maxTokensCeiling;

    @Value("${ai-meeting.llm.model:unknown}")
    private String modelName;

    /**
     * 【面试主线 2/6】准备流水线：校验 JD → 会话重操作锁 → 存储/解析双材料 → 联合出题 → 事务落库。
     * 耗时模型调用在提交事务之外，成功落库后才向调用方报告出题成功。
     */
    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        // 第 1 段：先建立失败响应。只有材料解析、模型结构校验和事务提交全部通过，才把 isSuccess 改为 1。
        InterviewQuestionRespDTO response = new InterviewQuestionRespDTO();
        response.setSessionId(reqDTO.getSessionId());
        response.setUserName(reqDTO.getUserName());
        response.setIsSuccess(0);

        // 【关键节点：选项校验】题量与难度先于任何 IO 校验：非法入参直接拒绝，不占锁、不落文件、不调用模型。
        InterviewPreparationOptions.ParseResult options = InterviewPreparationOptions.parse(
                reqDTO.getQuestionCount(), reqDTO.getDifficulty(),
                questionCount, minQuestionCount, maxQuestionCount);
        if (!options.ok()) {
            response.setErrorMessage(options.errorMessage());
            return response;
        }
        int targetCount = options.options().questionCount();

        // 【关键节点：材料入口】JD 文字与文件二选一；失败应明确返回，不能静默变成无 JD 出题。
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
            // 【关键节点：出题互斥】同一 session 的提取属于重操作，先拿会话级重锁，避免并发上传造成重复消耗和状态覆盖。
            heavyLock = interviewAiSessionLockService.acquire(
                    reqDTO.getSessionId(), InterviewAiGuardStage.INTERVIEW_EXTRACTION);
            if (heavyLock == null) {
                response.setErrorMessage("AI_OVERLOADED: extraction is processing, please retry");
                return response;
            }

            // 第 2 段：先保存简历原件，再提取文字。相对路径用于后续预览和材料记录；文件保存成功不代表出题成功。
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
            // 【关键节点：材料角色自检】把岗位描述误放进简历位置时，模型会照着 JD"编"候选人经历。
            if (!acceptResumeText(resumeText, reqDTO.getSessionId(), response)) {
                return response;
            }

            // 【关键节点：来源隔离】简历先解析，JD 单独解析；分别传入提示词，不能把岗位要求当成候选人经历。
            jd = resolveJdMaterial(reqDTO, response);
            if (jd.failed) {
                return response;
            }
            // 【关键节点：材料角色自检】JD 槽位只校验过长度与后缀，不看内容含义；误放简历会静默污染题目与岗位映射。
            if (jd.present && !acceptJdText(jd.text, resumeText, reqDTO.getSessionId(), response)) {
                // 原件在校验之前就已落盘；被拦下的岗位文件不会被任何流程引用，留在 uploads/jd 只会越积越多。
                // 简历原件不在此列：失败记录表会引用它，留着便于排查。
                localFileStorageService.deleteQuietly(jd.fileUrl);
                return response;
            }

            // 第 3 段：加载版本化提示词，把题量、难度倾向、简历经历、可选岗位要求分别填入用户消息；无 JD 使用明确占位符。
            int tokenBudget = outputTokenBudget(targetCount);
            PromptTemplate prompt = promptLibrary.get(PromptLibrary.INTERVIEW_QUESTION_EXTRACTION);
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("questionCount", targetCount);
            variables.put("difficultyPreference", options.options().difficulty().promptText());
            variables.put("resumeText", resumeText);
            variables.put("jobDescription", jd.present ? jd.text : NO_JD_PLACEHOLDER);
            LlmRequest request = LlmRequest.of(
                    InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                    prompt.renderSystem(),
                    prompt.renderUser(variables)
            ).withMaxTokens(tokenBudget);

            // 【关键节点：模型边界】统一调用治理入口；返回文本还需结构校验与数据库提交，并非业务成功终态。
            String callKey = interviewAiInvoker.buildCallKey(
                    InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                    reqDTO.getSessionId(),
                    computeExtractionHash(reqDTO));
            String fullContent = interviewAiInvoker.call(
                    request,
                    InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                    callKey);

            // 【关键节点：模型输出验收】严格校验 → （必要时）一次修复 → 转换 → 提交。
            // 任一步失败都提前返回，不能仅因为模型返回了文字就宣布出题成功。
            if (!populateStructuredResponse(
                    reqDTO, response, fullContent, prompt, resumeText, jd,
                    startTime, callKey, targetCount, options.options().difficulty())) {
                return response;
            }

            // 第 4 段：数据库提交已经成功，再设置业务成功标记；Facade 收到该标记后会将会话标为 READY。
            response.setIsSuccess(1);
            log.info("Interview question extraction completed, sessionId={}, prompt={}, jdEnabled={}, costMs={}",
                    reqDTO.getSessionId(), prompt.versionLabel(), jd.present, response.getResponseTime());
            return response;
        // 【关键节点：模型治理失败】超时、过载或不可用统一走这里：尝试记录诊断，保留失败标记并返回可识别错误。
        } catch (InterviewAiGuardException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Interview question extraction guarded failure, sessionId={}, code={}, message={}",
                    reqDTO.getSessionId(), e.getErrorCode(), e.getMessage());
            saveFailure(reqDTO, resumeFilePath, resumeText, e.getMessage(), responseTime);
            response.setErrorMessage(e.getMessage());
            response.setIsSuccess(0);
            return response;
        // 未归入模型治理的异常（例如后续解析或存储异常）在这里兜底；仍然返回失败，不进入面试房间。
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Interview question extraction failed, sessionId={}", reqDTO.getSessionId(), e);
            saveFailure(reqDTO, resumeFilePath, resumeText, e.getMessage(), responseTime);
            response.setErrorMessage("interview question extraction failed: " + e.getMessage());
            response.setIsSuccess(0);
            return response;
        } finally {
            // 【关键节点：释放互斥锁】正常返回、提前 return 和抛异常都会经过 finally；是否持锁由释放服务判断。
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
        // 清理首尾空白：纯空白文本视为未提供；文件只有非空才算提供。随后检查互斥、长度、大小和扩展名。
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
        // 岗位解析分三路：已有文字直接使用；文字和文件都没有则走无 JD；提供文件则先保存原件再提取文字。
        String text = reqDTO.getJdText();
        if (StrUtil.isNotBlank(text)) {
            return JdMaterial.ofText(text);
        }
        MultipartFile file = reqDTO.getJdFile();
        if (file == null || file.isEmpty()) {
            return JdMaterial.absent();
        }

        // 文件名只用于展示，实际文件路径由存储服务生成；解析时明确标记为 JOB_DESCRIPTION，避免使用简历识别提示词。
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

    // 简历原件存储：缺文件或保存失败时把提示写入 response，并返回 null 让主流程立即停止。
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

    // 简历文本提取：由统一材料解析器区分文本 PDF、扫描件和图片；空文本不能继续送模型出题。
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

    /**
     * 简历槽位的内容自检：岗位描述被放进简历位置时，模型会把"岗位要求"当成候选人经历，
     * 出题与评分都失去真实依据，但全程不会报错。这里在调用模型之前拦下。
     *
     * <p>返回 false 表示已写入可读提示，调用方应立即结束。</p>
     */
    private boolean acceptResumeText(
            String resumeText, String sessionId, InterviewQuestionRespDTO response) {
        MaterialRoleDetector.Detection detection = materialRoleDetector.detect(resumeText);
        if (!detection.is(MaterialRoleDetector.Role.JD_LIKE)) {
            return true;
        }
        meterRegistry.counter("material_role_mismatch_total", "slot", "RESUME", "reason", "ROLE").increment();
        log.warn("Resume slot holds job-description-like text, sessionId={}, jdScore={}, resumeScore={}, hits={}",
                sessionId, detection.jdScore(), detection.resumeScore(), detection.jdHits());
        response.setErrorMessage("简历位置的内容看起来是岗位描述（命中：" + String.join("、", detection.jdHits())
                + "），请上传你的简历；岗位描述请填到下方「目标岗位」");
        return false;
    }

    /**
     * 岗位槽位的内容自检：先查"和简历是不是同一份"，再查"是不是简历"。
     *
     * <p>重复检查放在前面，因为它是最高置信度的判定（用户把同一个文件丢进两个框），
     * 此时给出"别把同一份文件放到两个位置"比"这看起来像简历"更准确。</p>
     */
    private boolean acceptJdText(
            String jdText, String resumeText, String sessionId, InterviewQuestionRespDTO response) {
        if (materialRoleDetector.sameContent(jdText, resumeText)) {
            meterRegistry.counter("material_role_mismatch_total", "slot", "JD", "reason", "DUPLICATE").increment();
            log.warn("JD text duplicates resume, sessionId={}, jdLength={}, resumeLength={}",
                    sessionId, jdText.length(), resumeText.length());
            response.setErrorMessage("岗位描述与简历内容几乎相同，请确认没有把同一份文件放到两个位置");
            return false;
        }
        MaterialRoleDetector.Detection detection = materialRoleDetector.detect(jdText);
        if (!detection.is(MaterialRoleDetector.Role.RESUME_LIKE)) {
            return true;
        }
        meterRegistry.counter("material_role_mismatch_total", "slot", "JD", "reason", "ROLE").increment();
        log.warn("JD slot holds resume-like text, sessionId={}, jdScore={}, resumeScore={}, hits={}",
                sessionId, detection.jdScore(), detection.resumeScore(), detection.resumeHits());
        response.setErrorMessage("岗位描述的内容看起来是简历（命中：" + String.join("、", detection.resumeHits())
                + "），请把简历放到上方简历位置，或改用真正的岗位描述");
        return false;
    }

    // 将模型原始文字转换成业务结果。返回 true 表示已成功提交数据库并填好响应，false 表示已写入失败提示。
    private boolean populateStructuredResponse(
            InterviewQuestionReqDTO reqDTO,
            InterviewQuestionRespDTO response,
            String fullContent,
            PromptTemplate prompt,
            String resumeText,
            JdMaterial jd,
            long startTime,
            String callKey,
            int targetCount,
            InterviewDifficulty difficulty) {
        // 第 1 段：严格校验。类型不符、数量不符、题干重复都算阻断问题，不做"对象转字符串"式的静默兜底。
        String jdText = jd.present ? jd.text : null;
        ValidationResult validation = questionBatchValidator.validate(fullContent, resumeText, jdText, targetCount);
        recordEvidenceIssues(validation);
        String acceptedOutput = fullContent;

        // 第 2 段：阻断问题最多发起一次修复；修复结果必须重新走完整校验，不能只看"修了没有"。
        if (!validation.accepted()) {
            log.warn("Interview question output rejected, sessionId={}, prompt={}, reason={}, errors={}",
                    reqDTO.getSessionId(), prompt.versionLabel(), validation.reasonTag(),
                    StrUtil.maxLength(String.join("; ", validation.criticalDetails()), 500));
            meterRegistry.counter("extraction_validation_fail_total",
                    "phase", "first", "reason", validation.reasonTag()).increment();
            ValidatedBatch repaired = repairAndValidate(
                    reqDTO, response, fullContent, resumeText, jd, callKey, targetCount, difficulty,
                    validation, startTime);
            if (repaired == null) {
                return false;
            }
            validation = repaired.validation();
            acceptedOutput = repaired.output();
        }

        // 第 3 段：题号由后端按数组顺序生成，题干/要点/依据共用同一下标，不再依赖模型自报题号。
        InterviewQuestionBatchConverter.Conversion conversion =
                questionBatchConverter.convert(validation.batch(), jd.present);
        List<InterviewQuestionStore.MainQuestion> questions = conversion.questions();
        int costMs = (int) (System.currentTimeMillis() - startTime);

        try {
            // 【关键节点：出题提交】题库、材料、内部考察点和岗位映射一起提交，避免新题配上旧 JD。
            preparationCommitService.commitSucceeded(
                    InterviewPreparationCommitService.PreparationCommand.builder()
                            .sessionId(reqDTO.getSessionId())
                            .resumeFileUrl(reqDTO.getResumeFileUrl())
                            .resumeText(resumeText)
                            .interviewType(conversion.interviewType())
                            .resumeScore(conversion.resumeScore())
                            .questions(questions)
                            .suggestions(validation.batch().suggestions())
                            .questionPoints(conversion.questionPoints())
                            .questionTargets(conversion.questionTargets())
                            .jdSource(jd.present ? jd.source : null)
                            .jdFileUrl(jd.present ? jd.fileUrl : null)
                            .jdFileName(jd.present ? jd.fileName : null)
                            .jdText(jdText)
                            .jdContext(conversion.jdContext().isEmpty() ? null : conversion.jdContext())
                            .resumeContext(conversion.resumeContext())
                            .expectedSessionVersion(reqDTO.getExpectedSessionVersion())
                            .modelName(modelName)
                            .promptVersion(prompt.versionLabel())
                            // 存实际产出了题目的那份文本：修复过就是修复后的结果，便于事后复盘。
                            .rawResponse(acceptedOutput)
                            .responseTime(costMs)
                            .build());
        } catch (Exception ex) {
            log.error("Failed to persist interview preparation, sessionId={}", reqDTO.getSessionId(), ex);
            response.setErrorMessage("failed to save interview questions: " + ex.getMessage());
            saveFailure(reqDTO, reqDTO.getResumeFileUrl(), resumeText,
                    "failed to save interview questions", costMs);
            return false;
        }

        // 第 4 段：按 1、2、3…建立前端题目映射；考察点和 questionTargets 留在服务端，避免提前泄露评分依据。
        Map<String, String> questionMap = new LinkedHashMap<>();
        for (InterviewQuestionStore.MainQuestion question : questions) {
            questionMap.put(question.questionNo(), question.content());
        }
        response.setQuestions(questionMap);
        response.setQuestionCount(questions.size());
        List<String> suggestions = validation.batch().suggestions();
        if (!suggestions.isEmpty()) {
            Map<String, String> suggestionMap = new LinkedHashMap<>();
            for (int i = 0; i < suggestions.size(); i++) {
                suggestionMap.put(String.valueOf(i + 1), suggestions.get(i));
            }
            response.setSuggestions(suggestionMap);
            response.setSuggestionCount(suggestions.size());
        }
        if (StrUtil.isNotBlank(conversion.interviewType())) {
            response.setInterviewType(conversion.interviewType());
        }
        if (conversion.resumeScore() != null) {
            response.setResumeScore(conversion.resumeScore());
        }
        response.setJdEnabled(jd.present);
        if (jd.present) {
            response.setJdSource(jd.source);
            response.setJdFileName(jd.fileName);
            response.setJdSummary(buildJdSummary(conversion.jdContext(), jdText));
        }
        response.setResponseTime(costMs);
        return true;
    }

    /**
     * 发起唯一一次修复调用，并把修复结果重新送进完整校验。
     *
     * <p>返回 null 表示修复不可用（调用失败或仍未通过），此时失败提示与诊断记录已经写好，
     * 调用方直接返回失败即可。绝不落库部分题目。</p>
     */
    private ValidatedBatch repairAndValidate(
            InterviewQuestionReqDTO reqDTO,
            InterviewQuestionRespDTO response,
            String previousOutput,
            String resumeText,
            JdMaterial jd,
            String callKey,
            int targetCount,
            InterviewDifficulty difficulty,
            ValidationResult previous,
            long startTime) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("validationErrors", "- " + String.join("\n- ", previous.criticalDetails()));
        variables.put("previousResult", StrUtil.maxLength(previousOutput, REPAIR_PREVIOUS_RESULT_MAX_CHARS));
        variables.put("questionCount", targetCount);
        variables.put("difficultyPreference", difficulty.promptText());
        variables.put("resumeText", resumeText);
        variables.put("jobDescription", jd.present ? jd.text : NO_JD_PLACEHOLDER);

        PromptTemplate repairPrompt = promptLibrary.get(PromptLibrary.INTERVIEW_QUESTION_EXTRACTION_REPAIR);
        LlmRequest request = LlmRequest.of(
                InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                repairPrompt.renderSystem(),
                repairPrompt.renderUser(variables)
        ).withMaxTokens(outputTokenBudget(targetCount));

        String repairedOutput;
        try {
            repairedOutput = interviewAiInvoker.call(
                    request, InterviewAiGuardStage.INTERVIEW_EXTRACTION, callKey + "|repair");
        } catch (InterviewAiGuardException e) {
            // 修复调用本身被治理层拒绝（超时/过载/不可用）：不再发起第三次，按模型不可用返回。
            log.warn("Interview question repair call failed, sessionId={}, code={}",
                    reqDTO.getSessionId(), e.getErrorCode());
            response.setErrorMessage(e.getMessage());
            saveFailure(reqDTO, reqDTO.getResumeFileUrl(), resumeText,
                    "repair call failed: " + e.getMessage(), elapsedMs(startTime));
            return null;
        } catch (Exception e) {
            log.error("Interview question repair call error, sessionId={}", reqDTO.getSessionId(), e);
            response.setErrorMessage("面试题生成失败，请重试");
            saveFailure(reqDTO, reqDTO.getResumeFileUrl(), resumeText,
                    "repair call error", elapsedMs(startTime));
            return null;
        }

        String jdText = jd.present ? jd.text : null;
        ValidationResult result = questionBatchValidator.validate(repairedOutput, resumeText, jdText, targetCount);
        recordEvidenceIssues(result);
        if (result.accepted()) {
            meterRegistry.counter("extraction_repair_total", "outcome", "succeeded").increment();
            return new ValidatedBatch(result, repairedOutput);
        }

        meterRegistry.counter("extraction_repair_total", "outcome", "failed").increment();
        meterRegistry.counter("extraction_validation_fail_total",
                "phase", "repair", "reason", result.reasonTag()).increment();
        log.warn("Interview question repair still rejected, sessionId={}, reason={}, errors={}",
                reqDTO.getSessionId(), result.reasonTag(),
                StrUtil.maxLength(String.join("; ", result.criticalDetails()), 500));
        response.setErrorMessage("面试题生成结果未通过校验，请重试");
        saveFailure(reqDTO, reqDTO.getResumeFileUrl(), resumeText,
                "question batch invalid after repair: " + result.reasonTag(), elapsedMs(startTime));
        return null;
    }

    /**
     * 证据类问题单独计数：它和"结构错误"是不同的故障来源，混在一起看不出是模型编造还是校验过严。
     */
    private void recordEvidenceIssues(ValidationResult result) {
        for (ValidationIssue issue : result.issues()) {
            if (issue.code() != ValidationIssue.IssueCode.EVIDENCE) {
                continue;
            }
            meterRegistry.counter("evidence_match_fail_total",
                    "category", StrUtil.blankToDefault(issue.category(), "UNKNOWN"),
                    "critical", String.valueOf(issue.critical())).increment();
        }
    }

    /**
     * 输出预算：按题量线性放大，再由上下限夹住。题目对象比 v3 的纯字符串多四个字段，
     * 固定 6000 tokens 在 20 题时会截断 JSON；上限取供应商常见的单次输出极限。
     */
    private int outputTokenBudget(int count) {
        int budget = OUTPUT_META_RESERVE_TOKENS + Math.max(0, count) * Math.max(0, maxTokensPerQuestion);
        return Math.min(Math.max(budget, maxTokensFloor), maxTokensCeiling);
    }

    private int elapsedMs(long startTime) {
        return (int) (System.currentTimeMillis() - startTime);
    }

    /**
     * 修复路径的返回载体：既带回校验结果，也带回真正产出了题目的文本。
     */
    private record ValidatedBatch(ValidationResult validation, String output) {
    }

    // 展示用摘要优先采用结构化岗位名称，否则压缩原文空白并截短；完整 JD 仍单独保存在材料表。
    private String buildJdSummary(Map<String, Object> jdContext, String jdText) {
        Object title = jdContext.get("jobTitle");
        if (title != null && StrUtil.isNotBlank(String.valueOf(title))) {
            return StrUtil.maxLength(String.valueOf(title).trim(), 120);
        }
        String compact = StrUtil.blankToDefault(jdText, "").replaceAll("\\s+", " ").trim();
        return StrUtil.maxLength(compact, 120);
    }

    // 【关键节点：诊断兜底】失败记录采用尽力保存：若诊断自身落库失败，只记日志，不能覆盖本次原始出题错误。
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

    /**
     * 出题输入摘要（简历字节 + 岗位文本/文件字节），只用于日志与指标区分重复提取。
     *
     * <p>调用键不再承担"结果复用"职责——分布式 single-flight 已在 P3 移除，
     * 答题重复提交由 MySQL 唯一键与 attemptToken 拦截；出题另由重操作锁及会话 version 保护。</p>
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
            // 题量与难度也是输入的一部分：同简历不同选项应被当作两次不同的出题（日志与指标据此区分）。
            raw.append('|').append(StrUtil.blankToDefault(reqDTO.getQuestionCount(), "default"))
                    .append('|').append(StrUtil.blankToDefault(reqDTO.getDifficulty(), "default"));
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

        // 未提供与解析失败是两种结果：前者允许仅按简历出题，后者要求主流程停止并提示用户。
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
