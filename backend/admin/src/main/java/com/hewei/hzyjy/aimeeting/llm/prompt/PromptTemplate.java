package com.hewei.hzyjy.aimeeting.llm.prompt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一份版本化的提示词：元信息 + 系统提示 + 用户模板。
 *
 * <p>文件格式见 {@code classpath:prompts/**.md}：以 {@code ---} 包裹的头部声明 id/version，
 * 正文用 {@code ## system} 与 {@code ## user} 分节。渲染只做 {@code {{name}}} 的直接替换，
 * 不做表达式求值，避免提示词里出现模板引擎语法造成歧义。</p>
 */
public final class PromptTemplate {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private final String id;

    private final String version;

    private final String description;

    private final String systemPrompt;

    private final String userTemplate;

    public PromptTemplate(String id, String version, String description, String systemPrompt, String userTemplate) {
        this.id = id;
        this.version = version;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.userTemplate = userTemplate;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserTemplate() {
        return userTemplate;
    }

    public String versionLabel() {
        return id + "@" + version;
    }

    public String renderSystem() {
        return systemPrompt;
    }

    public String renderUser(Map<String, ?> variables) {
        return render(userTemplate, variables);
    }

    private String render(String template, Map<String, ?> variables) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, ?> safeVariables = variables == null ? Collections.emptyMap() : new LinkedHashMap<>(variables);
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = safeVariables.get(key);
            String replacement = value == null ? "" : String.valueOf(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
