package com.hewei.hzyjy.aimeeting.interview.api.io.req;

import lombok.Data;

@Data
public class InterviewConversationPageReqDTO {

    private Integer current = 1;

    private Integer size = 10;

    private String status;

    private String keyword;
}
