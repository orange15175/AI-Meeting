package com.hewei.hzyjy.aimeeting.interview.api.io.resp;

import lombok.Data;

import java.util.List;

/**
 * 报告中的岗位要求覆盖项：只陈述"考了什么、答得如何"，不做匹配率推算。
 */
@Data
public class JdCoverageItemRespDTO {

    public static final String STATUS_COVERED = "COVERED";

    public static final String STATUS_PENDING = "PENDING";

    /**
     * 岗位要求，来自 JD 结构化字段或题目映射
     */
    private String requirement;

    /**
     * 对应题号，未出题时为空数组
     */
    private List<String> questionNumbers;

    /**
     * COVERED：已有作答证据；PENDING：未出题或未作答
     */
    private String status;
}
