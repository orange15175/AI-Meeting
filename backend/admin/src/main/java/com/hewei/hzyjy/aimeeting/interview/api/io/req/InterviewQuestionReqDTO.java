package com.hewei.hzyjy.aimeeting.interview.api.io.req;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 面试题抽取请求DTO
 * @author system
 */
@Data
public class InterviewQuestionReqDTO {

    /**
     * 用户名
     */
    private String userName;

    /**
     * AgentID
     */
    private Long agentId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 简历PDF文件
     */
    private MultipartFile resumePdf;

    /**
     * 简历文件URL（上传后的文件地址）
     */
    private String resumeFileUrl;

    /**
     * 岗位描述文字（与 jdFile 二选一）
     */
    private String jdText;

    /**
     * 岗位描述文件（PDF 或图片，与 jdText 二选一）
     */
    private MultipartFile jdFile;

    /**
     * 本次准备开始时会话版本，提交时用于校验期间会话是否被改动
     */
    private Integer expectedSessionVersion;

    /**
     * 本场主问题数量（字符串原样承载，由出题服务解析并校验区间；未传时用配置默认值）。
     *
     * <p>不用 {@code Integer}：multipart 缺省时基本类型会拿到 null 之外的语义分歧，
     * 而非法字符串会在绑定阶段直接 400，前端拿不到可读提示。</p>
     */
    private String questionCount;

    /**
     * 整场难度倾向：AUTO / BASIC / ADVANCED / CHALLENGE，也接受中文标签；未传时为 AUTO。
     */
    private String difficulty;

}