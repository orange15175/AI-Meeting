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
import java.util.Map;

/**
 * 简历结构化上下文读取：评分与追问都需要，统一从这里取，避免各自解析 JSON。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewResumeContextReader {

    private static final String[] SUMMARY_KEYS = new String[]{"resumeSummary", "resume_context", "summary"};

    private final InterviewMaterialStore materialStore;

    public Map<String, Object> read(String sessionId) {
        InterviewMaterial material = materialStore.findBySession(sessionId);
        if (material == null || StrUtil.isBlank(material.getResumeContext())) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(
                    material.getResumeContext(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse resume context, sessionId={}, error={}", sessionId, ex.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 摘要优先，其次整体 JSON；缺失时返回"（未提供）"，避免提示词出现空段。
     */
    public String readAsText(String sessionId, int maxLength) {
        Map<String, Object> context = read(sessionId);
        if (context.isEmpty()) {
            return "（未提供）";
        }
        for (String key : SUMMARY_KEYS) {
            Object value = context.get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return clip(String.valueOf(value), maxLength);
            }
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        context.forEach((key, value) -> {
            if (StrUtil.isBlank(key) || value == null) {
                return;
            }
            if ("questions".equals(key) || "suggestions".equals(key) || "type".equals(key)
                    || "resumeScore".equals(key) || "score".equals(key)) {
                return;
            }
            filtered.put(key, value);
        });
        return clip(JSON.toJSONString(filtered.isEmpty() ? context : filtered), maxLength);
    }

    private String clip(String value, int maxLen) {
        if (StrUtil.isBlank(value)) {
            return "（未提供）";
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }
}
