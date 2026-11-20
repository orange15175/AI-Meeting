package com.hewei.hzyjy.aimeeting.interview.dao.store;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hewei.hzyjy.aimeeting.interview.dao.entity.InterviewMaterial;
import com.hewei.hzyjy.aimeeting.interview.dao.mapper.InterviewMaterialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 面试材料存取：一份简历对应一行，重试出题时原地更新。
 */
@Service
@RequiredArgsConstructor
public class InterviewMaterialStore {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private final InterviewMaterialMapper materialMapper;

    public InterviewMaterial findBySession(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        return materialMapper.selectOne(Wrappers.lambdaQuery(InterviewMaterial.class)
                .eq(InterviewMaterial::getSessionId, sessionId)
                .eq(InterviewMaterial::getDelFlag, 0));
    }

    /**
     * 显式写入岗位列：五个字段全部覆盖，缺省传 null。
     *
     * <p>不能用 {@code updateById} 承担这件事——MyBatis-Plus 默认跳过 null 字段，
     * 一旦用户从"有 JD"退回"无 JD"，旧岗位会残留在行里继续污染下一轮出题。</p>
     */
    public void updateJdColumns(String sessionId, String jdSource, String jdFileUrl, String jdFileName,
                                String jdText, String jdContext) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        materialMapper.update(null, Wrappers.lambdaUpdate(InterviewMaterial.class)
                .eq(InterviewMaterial::getSessionId, sessionId)
                .eq(InterviewMaterial::getDelFlag, 0)
                .set(InterviewMaterial::getJdSource, StrUtil.emptyToNull(jdSource))
                .set(InterviewMaterial::getJdFileUrl, StrUtil.emptyToNull(jdFileUrl))
                .set(InterviewMaterial::getJdFileName, StrUtil.emptyToNull(jdFileName))
                .set(InterviewMaterial::getJdText, StrUtil.emptyToNull(jdText))
                .set(InterviewMaterial::getJdContext, StrUtil.emptyToNull(jdContext))
                .set(InterviewMaterial::getUpdateTime, new Date()));
    }

    /**
     * 只更新失败诊断，不改写安全快照里的简历/岗位内容。
     */
    public void updateFailureDiagnostics(String sessionId, String errorMessage, Integer responseTime) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        materialMapper.update(null, Wrappers.lambdaUpdate(InterviewMaterial.class)
                .eq(InterviewMaterial::getSessionId, sessionId)
                .eq(InterviewMaterial::getDelFlag, 0)
                .set(InterviewMaterial::getErrorMessage, StrUtil.maxLength(errorMessage, 480))
                .set(InterviewMaterial::getResponseTime, responseTime)
                .set(InterviewMaterial::getUpdateTime, new Date()));
    }

    /**
     * 原地 upsert：唯一键 session_id 保证不会产生第二份材料。
     */
    public InterviewMaterial save(InterviewMaterial material) {
        if (material == null || StrUtil.isBlank(material.getSessionId())) {
            return null;
        }
        Date now = new Date();
        InterviewMaterial existing = findBySession(material.getSessionId());
        if (existing == null) {
            material.setCreateTime(now);
            material.setUpdateTime(now);
            material.setDelFlag(0);
            if (material.getParseVersion() == null) {
                material.setParseVersion(1);
            }
            materialMapper.insert(material);
            return material;
        }
        material.setId(existing.getId());
        material.setCreateTime(existing.getCreateTime());
        material.setUpdateTime(now);
        material.setDelFlag(0);
        if (material.getParseVersion() == null) {
            material.setParseVersion(existing.getParseVersion());
        }
        materialMapper.updateById(material);
        return material;
    }
}
