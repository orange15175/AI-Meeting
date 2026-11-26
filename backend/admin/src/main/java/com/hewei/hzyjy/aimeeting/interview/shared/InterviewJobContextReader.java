package com.hewei.hzyjy.aimeeting.interview.shared;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewMaterial;
import com.hewei.hzyjy.aimeeting.interview.dao.store.InterviewMaterialStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位上下文读取：评分、追问与复盘共用，独立于简历上下文。
 *
 * <p>两类材料不能混成一份"候选人背景"：岗位要求是考核目标，简历是经历依据，
 * 混在一起会让模型把岗位要求当成候选人已具备的能力。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewJobContextReader {

    public static final String NOT_PROVIDED = "（未提供）";

    private final InterviewMaterialStore materialStore;

    public Map<String, Object> readContext(String sessionId) {
        InterviewMaterial material = materialStore.findBySession(sessionId);
        if (material == null || StrUtil.isBlank(material.getJdContext())) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(
                    material.getJdContext(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse jd context, sessionId={}, error={}", sessionId, ex.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 供提示词使用的岗位摘要：优先结构化画像，缺失时退回原文裁剪。
     */
    public String readAsText(String sessionId, int maxLength) {
        InterviewMaterial material = materialStore.findBySession(sessionId);
        if (material == null || StrUtil.isBlank(material.getJdSource())) {
            return NOT_PROVIDED;
        }
        String summary = summarize(readContext(sessionId));
        if (StrUtil.isNotBlank(summary)) {
            return clip(summary, maxLength);
        }
        return clip(material.getJdText(), maxLength);
    }

    /**
     * 展示用岗位摘要，不含内部证据原文。
     */
    public String readSummary(String sessionId, int maxLength) {
        InterviewMaterial material = materialStore.findBySession(sessionId);
        if (material == null || StrUtil.isBlank(material.getJdSource())) {
            return null;
        }
        String summary = summarize(readContext(sessionId));
        if (StrUtil.isBlank(summary)) {
            summary = material.getJdText();
        }
        if (StrUtil.isBlank(summary)) {
            return null;
        }
        return clip(summary, maxLength);
    }

    private String summarize(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        appendPart(builder, "岗位", context.get("jobTitle"));
        appendPart(builder, "资历", context.get("seniority"));
        appendList(builder, "职责", context.get("responsibilities"));
        appendList(builder, "必需技能", context.get("requiredSkills"));
        appendList(builder, "加分技能", context.get("preferredSkills"));
        return builder.length() == 0 ? null : builder.toString();
    }

    private void appendPart(StringBuilder builder, String label, Object value) {
        if (value == null) {
            return;
        }
        String text = StrUtil.trim(String.valueOf(value));
        if (StrUtil.isBlank(text) || "null".equalsIgnoreCase(text)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('；');
        }
        builder.append(label).append('：').append(text);
    }

    private void appendList(StringBuilder builder, String label, Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        String joined = list.stream()
                .filter(item -> item != null && StrUtil.isNotBlank(String.valueOf(item)))
                .map(item -> StrUtil.trim(String.valueOf(item)))
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
        if (StrUtil.isNotBlank(joined)) {
            appendPart(builder, label, joined);
        }
    }

    private String clip(String value, int maxLength) {
        if (StrUtil.isBlank(value)) {
            return NOT_PROVIDED;
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}
