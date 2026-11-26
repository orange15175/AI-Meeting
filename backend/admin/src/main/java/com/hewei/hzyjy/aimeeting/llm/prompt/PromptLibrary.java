package com.hewei.hzyjy.aimeeting.llm.prompt;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词仓库：按 classpath 路径加载并缓存 {@link PromptTemplate}。
 *
 * <p>提示词从 Java 字符串搬到独立文件，一是改措辞不必重新编译，二是文件后缀里带着版本号，
 * 便于把"某次评分用了哪版提示词"写进日志和数据（P3 起会落库）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptLibrary {

    /**
     * v3 出题模板：questions 为字符串数组，题号与考察点靠模型自带编号对齐。
     * 保留常量只为历史溯源（prompt_version 已落库），运行期不再加载。
     */
    public static final String INTERVIEW_QUESTION_EXTRACTION_V3 = "prompts/interview/question-extraction.v3.md";

    /**
     * v4 出题模板：questions 为对象数组，题干与分类/难度/依据/要点绑定；
     * 题号由后端在整批校验通过后生成，避免空题过滤造成的题号错位。
     */
    public static final String INTERVIEW_QUESTION_EXTRACTION = "prompts/interview/question-extraction.v4.md";

    /**
     * v4 校验失败后的一次性修复模板：携带原结果与明确错误，要求返回完整对象。
     */
    public static final String INTERVIEW_QUESTION_EXTRACTION_REPAIR =
            "prompts/interview/question-extraction-repair.v1.md";

    public static final String INTERVIEW_ANSWER_EVALUATION = "prompts/interview/answer-evaluation.v3.md";

    public static final String INTERVIEW_FOLLOW_UP = "prompts/interview/follow-up.v2.md";

    public static final String CHAT_GENERAL = "prompts/chat/general-chat.v1.md";

    private static final String HEADER_DELIMITER = "---";

    private static final String SECTION_PREFIX = "## ";

    private final ResourceLoader resourceLoader;

    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public PromptTemplate get(String path) {
        if (StrUtil.isBlank(path)) {
            throw new IllegalArgumentException("prompt path must not be blank");
        }
        return cache.computeIfAbsent(path, this::load);
    }

    private PromptTemplate load(String path) {
        String location = path.startsWith("classpath:") ? path : "classpath:" + path;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("prompt file not found: " + location);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            String raw = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            PromptTemplate template = parse(path, raw);
            log.info("Prompt loaded, path={}, id={}, version={}", path, template.getId(), template.getVersion());
            return template;
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read prompt file: " + location, ex);
        }
    }

    private PromptTemplate parse(String path, String raw) {
        Map<String, String> metadata = new HashMap<>();
        StringBuilder currentSection = new StringBuilder();
        String currentSectionName = null;
        StringBuilder system = new StringBuilder();
        StringBuilder user = new StringBuilder();

        String[] lines = raw.split("\\R", -1);
        int index = 0;
        if (lines.length > 0 && HEADER_DELIMITER.equals(lines[0].trim())) {
            index = 1;
            while (index < lines.length && !HEADER_DELIMITER.equals(lines[index].trim())) {
                String line = lines[index];
                int colon = line.indexOf(':');
                if (colon > 0) {
                    metadata.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
                index++;
            }
            index++;
        }

        for (; index < lines.length; index++) {
            String line = lines[index];
            if (line.startsWith(SECTION_PREFIX)) {
                appendSection(currentSectionName, currentSection, system, user);
                currentSectionName = line.substring(SECTION_PREFIX.length()).trim().toLowerCase();
                currentSection = new StringBuilder();
                continue;
            }
            if (currentSectionName != null) {
                currentSection.append(line).append('\n');
            }
        }
        appendSection(currentSectionName, currentSection, system, user);

        String id = StrUtil.blankToDefault(metadata.get("id"), path);
        String version = StrUtil.blankToDefault(metadata.get("version"), "v1");
        return new PromptTemplate(
                id,
                version,
                StrUtil.blankToDefault(metadata.get("description"), ""),
                system.toString().trim(),
                user.toString().trim()
        );
    }

    private void appendSection(String name, StringBuilder buffer, StringBuilder system, StringBuilder user) {
        if (name == null || buffer.isEmpty()) {
            return;
        }
        String content = buffer.toString().trim();
        if (content.isEmpty()) {
            return;
        }
        if ("system".equals(name)) {
            system.append(content);
        } else if ("user".equals(name)) {
            user.append(content);
        }
    }
}
