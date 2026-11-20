package com.hewei.hzyjy.aimeeting.common.config.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Runtime storage configuration for uploads and logs.
 *
 * <p>语音/转写功能下线后不再需要音频临时目录。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-meeting.storage")
public class ApplicationStorageProperties {

    private String baseDir;

    private String uploadTempDir;

    private String logDir;

    public Path getBasePath() {
        return Path.of(baseDir).toAbsolutePath().normalize();
    }

    public Path getUploadTempPath() {
        return Path.of(uploadTempDir).toAbsolutePath().normalize();
    }

    public Path getLogPath() {
        return Path.of(logDir).toAbsolutePath().normalize();
    }
}
