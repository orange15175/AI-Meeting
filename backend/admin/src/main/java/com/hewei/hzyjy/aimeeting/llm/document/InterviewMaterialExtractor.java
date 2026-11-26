package com.hewei.hzyjy.aimeeting.llm.document;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;
import com.hewei.hzyjy.aimeeting.common.convention.exception.ClientException;
import com.hewei.hzyjy.aimeeting.llm.VisionChatClient;
import com.hewei.hzyjy.aimeeting.llm.VisionImage;
import com.hewei.hzyjy.aimeeting.llm.VisionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 面试材料统一文本抽取入口，按材料类型区分提示词与长度预算。
 *
 * <p>三层策略，按成本从低到高：</p>
 * <ol>
 *   <li>文本型 PDF：走 {@link PdfTextExtractor} 本地抽取，零模型成本；</li>
 *   <li>扫描型 PDF（抽不出文本）：用 PDFBox 渲染成图片，交视觉模型 OCR；</li>
 *   <li>图片文件（png/jpg/webp/bmp）：直接交视觉模型识别。</li>
 * </ol>
 *
 * <p>视觉模型密钥留空时，前两层仍可用；只有真正需要视觉识别时会抛
 * 明确的 ClientException，提示配置 VISION_API_KEY，不会影响既有文本简历流程。</p>
 *
 * <p>扫描件超过配置页数时直接拒绝，不再用 {@code Math.min} 静默裁剪：
 * 裁掉的页往往正是岗位要求或项目经历的后半段，静默丢弃比报错更危险。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewMaterialExtractor {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "bmp");

    /**
     * 材料类型：决定 OCR 提示词、错误措辞与文本长度预算。
     */
    public enum MaterialType {

        RESUME("简历", 20000,
                "这是一份中文简历。请完整识别并转写其中所有文字内容，"
                        + "保留原有的分段、标题和项目符号结构；不要总结、不要翻译、不要遗漏数字与日期。"),

        JOB_DESCRIPTION("岗位描述", 12000,
                "这是一份招聘岗位描述（JD）。请完整识别并转写其中所有文字内容，"
                        + "保留原有的分段与条目结构；不要总结、不要翻译、不要遗漏技能名称与年限要求；"
                        + "不要补充原文没有的要求、薪资或任职经历，"
                        + "不要把页面导航、按钮、投递入口等非岗位内容写入结果。");

        private final String label;
        private final int maxChars;
        private final String ocrPrompt;

        MaterialType(String label, int maxChars, String ocrPrompt) {
            this.label = label;
            this.maxChars = maxChars;
            this.ocrPrompt = ocrPrompt;
        }

        public String label() {
            return label;
        }

        public int maxChars() {
            return maxChars;
        }

        public String ocrPrompt() {
            return ocrPrompt;
        }
    }

    private final PdfTextExtractor pdfTextExtractor;
    private final VisionChatClient visionChatClient;
    private final VisionProperties visionProperties;

    /**
     * 抽取纯文本，供出题、评分与复盘提示词使用。
     */
    public String extract(MultipartFile file, MaterialType type) throws IOException {
        if (file == null || file.isEmpty()) {
            return "";
        }
        MaterialType materialType = type == null ? MaterialType.RESUME : type;
        String extension = extensionOf(file.getOriginalFilename());
        byte[] bytes = file.getBytes();

        // 【关键节点：解析分流】图片直接走视觉识别；PDF 先读文本层，无文字才渲染页面走 OCR。
        // type 决定简历或 JD 的识别提示词；识别结果只是材料文本，不是模型出题结果。
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return extractFromImage(bytes, extension, file.getOriginalFilename(), materialType);
        }
        // 其余一律按 PDF 处理（原流程只接受 PDF，保持兼容）。
        String text = pdfTextExtractor.extract(bytes);
        if (StrUtil.isNotBlank(text)) {
            return normalize(text, materialType);
        }
        log.info("No text layer in {}, falling back to vision OCR, file={}",
                materialType.label(), file.getOriginalFilename());
        return extractFromScannedPdf(bytes, materialType);
    }

    // 图片路径：校验视觉模型可用，将原图编码为带 MIME 类型的 Base64，再用简历/JD 各自的 OCR 提示词识别。
    private String extractFromImage(byte[] bytes, String extension, String fileName, MaterialType type) {
        requireVisionConfigured("图片" + type.label());
        String mimeType = "jpg".equals(extension) ? "jpeg" : extension;
        VisionImage image = new VisionImage("image/" + mimeType, Base64.encode(bytes));
        log.info("{} image sent to vision model, file={}, mime=image/{}", type.label(), fileName, mimeType);
        String text = visionChatClient.recognize(type.ocrPrompt(), List.of(image));
        return normalize(text, type);
    }

    // 扫描 PDF 路径：先把各页渲染成图片，再一次提交视觉识别；识别只提取文字，出题仍是后续文本模型任务。
    private String extractFromScannedPdf(byte[] bytes, MaterialType type) throws IOException {
        requireVisionConfigured("扫描版 PDF " + type.label());
        List<VisionImage> images = renderPdfPages(bytes, type);
        if (images.isEmpty()) {
            return "";
        }
        log.info("Scanned PDF pages sent to vision model, type={}, pages={}", type.label(), images.size());
        String text = visionChatClient.recognize(type.ocrPrompt(), images);
        return normalize(text, type);
    }

    // 控制视觉输入体积：读取 DPI 与页数上限，超过上限明确报错；逐页转 JPEG 后在 try-with-resources 结束时关闭 PDF。
    private List<VisionImage> renderPdfPages(byte[] bytes, MaterialType type) throws IOException {
        List<VisionImage> images = new ArrayList<>();
        int dpi = visionProperties.getRenderDpi() == null ? 150 : visionProperties.getRenderDpi();
        int maxPages = visionProperties.getMaxImagePages() == null ? 6 : visionProperties.getMaxImagePages();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int totalPages = document.getNumberOfPages();
            if (totalPages > maxPages) {
                throw new ClientException(type.label() + "共 " + totalPages + " 页，超过单次识别的 "
                        + maxPages + " 页上限，请拆分文件或精简后重试");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            for (int i = 0; i < totalPages; i++) {
                BufferedImage page = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                images.add(new VisionImage("image/jpeg", encodeJpeg(page)));
            }
        }
        return images;
    }

    private String encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return Base64.encode(output.toByteArray());
    }

    private void requireVisionConfigured(String scenario) {
        if (!visionChatClient.isConfigured()) {
            throw new ClientException(scenario + "需要视觉模型识别，"
                    + "请设置环境变量 VISION_API_KEY 后重试（文本 PDF 不受影响）");
        }
    }

    // 普通文本整理：清理不换行空格、压缩过多空行，并按简历/JD 各自长度预算截断，限制后续提示词体积。
    private String normalize(String text, MaterialType type) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String cleaned = text.replace('\u00A0', ' ')
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        int maxChars = type.maxChars();
        return cleaned.length() > maxChars ? cleaned.substring(0, maxChars) : cleaned;
    }

    private String extensionOf(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return "";
        }
        String ext = StrUtil.subAfter(fileName, ".", true).toLowerCase(Locale.ROOT);
        return ext;
    }
}
