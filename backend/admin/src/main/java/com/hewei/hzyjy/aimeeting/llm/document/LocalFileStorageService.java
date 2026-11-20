package com.hewei.hzyjy.aimeeting.llm.document;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.common.config.storage.ApplicationStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 本地文件落盘。
 *
 * <p>简历原件此前上传到星辰的对象存储，改造后不再依赖外部服务，统一放在
 * {@code ai-meeting.storage.base-dir/uploads} 下，按业务与月份分目录，
 * 返回相对 base-dir 的路径写入数据库，迁移机器时不需要改数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFileStorageService {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    private final ApplicationStorageProperties storageProperties;

    public String store(String bizType, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("file is empty");
        }
        String originalName = StrUtil.blankToDefault(file.getOriginalFilename(), "unnamed");
        String extension = StrUtil.subAfter(originalName, ".", true);
        String fileName = IdUtil.fastSimpleUUID() + (StrUtil.isBlank(extension) || extension.length() > 10
                ? ""
                : "." + extension.toLowerCase());

        String relativeDir = "uploads/"
                + StrUtil.blankToDefault(bizType, "common").replaceAll("[^A-Za-z0-9_-]", "_")
                + "/" + LocalDate.now().format(MONTH_FORMAT);
        Path targetDir = storageProperties.getBasePath().resolve(relativeDir).normalize();
        assertUnderBase(targetDir);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(fileName);
        try (var inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        String relativePath = relativeDir + "/" + fileName;
        log.info("File stored locally, bizType={}, originalName={}, relativePath={}, size={}",
                bizType, originalName, relativePath, file.getSize());
        return relativePath;
    }

    public Path resolve(String relativePath) {
        Path target = storageProperties.getBasePath().resolve(relativePath).normalize();
        assertUnderBase(target);
        return target;
    }

    public void deleteQuietly(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (Exception ex) {
            log.warn("Failed to delete stored file, path={}, error={}", relativePath, ex.getMessage());
        }
    }

    private void assertUnderBase(Path target) {
        if (!target.startsWith(storageProperties.getBasePath())) {
            throw new IllegalArgumentException("illegal storage path: " + target);
        }
    }
}
