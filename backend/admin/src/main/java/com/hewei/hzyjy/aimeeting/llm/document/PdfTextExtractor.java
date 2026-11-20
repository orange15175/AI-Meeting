package com.hewei.hzyjy.aimeeting.llm.document;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 简历 PDF 文本抽取。
 *
 * <p>走星辰工作流时，文件由对方托管并解析；改成模型直连后，模型只接受文本，
 * 所以必须在本地把 PDF 转成纯文本再拼进提示词。抽取结果做长度截断，
 * 避免超长简历把上下文顶爆。</p>
 */
@Slf4j
@Component
public class PdfTextExtractor {

    /**
     * 单份简历送入模型的最大字符数，约对应 1.5 万 tokens 以内的中文。
     */
    private static final int MAX_CHARS = 20000;

    public String extract(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return "";
        }
        return extract(file.getBytes());
    }

    public String extract(byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            return "";
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            return normalize(text);
        }
    }

    private String normalize(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String cleaned = text
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (cleaned.length() > MAX_CHARS) {
            log.info("Resume text truncated from {} to {} characters", cleaned.length(), MAX_CHARS);
            return cleaned.substring(0, MAX_CHARS);
        }
        return cleaned;
    }
}
