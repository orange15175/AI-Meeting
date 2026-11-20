
package com.hewei.hzyjy.aimeeting;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 面试平台后端应用
 */
@SpringBootApplication
@MapperScan("com.hewei.hzyjy.aimeeting.**.dao.mapper")
@EnableScheduling
public class AiMeetingAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiMeetingAdminApplication.class, args);
    }
}
